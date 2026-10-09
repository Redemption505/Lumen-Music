from __future__ import annotations

import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from xml.etree import ElementTree

from lumen_service.library import LibraryError, LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


def track(identifier: str) -> dict:
    return {"id": identifier, "title": identifier, "artist": "artist",
            "durationSeconds": 10, "source": "demo"}


class PlaylistEditingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "library.json"
        self.store = LibraryStore(self.path)
        self.first = self.store.create_playlist("first")["playlists"][0]["id"]
        self.second = self.store.create_playlist("second")["playlists"][1]["id"]
        for identifier in ("a", "b", "c", "d"):
            self.store.add_to_playlist(self.first, track(identifier))
        self.store.add_to_playlist(self.second, track("b"))
        self.store.favorite(track("b"))

    def test_batch_removal_and_undo_preserve_order_and_other_data(self) -> None:
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        payload["favoriteAt"] = {"b": "2026-01-01T00:00:00Z"}
        payload["trackTags"] = {"b": ["work"]}
        payload["folders"] = [{"id": "folder", "name": "Folder"}]
        payload["playlistFolders"] = {self.first: "folder"}
        payload["smartPlaylists"] = [{"id": "smart", "name": "Smart", "rules": {}}]
        self.path.write_text(json.dumps(payload), encoding="utf-8")
        self.store = LibraryStore(self.path)
        before = self.store.snapshot()

        removed, token = self.store.remove_tracks_from_playlist(self.first, ["c", "a", "a"])
        self.assertEqual(["b", "d"], [item["id"] for item in removed["playlists"][0]["tracks"]])
        self.store.add_to_playlist(self.second, track("e"))
        self.store.favorite(track("d"))
        restored = self.store.undo_playlist_removal(self.first, token)
        self.assertEqual(["a", "b", "c", "d"],
                         [item["id"] for item in restored["playlists"][0]["tracks"]])
        self.assertEqual(["b", "e"], [item["id"] for item in restored["playlists"][1]["tracks"]])
        for key in ("trackTags", "folders", "playlistFolders", "smartPlaylists"):
            self.assertEqual(before[key], restored[key])
        self.assertEqual(before["favoriteAt"]["b"], restored["favoriteAt"]["b"])
        self.assertIn("d", restored["favoriteAt"])
        self.assertEqual(restored, LibraryStore(self.path).snapshot())
        with self.assertRaisesRegex(LibraryError, "没有匹配"):
            self.store.undo_playlist_removal(self.first, token)

    def test_invalid_request_and_later_playlist_edit_do_not_lose_data(self) -> None:
        original = self.path.read_bytes()
        with self.assertRaisesRegex(LibraryError, "已不在歌单"):
            self.store.remove_tracks_from_playlist(self.first, ["a", "missing"])
        self.assertEqual(original, self.path.read_bytes())
        _, token = self.store.remove_tracks_from_playlist(self.first, ["a", "c"])
        self.store.move_in_playlist(self.first, 1, 0)
        before_undo = self.path.read_bytes()
        with self.assertRaisesRegex(LibraryError, "后续修改"):
            self.store.undo_playlist_removal(self.first, token)
        self.assertEqual(before_undo, self.path.read_bytes())

    def test_newer_operation_supersedes_old_token(self) -> None:
        _, old = self.store.remove_tracks_from_playlist(self.first, ["a"])
        _, recent = self.store.remove_tracks_from_playlist(self.second, ["b"])
        with self.assertRaisesRegex(LibraryError, "没有匹配"):
            self.store.undo_playlist_removal(self.first, old)
        self.store.undo_playlist_removal(self.second, recent)

    def test_external_damage_blocks_undo_without_overwrite(self) -> None:
        _, token = self.store.remove_tracks_from_playlist(self.first, ["a"])
        self.path.write_text("{broken", encoding="utf-8")
        with self.assertRaisesRegex(LibraryError, "外部修改"):
            self.store.undo_playlist_removal(self.first, token)
        self.assertEqual("{broken", self.path.read_text(encoding="utf-8"))

    def test_http_batch_and_undo_contract(self) -> None:
        server = create_server(port=0, provider=DemoProvider(), library_store=self.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

        def request(path: str, body: str) -> tuple[int, ElementTree.Element]:
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            try:
                connection.request("POST", path, body.encode("utf-8"),
                                   {"Content-Type": "application/xml; charset=utf-8"})
                response = connection.getresponse()
                return response.status, ElementTree.fromstring(response.read())
            finally:
                connection.close()

        route = f"/api/library/playlists/{self.first}/tracks"
        status, root = request(route + "/remove",
                               '<remove><track id="d"/><track id="b"/><track id="b"/></remove>')
        self.assertEqual(200, status)
        token = root.get("undoToken")
        self.assertTrue(token)
        self.assertEqual(["a", "c"], [item.get("id") for item in root.find("playlists/playlist")])
        status, error = request(route + "/undo", '<undo token="wrong"/>')
        self.assertEqual((400, "LIBRARY_INVALID"), (status, error.get("code")))
        status, root = request(route + "/undo", f'<undo token="{token}"/>')
        self.assertEqual(200, status)
        self.assertEqual(["a", "b", "c", "d"],
                         [item.get("id") for item in root.find("playlists/playlist")])


if __name__ == "__main__":
    unittest.main()
