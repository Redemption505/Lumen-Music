from __future__ import annotations

import http.client
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.lyrics import LyricCandidate, LyricLookup
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


class PhaseThreeHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.library_path = root / "library.json"
        self.history_path = root / "history.json"
        self.archive = root / "library-backup.zip"
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=LibraryStore(self.library_path),
                                    history_store=HistoryStore(self.history_path))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: bytes | None = None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=8)
        try:
            conn.request(method, path, body=body,
                         headers={"Content-Type": "application/xml; charset=utf-8"})
            response = conn.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            conn.close()

    def backup(self, action: str, path: Path | None = None):
        request = ElementTree.tostring(ElementTree.Element("backup", {
            "path": str(path or self.archive),
        }), encoding="utf-8")
        return self.request("POST", "/api/backup/" + action, request)

    def test_export_inspect_restore_reloads_running_store(self) -> None:
        first = b'<track id="demo-1" title="First" artist="Artist" durationSeconds="8" source="demo"/>'
        second = b'<track id="demo-2" title="Second" artist="Artist" durationSeconds="8" source="demo"/>'
        self.assertEqual(200, self.request("POST", "/api/library/favorites", first)[0])
        status, preview = self.backup("export")
        self.assertEqual((200, "1"), (status, preview.get("favorites")))
        self.assertTrue(self.archive.is_file())
        self.assertEqual("1", self.backup("inspect")[1].get("favorites"))
        self.assertEqual(200, self.request("POST", "/api/library/favorites", second)[0])
        self.assertEqual(2, len(self.request("GET", "/api/library")[1].find("favorites")))
        status, restored = self.backup("restore")
        self.assertEqual((200, "1"), (status, restored.get("favorites")))
        favorites = self.request("GET", "/api/library")[1].find("favorites")
        self.assertEqual(["demo-1"], [track.get("id") for track in favorites])
        self.assertEqual(["demo-1"], [item["id"] for item in LibraryStore(self.library_path).snapshot()["favorites"]])

    def test_corrupt_backup_does_not_change_existing_data(self) -> None:
        self.request("POST", "/api/library/favorites",
                     b'<track id="demo-1" title="First" artist="Artist" durationSeconds="8" source="demo"/>')
        self.archive.write_bytes(b"not a zip")
        original = self.library_path.read_bytes()
        status, error = self.backup("restore")
        self.assertEqual((400, "BACKUP_INVALID"), (status, error.get("code")))
        self.assertEqual(original, self.library_path.read_bytes())
        self.assertEqual(1, len(self.request("GET", "/api/library")[1].find("favorites")))

    def test_optional_history_ai_and_background_restore(self) -> None:
        track = {"id": "demo-1", "title": "演示", "artist": "歌手",
                 "durationSeconds": 8, "source": "demo"}
        HistoryStore(self.history_path).record(track, 8)
        ai_path = self.library_path.with_name("ai-settings.json")
        ai_path.write_text('{"version":1,"enabled":true,"provider":"example","model":"m"}',
                           encoding="utf-8")
        background = self.library_path.with_name("background.png")
        background.write_bytes(b"\x89PNG\r\n\x1a\noriginal")
        status, preview = self.backup("export")
        self.assertEqual((200, "1"), (status, preview.get("history")))
        self.assertIn("background.png", preview.get("members"))
        ai_path.write_text('{"version":1,"enabled":false}', encoding="utf-8")
        background.write_bytes(b"\x89PNG\r\n\x1a\nchanged")
        self.history_path.write_text('{"version":1,"entries":[]}', encoding="utf-8")
        status, _ = self.backup("restore")
        self.assertEqual(200, status)
        self.assertEqual(1, len(self.request("GET", "/api/history")[1]))
        self.assertIn('"enabled": true', ai_path.read_text(encoding="utf-8"))
        self.assertEqual(b"\x89PNG\r\n\x1a\noriginal", background.read_bytes())

    def test_lyrics_xml_keeps_candidate_and_auto_choice_distinct(self) -> None:
        result = LyricLookup((LyricCandidate(19, "晴天 & 雨", "歌手", 200,
                                              "[00:01.00]风 < 雨", 70, "仅供手动确认"),), None)
        with patch("lumen_service.server.lookup_lyrics", return_value=result) as lookup:
            status, root = self.request("GET", "/api/lyrics?title=%E6%99%B4%E5%A4%A9&artist=x&duration=200")
        self.assertEqual(200, status)
        self.assertEqual("", root.get("autoId"))
        self.assertEqual("晴天 & 雨", root[0].get("title"))
        self.assertEqual("[00:01.00]风 < 雨", root[0].findtext("lrc"))
        self.assertEqual("200", root[0].get("duration"))
        lookup.assert_called_once_with("晴天", "x", 200)


if __name__ == "__main__":
    unittest.main()
