"""Qualification and on-disk history regression tests."""

import json
import tempfile
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path

from lumen_service.history import (
    HistoryError, HistoryFormatError, HistoryRuntime, HistoryStore, ListeningSession,
    required_listening_seconds,
)


TRACK = {"id": "bilibili:BV1abc:p2", "title": "第二 P", "artist": "演唱者",
         "durationSeconds": 180, "source": "bilibili", "bvid": "BV1abc", "page": 2}


class ListeningSessionTest(unittest.TestCase):
    def test_boundary_and_thresholds(self):
        self.assertIsNone(required_listening_seconds(0))
        self.assertAlmostEqual(53.1, required_listening_seconds(59))
        self.assertEqual(30, required_listening_seconds(60))
        with self.assertRaises(HistoryError):
            required_listening_seconds(-1)

    def test_long_track_counts_once_after_thirty_real_seconds(self):
        session = ListeningSession(TRACK["id"], 180)
        self.assertFalse(session.observe(0, 0, True))
        self.assertFalse(session.observe(29.9, 29.9, True))
        self.assertTrue(session.observe(30, 30, True))
        self.assertFalse(session.observe(60, 60, True))
        self.assertEqual(60, session.played_seconds)

    def test_short_track_needs_ninety_percent(self):
        session = ListeningSession("short", 10)
        session.observe(0, 0, True)
        self.assertFalse(session.observe(8.9, 8.9, True))
        self.assertTrue(session.observe(9, 9, True))

    def test_pause_seek_repeat_and_unannounced_jump_do_not_inflate(self):
        session = ListeningSession("seek", 180)
        session.observe(0, 0, True)
        session.observe(5, 5, False)
        session.observe(5, 20, False)
        session.observe(5, 21, True)
        session.observe(100, 22, True, seeked=True)
        session.observe(105, 27, True)
        session.observe(105, 27, True)
        session.observe(150, 28, True)  # unannounced seek-like jump
        session.observe(149, 29, True)  # backwards
        self.assertEqual(10, session.played_seconds)
        session.observe(150, 30, True)
        self.assertEqual(11, session.played_seconds)

    def test_position_progress_cannot_exceed_wall_time(self):
        session = ListeningSession("speed", 180)
        session.observe(0, 0, True)
        session.observe(1, 10, True)
        self.assertEqual(1, session.played_seconds)

    def test_rates_count_actual_playing_seconds(self):
        for rate in (0.75, 1.0, 1.25, 1.5):
            with self.subTest(rate=rate):
                session = ListeningSession("speed", 180)
                session.observe(0, 0, True, playback_rate=rate)
                self.assertFalse(session.observe(29.9 * rate, 29.9, True, playback_rate=rate))
                self.assertTrue(session.observe(30 * rate, 30, True, playback_rate=rate))
                self.assertAlmostEqual(30, session.played_seconds)

    def test_rate_change_pause_stall_and_seek_do_not_inflate(self):
        session = ListeningSession("transitions", 180)
        session.observe(0, 0, True, playback_rate=0.75)
        session.observe(7.5, 10, True, playback_rate=1.5)
        session.observe(22.5, 20, False, playback_rate=1.5)
        session.observe(22.5, 50, True, playback_rate=1.5)
        session.observe(22.5, 60, True, playback_rate=1.5)  # stalled
        session.observe(100, 61, True, playback_rate=1.5)  # unannounced seek
        session.observe(10, 62, True, seeked=True, playback_rate=1.5)
        self.assertEqual(20, session.played_seconds)
        self.assertEqual(22.5, session.media_played_seconds)
        self.assertTrue(session.observe(25, 72, True, playback_rate=1.5))
        self.assertEqual(30, session.played_seconds)

    def test_short_track_loop_counts_real_time_without_requalifying(self):
        session = ListeningSession("short-loop", 10)
        session.observe(0, 0, True, playback_rate=1.5)
        self.assertTrue(session.observe(9, 6, True, playback_rate=1.5))
        session.observe(0, 6, True, seeked=True, playback_rate=1.5)
        self.assertFalse(session.observe(4.5, 9, True, playback_rate=1.5))
        session.observe(0, 9, True, seeked=True, playback_rate=1.5)
        self.assertFalse(session.observe(9, 15, True, playback_rate=1.5))
        self.assertEqual(15, session.played_seconds)
        self.assertEqual(22.5, session.media_played_seconds)

    def test_short_track_rate_qualification_uses_media_progress(self):
        for rate in (0.75, 1.0, 1.25, 1.5):
            with self.subTest(rate=rate):
                session = ListeningSession("short", 10)
                session.observe(0, 0, True, playback_rate=rate)
                self.assertFalse(session.observe(8.9, 8.9 / rate, True, playback_rate=rate))
                self.assertTrue(session.observe(9, 9 / rate, True, playback_rate=rate))
                self.assertAlmostEqual(9 / rate, session.played_seconds)
                self.assertAlmostEqual(9, session.media_played_seconds)

    def test_receive_time_jitter_does_not_trim_verified_media_qualification(self):
        session = ListeningSession("short-jitter", 6)
        session.observe(0, 0.24, True, playback_rate=1.5)
        for position, received_at in ((1.5, 1), (3, 2.24), (4.5, 3)):
            self.assertFalse(session.observe(position, received_at, True, playback_rate=1.5))
        self.assertTrue(session.finish(6, 4.24, playback_rate=1.5))
        self.assertAlmostEqual(3.52, session.played_seconds)
        self.assertAlmostEqual(6, session.media_played_seconds)

    def test_unannounced_fast_jump_adds_neither_real_nor_media_seconds(self):
        session = ListeningSession("short-jump", 10)
        session.observe(0, 0, True, playback_rate=1.5)
        self.assertFalse(session.observe(9, 1, True, playback_rate=1.5))
        self.assertEqual(0, session.played_seconds)
        self.assertEqual(0, session.media_played_seconds)
        self.assertFalse(session.observe(0, 2, True, seeked=True, playback_rate=1.5))
        self.assertEqual(0, session.media_played_seconds)
        session.observe(1.5, 3, True, playback_rate=1.5)
        self.assertEqual(1, session.played_seconds)
        self.assertEqual(1.5, session.media_played_seconds)

    def test_invalid_rates_do_not_change_the_last_valid_sample(self):
        session = ListeningSession("invalid-rate", 180)
        session.observe(0, 0, True)
        for rate in (0, -1, float("inf"), float("nan"), True, "1"):
            with self.subTest(rate=rate), self.assertRaises(HistoryError):
                session.observe(10, 10, True, playback_rate=rate)
        session.observe(10, 10, True)
        self.assertEqual(10, session.played_seconds)

    def test_finish_credits_final_interval_and_rejects_reuse(self):
        session = ListeningSession("end", 60)
        session.observe(0, 0, True)
        self.assertTrue(session.finish(30, 30))
        with self.assertRaises(HistoryError):
            session.observe(31, 31, True)


