from __future__ import annotations

import copy
import http.client
import json
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service.backup import BackupError, export_backup, restore_backup
from lumen_service.home import defaults, to_xml
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryError, LibraryFormatError, LibraryStorageError, LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


class HomeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "library.json"
        self.library = LibraryStore(self.path)

    def prepare(self) -> dict:
        self.library.favorite({"id": "track", "title": "Song", "artist": "A",
                               "durationSeconds": 60, "source": "demo"})
        self.library.create_playlist("夜晚")
        regular = self.library.snapshot()["playlists"][0]["id"]
        snapshot, _, _ = self.library.organization("save", {"name": "老歌", "rules": {}}, {})
        smart = snapshot["smartPlaylists"][0]["id"]
        return {"version": 1, "sections": ["pinned", "continue", "discover", "reunion"],
                "pinned": [{"kind": "playlist", "id": regular}, {"kind": "smart", "id": smart}]}

    def test_old_library_defaults_and_save_restart_preserve_all_other_fields(self) -> None:
        self.path.write_text('{"version":1,"favorites":[],"playlists":[]}', encoding="utf-8")
        self.library = LibraryStore(self.path)
        self.assertEqual(defaults(), self.library.snapshot()["homePreferences"])
        preferences = self.prepare()
        before = self.library.snapshot()
        after = self.library.save_home_preferences(preferences)
        self.assertEqual(preferences, after.pop("homePreferences"))
        before.pop("homePreferences")
        self.assertEqual(before, after)
        self.assertEqual(preferences, LibraryStore(self.path).snapshot()["homePreferences"])
        preferences["pinned"].clear()
        self.assertEqual(2, len(self.library.snapshot()["homePreferences"]["pinned"]))

    def test_invalid_values_and_write_failure_leave_state_and_file_untouched(self) -> None:
        preferences = self.prepare()
        self.library.save_home_preferences(preferences)
        original, snapshot = self.path.read_bytes(), self.library.snapshot()
        invalid = [None, {**preferences, "version": 2}, {**preferences, "version": True},
                   {**preferences, "sections": ["continue"] * 4},
                   {**preferences, "sections": ["continue", "pinned", "reunion", "unknown"]},
                   {**preferences, "pinned": preferences["pinned"] * 2},
                   {**preferences, "pinned": preferences["pinned"] * 4},
                   {**preferences, "pinned": [{"kind": "smart", "id": preferences["pinned"][0]["id"]}]},
                   {**preferences, "pinned": [{"kind": "playlist", "id": "missing"}]}]
        for value in invalid:
            with self.subTest(value=value):
                with self.assertRaises(LibraryError):
                    self.library.save_home_preferences(value)
                self.assertEqual(original, self.path.read_bytes())
                self.assertEqual(snapshot, self.library.snapshot())
        changed = {**preferences, "pinned": []}
        with patch.object(self.library, "_atomic_write", side_effect=OSError("disk")):
            with self.assertRaises(LibraryStorageError):
                self.library.save_home_preferences(changed)
        self.assertEqual(original, self.path.read_bytes())
        self.assertEqual(snapshot, self.library.snapshot())

    def test_rename_retains_pins_delete_removes_only_its_pin(self) -> None:
        preferences = self.prepare()
        self.library.save_home_preferences(preferences)
        regular, smart = [pin["id"] for pin in preferences["pinned"]]
        self.library.rename_playlist(regular, "新夜晚")
        self.library.organization("save", {"id": smart, "name": "新老歌", "rules": {}}, {})
        self.assertEqual(preferences, self.library.snapshot()["homePreferences"])
        self.library.delete_playlist(regular)
        self.assertEqual([preferences["pinned"][1]], self.library.snapshot()["homePreferences"]["pinned"])
        self.library.organization("delete", {"id": smart}, {})
        self.assertEqual([], LibraryStore(self.path).snapshot()["homePreferences"]["pinned"])

    def test_preference_change_retains_existing_catalog_undo(self) -> None:
        preferences = self.prepare()
        self.library.create_song("歌", "", [("版本", "歌手", {"id": "song-track", "title": "Song",
                                 "artist": "A", "durationSeconds": 60, "source": "demo"})])
        before = self.library.snapshot()
        after = self.library.save_home_preferences(preferences)
        self.assertEqual(before["versionsUndo"], after["versionsUndo"])
        self.library.undo_catalog()
        self.assertEqual(before["versionsUndo"]["songs"], self.library.snapshot()["songs"])
        self.assertEqual(preferences, LibraryStore(self.path).snapshot()["homePreferences"])

    def test_backup_roundtrip_and_invalid_home_rejection_preserve_previous_backup(self) -> None:
        preferences = self.prepare()
        self.library.save_home_preferences(preferences)
        before = self.library.snapshot()
        archive = self.path.with_suffix(".zip")
        export_backup(archive, self.path)
        with zipfile.ZipFile(archive) as zipped:
            self.assertEqual(preferences, json.loads(zipped.read("library.json"))["homePreferences"])
        self.library.save_home_preferences(defaults())
        restore_backup(archive, self.path)
        self.assertEqual(before, LibraryStore(self.path).snapshot())
        backup_bytes = archive.read_bytes()
        broken = copy.deepcopy(before)
        broken["homePreferences"]["version"] = 2
        self.path.write_text(json.dumps(broken), encoding="utf-8")
        with self.assertRaises(BackupError):
            export_backup(archive, self.path)
        self.assertEqual(backup_bytes, archive.read_bytes())
        with self.assertRaises(LibraryFormatError):
            LibraryStore(self.path)

    def test_http_get_head_post_atomic_validation_and_no_history_effect(self) -> None:
        preferences = self.prepare()
        history = HistoryStore(self.path.with_name("history.json"))
        server = create_server(port=0, provider=DemoProvider(), library_store=self.library,
                               history_store=history)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            def request(method: str, body: bytes | None = None) -> tuple[int, bytes]:
                conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                try:
                    conn.request(method, "/api/library/home", body,
                                 {"Content-Type": "application/xml; charset=utf-8"})
                    response = conn.getresponse()
                    return response.status, response.read()
                finally:
                    conn.close()
            status, body = request("GET")
            self.assertEqual(200, status)
            self.assertEqual(["continue", "pinned", "reunion", "discover"],
                             [node.get("id") for node in ElementTree.fromstring(body).findall("sections/section")])
            self.assertEqual((200, b""), request("HEAD"))
            status, body = request("POST", to_xml(preferences))
            self.assertEqual(200, status)
            self.assertEqual([pin["id"] for pin in preferences["pinned"]],
                             [node.get("id") for node in ElementTree.fromstring(body).findall("pinned/pin")])
            original = self.path.read_bytes()
            for invalid in [b'<home version="2"><sections/><pinned/></home>',
                            b'<home version="1"><sections/><pinned/></home>',
                            to_xml({**preferences, "pinned": [{"kind": "playlist", "id": "missing"}]}),
                            to_xml(preferences).replace(b'</pinned>', b'<unknown/></pinned>')]:
                self.assertEqual(400, request("POST", invalid)[0])
                self.assertEqual(original, self.path.read_bytes())
            self.assertEqual([], history.snapshot()["entries"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
