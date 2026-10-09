from __future__ import annotations

import os
import http.client
import io
import json
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

from lumen_service.providers import BilibiliProvider, ProviderError, parse_bv
from lumen_service.server import create_server


def sample_payload():
    return {
        "code": 0,
        "data": {
            "result": [
                {
                    "bvid": "BV1xx411c7mD",
                    "title": '<em class="keyword">夜曲</em> &amp; Live',
                    "author": "示例作者",
                    "duration": "1:02:03",
                    "pic": "//i0.hdslb.com/bfs/archive/example.jpg?x=1&amp;y=2",
                },
                {"title": "缺少 BV 号", "duration": "03:00"},
            ]
        },
    }


class BilibiliProviderTest(unittest.TestCase):
    def test_music_popular_reads_music_ranking_and_preserves_real_order(self) -> None:
        calls = []
        rows = [
            {"bvid": "BV1xx411c7mE", "title": "第二首 原曲", "duration": 240,
             "owner": {"name": "作者乙"}, "pic": "//i0.hdslb.com/bfs/archive/second.jpg",
             "videos": 2},
            {"bvid": "BV1xx411c7mD", "title": "第一首 翻唱", "duration": 200,
             "owner": {"name": "作者甲"}, "videos": 1},
        ]

        def fetch(url):
            calls.append(url)
            return {"code": 0, "data": {"list": [*rows, rows[0], {"bvid": "bad"}]}}

        result = BilibiliProvider(fetch_json=fetch).music_popular()
        self.assertEqual(1, len(calls))
        self.assertEqual("/x/web-interface/ranking/v2", urlsplit(calls[0]).path)
        self.assertEqual({"rid": ["3"], "type": ["all"]}, parse_qs(urlsplit(calls[0]).query))
        self.assertEqual(("BV1xx411c7mE", "BV1xx411c7mD"), tuple(item.bvid for item in result))
        self.assertEqual(("作者乙", "作者甲"), tuple(item.artist for item in result))
        self.assertEqual((240, 200), tuple(item.duration_seconds for item in result))
        self.assertEqual((2, 1), tuple(item.part_count for item in result))
        self.assertEqual("https://i0.hdslb.com/bfs/archive/second.jpg", result[0].cover_uri)

    def test_music_popular_rejects_failed_or_unusable_rankings(self) -> None:
        for payload, expected in (
            (None, "UPSTREAM_INVALID_RESPONSE"),
            ({"code": -412}, "UPSTREAM_REJECTED"),
            ({"code": -352}, "UPSTREAM_REJECTED"),
            ({"code": -400}, "UPSTREAM_API_ERROR"),
            ({"code": 0, "data": {"list": [], "v_voucher": "verify"}}, "UPSTREAM_REJECTED"),
            ({"code": 0, "data": {"list": []}}, "UPSTREAM_INVALID_RESPONSE"),
            ({"code": 0, "data": {"list": [{"bvid": "bad", "title": "假的"}]}},
             "UPSTREAM_INVALID_RESPONSE"),
        ):
            with self.subTest(payload=payload), self.assertRaises(ProviderError) as error:
                BilibiliProvider(fetch_json=lambda _, result=payload: result).music_popular()
            self.assertEqual(expected, error.exception.code)

    def test_bv_cache_keeps_at_most_64_successes(self) -> None:
        calls = []
        def fetch(url):
            calls.append(url)
            bvid = parse_qs(urlsplit(url).query)["bvid"][0]
            return {"code": 0, "data": {
                "bvid": bvid, "title": "歌曲",
                "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                "pages": [{"page": 1, "part": "歌曲", "duration": 180}],
            }}
        provider = BilibiliProvider(fetch_json=fetch)
        for number in range(65):
            provider.resolve_bv(f"BV{number:010d}")
        provider.resolve_bv("BV0000000064")
        self.assertEqual(65, len(calls))
        provider.resolve_bv("BV0000000000")
        self.assertEqual(66, len(calls))

    def test_network_sessions_are_isolated_between_threads(self) -> None:
        provider = BilibiliProvider()
        worker_sessions = []
        worker_failures = []
        url = "https://api.bilibili.com/x/player/pagelist?bvid=BV1xx411c7mD"

        class FakeOpener:
            def open(self, request, timeout):
                return io.BytesIO(b'{"code":0,"data":[]}')

        def create_session():
            try:
                provider.fetch_api(url, "pagelist")
                worker_sessions.append((provider._session.cookies,
                                        provider._network._session.openers["direct"]))
            except Exception as error:
                worker_failures.append(error)

        with patch("urllib.request.getproxies", return_value={}), \
                patch("lumen_service.providers.build_opener", side_effect=lambda *handlers: FakeOpener()):
            provider.fetch_api(url, "pagelist")
            original_cookies = provider._session.cookies
            original_opener = provider._network._session.openers["direct"]
            worker = threading.Thread(target=create_session)
            worker.start()
            worker.join(2)
            self.assertIs(original_opener, provider._network._session.openers["direct"])
        self.assertFalse(worker.is_alive())
        self.assertEqual([], worker_failures)
        self.assertIs(original_cookies, provider._session.cookies)
        self.assertIsNot(original_cookies, worker_sessions[0][0])
        self.assertIsNot(original_opener, worker_sessions[0][1])

    def test_complete_bv_metadata_is_reused_for_collection_and_count(self) -> None:
        calls = []
        def fetch(url):
            calls.append(url)
            return {"code": 0, "data": {
                "bvid": "BV1xx411c7mD", "title": "合集",
                "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                "pages": [{"page": 1, "part": "第一首", "duration": 180},
                          {"page": 2, "part": "第二首", "duration": 181}],
            }}
        provider = BilibiliProvider(fetch_json=fetch)
        tracks = provider.resolve_bv("BV1xx411c7mD")
        self.assertEqual(tracks, provider.resolve_collection(
            "https://www.bilibili.com/video/BV1xx411c7mD"))
        self.assertEqual(2, provider.page_count("BV1xx411c7mD"))
        self.assertEqual(1, len(calls))
        with patch("lumen_service.providers.time.monotonic", return_value=10**12):
            provider.resolve_collection("BV1xx411c7mD")
        self.assertEqual(2, len(calls))

    def test_bv_errors_are_not_cached(self) -> None:
        calls = []
        def fetch(url):
            calls.append(url)
            return {"code": -404}
        provider = BilibiliProvider(fetch_json=fetch)
        for _ in range(2):
            with self.assertRaises(ProviderError):
                provider.resolve_bv("BV1xx411c7mD")
        self.assertEqual(2, len(calls))

    def test_slow_recommendation_does_not_block_collection_upstream(self) -> None:
        provider = BilibiliProvider()
        slow_entered = threading.Event()
        release_slow = threading.Event()
        collection_entered = threading.Event()
        failures = []
        def request(url):
            if url == "slow":
                slow_entered.set()
                release_slow.wait(3)
                return {"code": 0}
            collection_entered.set()
            return {"code": 0, "data": {
                "bvid": "BV1xx411c7mD", "title": "合集",
                "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                "pages": [{"page": 1, "part": "第一首", "duration": 180},
                          {"page": 2, "part": "第二首", "duration": 181}],
            }}
        def run(action):
            try:
                action()
            except Exception as error:
                failures.append(error)
        with patch.object(provider, "_bootstrap"), patch.object(provider, "_request_json", request):
            slow = threading.Thread(target=lambda: run(lambda: provider.fetch_api("slow", "recommendation")))
            collection = threading.Thread(target=lambda: run(lambda: provider.resolve_collection("BV1xx411c7mD")))
            slow.start()
            try:
                self.assertTrue(slow_entered.wait(1))
                collection.start()
                self.assertTrue(collection_entered.wait(1))
            finally:
                release_slow.set()
                slow.join(3)
                if collection.ident is not None:
                    collection.join(3)
        self.assertEqual([], failures)
        self.assertFalse(slow.is_alive())
        self.assertFalse(collection.is_alive())

    def test_parse_bv_accepts_only_video_identity_or_canonical_link(self) -> None:
        self.assertEqual("BV1xx411c7mD", parse_bv(" BV1xx411c7mD "))
        self.assertEqual(
            "BV1xx411c7mD",
            parse_bv("https://www.bilibili.com/video/BV1xx411c7mD/?p=2"),
        )
        for value in (
            "BV1xx411c7mDextra",
            "https://evil.example/video/BV1xx411c7mD",
            "https://www.bilibili.com/other/BV1xx411c7mD",
            "https://www.bilibili.com/video/BVbad",
            "",
        ):
            with self.subTest(value=value):
                self.assertIsNone(parse_bv(value))

    def test_resolve_bv_returns_distinct_pages_with_page_titles(self) -> None:
        seen_urls: list[str] = []

        def fetch(url: str):
            seen_urls.append(url)
            return {
                "code": 0,
                "data": {
                    "bvid": "BV1xx411c7mD",
                    "title": "周杰伦现场合集",
                    "owner": {"name": "演出方"},
                    "pic": "//i0.hdslb.com/bfs/archive/cover.jpg",
                    "pages": [
                        {"page": 2, "part": "晴天", "duration": 240},
                        {"page": 1, "part": "七里香", "duration": 250},
                    ],
                },
            }

        tracks = BilibiliProvider(fetch_json=fetch).resolve_bv(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2"
        )
        self.assertEqual(1, len(seen_urls))
        self.assertEqual(["BV1xx411c7mD"], parse_qs(urlsplit(seen_urls[0]).query)["bvid"])
        self.assertEqual(
            ("bilibili:BV1xx411c7mD:p1", "bilibili:BV1xx411c7mD:p2"),
            tuple(track.id for track in tracks),
        )
        self.assertEqual(("七里香", "晴天"), tuple(track.title for track in tracks))
        self.assertEqual((1, 2), tuple(track.page for track in tracks))
        self.assertEqual((2, 2), tuple(track.part_count for track in tracks))
        self.assertEqual((250, 240), tuple(track.duration_seconds for track in tracks))
        self.assertTrue(all(track.artist == "演出方" for track in tracks))
        self.assertTrue(all(track.cover_uri.endswith("cover.jpg") for track in tracks))

    def test_resolve_single_page_uses_video_title(self) -> None:
        provider = BilibiliProvider(fetch_json=lambda _: {
            "code": 0,
            "data": {
                "bvid": "BV1xx411c7mD",
                "title": "青花瓷 MV",
                "pages": [{"page": 1, "part": "默认分 P", "duration": 215}],
            },
        })
        tracks = provider.resolve_bv("BV1xx411c7mD")
        self.assertEqual(1, len(tracks))
        self.assertEqual("青花瓷 MV", tracks[0].title)
        self.assertEqual(1, tracks[0].part_count)

    def test_resolve_collection_returns_every_part_in_playback_order(self) -> None:
        provider = BilibiliProvider(fetch_json=lambda _: {
            "code": 0,
            "data": {
                "bvid": "BV1xx411c7mD",
                "title": "现场合集",
                "owner": {"name": "演出方"},
                "pages": [
                    {"page": 3, "part": "第三首", "duration": 180},
                    {"page": 1, "part": "第一首", "duration": 181},
                    {"page": 2, "part": "第二首", "duration": 182},
                ],
            },
        })

        tracks = provider.resolve_collection("BV1xx411c7mD")

        self.assertEqual((1, 2, 3), tuple(track.page for track in tracks))
        self.assertEqual(("第一首", "第二首", "第三首"), tuple(track.title for track in tracks))
        self.assertTrue(all(track.part_count == 3 for track in tracks))

    def test_resolve_collection_rejects_single_video_clearly(self) -> None:
        provider = BilibiliProvider(fetch_json=lambda _: {
            "code": 0,
            "data": {
                "bvid": "BV1xx411c7mD",
                "title": "单曲",
                "pages": [{"page": 1, "part": "单曲", "duration": 180}],
            },
        })

        with self.assertRaises(ProviderError) as failure:
            provider.resolve_collection("BV1xx411c7mD")

        self.assertEqual("NOT_A_COLLECTION", failure.exception.code)
        self.assertEqual(422, failure.exception.http_status)

    def test_rejected_view_falls_back_to_page_list_for_bv_playback(self) -> None:
        def fetch(url: str):
            if "/x/web-interface/view?" in url:
                return {"code": -412}
            if "/x/player/pagelist?" in url:
                return {"code": 0, "data": [
                    {"page": 2, "part": "晴天", "duration": 240},
                    {"page": 1, "part": "七里香", "duration": 250},
                ]}
            return {"code": 0, "data": {"result": [
                {"bvid": "BV1other00000", "title": "错误候选", "duration": "4:00",
                 "pic": "https://i0.hdslb.com/bfs/archive/wrong.jpg"},
                {"bvid": "BV1xx411c7mD", "title": "正确候选", "duration": "4:00",
                 "pic": "//i0.hdslb.com/bfs/archive/right.jpg"},
            ]}}

        tracks = BilibiliProvider(fetch_json=fetch).resolve_bv("BV1xx411c7mD")
        self.assertEqual(("七里香", "晴天"), tuple(track.title for track in tracks))
        self.assertEqual((1, 2), tuple(track.page for track in tracks))
        self.assertTrue(all(track.cover_uri == "https://i0.hdslb.com/bfs/archive/right.jpg"
                            for track in tracks))
        self.assertEqual(2, BilibiliProvider(fetch_json=fetch).page_count("BV1xx411c7mD"))

    def test_missing_detail_cover_uses_exact_bv_search_metadata(self) -> None:
        def fetch(url: str):
            if "/x/web-interface/view?" in url:
                return {"code": 0, "data": {
                    "bvid": "BV1xx411c7mD", "title": "公开视频", "owner": {"name": "作者"},
                    "pages": [{"page": 1, "part": "歌曲", "duration": 200}],
                }}
            return {"code": 0, "data": {"result": [
                {"bvid": "BV1xx411c7mD", "title": "公开视频", "duration": "3:20",
                 "pic": "//i0.hdslb.com/bfs/archive/recovered.webp"},
            ]}}

        track = BilibiliProvider(fetch_json=fetch).resolve_bv("BV1xx411c7mD")[0]
        self.assertEqual("https://i0.hdslb.com/bfs/archive/recovered.webp", track.cover_uri)

    def test_transient_view_http_failure_uses_page_list(self) -> None:
        calls: list[str] = []

        def fetch(url: str):
            calls.append(url)
            if "/x/web-interface/view?" in url:
                raise ProviderError("UPSTREAM_HTTP_ERROR", "Bilibili video details returned HTTP 502")
            return {"code": 0, "data": [
                {"page": 1, "part": "示例歌曲", "duration": 220},
            ]}

        tracks = BilibiliProvider(fetch_json=fetch).resolve_bv(
            "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=share"
        )
        self.assertEqual(3, len(calls))
        self.assertEqual("bilibili:BV1xx411c7mD:p1", tracks[0].id)
        self.assertEqual("示例歌曲", tracks[0].title)

    def test_transient_http_502_retries_once(self) -> None:
        provider = BilibiliProvider()
        responses = [HTTPError("https://api.bilibili.com/", 502, "Bad Gateway", None, None),
                     sample_payload()]
        calls: list[str] = []

        def request(url: str):
            calls.append(url)
            response = responses.pop(0)
            if isinstance(response, HTTPError):
                raise response
            return response

        with patch.object(provider, "_bootstrap"), patch.object(provider, "_request_json", request), \
                patch("lumen_service.providers.time.sleep") as sleep:
            tracks = provider.search("夜曲")
        self.assertEqual(2, len(calls))
        self.assertEqual("BV1xx411c7mD", tracks[0].bvid)
        sleep.assert_called_once()

    def test_resolve_rejects_invalid_input_and_upstream_failure(self) -> None:
        def unexpected_fetch(_: str):
            self.fail("invalid input must not call upstream")

        with self.assertRaises(ProviderError) as invalid:
            BilibiliProvider(fetch_json=unexpected_fetch).resolve_bv("bad")
        self.assertEqual("INVALID_BV", invalid.exception.code)
        self.assertEqual(400, invalid.exception.http_status)

        for payload, expected_code in (
            ({"code": -404}, "VIDEO_NOT_FOUND"),
            ({"code": -412}, "UPSTREAM_REJECTED"),
            ({"code": 0, "data": {"bvid": "BV1xx411c7mD", "pages": []}},
             "UPSTREAM_INVALID_RESPONSE"),
        ):
            with self.subTest(payload=payload):
                with self.assertRaises(ProviderError) as failure:
                    BilibiliProvider(fetch_json=lambda _, p=payload: p).resolve_bv(
                        "BV1xx411c7mD"
                    )
                self.assertEqual(expected_code, failure.exception.code)

    def test_search_part_count_uses_existing_response_without_detail_calls(self) -> None:
        calls: list[str] = []

        def fetch(url: str):
            calls.append(url)
            payload = sample_payload()
            payload["data"]["result"][0]["videos"] = 3
            return payload

        tracks = BilibiliProvider(fetch_json=fetch).search("周杰伦")
        self.assertEqual(1, len(calls))
        self.assertEqual(3, tracks[0].part_count)

    def test_parses_real_response_shape_and_identity(self) -> None:
        seen_urls: list[str] = []

        def fetch(url: str):
            seen_urls.append(url)
            return sample_payload()

        tracks = BilibiliProvider(fetch_json=fetch).search("夜曲 live")

        self.assertEqual(1, len(tracks))
        self.assertEqual("bilibili:BV1xx411c7mD:p1", tracks[0].id)
        self.assertEqual("夜曲 & Live", tracks[0].title)
        self.assertEqual(3723, tracks[0].duration_seconds)
        self.assertEqual("bilibili", tracks[0].source)
        self.assertEqual("BV1xx411c7mD", tracks[0].bvid)
        self.assertEqual(1, tracks[0].page)
        self.assertEqual("https://i0.hdslb.com/bfs/archive/example.jpg?x=1&y=2",
                         tracks[0].cover_uri)
        query = parse_qs(urlsplit(seen_urls[0]).query)
        self.assertEqual(["夜曲 live"], query["keyword"])
        self.assertEqual(["video"], query["search_type"])

    def test_empty_query_does_not_call_upstream(self) -> None:
        def unexpected_fetch(url: str):
            self.fail(f"unexpected upstream request: {url}")

        self.assertEqual((), BilibiliProvider(fetch_json=unexpected_fetch).search("  "))

    def test_non_image_or_non_http_pic_is_ignored(self) -> None:
        for pic in ("file:///tmp/cover.jpg", "https://example.com/audio.mp3", "javascript:alert(1)"):
            with self.subTest(pic=pic):
                payload = sample_payload()
                payload["data"]["result"][0]["pic"] = pic
                track = BilibiliProvider(fetch_json=lambda _: payload).search("夜曲")[0]
                self.assertIsNone(track.cover_uri)

    def test_api_error_is_explicit(self) -> None:
        provider = BilibiliProvider(fetch_json=lambda _: {"code": -400, "message": "bad request"})
        with self.assertRaises(ProviderError) as raised:
            provider.search("test")
        self.assertEqual("UPSTREAM_API_ERROR", raised.exception.code)
        self.assertEqual(502, raised.exception.http_status)

    def test_risk_control_api_error_is_identified(self) -> None:
        provider = BilibiliProvider(fetch_json=lambda _: {"code": -412, "message": "blocked"})
        with self.assertRaises(ProviderError) as raised:
            provider.search("test")
        self.assertEqual("UPSTREAM_REJECTED", raised.exception.code)
        self.assertEqual(502, raised.exception.http_status)

    def test_invalid_nonempty_results_are_not_reported_as_empty(self) -> None:
        provider = BilibiliProvider(
            fetch_json=lambda _: {"code": 0, "data": {"result": [{"bvid": "bad"}]}}
        )
        with self.assertRaises(ProviderError) as raised:
            provider.search("test")
        self.assertEqual("UPSTREAM_INVALID_RESPONSE", raised.exception.code)

    def test_timeout_is_mapped_to_gateway_timeout(self) -> None:
        def timed_out(url: str):
            raise TimeoutError

        with self.assertRaises(ProviderError) as raised:
            BilibiliProvider(fetch_json=timed_out).search("test")
        self.assertEqual("UPSTREAM_TIMEOUT", raised.exception.code)
        self.assertEqual(504, raised.exception.http_status)