class HistoryStoreTest(unittest.TestCase):
    def test_generated_time_qualifies_when_server_receives_a_delayed_batch(self):
        with tempfile.TemporaryDirectory() as directory:
            received = [1.2]
            runtime = HistoryRuntime(HistoryStore(Path(directory) / "history.json"),
                                     clock=lambda: received[0])
            short = {**TRACK, "durationSeconds": 6}
            session_id = str(uuid.uuid4())
            for observed in range(5):
                result = runtime.sample(session_id, short, observed * 1.5, True,
                                        playback_rate=1.5, elapsed_seconds=observed,
                                        finished=observed == 4)
            self.assertEqual(4, result["playedSeconds"])
            self.assertEqual(6, result["mediaPlayedSeconds"])
            self.assertTrue(result["recorded"])
            self.assertEqual(4, runtime.store.snapshot()["entries"][0]["totalQualifiedSeconds"])

    def test_short_track_verified_media_qualifies_without_inflating_stored_time(self):
        with tempfile.TemporaryDirectory() as directory:
            store = HistoryStore(Path(directory) / "history.json")
            short = {**TRACK, "durationSeconds": 10}
            with self.assertRaises(HistoryError):
                store.record(short, 6)
            with self.assertRaises(HistoryError):
                store.record(short, 12, verified_media_seconds=8.9)
            store.record(short, 6, verified_media_seconds=9)
            entry = store.snapshot()["entries"][0]
            self.assertEqual(6, entry["totalQualifiedSeconds"])
            self.assertNotIn("verifiedMediaSeconds", entry)
            self.assertNotIn("mediaPlayedSeconds", entry)
            for media in (float("nan"), -1, True, "9"):
                with self.subTest(media=media), self.assertRaises(HistoryError):
                    store.record(short, 6, verified_media_seconds=media)
            with self.assertRaises(HistoryError):
                store.record(TRACK, 20, verified_media_seconds=30)

    def test_loop_records_once_and_restart_rejects_recorded_session(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.json"
            now = [0.0]
            runtime = HistoryRuntime(HistoryStore(path), clock=lambda: now[0])
            session_id = str(uuid.uuid4())
            runtime.sample(session_id, TRACK, 50, True, playback_rate=1.5)
            for loop in range(4):
                now[0] = (loop + 1) * 10
                result = runtime.sample(session_id, TRACK, 65, True, playback_rate=1.5)
                self.assertEqual(loop == 2, result["justRecorded"])
                runtime.sample(session_id, TRACK, 50, True, seeked=True, playback_rate=1.5)
            self.assertEqual(40, result["playedSeconds"])
            self.assertEqual(1, runtime.store.snapshot()["entries"][0]["playCount"])
            restarted = HistoryRuntime(HistoryStore(path), clock=lambda: now[0])
            with self.assertRaises(HistoryError):
                restarted.sample(session_id, TRACK, 50, True)

    def test_new_session_and_track_switch_do_not_share_progress(self):
        with tempfile.TemporaryDirectory() as directory:
            now = [0.0]
            runtime = HistoryRuntime(HistoryStore(Path(directory) / "history.json"),
                                     clock=lambda: now[0])
            first = str(uuid.uuid4())
            second = str(uuid.uuid4())
            runtime.sample(first, TRACK, 0, True, playback_rate=1.5)
            now[0] = 20
            runtime.sample(first, TRACK, 30, True, playback_rate=1.5)
            other = {**TRACK, "id": "bilibili:BV1abc:p3", "page": 3}
            with self.assertRaises(HistoryError):
                runtime.sample(first, other, 30, True, playback_rate=1.5)
            runtime.sample(second, other, 0, True, playback_rate=0.75)
            now[0] = 30
            result = runtime.sample(second, other, 7.5, True, playback_rate=0.75)
            self.assertEqual(10, result["playedSeconds"])
            self.assertFalse(result["recorded"])

    def test_record_survives_restart_and_groups_by_track_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.json"
            store = HistoryStore(path)
            self.assertEqual([], store.snapshot()["entries"])
            at = datetime(2026, 9, 25, tzinfo=timezone.utc)
            store.record(TRACK, 30, played_at=at)
            store.record(TRACK, 35, played_at=at)
            restarted = HistoryStore(path)
            entries = restarted.snapshot()["entries"]
            self.assertEqual(1, len(entries))
            self.assertEqual(2, entries[0]["playCount"])
            self.assertEqual(65, entries[0]["totalQualifiedSeconds"])
            self.assertEqual("bilibili:BV1abc:p2", entries[0]["track"]["id"])
            self.assertEqual(2, entries[0]["track"]["page"])
            self.assertTrue(path.with_name("history.json.bak").exists())

    def test_incomplete_play_is_not_persisted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.json"
            store = HistoryStore(path)
            with self.assertRaises(HistoryError):
                store.record(TRACK, 29.99)
            self.assertFalse(path.exists())

    def test_corrupt_file_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.json"
            original = b'{"version":1,"entries":[bad]}'
            path.write_bytes(original)
            with self.assertRaises(HistoryFormatError):
                HistoryStore(path)
            self.assertEqual(original, path.read_bytes())

    def test_invalid_entry_is_rejected_and_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.json"
            payload = {"version": 1, "entries": [{"track": TRACK,
                       "playCount": True, "lastPlayedAt": "yesterday",
                       "totalQualifiedSeconds": 30}]}
            original = json.dumps(payload).encode()
            path.write_bytes(original)
            with self.assertRaises(HistoryFormatError):
                HistoryStore(path)
            self.assertEqual(original, path.read_bytes())

    def test_external_edit_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.json"
            store = HistoryStore(path)
            store.record(TRACK, 30)
            path.write_text("external", encoding="utf-8")
            with self.assertRaises(HistoryError):
                store.record(TRACK, 30)
            self.assertEqual("external", path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
