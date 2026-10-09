from __future__ import annotations

import http.client
import io
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from xml.etree import ElementTree

from lumen_service.favorite_import import fetch_public_favorite, parse_favorite_id
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.providers import BilibiliProvider, ProviderError
from lumen_service.server import create_server


_ID = "107039480"
_BV1 = "BV1xx411c7mD"
_BV2 = "BV1xx411c7mE"


def _media(bvid: str, *, attr: int = 0, media_type: int = 2) -> dict:
    return {"type": media_type, "attr": attr, "bvid": bvid,
            "title": "<em>晴天</em> &amp; 风", "duration": 240, "page": 2,
            "upper": {"name": "周杰伦"},
            "cover": "//i0.hdslb.com/bfs/archive/example.jpg"}


def _page(number: int, medias: list[dict] | None, has_more: bool) -> dict:
    return {"code": 0, "data": {"info": {"id": int(_ID), "title": "周杰伦歌单"},
                                 "medias": medias, "has_more": has_more}}


class FavoriteImportTest(unittest.TestCase):
    def test_parse_canonical_links(self) -> None:
        values = (_ID, f"https://www.bilibili.com/medialist/detail/ml{_ID}",
                  f"https://www.bilibili.com/medialist/play/ml{_ID}?oid=1",
                  f"https://space.bilibili.com/123/favlist?fid={_ID}&ftype=create",
                  f"https://www.bilibili.com/list/ml{_ID}")
        for value in values:
            with self.subTest(value=value):
                self.assertEqual(_ID, parse_favorite_id(value))
        for value in ("https://evil.example/favlist?fid=123", "https://b23.tv/abcd",
                      "https://space.bilibili.com/123/favlist?fid=opus", "0", ""):
            with self.subTest(value=value), self.assertRaises(ProviderError):
                parse_favorite_id(value)

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.library = LibraryStore(root / "library.json")
        self.history = HistoryStore(root / "history.json")
        self.urls: list[str] = []
        self.responses = {
            1: _page(1, [_media(_BV1), _media(_BV1), _media(_BV2, attr=9),
                         _media(_BV2, attr=1), _media(_BV2, media_type=12)], True),
            2: _page(2, [_media(_BV2)], False),
        }

        def fetch(url: str) -> dict:
            self.urls.append(url)
            page = int(parse_qs(urlsplit(url).query)["pn"][0])
            return self.responses[page]

        self.provider = BilibiliProvider(fetch_json=fetch)
        self.server = create_server(port=0, provider=self.provider, library_store=self.library,
                                    history_store=self.history)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: bytes | None = None) -> tuple[int, ElementTree.Element]:
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            conn.request(method, path, body=body,
                         headers={"Content-Type": "application/xml"} if body else {})
            response = conn.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            conn.close()

    def test_two_pages_skip_invalid_and_duplicates_then_persist(self) -> None:
        body = f'<import url="https://www.bilibili.com/medialist/detail/ml{_ID}" />'.encode()
        status, result = self.request("POST", "/api/library/playlists/import", body)
        self.assertEqual((200, "2", "3", "1"),
                         (status, result.get("importedCount"), result.get("skippedCount"),
                          result.get("duplicateCount")))
        self.assertEqual("周杰伦歌单", result.get("name"))
        self.assertEqual("false", result.get("truncated"))
        self.assertEqual(["1", "2"], [parse_qs(urlsplit(url).query)["pn"][0]
                                     for url in self.urls])
        playlist = LibraryStore(self.library.path).snapshot()["playlists"][0]
        self.assertEqual(result.get("playlistId"), playlist["id"])
        self.assertEqual([f"bilibili:{_BV1}:p1", f"bilibili:{_BV2}:p1"],
                         [item["id"] for item in playlist["tracks"]])
        self.assertEqual("晴天 & 风", playlist["tracks"][0]["title"])
        self.assertEqual(2, playlist["tracks"][0]["partCount"])

    def test_error_on_second_page_leaves_library_unmodified(self) -> None:
        self.responses[2] = {"code": -403, "data": None}
        status, error = self.request("POST", "/api/library/playlists/import",
                                     f'<import url="{_ID}" />'.encode())
        self.assertEqual((403, "FAVORITE_INACCESSIBLE"), (status, error.get("code")))
        self.assertEqual([], self.library.snapshot()["playlists"])
        self.assertFalse(self.library.path.exists())

    def test_two_hundred_limit_is_reported_and_persisted_through_http(self) -> None:
        self.responses = {
            page: _page(page, [_media(f"BV{number:010d}")
                              for number in range((page - 1) * 20, page * 20)], True)
            for page in range(1, 11)
        }
        status, result = self.request("POST", "/api/library/playlists/import",
                                      f'<import url="{_ID}" />'.encode())
        self.assertEqual((200, "200", "true"),
                         (status, result.get("importedCount"), result.get("truncated")))
        playlist = LibraryStore(self.library.path).snapshot()["playlists"][0]
        self.assertEqual(200, len(playlist["tracks"]))
        self.assertEqual(10, len(self.urls))

    def test_invalid_url_and_invalid_response_are_explicit(self) -> None:
        status, error = self.request("POST", "/api/library/playlists/import",
                                     b'<import url="https://other.example/test" />')
        self.assertEqual((400, "INVALID_FAVORITE_URL"), (status, error.get("code")))
        self.assertEqual([], self.urls)
        self.responses[1] = _page(1, [], True)
        status, error = self.request("POST", "/api/library/playlists/import",
                                     f'<import url="{_ID}" />'.encode())
        self.assertEqual((502, "UPSTREAM_INVALID_RESPONSE"), (status, error.get("code")))
        self.assertEqual([], self.library.snapshot()["playlists"])

    def test_slow_invalid_pages_share_import_budget_and_leave_library_untouched(self) -> None:
        self.library.create_playlist("保留的歌单")
        before = self.library.path.read_bytes()
        self.provider._injected_fetch_json = None
        now = [0.0]
        calls = []

        class Opener:
            def open(self, request, timeout):
                calls.append(timeout)
                now[0] += timeout
                return io.BytesIO(json.dumps(_page(1, [_media(_BV1, attr=1)], True)).encode())

        def bootstrap():
            if not hasattr(self.provider._session, "opener"):
                self.provider._reset_session()

        with patch.object(self.provider, "_bootstrap", side_effect=bootstrap), \
                patch("urllib.request.getproxies", return_value={}), \
                patch("lumen_service.providers.build_opener", return_value=Opener()), \
                patch("lumen_service.providers.time.monotonic", side_effect=lambda: now[0]):
            status, error = self.request("POST", "/api/library/playlists/import",
                                         f'<import url="{_ID}" />'.encode())
        self.assertEqual((504, "UPSTREAM_TIMEOUT"), (status, error.get("code")))
        self.assertAlmostEqual(120.0, now[0])
        self.assertEqual(15, len(calls))
        self.assertEqual(before, self.library.path.read_bytes())

    def test_playlist_reordering_http_round_trip(self) -> None:
        self.library.create_playlist("甲")
        self.library.create_playlist("乙")
        status, root = self.request("POST", "/api/library/playlists/move",
                                    b'<move from="1" to="0" />')
        self.assertEqual((200, ["乙", "甲"]),
                         (status, [playlist.get("name") for playlist in root.find("playlists")]))
        self.assertEqual(["乙", "甲"], [item["name"] for item in
                                         LibraryStore(self.library.path).snapshot()["playlists"]])


