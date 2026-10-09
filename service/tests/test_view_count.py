from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

from lumen_service.favorite_import import fetch_public_favorite
from lumen_service.library import LibraryError, LibraryStore, StoredTrack
from lumen_service.models import Track, parse_view_count
from lumen_service.providers import BilibiliProvider
from lumen_service.server import _history_xml, _library_xml, _track_input, _tracks_xml


BVID = "BV1xx411c7mD"


class ViewCountTest(unittest.TestCase):
    def test_only_exact_nonnegative_counts_are_accepted(self):
        for value, expected in ((123, 123), ("456", 456), (0, 0), (None, None),
                                (True, None), (-1, None), (12.3, None), ("1.2万", None)):
            with self.subTest(value=value):
                self.assertEqual(expected, parse_view_count(value))

    def test_search_reads_play_and_omits_unknown_count_from_xml(self):
        items = [{"bvid": BVID, "title": "歌曲", "author": "作者", "duration": "03:00", "play": "7809"},
                 {"bvid": "BV1xx411c7mE", "title": "另一首", "duration": "02:00"}]
        provider = BilibiliProvider(fetch_json=lambda _: {"code": 0, "data": {"result": items}})
        tracks = provider.search("歌曲")
        self.assertEqual((7809, None), tuple(track.view_count for track in tracks))
        self.assertEqual((f"bilibili:{BVID}:p1", 180), (tracks[0].id, tracks[0].duration_seconds))
        nodes = ElementTree.fromstring(_tracks_xml(tracks)).findall("track")
        self.assertEqual("7809", nodes[0].get("viewCount"))
        self.assertIsNone(nodes[1].get("viewCount"))

    def test_detail_parts_share_video_stat_view_without_changing_identity_or_duration(self):
        payload = {"code": 0, "data": {
            "bvid": BVID, "title": "合集", "stat": {"view": 200987},
            "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
            "pages": [{"page": 1, "part": "第一首", "duration": 180},
                      {"page": 2, "part": "第二首", "duration": 240}],
        }}
        tracks = BilibiliProvider(fetch_json=lambda _: payload).resolve_bv(BVID)
        self.assertEqual((200987, 200987), tuple(track.view_count for track in tracks))
        self.assertEqual((f"bilibili:{BVID}:p1", f"bilibili:{BVID}:p2"),
                         tuple(track.id for track in tracks))
        self.assertEqual((180, 240), tuple(track.duration_seconds for track in tracks))

    def test_ranking_uses_existing_stat_view(self):
        payload = {"code": 0, "data": {"list": [{
            "bvid": BVID, "title": "音乐榜", "duration": 180,
            "owner": {"name": "作者"}, "stat": {"view": 345678},
        }]}}
        self.assertEqual(345678, BilibiliProvider(fetch_json=lambda _: payload).music_popular()[0].view_count)

    def test_favorite_reads_cnt_info_play_and_preserves_unknown(self):
        media = {"type": 2, "attr": 0, "bvid": BVID, "title": "歌曲", "duration": 180,
                 "cnt_info": {"play": 87654}}
        unknown = {**media, "bvid": "BV1xx411c7mE", "cnt_info": {}}
        payload = {"code": 0, "data": {"info": {"id": 123, "title": "收藏夹"},
                                            "medias": [media, unknown], "has_more": False}}
        result = fetch_public_favorite(BilibiliProvider(fetch_json=lambda _: payload), "123")
        self.assertEqual((87654, None), tuple(track.view_count for track in result.tracks))

    def test_count_round_trips_through_library_storage_and_xml(self):
        node = ElementTree.fromstring(
            f'<track id="bilibili:{BVID}:p1" title="song" artist="artist" '
            'durationSeconds="180" source="bilibili" viewCount="987654" />')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "library.json"
            LibraryStore(path).favorite(_track_input(node))
            snapshot = LibraryStore(path).snapshot()
        self.assertEqual(987654, snapshot["favorites"][0]["viewCount"])
        self.assertEqual("987654", ElementTree.fromstring(_library_xml(snapshot)).find("favorites/track").get("viewCount"))
        history = {"version": 1, "entries": [{"playCount": 1, "lastPlayedAt": "2026-09-29T00:00:00Z",
                    "totalQualifiedSeconds": 60, "track": snapshot["favorites"][0]}]}
        self.assertEqual("987654", ElementTree.fromstring(_history_xml(history)).find("entry/track").get("viewCount"))

    def test_old_track_remains_unknown_and_invalid_stored_count_is_rejected(self):
        track = Track("local", "song", "artist", 180, "local")
        stored = StoredTrack.from_value(track)
        self.assertIsNone(stored.view_count)
        self.assertNotIn("viewCount", stored.to_dict())
        for value in (-1, True, "123"):
            with self.subTest(value=value), self.assertRaises(LibraryError):
                StoredTrack.from_value({**stored.to_dict(), "viewCount": value})


if __name__ == "__main__":
    unittest.main()