class BilibiliProviderHttpContractTest(unittest.TestCase):
    def test_simulated_upstream_is_exposed_through_real_http_service(self) -> None:
        provider = BilibiliProvider(fetch_json=lambda _: sample_payload())
        server = create_server(port=0, provider=provider)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        try:
            connection.request("GET", "/api/tracks?q=%E5%A4%9C%E6%9B%B2")
            response = connection.getresponse()
            status = response.status
            body = response.read().decode("utf-8")
        finally:
            connection.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(200, status)
        self.assertIn('id="bilibili:BV1xx411c7mD:p1"', body)
        self.assertIn('title="夜曲 &amp; Live"', body)
        self.assertIn('source="bilibili"', body)
        self.assertIn('bvid="BV1xx411c7mD"', body)
        self.assertIn('page="1"', body)
        self.assertIn('coverUri="https://i0.hdslb.com/bfs/archive/example.jpg?x=1&amp;y=2"', body)


class BilibiliNetworkRetryTest(unittest.TestCase):
    API = "https://api.bilibili.com/x/player/pagelist?bvid=BV1xx411c7mD"

    def fetch_with_errors(self, errors):
        provider = BilibiliProvider()
        calls = []

        class Opener:
            def open(self, request, timeout):
                calls.append((request.full_url, timeout))
                if errors:
                    raise errors.pop(0)
                return io.BytesIO(json.dumps({"code": 0, "data": []}).encode())

        with patch("urllib.request.getproxies", return_value={}), \
                patch("lumen_service.providers.build_opener", return_value=Opener()), \
                patch("lumen_service.providers.time.sleep"):
            try:
                result = provider.fetch_api(self.API, "pagelist")
            except ProviderError as error:
                result = error
        return result, calls

    def test_http_gateway_failures_retry_once_through_real_network_flow(self):
        for status in (502, 503, 504):
            with self.subTest(status=status):
                failure = HTTPError(self.API, status, "temporary", {}, io.BytesIO())
                result, calls = self.fetch_with_errors([failure])
                self.assertEqual({"code": 0, "data": []}, result)
                self.assertEqual(2, len(calls))
                self.assertTrue(failure.closed)

    def test_real_socket_disconnect_is_retried_and_recovers(self):
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path)
                if len(calls) == 1:
                    self.close_connection = True
                    return
                body = b'{"code":0,"data":[]}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        provider = BilibiliProvider(timeout_seconds=2)
        provider._reset_session()
        try:
            with patch.object(provider, "_bootstrap"), \
                    patch("urllib.request.getproxies", return_value={}):
                result = provider.fetch_api(f"http://127.0.0.1:{server.server_port}/pagelist", "pagelist")
            self.assertEqual({"code": 0, "data": []}, result)
            self.assertEqual(["/pagelist", "/pagelist"], calls)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_connection_reset_and_incomplete_first_byte_recover_once(self):
        for error in (ConnectionResetError("lost"), http.client.IncompleteRead(b"", 1)):
            with self.subTest(error=error):
                result, calls = self.fetch_with_errors([error])
                self.assertEqual({"code": 0, "data": []}, result)
                self.assertEqual(2, len(calls))

    def test_persistent_transport_and_certificate_failures_are_bounded(self):
        for failures, expected, count in (
            ([ConnectionResetError("lost")] * 3, "UPSTREAM_CONNECTION_LOST", 2),
            ([URLError(ssl.SSLCertVerificationError("untrusted"))], "UPSTREAM_TLS_ERROR", 1),
            ([HTTPError(self.API, 500, "unexpected", {}, io.BytesIO())], "UPSTREAM_HTTP_ERROR", 1),
            ([HTTPError(self.API, 404, "deleted", {}, io.BytesIO())], "VIDEO_NOT_FOUND", 1),
        ):
            with self.subTest(expected=expected):
                result, calls = self.fetch_with_errors(failures)
                self.assertIsInstance(result, ProviderError)
                self.assertEqual(expected, result.code)
                self.assertEqual(count, len(calls))
                self.assertRegex(result.message, r"[\u4e00-\u9fff]")

    def test_slow_attempts_consume_one_twelve_second_budget(self):
        now = [0.0]
        timeouts = []
        provider = BilibiliProvider()

        class Opener:
            def open(self, request, timeout):
                timeouts.append(timeout)
                now[0] += timeout
                raise TimeoutError("offline")

        def sleep(seconds):
            now[0] += seconds

        with patch("urllib.request.getproxies", return_value={}), \
                patch("lumen_service.providers.build_opener", return_value=Opener()), \
                patch("lumen_service.providers.time.monotonic", side_effect=lambda: now[0]), \
                patch("lumen_service.providers.time.sleep", side_effect=sleep):
            with self.assertRaises(ProviderError) as raised:
                provider.fetch_api(self.API, "pagelist")
        self.assertEqual("UPSTREAM_TIMEOUT", raised.exception.code)
        self.assertEqual(2, len(timeouts))
        self.assertAlmostEqual(12.0, now[0])

    def test_deleted_pagelist_and_permission_errors_are_not_retried(self):
        for code, expected in ((-404, "VIDEO_NOT_FOUND"), (-403, "VIDEO_ACCESS_DENIED")):
            calls = []
            def fetch(url):
                calls.append(url)
                return {"code": code}
            provider = BilibiliProvider(fetch_json=fetch)
            with self.subTest(code=code), self.assertRaises(ProviderError) as raised:
                provider.page_count("BV1xx411c7mD")
            self.assertEqual(expected, raised.exception.code)
            self.assertEqual(1, len(calls))

    def test_http_error_reason_keeps_upstream_status_in_chinese(self):
        result, calls = self.fetch_with_errors([
            HTTPError(self.API, 503, "busy", {}, io.BytesIO()),
            HTTPError(self.API, 503, "busy", {}, io.BytesIO()),
        ])
        self.assertEqual("UPSTREAM_HTTP_ERROR", result.code)
        self.assertIn("HTTP 503", result.message)
        self.assertRegex(result.message, r"[\u4e00-\u9fff]")
        self.assertEqual(2, len(calls))


