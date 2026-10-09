"""Real HTTP tests for listening qualification and history isolation."""

from __future__ import annotations

import http.client
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


class FakeClock:
    value = 0.0

    def __call__(self) -> float:
        return self.value


class HistoryHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.library_path = Path(self.temp.name) / "library.json"
        self.history_path = Path(self.temp.name) / "history.json"
        self.clock = FakeClock()
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=LibraryStore(self.library_path),
                                    history_store=HistoryStore(self.history_path),
                                    history_clock=self.clock)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: bytes | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, body=body,
                               headers={"Content-Type": "application/xml; charset=utf-8"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def sample(self, session_id: str, position: float, at: float,
               *, duration: int = 180, playing: bool = True,
               seeked: bool = False, finished: bool = False,
               track_id: str = "bilibili:BV1abc:p2", playback_rate=None, elapsed_seconds=None):
        self.clock.value = at
        root = ElementTree.Element("sample", {
            "sessionId": session_id, "positionSeconds": str(position),
            "playing": str(playing).lower(), "seeked": str(seeked).lower(),
            "finished": str(finished).lower(),
        })
        if playback_rate is not None:
            root.set("playbackRate", str(playback_rate))
        if elapsed_seconds is not None:
            root.set("elapsedSeconds", str(elapsed_seconds))
        ElementTree.SubElement(root, "track", {
            "id": track_id, "title": "第二 P", "artist": "歌手",
            "durationSeconds": str(duration), "source": "bilibili",
            "bvid": "BV1abc", "page": "2", "partCount": "",
            "coverUri": "https://i0.hdslb.com/example.jpg",
        })
        return self.request("POST", "/api/listening", ElementTree.tostring(root))

    def segment_sample(self, session_id: str, clip: dict, position: float, at: float,
                       *, playback_rate=1, finished=False):
        self.clock.value = at
        root = ElementTree.Element("sample", {
            "sessionId": session_id, "positionSeconds": str(position),
            "playing": "true", "seeked": "false", "finished": str(finished).lower(),
            "playbackRate": str(playback_rate),
        })
        ElementTree.SubElement(root, "track", {**clip, "partCount": ""})
        return self.request("POST", "/api/listening", ElementTree.tostring(root))

    def test_xml_response_explicitly_closes_http_connection(self):
        for method, path, body, expected_status in (
                ("GET", "/api/health", None, 200),
                ("POST", "/api/listening", b"<sample/>", 400)):
            with self.subTest(method=method, path=path):
                connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
                try:
                    connection.request(method, path, body=body,
                                       headers={"Content-Type": "application/xml"})
                    response = connection.getresponse()
                    self.assertEqual(expected_status, response.status)
                    self.assertEqual(10, response.version)
                    self.assertEqual("close", response.getheader("Connection"))
                    self.assertEqual(int(response.getheader("Content-Length")), len(response.read()))
                finally:
                    connection.close()

    def test_segment_sample_uses_segment_duration_and_keeps_identity(self):
        session_id = str(uuid.uuid4())
        clip = {"id": "segment:" + str(uuid.uuid4()), "title": "片段", "artist": "歌手",
                "durationSeconds": "3", "source": "bilibili", "bvid": "BV1abc", "page": "2",
                "sourceTrackId": "bilibili:BV1abc:p2", "segmentStartMillis": "2000",
                "segmentEndMillis": "5000", "sourceDurationSeconds": "180"}
        self.assertEqual("false", self.segment_sample(session_id, clip, 0, 0)[1].get("recorded"))
        self.assertEqual("false", self.segment_sample(session_id, clip, 2.6, 2.6)[1].get("recorded"))
        status, response = self.segment_sample(session_id, clip, 2.8, 2.8)
        self.assertEqual(200, status)
        self.assertEqual("true", response.get("recorded"))

        status, history = self.request("GET", "/api/history")
        self.assertEqual(200, status)
        track = history.find("entry/track")
        self.assertEqual(clip["id"], track.get("id"))
        self.assertEqual("2000", track.get("segmentStartMillis"))
        self.assertEqual("5000", track.get("segmentEndMillis"))
        self.assertEqual("180", track.get("sourceDurationSeconds"))

    def test_qualification_pause_seek_finish_and_restart(self):
        session_id = str(uuid.uuid4())
        self.assertEqual("false", self.sample(session_id, 0, 0)[1].get("recorded"))
        self.assertEqual("false", self.sample(session_id, 10, 10, playing=False)[1].get("recorded"))
        self.sample(session_id, 10, 20, playing=False)
        self.sample(session_id, 10, 21)
        self.sample(session_id, 100, 22, seeked=True)
        self.assertEqual("false", self.sample(session_id, 119.9, 41.9)[1].get("recorded"))
        status, response = self.sample(session_id, 120, 42)
        self.assertEqual(200, status)
        self.assertEqual("true", response.get("qualified"))
        self.assertEqual("true", response.get("recorded"))
        self.assertEqual("30.0", response.get("playedSeconds"))
        self.assertEqual("true", self.sample(session_id, 125, 47, finished=True)[1].get("finished"))
        self.assertEqual(400, self.sample(session_id, 126, 48)[0])

        status, history = self.request("GET", "/api/history")
        self.assertEqual(200, status)
        self.assertEqual("1", history.get("version"))
        entry = history.find("entry")
        self.assertEqual("1", entry.get("playCount"))
        self.assertEqual("bilibili:BV1abc:p2", entry.find("track").get("id"))
        self.assertEqual(1, len(HistoryStore(self.history_path).snapshot()["entries"]))

    def test_short_track_and_invalid_input(self):
        session_id = str(uuid.uuid4())
        self.sample(session_id, 0, 0, duration=10)
        self.assertEqual("false", self.sample(session_id, 8.9, 8.9, duration=10)[1].get("recorded"))
        self.assertEqual("true", self.sample(session_id, 9, 9, duration=10)[1].get("recorded"))
        self.assertEqual(400, self.sample(session_id, 9.1, 10, duration=60)[0])
        self.assertEqual(400, self.sample("not-a-uuid", 0, 10)[0])
        self.assertEqual(400, self.request("POST", "/api/listening", b"<sample/>")[0])

    def test_playback_rate_is_parsed_and_uses_real_time(self):
        for rate in (0.75, 1.0, 1.25, 1.5):
            with self.subTest(rate=rate):
                session_id = str(uuid.uuid4())
                self.assertEqual(200, self.sample(session_id, 0, 0, playback_rate=rate)[0])
                response = self.sample(session_id, 29 * rate, 29, playback_rate=rate)[1]
                self.assertEqual("false", response.get("recorded"))
                self.assertEqual("29.0", response.get("playedSeconds"))
                status, response = self.sample(session_id, 30 * rate, 30, playback_rate=rate)
                self.assertEqual(200, status)
                self.assertEqual("true", response.get("justRecorded"))
                self.assertEqual("30.0", response.get("playedSeconds"))
        self.assertEqual(4, HistoryStore(self.history_path).snapshot()["entries"][0]["playCount"])

    def test_fast_short_track_natural_finish_stores_actual_seconds(self):
        session_id = str(uuid.uuid4())
        self.sample(session_id, 0, 0, duration=10, playback_rate=1.5)
        response = self.sample(session_id, 8.9, 8.9 / 1.5, duration=10, playback_rate=1.5)[1]
        self.assertEqual("false", response.get("recorded"))
        status, response = self.sample(session_id, 10, 10 / 1.5, duration=10,
                                       playback_rate=1.5, finished=True)
        self.assertEqual(200, status)
        self.assertEqual("true", response.get("justRecorded"))
        self.assertEqual("6.667", response.get("playedSeconds"))
        self.assertEqual("10.0", response.get("mediaPlayedSeconds"))
        stored = HistoryStore(self.history_path).snapshot()["entries"][0]
        self.assertEqual(6.667, stored["totalQualifiedSeconds"])

    def test_slow_short_track_qualifies_at_ninety_percent_media(self):
        session_id = str(uuid.uuid4())
        self.sample(session_id, 0, 0, duration=10, playback_rate=0.75)
        self.assertEqual("false", self.sample(session_id, 8.9, 8.9 / 0.75, duration=10,
                                              playback_rate=0.75)[1].get("recorded"))
        status, response = self.sample(session_id, 9, 12, duration=10, playback_rate=0.75)
        self.assertEqual(200, status)
        self.assertEqual("true", response.get("justRecorded"))
        self.assertEqual("12.0", response.get("playedSeconds"))
        self.assertEqual(12, HistoryStore(self.history_path).snapshot()["entries"][0]["totalQualifiedSeconds"])

    def test_fast_short_segment_natural_finish_uses_precise_media_length(self):
        for length_millis in (10000, 59500):
            with self.subTest(length_millis=length_millis):
                session_id = str(uuid.uuid4())
                clip = {"id": "segment:" + str(uuid.uuid4()), "title": "片段", "artist": "歌手",
                        "durationSeconds": str((length_millis + 999) // 1000), "source": "bilibili",
                        "bvid": "BV1abc", "page": "2", "sourceTrackId": "bilibili:BV1abc:p2",
                        "segmentStartMillis": "2000", "segmentEndMillis": str(2000 + length_millis),
                        "sourceDurationSeconds": "180"}
                self.segment_sample(session_id, clip, 0, 0, playback_rate=1.5)
                threshold = length_millis / 1000 * 0.9
                self.assertEqual("false", self.segment_sample(session_id, clip, threshold - 0.1,
                                 (threshold - 0.1) / 1.5, playback_rate=1.5)[1].get("recorded"))
                status, response = self.segment_sample(session_id, clip, length_millis / 1000,
                    length_millis / 1000 / 1.5, playback_rate=1.5, finished=True)
                self.assertEqual(200, status)
                self.assertEqual("true", response.get("justRecorded"))
                self.assertAlmostEqual(threshold, float(response.get("thresholdSeconds")))
                entry = next(entry for entry in HistoryStore(self.history_path).snapshot()["entries"]
                             if entry["track"]["id"] == clip["id"])
                self.assertEqual(round(length_millis / 1000 / 1.5, 3), entry["totalQualifiedSeconds"])

    def test_invalid_playback_rate_is_rejected(self):
        for rate in (0, -1, "NaN", "Infinity", "invalid", ""):
            with self.subTest(rate=rate):
                status, response = self.sample(str(uuid.uuid4()), 0, 0, playback_rate=rate)
                self.assertEqual(400, status)
                self.assertEqual("HISTORY_INVALID", response.get("code"))

    def test_queued_observations_use_generation_time_without_losing_progress(self):
        session_id = str(uuid.uuid4())
        for position, received, observed in ((0, 1.2005, 0), (1.5, 1.2625, 1),
                                             (3, 1.9235, 2), (4.5, 2.9243, 3), (6, 3.9228, 4)):
            status, response = self.sample(session_id, position, received, duration=6,
                    playback_rate=1.5, elapsed_seconds=observed, finished=position == 6)
            self.assertEqual(200, status)
        self.assertEqual("true", response.get("justRecorded"))
        self.assertEqual("4.0", response.get("playedSeconds"))
        self.assertEqual("6.0", response.get("mediaPlayedSeconds"))
        self.assertEqual(4, HistoryStore(self.history_path).snapshot()["entries"][0]["totalQualifiedSeconds"])

    def test_generated_clock_rejects_invalid_backward_and_changed_sources(self):
        for elapsed in (-1, "NaN", "Infinity", "invalid", ""):
            with self.subTest(elapsed=elapsed):
                self.assertEqual(400, self.sample(str(uuid.uuid4()), 0, 0, elapsed_seconds=elapsed)[0])
        session_id = str(uuid.uuid4())
        self.sample(session_id, 0, 0, elapsed_seconds=0)
        self.sample(session_id, 1, 0, elapsed_seconds=1)
        self.assertEqual(400, self.sample(session_id, 1, 0, elapsed_seconds=0.9)[0])
        self.assertEqual(400, self.sample(session_id, 1, 0)[0])
        old_session = str(uuid.uuid4())
        self.sample(old_session, 0, 0)
        self.assertEqual(400, self.sample(old_session, 0, 0, elapsed_seconds=0)[0])

    def test_damaged_history_does_not_disable_search(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        original = b"{broken"
        self.history_path.write_bytes(original)
        with patch.dict("os.environ", {"LUMEN_HISTORY_PATH": str(self.history_path)}):
            self.server = create_server(port=0, provider=DemoProvider(),
                                        library_store=LibraryStore(self.library_path))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        status, response = self.request("GET", "/api/history")
        self.assertEqual(500, status)
        self.assertEqual("HISTORY_DAMAGED", response.get("code"))
        self.assertEqual(500, self.sample(str(uuid.uuid4()), 0, 0)[0])
        self.assertEqual(200, self.request("GET", "/api/tracks")[0])
        self.assertEqual(original, self.history_path.read_bytes())

    def test_history_path_defaults_beside_custom_library(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=LibraryStore(self.library_path),
                                    history_clock=self.clock)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        session_id = str(uuid.uuid4())
        self.sample(session_id, 0, 0)
        self.sample(session_id, 30, 30)
        self.assertTrue(self.history_path.exists())


if __name__ == "__main__":
    unittest.main()
