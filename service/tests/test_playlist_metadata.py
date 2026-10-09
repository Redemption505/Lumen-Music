from __future__ import annotations

import base64
import http.client
import json
import struct
import tempfile
import threading
import unittest
import zlib
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service.backup import export_backup, restore_backup
from lumen_service.diary import diary_view, diary_xml
from lumen_service.discovery import build_taste_profile, recommend
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryError, LibraryFormatError, LibraryStorageError, LibraryStore
from lumen_service.playlist_metadata import MAX_COVER_BYTES, MAX_METADATA_BODY_BYTES, cover_data_uri
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server

TRACK = {"id": "demo:one", "title": "第一首", "artist": "作者", "durationSeconds": 60, "source": "demo"}
# A real 1x1 JPEG fixture; generated once, tests use only the standard library.
JPEG = (
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIs"
    "IxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy"
    "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAA"
    "AAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAk"
    "M2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKT"
    "lJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA"
    "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdh"
    "cRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hp"
    "anN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk"
    "5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDzqiiisz6Y/9k="
)


def png(padding: int = 0) -> bytes:
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
            + (chunk(b"tEXt", b"padding\0" + b"a" * padding) if padding else b"")
            + chunk(b"IDAT", zlib.compress(b"\0\xff\0\0\xff")) + chunk(b"IEND", b""))


def cover(padding: int = 0) -> str:
    return "data:image/png;base64," + base64.b64encode(png(padding)).decode()


class PlaylistMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = LibraryStore(self.root / "library.json")
        self.identifier = self.store.create_playlist("歌单")["playlists"][0]["id"]

    def test_restart_zip_rename_pin_and_clear(self):
        self.store.add_to_playlist(self.identifier, TRACK)
        home = self.store.snapshot()["homePreferences"]
        home["pinned"] = [{"kind": "playlist", "id": self.identifier}]
        self.store.save_home_preferences(home)
        self.store.update_playlist_metadata(self.identifier, description="独处 & 夜晚\n心情", cover_data_uri=cover())
        self.store.save_diary_note(TRACK, "私人记忆")
        self.store.rename_playlist(self.identifier, "新名字")
        expected = self.store.snapshot()
        self.assertEqual(expected, LibraryStore(self.store.path).snapshot())
        archive = self.root / "backup.zip"
        export_backup(archive, self.store.path)
        restored = self.root / "restored.json"
        restore_backup(archive, restored)
        self.assertEqual(expected, LibraryStore(restored).snapshot())
        cleared = self.store.update_playlist_metadata(self.identifier, cover_data_uri="")["playlists"][0]
        self.assertEqual("", cleared["coverDataUri"])
        self.assertEqual("独处 & 夜晚\n心情", cleared["description"])
        self.store.delete_playlist(self.identifier)
        self.assertEqual([], self.store.snapshot()["homePreferences"]["pinned"])

    def test_old_defaults_and_invalid_saved_metadata_preserve_file(self):
        old = {"version": 1, "favorites": [], "playlists": [{"id": "old", "name": "旧", "tracks": []}]}
        self.store.path.write_text(json.dumps(old), encoding="utf-8")
        loaded = LibraryStore(self.store.path).snapshot()["playlists"][0]
        self.assertEqual(("", ""), (loaded["description"], loaded["coverDataUri"]))
        for field, value in (("description", "字" * 281), ("coverDataUri", "file:///cover.png")):
            broken = json.loads(json.dumps(old))
            broken["playlists"][0][field] = value
            content = json.dumps(broken).encode()
            self.store.path.write_bytes(content)
            with self.assertRaises(LibraryFormatError):
                LibraryStore(self.store.path)
            self.assertEqual(content, self.store.path.read_bytes())

    def test_cover_validation_and_limits(self):
        self.assertEqual(cover(), cover_data_uri(cover()))
        jpeg = "data:image/jpeg;base64," + JPEG
        self.assertEqual(jpeg, cover_data_uri(jpeg))
        # Exactly 512 KiB remains valid; one more decoded byte is rejected.
        base_size = len(png(1)) - 1
        exact = cover(MAX_COVER_BYTES - base_size)
        self.assertEqual(MAX_COVER_BYTES, len(base64.b64decode(exact.split(",")[1])))
        self.assertEqual(exact, cover_data_uri(exact))
        invalid = [cover(MAX_COVER_BYTES - base_size + 1), "file:///a.png", None,
                   "data:image/png;base64,!!!!", "data:image/webp;base64,YQ==",
                   cover().replace("image/png", "image/jpeg"),
                   jpeg.replace("image/jpeg", "image/png"),
                   "data:image/png;base64," + base64.b64encode(png()[:-1]).decode()]
        for value in invalid:
            with self.subTest(value=str(value)[:35]), self.assertRaises(ValueError):
                cover_data_uri(value)
        with self.assertRaises(LibraryError):
            self.store.update_playlist_metadata(self.identifier, description="字" * 281)
        self.store.update_playlist_metadata(self.identifier, description="😀" * 280)

    def test_actual_main_replace_failure_preserves_memory_and_bytes(self):
        original, before = self.store.path.read_bytes(), self.store.snapshot()
        import os
        real_replace = os.replace
        def fail_main(source, target):
            if Path(target) == self.store.path:
                raise OSError("simulated disk failure")
            return real_replace(source, target)
        with patch("lumen_service.library.os.replace", side_effect=fail_main):
            with self.assertRaises(LibraryStorageError):
                self.store.update_playlist_metadata(self.identifier, description="保存失败", cover_data_uri=cover())
            with self.assertRaises(LibraryStorageError):
                self.store.save_diary_note(TRACK, "失败的私密备注")
        self.assertEqual(original, self.store.path.read_bytes())
        self.assertEqual(before, self.store.snapshot())
        self.assertEqual(before, LibraryStore(self.store.path).snapshot())
        self.assertEqual([], list(self.root.glob(".*.tmp")))

    def test_version_replacement_and_undo_keep_playlist_metadata_and_note_dates(self):
        self.store.add_to_playlist(self.identifier, TRACK)
        self.store.update_playlist_metadata(self.identifier, description="说明", cover_data_uri=cover())
        self.store.save_diary_note(TRACK, "私密内容")
        song = self.store.create_song("歌", "", [("原版", "", TRACK)])["songs"][0]
        notes = self.store.snapshot()["diaryNotes"]
        self.store.replace_version(song["id"], song["versions"][0]["id"], {**TRACK, "id": "demo:replacement"})
        self.assertEqual(("说明", cover()), tuple(self.store.snapshot()["playlists"][0][key]
                                                 for key in ("description", "coverDataUri")))
        reloaded = LibraryStore(self.store.path)
        self.assertEqual(notes, reloaded.snapshot()["diaryNotes"])
        undone = reloaded.undo_catalog()
        self.assertEqual(TRACK["id"], undone["playlists"][0]["tracks"][0]["id"])
        self.assertEqual("说明", undone["playlists"][0]["description"])
        self.assertEqual(cover(), undone["playlists"][0]["coverDataUri"])

    def test_http_patch_partial_clear_name_errors_and_body_scope(self):
        server = create_server(port=0, provider=DemoProvider(), library_store=self.store)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(worker.join, 2)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        def request(method, path, body, declared_length=None):
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
            try:
                headers = {"Content-Type": "application/xml"}
                if declared_length is not None:
                    headers["Content-Length"] = str(declared_length)
                connection.request(method, path, body.encode(), headers)
                result = connection.getresponse()
                return result.status, ElementTree.fromstring(result.read())
            finally:
                connection.close()
        route = "/api/library/playlists/" + self.identifier
        status, result = request("PATCH", route, "<playlist><description>你好 &amp; 夜晚</description></playlist>")
        self.assertEqual(200, status)
        self.assertEqual("你好 & 夜晚", result.findtext("playlists/playlist/description"))
        large = "<playlist><coverDataUri>" + cover(60000) + "</coverDataUri></playlist>"
        self.assertGreater(len(large), 64 * 1024)
        self.assertLess(len(large), MAX_METADATA_BODY_BYTES)
        self.assertEqual(200, request("PATCH", route, large)[0])
        self.assertEqual(400, request("POST", "/api/library/playlists", "<playlist/>", len(large))[0])
        self.assertEqual(400, request("PATCH", route, "<playlist><description>" + "a" * 66000 + "</description></playlist>")[0])
        self.assertEqual(400, request("PATCH", route, "<playlist/>", MAX_METADATA_BODY_BYTES + 1)[0])
        for bad in ("<playlist/>", "<playlist extra='bad'/>", "<playlist><unknown/></playlist>",
                    "<playlist><description/><description/></playlist>"):
            self.assertEqual(400, request("PATCH", route, bad)[0])
        self.assertEqual(200, request("PATCH", route, '<playlist name="改名"/>')[0])
        status, result = request("PATCH", route, "<playlist><coverDataUri/></playlist>")
        self.assertEqual(200, status)
        self.assertEqual("改名", result.find("playlists/playlist").get("name"))
        self.assertIsNone(result.find("playlists/playlist/coverDataUri"))
        self.assertEqual("你好 & 夜晚", result.findtext("playlists/playlist/description"))


class NoteMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "library.json"
        self.store = LibraryStore(self.path)

    def test_real_timestamps_idempotency_restart_xml_and_private_recommendation(self):
        before = datetime.now(timezone.utc)
        initial = self.store.save_diary_note(TRACK, "不向推荐泄露的个人秘密")["diaryNotes"][0]
        after = datetime.now(timezone.utc)
        created = datetime.fromisoformat(initial["createdAt"].replace("Z", "+00:00"))
        self.assertLessEqual(before, created)
        self.assertLessEqual(created, after)
        self.assertEqual(initial["createdAt"], initial["updatedAt"])
        content = self.path.read_bytes()
        self.store.save_diary_note(TRACK, initial["text"])
        self.assertEqual(content, self.path.read_bytes())
        changed = self.store.save_diary_note(TRACK, "修改后的个人秘密")["diaryNotes"][0]
        self.assertEqual(initial["createdAt"], changed["createdAt"])
        self.assertGreaterEqual(changed["updatedAt"], initial["updatedAt"])
        self.assertEqual(changed, LibraryStore(self.path).snapshot()["diaryNotes"][0])
        history = HistoryStore(Path(self.temp.name) / "history.json").snapshot()
        view = diary_view(self.store.snapshot(), history, "UTC")
        node = ElementTree.fromstring(diary_xml(view)).find("notes/note")
        self.assertEqual(changed["createdAt"], node.get("createdAt"))
        self.assertEqual(changed["updatedAt"], node.get("updatedAt"))
        clean = LibraryStore._empty()
        self.assertEqual(build_taste_profile(clean, history), build_taste_profile(self.store.snapshot(), history))
        self.assertEqual(recommend(DemoProvider(), clean, history), recommend(DemoProvider(), self.store.snapshot(), history))

    def test_unknown_old_creation_date_stays_unknown_even_after_update_and_zip(self):
        old = LibraryStore._empty()
        old["diaryNotes"] = [{"track": TRACK, "text": "旧备注"}]
        self.path.write_text(json.dumps(old), encoding="utf-8")
        self.store = LibraryStore(self.path)
        self.assertNotIn("createdAt", self.store.snapshot()["diaryNotes"][0])
        self.store.save_diary_note(TRACK, "新内容")
        note = self.store.snapshot()["diaryNotes"][0]
        self.assertNotIn("createdAt", note)
        self.assertTrue(note["updatedAt"].endswith("Z"))
        archive, restored = self.path.parent / "notes.zip", self.path.parent / "copy.json"
        export_backup(archive, self.path)
        restore_backup(archive, restored)
        self.assertEqual(note, LibraryStore(restored).snapshot()["diaryNotes"][0])
        self.store.save_diary_note(TRACK, "")
        self.assertEqual([], self.store.snapshot()["diaryNotes"])

    def test_invalid_note_dates_reject_without_overwriting(self):
        for field, value in (("createdAt", "2026-10-07T00:00:00"), ("updatedAt", "invalid"),
                             ("createdAt", None)):
            payload = LibraryStore._empty()
            payload["diaryNotes"] = [{"track": TRACK, "text": "备注", field: value}]
            content = json.dumps(payload).encode()
            self.path.write_bytes(content)
            with self.assertRaises(LibraryFormatError):
                LibraryStore(self.path)
            self.assertEqual(content, self.path.read_bytes())


if __name__ == "__main__":
    unittest.main()