@unittest.skipUnless(
    os.environ.get("LUMEN_RUN_LIVE_TESTS") == "1",
    "set LUMEN_RUN_LIVE_TESTS=1 to call Bilibili",
)
class BilibiliProviderLiveTest(unittest.TestCase):
    def test_small_live_search_sample(self) -> None:
        tracks = BilibiliProvider(timeout_seconds=10).search("周杰伦 官方MV")
        self.assertGreater(len(tracks), 0)
        self.assertTrue(all(track.source == "bilibili" for track in tracks))
        self.assertTrue(all(track.bvid and track.bvid.startswith("BV") for track in tracks))

    def test_live_http_search_and_bv_direct(self) -> None:
        provider = BilibiliProvider(timeout_seconds=10)
        server = create_server(port=0, provider=provider)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=20)
        try:
            connection.request("GET", "/api/tracks?q=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E5%AE%98%E6%96%B9MV&kind=single")
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            from xml.etree import ElementTree
            singles = ElementTree.fromstring(response.read())
            self.assertTrue(all(track.get("partCount") == "1" for track in singles))
            self.assertGreater(len(singles), 0)
            bvid = singles[0].get("bvid")
            connection.request("GET", "/api/tracks?q=" + bvid)
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            resolved = ElementTree.fromstring(response.read())
            self.assertGreater(len(resolved), 0)
            self.assertEqual(bvid, resolved[0].get("bvid"))
            self.assertEqual("1", resolved[0].get("page"))
        finally:
            connection.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
