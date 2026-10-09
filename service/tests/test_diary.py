"""Qualified diary events, local periods and personal data persistence."""

from __future__ import annotations

import http.client
import json
import tempfile
import threading
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

from lumen_service.backup import export_backup, restore_backup
from lumen_service.diary import diary_view
from lumen_service.history import HistoryFormatError, HistoryRuntime, HistoryStore
from lumen_service.library import LibraryFormatError, LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


TRACK = {"id": "local:one", "title": "第一首", "artist": "作者",
         "durationSeconds": 120, "source": "local", "mediaUri": "file:///one.mp3"}
SEGMENT = {"id": "segment:" + str(uuid.uuid4()), "title": "片段", "artist": "作者",
           "durationSeconds": 5, "source": "local", "mediaUri": "file:///source.mp3",
           "sourceTrackId": "local:source", "segmentStartMillis": 1000,
           "segmentEndMillis": 6000, "sourceDurationSeconds": 180}


def utc(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class DiaryStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.library = LibraryStore(self.root / "library.json")
        self.history = HistoryStore(self.root / "history.json")

    def test_legacy_history_never_infers_first_or_weekly_play(self):
        old = {"version": 1, "entries": [{"track": TRACK, "playCount": 4,
               "lastPlayedAt": "2026-09-25T12:00:00Z", "totalQualifiedSeconds": 120.0}]}
        self.history.path.write_text(json.dumps(old), encoding="utf-8")
        self.history = HistoryStore(self.history.path)
        self.assertNotIn("firstPlayedAt", self.history.snapshot()["entries"][0])
        self.history.record(TRACK, 30, played_at=utc("2026-10-02T12:00:00Z"))
        view = diary_view(self.library.snapshot(), self.history.snapshot(), "Asia/Shanghai",
                          now=utc("2026-10-06T00:00:00Z"))
        self.assertEqual([], view["sections"]["weekly-first"])
        self.assertEqual(1, view["summary"]["unknownFirstCount"])
        self.assertEqual(5, view["summary"]["totalPlays"])
        self.assertEqual(1, len(view["sections"]["recent"]))

    def test_legacy_recent_uses_real_last_date_and_count_survives_limit(self):
        entries = [{"track": {**TRACK, "id": f"local:{index}"}, "playCount": 1,
                    "lastPlayedAt": f"2026-10-05T00:{index:02d}:00Z",
                    "totalQualifiedSeconds": 30.0} for index in range(35)]
        snapshot = HistoryStore._decode(json.dumps({"version": 1, "entries": entries}))
        view = diary_view(self.library.snapshot(), snapshot, "Asia/Shanghai",
                          now=utc("2026-10-06T00:00:00Z"))
        self.assertEqual(35, view["sectionCounts"]["recent"])
        self.assertEqual(30, len(view["sections"]["recent"]))
        self.assertTrue(view["sections"]["recent"][0]["legacy"])
        self.assertEqual(0, view["summary"]["thisMonthPlays"])
        self.assertEqual([], view["sections"]["weekly-first"])

    def test_period_boundary_source_segment_and_playlist_evidence(self):
        self.library.create_playlist("夜间")
        playlist_id = self.library.snapshot()["playlists"][0]["id"]
        self.library.add_to_playlist(playlist_id, SEGMENT)
        self.history.record(SEGMENT, 4.5, played_at=utc("2026-09-28T15:59:59Z"),
                            playlist_id=playlist_id, playlist_name="夜间")
        self.history.record(SEGMENT, 4.5, played_at=utc("2026-09-28T16:00:00Z"),
                            playlist_id=playlist_id, playlist_name="夜间")
        view = diary_view(self.library.snapshot(), self.history.snapshot(), "Asia/Shanghai",
                          now=utc("2026-10-06T00:00:00Z"))
        self.assertEqual(1, len(view["sections"]["weekly-first"]))
        self.assertEqual(1, view["summary"]["sourceCount"])
        self.assertEqual(1, view["summary"]["trackCount"])
        self.assertEqual(0, view["summary"]["songCount"])
        self.assertEqual([], view["sections"]["monthly-playlists"])
        self.history.record(SEGMENT, 4.5, played_at=utc("2026-09-30T16:00:00Z"),
                            playlist_id=playlist_id, playlist_name="夜间")
        monthly = diary_view(self.library.snapshot(), self.history.snapshot(),
                             "Asia/Shanghai", now=utc("2026-10-06T00:00:00Z"))
        self.assertEqual(1, monthly["summary"]["thisMonthPlays"])
        self.assertEqual(1, monthly["sections"]["monthly-playlists"][0]["playCount"])

    def test_damaged_event_is_preserved(self):
        broken = {"version": 1, "entries": [], "events": [
            {"track": TRACK, "playedAt": "2026-10-01T00:00:00Z",
             "sessionId": "not-a-uuid"}]}
        content = json.dumps(broken).encode("utf-8")
        self.history.path.write_bytes(content)
        with self.assertRaises(HistoryFormatError):
            HistoryStore(self.history.path)
        self.assertEqual(content, self.history.path.read_bytes())

    def test_reheard_requires_thirty_day_gap_and_prior_favorite(self):
        self.library.favorite(TRACK)
        snapshot = self.library.snapshot()
        snapshot["favoriteAt"][TRACK["id"]] = "2026-07-01T00:00:00Z"
        self.library._atomic_write(self.library.path, self.library._encode(snapshot))
        self.library = LibraryStore(self.library.path)
        for day in ("2026-08-01", "2026-08-20", "2026-10-02"):
            self.history.record(TRACK, 30, played_at=utc(day + "T12:00:00Z"))
        view = diary_view(self.library.snapshot(), self.history.snapshot(), "Asia/Shanghai",
                          now=utc("2026-10-06T00:00:00Z"))
        self.assertEqual(1, len(view["sections"]["reheard"]))
        self.assertEqual("2026-08-20T12:00:00Z",
                         view["sections"]["reheard"][0]["previousPlayedAt"])

    def test_note_lyrics_zip_and_corrupt_preservation(self):
        self.library.save_diary_note(TRACK, "私密备注")
        self.library.add_lyric_favorite(TRACK, 3200, "一句歌词")
        self.history.record(TRACK, 30, played_at=utc("2026-10-02T12:00:00Z"))
        archive = self.root / "personal.zip"
        export_backup(archive, self.library.path, history_path=self.history.path)
        restored_library = self.root / "copy-library.json"
        restored_history = self.root / "copy-history.json"
        restore_backup(archive, restored_library, history_path=restored_history)
        self.assertEqual(self.library.snapshot()["lyricFavorites"],
                         LibraryStore(restored_library).snapshot()["lyricFavorites"])
        self.assertEqual(1, len(HistoryStore(restored_history).snapshot()["events"]))
        broken = self.library.path.read_bytes() + b"bad"
        self.library.path.write_bytes(broken)
        with self.assertRaises(LibraryFormatError):
            LibraryStore(self.library.path)
        self.assertEqual(broken, self.library.path.read_bytes())

    def test_runtime_records_once_and_requires_real_playlist_membership(self):
        self.library.create_playlist("实际歌单")
        playlist_id = self.library.snapshot()["playlists"][0]["id"]
        self.library.add_to_playlist(playlist_id, TRACK)
        time = [0.0]
        runtime = HistoryRuntime(self.history, lambda: time[0], library=self.library,
                                 calendar_clock=lambda: utc("2026-10-06T00:00:00Z"))
        session = str(uuid.uuid4())
        runtime.sample(session, TRACK, 0, True, playlist_id=playlist_id)
        time[0] = 30.0
        result = runtime.sample(session, TRACK, 30, True)
        self.assertTrue(result["justRecorded"])
        self.assertFalse(runtime.sample(session, TRACK, 31, True)["justRecorded"])
        self.assertEqual(1, len(self.history.snapshot()["events"]))
        self.assertEqual(playlist_id, self.history.snapshot()["events"][0]["playlistId"])
        restarted = HistoryRuntime(HistoryStore(self.history.path), lambda: time[0],
                                   library=self.library)
        with self.assertRaisesRegex(Exception, "已记录"):
            restarted.sample(session, TRACK, 0, True)
        other = str(uuid.uuid4())
        time[0] = 40.0
        runtime.sample(other, SEGMENT, 0, True, playlist_id=playlist_id)
        time[0] = 45.0
        runtime.sample(other, SEGMENT, 5, True)
        self.assertIsNone(self.history.snapshot()["events"][1]["playlistId"])

    def test_duplicate_lyric_submission_keeps_identity_and_survives_reload(self):
        first = self.library.add_lyric_favorite(SEGMENT, 3200, "被记住的一句")
        self.library.add_lyric_favorite(SEGMENT, 3200, "被记住的一句")
        reloaded = LibraryStore(self.library.path).snapshot()
        self.assertEqual(first["lyricFavorites"], reloaded["lyricFavorites"])
        self.library.add_lyric_favorite(SEGMENT, 4200, "被记住的一句")
        self.assertEqual(2, len(self.library.snapshot()["lyricFavorites"]))

    def test_same_source_segments_only_count_explicit_song_links(self):
        other = {**SEGMENT, "id": "segment:" + str(uuid.uuid4()), "title": "另一段"}
        unknown = {**SEGMENT, "id": "segment:" + str(uuid.uuid4()), "title": "尚未关联的片段"}
        self.library.create_song("已明确关联的歌曲", "", [
            ("第一段", "作者", SEGMENT), ("另一版本", "作者", other)])
        clock = [0.0]
        runtime = HistoryRuntime(self.history, lambda: clock[0], library=self.library,
                                 calendar_clock=lambda: utc("2026-10-06T00:00:00Z"))
        for item in (SEGMENT, other, unknown):
            session = str(uuid.uuid4())
            runtime.sample(session, item, 0, True)
            clock[0] += 4.5
            runtime.sample(session, item, 4.5, True, finished=True)
            clock[0] += 1
        view = diary_view(self.library.snapshot(), self.history.snapshot(), "Asia/Shanghai",
                          now=utc("2026-10-06T00:00:00Z"))
        self.assertEqual((1, 3, 1), tuple(view["summary"][key]
                                         for key in ("sourceCount", "trackCount", "songCount")))
        self.assertEqual(3, view["summary"]["thisMonthPlays"])

    def test_runtime_revisit_notification_uses_actual_prior_gap(self):
        self.library.favorite(TRACK)
        library = self.library.snapshot()
        library["favoriteAt"][TRACK["id"]] = "2026-07-01T00:00:00Z"
        self.library._atomic_write(self.library.path, self.library._encode(library))
        self.library = LibraryStore(self.library.path)
        self.history.record(TRACK, 30, played_at=utc("2026-08-01T00:00:00Z"))
        time = [0.0]
        runtime = HistoryRuntime(self.history, lambda: time[0], library=self.library,
                                 calendar_clock=lambda: utc("2026-10-06T00:00:00Z"))
        session = str(uuid.uuid4())
        runtime.sample(session, TRACK, 0, True)
        time[0] = 30.0
        result = runtime.sample(session, TRACK, 30, True)
        self.assertTrue(result["justRecorded"])
        self.assertTrue(result["revisitOldFavorite"])
        self.assertEqual(2, self.history.snapshot()["entries"][0]["playCount"])


class DiaryHttpTest(unittest.TestCase):
    def test_notes_lyrics_and_invalid_zone(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            server = create_server(port=0, provider=DemoProvider(),
                                   library_store=LibraryStore(root / "library.json"),
                                   history_store=HistoryStore(root / "history.json"))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            def request(method: str, path: str, body: bytes | None = None):
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                try:
                    connection.request(method, path, body=body,
                                       headers={"Content-Type": "application/xml"})
                    response = connection.getresponse()
                    return response.status, ElementTree.fromstring(response.read())
                finally:
                    connection.close()
            try:
                self.assertEqual(400, request("GET", "/api/diary?zone=Bad/Zone")[0])
                note = ElementTree.Element("note", {"trackId": TRACK["id"]})
                ElementTree.SubElement(note, "text").text = "独处时听"
                ElementTree.SubElement(note, "track", {key: str(value) for key, value in TRACK.items()})
                status, diary = request("POST", "/api/diary/notes", ElementTree.tostring(note))
                self.assertEqual(200, status)
                self.assertEqual(TRACK["id"], diary.find("notes/note/track").get("id"))
                created = diary.find("notes/note").get("createdAt")
                self.assertTrue(created.endswith("Z"))
                self.assertEqual(created, diary.find("notes/note").get("updatedAt"))
                note.find("text").text = "更新后的私人备注"
                status, diary = request("POST", "/api/diary/notes", ElementTree.tostring(note))
                self.assertEqual(200, status)
                self.assertEqual(created, diary.find("notes/note").get("createdAt"))
                self.assertGreaterEqual(diary.find("notes/note").get("updatedAt"), created)
                lyric = ElementTree.Element("lyric", {"trackId": TRACK["id"], "positionMillis": "3000"})
                ElementTree.SubElement(lyric, "text").text = "一句歌词"
                ElementTree.SubElement(lyric, "track", {key: str(value) for key, value in TRACK.items()})
                status, diary = request("POST", "/api/diary/lyrics", ElementTree.tostring(lyric))
                self.assertEqual(200, status)
                identifier = diary.find("lyricFavorites/lyric").get("id")
                self.assertEqual(200, request("DELETE", "/api/diary/lyrics/" + identifier)[0])
                self.assertIsNone(request("GET", "/api/diary")[1].find("lyricFavorites/lyric"))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)
