"""Segment identity, library mutation, history, and backup contracts."""

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
from lumen_service.history import HistoryError, HistoryRuntime, HistoryStore, ListeningSession
from lumen_service.library import LibraryError, LibraryStore, StoredTrack
from lumen_service.providers import DemoProvider, ProviderError
from lumen_service.server import create_server


SOURCE_ID = "bilibili:BV1abc:p2"


def segment(start: int, end: int, *, identifier: str | None = None) -> dict:
    return {"id": identifier or f"segment:{uuid.uuid4()}", "title": "片段",
            "artist": "歌手", "durationSeconds": (end - start + 999) // 1000,
            "source": "bilibili", "bvid": "BV1abc", "page": 2,
            "sourceTrackId": SOURCE_ID, "sourceDurationSeconds": 180,
            "segmentStartMillis": start, "segmentEndMillis": end}


class SegmentStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.library_path = self.root / "library.json"
        self.library = LibraryStore(self.library_path)

    def test_two_clips_same_source_edit_and_delete_all_references(self) -> None:
        first = segment(1000, 10501)
        second = segment(20000, 30000)
        playlist_id = self.library.create_playlist("歌单")["playlists"][0]["id"]
        self.library.favorite(first)
        self.library.favorite(second)
        self.library.add_to_playlist(playlist_id, first)
        self.library.add_to_playlist(playlist_id, second)
        changed = self.library.edit_segment(first["id"], {
            "title": "新标题", "artist": "新歌手",
            "segmentStartMillis": 2000, "segmentEndMillis": 13500})
        for track in (changed["favorites"][0], changed["playlists"][0]["tracks"][0]):
            self.assertEqual(("新标题", "新歌手", 12),
                             (track["title"], track["artist"], track["durationSeconds"]))
            self.assertEqual(SOURCE_ID, track["sourceTrackId"])
        self.assertEqual(StoredTrack.from_value(second).to_dict(), changed["favorites"][1])
        self.assertEqual(SOURCE_ID, self.library.source_track_id(first["id"]))
        self.library.delete_segment(first["id"])
        reopened = LibraryStore(self.library_path).snapshot()
        self.assertEqual([second["id"]], [t["id"] for t in reopened["favorites"]])
        self.assertEqual([second["id"]], [t["id"] for t in reopened["playlists"][0]["tracks"]])

    def test_invalid_ranges_and_ordinary_track_edits_leave_disk_untouched(self) -> None:
        valid = segment(1000, 10000)
        self.library.favorite(valid)
        self.library.favorite({"id": SOURCE_ID, "title": "原曲", "artist": "歌手",
                               "durationSeconds": 180, "source": "bilibili"})
        original = self.library_path.read_bytes()
        for updates in ({"segmentEndMillis": 1000}, {"segmentEndMillis": 180001},
                        {"segmentStartMillis": -1}, {"title": " "},
                        {"sourceTrackId": "other"}):
            with self.subTest(updates=updates), self.assertRaises(LibraryError):
                self.library.edit_segment(valid["id"], updates)
            self.assertEqual(original, self.library_path.read_bytes())
        with self.assertRaises(LibraryError):
            self.library.edit_segment(SOURCE_ID, {"title": "不允许"})
        with self.assertRaises(LibraryError):
            self.library.delete_segment(SOURCE_ID)
        self.assertEqual(original, self.library_path.read_bytes())
        for invalid in (segment(0, 0), segment(0, 180001),
                        {**valid, "id": f"segment:{uuid.uuid4()}",
                         "sourceDurationSeconds": -1},
                        {**valid, "id": f"segment:{uuid.uuid4()}", "durationSeconds": 180}):
            with self.assertRaises(LibraryError):
                self.library.favorite(invalid)
            self.assertEqual(original, self.library_path.read_bytes())

    def test_unknown_source_duration_persists_and_can_be_edited(self) -> None:
        clip = {**segment(190000, 195100), "sourceDurationSeconds": 0}
        self.library.favorite(clip)
        reopened = LibraryStore(self.library_path)
        self.assertEqual(0, reopened.snapshot()["favorites"][0]["sourceDurationSeconds"])
        changed = reopened.edit_segment(clip["id"], {"segmentEndMillis": 200000})
        self.assertEqual(10, changed["favorites"][0]["durationSeconds"])
        self.assertEqual(0, LibraryStore(self.library_path).snapshot()["favorites"][0]["sourceDurationSeconds"])

    def test_old_v1_and_backup_round_trip(self) -> None:
        old = {"id": SOURCE_ID, "title": "旧曲", "artist": "歌手",
               "durationSeconds": 180, "source": "bilibili"}
        self.library_path.write_text(json.dumps({"version": 1, "favorites": [old],
                                                 "playlists": []}), encoding="utf-8")
        self.assertNotIn("sourceTrackId", LibraryStore(self.library_path).snapshot()["favorites"][0])
        clip = segment(3000, 12000)
        reloaded = LibraryStore(self.library_path)
        reloaded.favorite(clip)
        history_path = self.root / "history.json"
        history = HistoryStore(history_path)
        with self.assertRaises(HistoryError):
            history.record(clip, 8.09)
        history.record(clip, 8.1)
        archive = self.root / "backup.zip"
        export_backup(archive, self.library_path, history_path=history_path)
        restored_library = self.root / "restored-library.json"
        restored_history = self.root / "restored-history.json"
        restore_backup(archive, restored_library, history_path=restored_history)
        normalized = StoredTrack.from_value(clip).to_dict()
        self.assertEqual(normalized, LibraryStore(restored_library).snapshot()["favorites"][1])
        self.assertEqual(normalized, HistoryStore(restored_history).snapshot()["entries"][0]["track"])
        session = ListeningSession(clip["id"], clip["durationSeconds"])
        session.observe(0, 0, True)
        self.assertFalse(session.observe(8.09, 8.09, True))
        self.assertTrue(session.observe(8.1, 8.1, True))

    def test_fractional_short_segment_uses_exact_ninety_percent(self) -> None:
        clip = segment(1000, 7100)
        history = HistoryStore(self.root / "history.json")
        with self.assertRaises(HistoryError):
            history.record(clip, 5.48)
        history.record(clip, 5.49)
        moment = [0.0]
        runtime = HistoryRuntime(HistoryStore(self.root / "runtime-history.json"),
                                 clock=lambda: moment[0])
        session_id = str(uuid.uuid4())
        self.assertAlmostEqual(5.49, runtime.sample(session_id, clip, 0, True)["thresholdSeconds"])
        moment[0] = 5.48
        self.assertFalse(runtime.sample(session_id, clip, 5.48, True)["recorded"])
        moment[0] = 5.49
        self.assertTrue(runtime.sample(session_id, clip, 5.49, True)["recorded"])


class SegmentHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "library.json"
        self.store = LibraryStore(self.path)
        class ProbeProxy:
            requested_id = None

            def open(self, track_id, requested_range=None, head=False):
                self.requested_id = track_id
                raise ProviderError("PROBE", "probe", 404)

        self.proxy = ProbeProxy()
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=self.store, audio_proxy=self.proxy)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: ElementTree.Element | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, body=ElementTree.tostring(body) if body is not None else None,
                               headers={"Content-Type": "application/xml"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def test_create_edit_delete_and_invalid_xml(self) -> None:
        clip = segment(5000, 15001)
        body = ElementTree.Element("track", {key: str(value) for key, value in clip.items()})
        status, root = self.request("POST", "/api/library/favorites", body)
        self.assertEqual(200, status)
        self.assertEqual("11", root.find("favorites/track").get("durationSeconds"))
        self.assertEqual(SOURCE_ID, root.find("favorites/track").get("sourceTrackId"))
        self.assertEqual(404, self.request("GET", f"/api/tracks/{clip['id']}/stream")[0])
        self.assertEqual(SOURCE_ID, self.proxy.requested_id)
        path = f"/api/library/tracks/{clip['id']}"
        updated = {**clip, "title": "修改", "segmentEndMillis": 17000,
                   "durationSeconds": 12}
        status, root = self.request("PATCH", path, ElementTree.Element("track", {
            key: str(value) for key, value in updated.items()}))
        self.assertEqual(200, status)
        self.assertEqual("修改", root.find("favorites/track").get("title"))
        self.assertEqual("12", root.find("favorites/track").get("durationSeconds"))
        before = self.path.read_bytes()
        self.assertEqual(400, self.request("PATCH", path, ElementTree.Element("track", {
            "segmentEndMillis": "200000"}))[0])
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(200, self.request("DELETE", path)[0])
        self.assertEqual([], LibraryStore(self.path).snapshot()["favorites"])

    def test_desktop_client_payloads_are_accepted(self) -> None:
        """桌面端 LibraryClient 生成的收藏与编辑 XML 必须被端点接受。"""
        ordinary = ElementTree.Element("track", {
            "id": "bilibili:BV1xx411c7mD:p1", "title": "普通曲目", "artist": "UP主",
            "durationSeconds": "180", "source": "bilibili", "bvid": "BV1xx411c7mD",
            "page": "1", "partCount": "3", "viewCount": "42"})
        status, root = self.request("POST", "/api/library/favorites", ordinary)
        self.assertEqual(200, status, ElementTree.tostring(root))
        self.assertEqual("180", root.find("favorites/track").get("durationSeconds"))
        self.assertIsNone(root.find("favorites/track").get("sourceTrackId"))
        # 兼容仍会输出空可选属性的旧客户端：空字符串等价于未提供。
        legacy = ElementTree.Element("track", {
            "id": "bilibili:BV1xx411c7mD:p2", "title": "旧格式", "artist": "UP主",
            "durationSeconds": "90", "source": "bilibili", "bvid": "BV1xx411c7mD",
            "page": "0", "mediaUri": "", "coverUri": "", "partCount": "", "viewCount": "",
            "sourceTrackId": "", "segmentStartMillis": "", "segmentEndMillis": "",
            "sourceDurationSeconds": ""})
        self.assertEqual(200, self.request("POST", "/api/library/favorites", legacy)[0])

        clip = segment(2000, 5000)
        self.assertEqual(200, self.request("POST", "/api/library/favorites", ElementTree.Element(
            "track", {key: str(value) for key, value in clip.items()}))[0])
        edited = {**clip, "title": "改名", "segmentEndMillis": 6000, "durationSeconds": 4}
        stored = f"favorites/track[@id='{clip['id']}']"
        status, root = self.request("PATCH", f"/api/library/tracks/{clip['id']}",
                                    ElementTree.Element("track", {
                                        key: str(value) for key, value in edited.items()}))
        self.assertEqual(200, status, ElementTree.tostring(root))
        self.assertEqual("6000", root.find(stored).get("segmentEndMillis"))
        # 旧客户端提交的完整 <track>（含空可选属性）同样必须能编辑。
        status, root = self.request("PATCH", f"/api/library/tracks/{clip['id']}",
                                    ElementTree.Element("track", {
                                        key: str(value) for key, value in edited.items()} | {
                                        "mediaUri": "", "coverUri": "", "partCount": "",
                                        "viewCount": ""}))
        self.assertEqual(200, status, ElementTree.tostring(root))
        self.assertEqual("2000", root.find(stored).get("segmentStartMillis"))

    def test_unknown_source_duration_http_round_trip(self) -> None:
        clip = {**segment(190000, 195100), "sourceDurationSeconds": 0}
        body = ElementTree.Element("track", {key: str(value) for key, value in clip.items()})
        status, root = self.request("POST", "/api/library/favorites", body)
        self.assertEqual(200, status)
        self.assertEqual("0", root.find("favorites/track").get("sourceDurationSeconds"))
        status, root = self.request("GET", "/api/library")
        self.assertEqual(200, status)
        self.assertEqual("0", root.find("favorites/track").get("sourceDurationSeconds"))


if __name__ == "__main__":
    unittest.main()
