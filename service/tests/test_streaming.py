from __future__ import annotations

import http.client
import io
import socket
import ssl
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit, urlencode, unquote

from lumen_service.providers import BilibiliProvider, ProviderError
from lumen_service.server import create_server
from lumen_service.streaming import AudioLocation, BilibiliAudioProxy


class FakeJsonProvider:
    def __init__(self) -> None:
        self.urls: list[str] = []

    def fetch_wbi_api(self, endpoint, params, operation):
        return self.fetch_api(f"{endpoint}?{urlencode(params)}", operation)

    def fetch_api(self, url: str, operation: str) -> object:
        self.urls.append(url)
        if operation == "pagelist":
            return {"code": 0, "data": [{"page": 1, "cid": 123}, {"page": 2, "cid": 456}]}
        return {
            "code": 0,
            "data": {
                "dash": {
                    "audio": [
                        {"id": 30216, "mimeType": "audio/mp4", "codecs": "mp4a.40.5", "bandwidth": 100000, "baseUrl": "https://cdn.invalid/he-aac"},
                        {"id": 30232, "mimeType": "audio/mp4", "codecs": "mp4a.40.2", "bandwidth": 90000, "baseUrl": "https://cdn.invalid/low"},
                        {"id": 30280, "mimeType": "audio/mp4", "codecs": "mp4a.40.2", "bandwidth": 140000, "baseUrl": "https://cdn.invalid/high", "backupUrl": ["https://backup.invalid/high"]},
                    ]
                }
            },
        }


class FakeResponse(io.BytesIO):
    def __init__(self, body: bytes, status: int, headers: dict[str, str]) -> None:
        super().__init__(body)
        self.status = status
        self.headers = headers


class AudioProxyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.provider = FakeJsonProvider()
        self.proxy = BilibiliAudioProxy(self.provider)

    def test_resolves_requested_page_and_prefers_aac_lc(self) -> None:
        location = self.proxy.resolve("bilibili:BV1xx411c7mD:p2")
        self.assertEqual(("https://cdn.invalid/high", "https://backup.invalid/high"), location.urls)
        self.assertEqual("audio/mp4", location.content_type)
        self.assertEqual("456", parse_qs(urlsplit(self.provider.urls[1]).query)["cid"][0])
        self.assertEqual("/x/player/wbi/playurl", urlsplit(self.provider.urls[1]).path)

    def test_reuses_short_lived_resolution_for_head_followed_by_get(self) -> None:
        self.proxy.resolve("bilibili:BV1xx411c7mD:p1")
        self.proxy.resolve("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(2, len(self.provider.urls))

    def test_slow_track_resolution_does_not_block_another_track(self) -> None:
        slow_entered = threading.Event()
        release_slow = threading.Event()
        fast_finished = threading.Event()
        failures = []
        locations = {}
        original_fetch = self.provider.fetch_api

        def fetch(url, operation):
            query = parse_qs(urlsplit(url).query)
            if operation == "pagelist" and query.get("bvid") == ["BV1xx411c7mD"]:
                slow_entered.set()
                release_slow.wait(3)
            return original_fetch(url, operation)

        def resolve(name, bvid):
            try:
                locations[name] = self.proxy.resolve(f"bilibili:{bvid}:p1")
            except Exception as error:
                failures.append(error)
            finally:
                if name == "fast":
                    fast_finished.set()

        with patch.object(self.provider, "fetch_api", side_effect=fetch):
            slow = threading.Thread(target=resolve, args=("slow", "BV1xx411c7mD"))
            fast = threading.Thread(target=resolve, args=("fast", "BV1xx411c7mE"))
            slow.start()
            try:
                self.assertTrue(slow_entered.wait(1))
                fast.start()
                self.assertTrue(fast_finished.wait(1), "another track waited for slow upstream")
            finally:
                release_slow.set()
                slow.join(3)
                if fast.ident is not None:
                    fast.join(3)
        self.assertFalse(slow.is_alive())
        self.assertFalse(fast.is_alive())
        self.assertEqual([], failures)
        self.assertEqual({"slow", "fast"}, set(locations))

    def test_location_ttl_still_refreshes_an_expired_cache(self) -> None:
        with patch("lumen_service.streaming.time.monotonic", return_value=0):
            self.proxy.resolve("bilibili:BV1xx411c7mD:p1")
        with patch("lumen_service.streaming.time.monotonic", return_value=59):
            self.proxy.resolve("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(2, len(self.provider.urls))
        with patch("lumen_service.streaming.time.monotonic", return_value=60):
            self.proxy.resolve("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(4, len(self.provider.urls))

    def test_head_probes_first_byte_and_exposes_full_length(self) -> None:
        def open_fake(request, timeout):
            self.assertEqual("bytes=0-0", request.get_header("Range"))
            self.assertEqual("https://www.bilibili.com/", request.get_header("Referer"))
            return FakeResponse(b"x", 206, {"Content-Length": "1", "Content-Range": "bytes 0-0/900"})

        with patch("lumen_service.streaming.urlopen", side_effect=open_fake):
            opened = self.proxy.open("bilibili:BV1xx411c7mD:p1", head=True)
        self.assertEqual(900, opened.total_length)
        self.assertEqual(1, opened.content_length)
        opened.close()

    def test_byte_range_and_backup_url_after_expired_primary(self) -> None:
        seen_urls: list[str] = []

        def open_fake(request, timeout):
            seen_urls.append(request.full_url)
            self.assertEqual("bytes=100-199", request.get_header("Range"))
            if len(seen_urls) == 1:
                raise HTTPError(request.full_url, 403, "expired", {}, None)
            return FakeResponse(b"z" * 100, 206, {"Content-Length": "100", "Content-Range": "bytes 100-199/900"})

        with patch("lumen_service.streaming.urlopen", side_effect=open_fake):
            opened = self.proxy.open("bilibili:BV1xx411c7mD:p1", "bytes=100-199")
        self.assertEqual(["https://cdn.invalid/high", "https://backup.invalid/high"], seen_urls)
        self.assertEqual(206, opened.status)
        self.assertEqual(100, opened.content_length)
        opened.close()

    def test_unranged_get_returns_full_audio_metadata(self) -> None:
        def open_fake(request, timeout):
            self.assertIsNone(request.get_header("Range"))
            return FakeResponse(b"f" * 16, 200, {"Content-Length": "16", "Content-Type": "video/mp4"})

        with patch("lumen_service.streaming.urlopen", side_effect=open_fake):
            opened = self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual((200, "audio/mp4", 16), (opened.status, opened.content_type, opened.total_length))
        opened.close()

    def test_refreshes_location_when_all_cached_sources_expire(self) -> None:
        attempts = 0

        def open_fake(request, timeout):
            nonlocal attempts
            attempts += 1
            if attempts <= 2:
                raise HTTPError(request.full_url, 403, "expired", {}, None)
            return FakeResponse(b"f" * 16, 200, {"Content-Length": "16"})

        with patch("lumen_service.streaming.urlopen", side_effect=open_fake):
            opened = self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(3, attempts)
        self.assertEqual(4, len(self.provider.urls))
        opened.close()

    def test_rejects_unusable_range_and_upstream_ignored_range(self) -> None:
        with self.assertRaises(ProviderError) as raised:
            self.proxy.open("bilibili:BV1xx411c7mD:p1", "bytes=0-1,3-4")
        self.assertEqual("INVALID_RANGE", raised.exception.code)

        with patch("lumen_service.streaming.urlopen", return_value=FakeResponse(b"x", 200, {"Content-Length": "1"})):
            with self.assertRaises(ProviderError) as raised:
                self.proxy.open("bilibili:BV1xx411c7mD:p1", "bytes=0-0")
        self.assertEqual("UPSTREAM_RANGE_UNSUPPORTED", raised.exception.code)

    def test_unknown_page_and_invalid_id_are_clear(self) -> None:
        with self.assertRaises(ProviderError) as raised:
            self.proxy.resolve("bilibili:BV1xx411c7mD:p3")
        self.assertEqual("TRACK_PAGE_NOT_FOUND", raised.exception.code)
        with self.assertRaises(ProviderError) as raised:
            self.proxy.resolve("demo-1")
        self.assertEqual("INVALID_TRACK_ID", raised.exception.code)

    def test_transient_source_failures_retry_same_range_once(self) -> None:
        failures = [HTTPError("https://cdn.invalid/audio", status, "temporary", {}, io.BytesIO())
                    for status in (502, 503, 504)]
        failures += [socket.timeout("slow"), ConnectionResetError("lost"),
                     URLError(ConnectionRefusedError("offline")),
                     http.client.IncompleteRead(b"", 1)]
        location = AudioLocation(("https://cdn.invalid/audio",), "audio/mp4")
        for failure in failures:
            with self.subTest(failure=failure):
                response = FakeResponse(b"abc", 206, {
                    "Content-Length": "3", "Content-Range": "bytes 10-12/90",
                })
                with patch.object(self.proxy, "resolve", return_value=location), \
                        patch("lumen_service.streaming.time.sleep"), \
                        patch("lumen_service.streaming.urlopen", side_effect=[failure, response]) as opening:
                    opened = self.proxy.open("bilibili:BV1xx411c7mD:p1", "bytes=10-12")
                self.assertEqual(2, opening.call_count)
                self.assertEqual(["bytes=10-12"] * 2,
                                 [call.args[0].get_header("Range") for call in opening.call_args_list])
                self.assertEqual(b"abc", opened.response.read())
                opened.close()

    def test_persistent_failure_caps_sources_and_attempts(self) -> None:
        location = AudioLocation(tuple(f"https://cdn.invalid/{index}" for index in range(20)), "audio/mp4")
        with patch.object(self.proxy, "resolve", return_value=location) as resolve, \
                patch("lumen_service.streaming.time.sleep"), \
                patch("lumen_service.streaming.urlopen", side_effect=ConnectionResetError("lost")) as opening:
            with self.assertRaises(ProviderError) as raised:
                self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual("UPSTREAM_CONNECTION_LOST", raised.exception.code)
        self.assertEqual(6, opening.call_count)
        resolve.assert_called_once()

    def test_source_timeouts_share_one_total_budget(self) -> None:
        now = [0.0]
        timeouts = []
        location = AudioLocation(("https://cdn.invalid/a", "https://cdn.invalid/b"), "audio/mp4")

        def opening(request, timeout):
            timeouts.append(timeout)
            now[0] += timeout
            raise TimeoutError("timeout")

        def sleep(seconds):
            now[0] += seconds

        with patch.object(self.proxy, "resolve", return_value=location), \
                patch("lumen_service.streaming.time.monotonic", side_effect=lambda: now[0]), \
                patch("lumen_service.streaming.time.sleep", side_effect=sleep), \
                patch("lumen_service.streaming.urlopen", side_effect=opening):
            with self.assertRaises(ProviderError) as raised:
                self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual("UPSTREAM_TIMEOUT", raised.exception.code)
        self.assertEqual(504, raised.exception.http_status)
        self.assertLessEqual(len(timeouts), 4)
        self.assertAlmostEqual(12.0, now[0])

    def test_certificate_failure_is_not_retried(self) -> None:
        location = AudioLocation(("https://cdn.invalid/a", "https://cdn.invalid/b"), "audio/mp4")
        failure = URLError(ssl.SSLCertVerificationError("untrusted"))
        with patch.object(self.proxy, "resolve", return_value=location), \
                patch("lumen_service.streaming.urlopen", side_effect=failure) as opening:
            with self.assertRaises(ProviderError) as raised:
                self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual("UPSTREAM_TLS_ERROR", raised.exception.code)
        opening.assert_called_once()

    def test_metadata_resolution_consumes_the_audio_open_budget(self) -> None:
        provider = BilibiliProvider()
        proxy = BilibiliAudioProxy(provider)
        now = [0.0]
        location = AudioLocation(("https://cdn.invalid/audio",), "audio/mp4")

        def resolve(track_id):
            self.assertAlmostEqual(12.0, provider._session.deadline)
            now[0] += 10.0
            return location

        response = FakeResponse(b"x", 200, {"Content-Length": "1"})
        with patch.object(proxy, "resolve", side_effect=resolve), \
                patch("lumen_service.streaming.time.monotonic", side_effect=lambda: now[0]), \
                patch("lumen_service.streaming.urlopen", return_value=response) as opening:
            opened = proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertLessEqual(opening.call_args.kwargs["timeout"], 2.0)
        self.assertIsNone(provider._session.deadline)
        opened.close()

    def test_deleted_video_and_empty_audio_have_stable_reasons_without_retry(self) -> None:
        with patch.object(self.provider, "fetch_api", return_value={"code": -404}) as fetching:
            with self.assertRaises(ProviderError) as raised:
                self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(("VIDEO_NOT_FOUND", "视频已删除或不存在", 404),
                         (raised.exception.code, raised.exception.message, raised.exception.http_status))
        fetching.assert_called_once()
        with patch.object(self.provider, "fetch_wbi_api",
                          return_value={"code": 0, "data": {"dash": {"audio": []}}}) as fetching:
            with self.assertRaises(ProviderError) as raised:
                self.proxy.open("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(("AUDIO_UNAVAILABLE", "该视频没有可播放的音轨", 422),
                         (raised.exception.code, raised.exception.message, raised.exception.http_status))
        fetching.assert_called_once()


class StreamHttpContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.proxy = BilibiliAudioProxy(FakeJsonProvider())
        self.server = create_server(0, provider=BilibiliProvider(fetch_json=lambda _: {"code": 0, "data": {"result": []}}), audio_proxy=self.proxy)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method: str, headers: dict[str, str] | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, "/api/tracks/bilibili%3ABV1xx411c7mD%3Ap1/stream", headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_head_and_ranged_get_contract(self) -> None:
        def open_fake(request, timeout):
            byte_range = request.get_header("Range")
            if byte_range == "bytes=0-0":
                return FakeResponse(b"x", 206, {"Content-Length": "1", "Content-Range": "bytes 0-0/900"})
            self.assertEqual("bytes=100-199", byte_range)
            return FakeResponse(b"z" * 100, 206, {"Content-Length": "100", "Content-Range": "bytes 100-199/900"})

        with patch("lumen_service.streaming.urlopen", side_effect=open_fake):
            status, headers, body = self.request("HEAD")
            self.assertEqual((200, b""), (status, body))
            self.assertEqual("900", headers["Content-Length"])
            self.assertEqual("audio/mp4", headers["Content-Type"])
            self.assertEqual("bytes", headers["Accept-Ranges"])

            status, headers, body = self.request("GET", {"Range": "bytes=100-199"})
            self.assertEqual(206, status)
            self.assertEqual("bytes 100-199/900", headers["Content-Range"])
            self.assertEqual(100, len(body))

    def test_head_failure_has_error_code_header(self) -> None:
        status, headers, body = self.request("HEAD", {"Range": "bytes=0-1,3-4"})
        self.assertEqual((416, b""), (status, body))
        self.assertEqual("INVALID_RANGE", headers["X-Lumen-Error-Code"])
        self.assertEqual("percent-encoded-utf8", headers["X-Lumen-Error-Message-Encoding"])
        self.assertEqual("音频请求只能包含一个字节范围", unquote(headers["X-Lumen-Error-Message"]))


if __name__ == "__main__":
    unittest.main()
