"""Version catalog persistence and HTTP behavior."""

from __future__ import annotations

import http.client
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from xml.etree import ElementTree

from lumen_service.backup import export_backup, restore_backup
from lumen_service.library import LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


def track(identifier: str) -> str:
    return (f'<track id="{identifier}" title="Track {identifier}" artist="Artist" '
            'durationSeconds="120" source="demo"/>')


class VersionsHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "library.json"
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=LibraryStore(self.path))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: str | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, body=body.encode("utf-8") if body else None,
                               headers={"Content-Type": "application/xml; charset=utf-8"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def create_song(self):
        status, catalog = self.request("POST", "/api/library/versions",
                                       '<song title="Song" note="note">'
                                       f'<version label="Studio" performer="A">{track("a")}</version>'
                                       '</song>')
        self.assertEqual(200, status)
        self.assertEqual("catalog", catalog.tag)
        song = catalog.find("song")
        version = song.find("version")
        self.assertEqual(version.get("id"), song.get("preferredVersionId"))
        return song.get("id"), version.get("id")

    def test_create_edit_add_detach_and_validation(self) -> None:
        self.assertEqual(400, self.request("POST", "/api/library/versions",
                                           '<song title="empty"/>')[0])
        song_id, version_id = self.create_song()
        status, catalog = self.request("POST", f"/api/library/versions/{song_id}/versions",
                                       f'<version label="Live" performer="B">{track("b")}</version>')
        self.assertEqual(200, status)
        second = catalog.findall("song/version")[1].get("id")
        self.assertEqual(400, self.request("POST", f"/api/library/versions/{song_id}/versions",
                                           f'<version label="Dup">{track("a")}</version>')[0])
        self.assertEqual(400, self.request("PATCH", f"/api/library/versions/{song_id}",
                                           '<song preferredVersionId="missing"/>')[0])
        status, catalog = self.request("PATCH", f"/api/library/versions/{song_id}",
                                       f'<song title="Renamed" note="updated" preferredVersionId="{second}"/>')
        self.assertEqual(200, status)
        self.assertEqual("Renamed", catalog.find("song").get("title"))
        status, catalog = self.request("PATCH", f"/api/library/versions/{song_id}/versions/{second}",
                                       '<version label="Concert" performer="Singer"/>')
        self.assertEqual(200, status)
        self.assertEqual("Concert", catalog.findall("song/version")[1].get("label"))
        self.assertEqual("Singer", catalog.findall("song/version")[1].get("performer"))
        status, catalog = self.request("DELETE", f"/api/library/versions/{song_id}/versions/{second}")
        self.assertEqual(200, status)
        self.assertEqual(version_id, catalog.find("song").get("preferredVersionId"))
        status, catalog = self.request("DELETE", f"/api/library/versions/{song_id}/versions/{version_id}")
        self.assertEqual(200, status)
        self.assertEqual([], catalog.findall("song"))

    def test_replace_preserves_positions_deduplicates_and_undoes_after_restart(self) -> None:
        for identifier in ("left", "a", "right", "b"):
            self.assertEqual(200, self.request("POST", "/api/library/favorites", track(identifier))[0])
        status, library = self.request("POST", "/api/library/playlists", '<playlist name="P"/>')
        self.assertEqual(200, status)
        playlist_id = library.find("playlists/playlist").get("id")
        for identifier in ("left", "a", "right", "b"):
            self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/tracks",
                                               track(identifier))[0])
        song_id, version_id = self.create_song()
        status, catalog = self.request("POST", f"/api/library/versions/{song_id}/versions/{version_id}/replace",
                                       track("b"))
        self.assertEqual(200, status)
        self.assertEqual("b", catalog.find("song/version/track").get("id"))
        status, library = self.request("GET", "/api/library")
        self.assertEqual(200, status)
        self.assertEqual(["left", "b", "right"],
                         [entry.get("id") for entry in library.findall("favorites/track")])
        self.assertEqual(["left", "b", "right"],
                         [entry.get("id") for entry in library.findall("playlists/playlist/track")])
        self.assertEqual("b", LibraryStore(self.path).snapshot()["songs"][0]["versions"][0]["track"]["id"])
        status, catalog = self.request("POST", "/api/library/versions/undo")
        self.assertEqual(200, status)
        self.assertEqual("a", catalog.find("song/version/track").get("id"))
        self.assertEqual(400, self.request("POST", "/api/library/versions/undo")[0])
        status, library = self.request("GET", "/api/library")
        self.assertEqual(200, status)
        self.assertEqual(["left", "a", "right", "b"],
                         [entry.get("id") for entry in library.findall("favorites/track")])

    def test_old_v1_and_backup_round_trip_catalog_and_undo(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.path.write_text(json.dumps({"version": 1, "favorites": [], "playlists": []}),
                             encoding="utf-8")
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=LibraryStore(self.path))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.assertEqual([], self.request("GET", "/api/library/versions")[1].findall("song"))
        song_id, version_id = self.create_song()
        self.assertEqual(200, self.request("PATCH", f"/api/library/versions/{song_id}/versions/{version_id}",
                                           '<version label="Second"/>')[0])
        archive = Path(self.temp.name) / "backup.zip"
        export_backup(archive, self.path)
        restored = Path(self.temp.name) / "restored.json"
        restore_backup(archive, restored)
        store = LibraryStore(restored)
        self.assertEqual("Second", store.snapshot()["songs"][0]["versions"][0]["label"])
        self.assertIn("versionsUndo", store.snapshot())
        self.assertEqual("Studio", store.undo_catalog()["songs"][0]["versions"][0]["label"])

    def test_unrelated_edit_invalidates_undo(self) -> None:
        self.create_song()
        self.request("POST", "/api/library/favorites", track("other"))
        status, error = self.request("POST", "/api/library/versions/undo")
        self.assertEqual(400, status)
        self.assertEqual("LIBRARY_INVALID", error.get("code"))

    def test_catalog_only_segment_edit_and_delete(self) -> None:
        segment_id = f"segment:{uuid.uuid4()}"
        clip = (f'<track id="{segment_id}" title="Clip" artist="Artist" '
                'durationSeconds="10" source="demo" sourceTrackId="original" '
                'segmentStartMillis="1000" segmentEndMillis="11000" '
                'sourceDurationSeconds="120"/>')
        status, catalog = self.request("POST", "/api/library/versions",
                                       '<song title="Clip song">'
                                       f'<version label="Clip">{clip}</version></song>')
        self.assertEqual(200, status)
        status, library = self.request("PATCH", f"/api/library/tracks/{segment_id}",
                                       '<track title="Edited" segmentEndMillis="13000"/>')
        self.assertEqual(200, status)
        self.assertEqual("library", library.tag)
        catalog = self.request("GET", "/api/library/versions")[1]
        self.assertEqual("Edited", catalog.find("song/version/track").get("title"))
        self.assertEqual("12", catalog.find("song/version/track").get("durationSeconds"))
        self.assertEqual(200, self.request("DELETE", f"/api/library/tracks/{segment_id}")[0])
        self.assertEqual([], self.request("GET", "/api/library/versions")[1].findall("song"))

    def test_explicit_merge_preserves_notes_and_undo(self) -> None:
        first_id, first_version = self.create_song()
        status, catalog = self.request("POST", "/api/library/versions",
                                       '<song title="Other" note="second note">'
                                       f'<version label="Live" performer="B">{track("b")}</version>'
                                       '</song>')
        self.assertEqual(200, status)
        other_id = catalog.findall("song")[1].get("id")
        before = self.path.read_bytes()
        self.assertEqual(400, self.request("POST", f"/api/library/versions/{first_id}/merge",
                                           f'<merge songId="{first_id}"/>')[0])
        self.assertEqual(before, self.path.read_bytes())
        status, catalog = self.request("POST", f"/api/library/versions/{first_id}/merge",
                                       f'<merge songId="{other_id}"/>')
        self.assertEqual(200, status)
        self.assertEqual(1, len(catalog.findall("song")))
        self.assertEqual("note\nsecond note", catalog.find("song").get("note"))
        self.assertEqual(first_version, catalog.find("song").get("preferredVersionId"))
        self.assertEqual(["a", "b"], [item.get("id") for item in catalog.findall("song/version/track")])
        status, catalog = self.request("POST", "/api/library/versions/undo")
        self.assertEqual(200, status)
        self.assertEqual(["note", "second note"], [item.get("note") for item in catalog.findall("song")])


if __name__ == "__main__":
    unittest.main()