class FavoriteLimitTest(unittest.TestCase):
    def import_pages(self, pages):
        calls = []
        def fetch(url):
            number = int(parse_qs(urlsplit(url).query)["pn"][0])
            calls.append(number)
            return pages[number]
        return fetch_public_favorite(BilibiliProvider(fetch_json=fetch), _ID), calls

    def rows(self, first, last):
        return [_media(f"BV{number:010d}") for number in range(first, last)]

    def test_stops_after_two_hundred_unique_valid_tracks(self):
        pages = {page: _page(page, self.rows((page - 1) * 20, page * 20), True)
                 for page in range(1, 11)}
        result, calls = self.import_pages(pages)
        self.assertEqual(200, len(result.tracks))
        self.assertEqual(200, len({track.id for track in result.tracks}))
        self.assertEqual(list(range(1, 11)), calls)
        self.assertTrue(result.truncated)

    def test_invalid_and_duplicates_do_not_consume_track_limit(self):
        pages = {1: _page(1, self.rows(0, 10) + self.rows(0, 5)
                         + [_media(_BV1, attr=9)] * 5, True)}
        pages.update({page: _page(page, self.rows(10 + (page - 2) * 20, 10 + (page - 1) * 20), True)
                      for page in range(2, 11)})
        pages[11] = _page(11, self.rows(190, 210), False)
        result, calls = self.import_pages(pages)
        self.assertEqual((200, 5, 5), (len(result.tracks), result.skipped_count, result.duplicate_count))
        self.assertEqual(list(range(1, 12)), calls)
        self.assertTrue(result.truncated, "ten items remain unread on the final page")

    def test_exact_two_hundred_on_final_page_is_complete(self):
        pages = {page: _page(page, self.rows((page - 1) * 20, page * 20), page < 10)
                 for page in range(1, 11)}
        result, calls = self.import_pages(pages)
        self.assertEqual(200, len(result.tracks))
        self.assertFalse(result.truncated)
        self.assertEqual(10, len(calls))

    def test_short_complete_favorite_with_only_invalid_entries(self):
        result, calls = self.import_pages({1: _page(1, [_media(_BV1, attr=1)], False)})
        self.assertEqual((), result.tracks)
        self.assertEqual(1, result.skipped_count)
        self.assertFalse(result.truncated)
        self.assertEqual([1], calls)


if __name__ == "__main__":
    unittest.main()
