from __future__ import annotations

import http.cookiejar
import http.client
import io
import json
import ssl
import threading
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request

from lumen_service.network import BilibiliNetwork
from lumen_service.providers import BilibiliProvider
from lumen_service.streaming import AudioLocation, BilibiliAudioProxy


API = "https://api.bilibili.com/x/player/pagelist?bvid=BV1xx411c7mD"
PROXY = {"https": "http://127.0.0.1:7897"}


class Response(io.BytesIO):
    def __init__(self, body=b"body", status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {"Content-Length": str(len(body))}


class NetworkTest(unittest.TestCase):
    def setUp(self):
        self.proxies = dict(PROXY)
        self.calls = []
        self.handlers = []
        self.behavior = lambda route, request: Response()
        proxies = patch("urllib.request.getproxies", side_effect=lambda: dict(self.proxies))
        proxies.start()
        self.addCleanup(proxies.stop)
        self.network = BilibiliNetwork(opener_factory=self.factory)

    def factory(self, *handlers):
        self.handlers.append(handlers)
        configured = next(handler.proxies for handler in handlers if isinstance(handler, ProxyHandler))
        route = configured.get("https", "direct")

        def open_request(request, timeout):
            self.calls.append((route, request, timeout))
            for handler in handlers:
                if isinstance(handler, HTTPCookieProcessor):
                    handler.cookiejar.add_cookie_header(request)
            return self.behavior(route, request)

        return Mock(open=open_request)

    def request(self):
        with self.network.open(Request(API), timeout=8) as response:
            return response.read()

    def test_invalid_system_proxy_does_not_block_working_direct_route(self):
        self.proxies = {"https": "http://127.0.0.1:1"}
        self.assertEqual(b"body", self.request())
        self.assertEqual(["direct"], [call[0] for call in self.calls])
        self.assertLessEqual(self.calls[0][2], 4)

    def test_direct_failure_falls_back_and_remembers_working_proxy(self):
        def open_request(route, request):
            if route == "direct":
                raise URLError(ConnectionRefusedError("direct unavailable"))
            return Response(b"proxy")

        self.behavior = open_request
        self.assertEqual(b"proxy", self.request())
        self.assertEqual(b"proxy", self.request())
        self.assertEqual(["direct", PROXY["https"], PROXY["https"]],
                         [call[0] for call in self.calls])

    def test_proxy_settings_change_rebuilds_route_and_direct_recovers(self):
        self.behavior = lambda route, request: (
            Response(b"proxy") if route != "direct" else self.fail_direct())
        self.request()
        self.calls.clear()
        self.proxies = {}
        self.behavior = lambda route, request: Response(b"direct")
        self.assertEqual(b"direct", self.request())
        self.assertEqual(["direct"], [call[0] for call in self.calls])
        self.calls.clear()
        self.proxies = {"https": "http://127.0.0.1:9000"}
        self.behavior = lambda route, request: (
            Response(b"new proxy") if route != "direct" else self.fail_direct())
        self.assertEqual(b"new proxy", self.request())
        self.assertEqual(["direct", "http://127.0.0.1:9000"], [call[0] for call in self.calls])

    @staticmethod
    def fail_direct():
        raise TimeoutError("direct unavailable")

    def test_remembered_proxy_failure_recovers_via_direct_without_config_change(self):
        self.behavior = lambda route, request: (
            Response() if route != "direct" else self.fail_direct())
        self.request()
        self.calls.clear()

        def recover(route, request):
            if route != "direct":
                raise URLError(ConnectionRefusedError("proxy stopped"))
            return Response()

        self.behavior = recover
        self.request()
        self.request()
        self.assertEqual([PROXY["https"], "direct", "direct"], [call[0] for call in self.calls])

    def test_http_errors_do_not_change_network_route(self):
        for code in (403, 412, 503):
            with self.subTest(code=code):
                self.calls.clear()
                error = HTTPError(API, code, "upstream rejected", {}, io.BytesIO())

                def rejected(route, request):
                    raise error

                self.behavior = rejected
                with self.assertRaises(HTTPError) as raised:
                    self.request()
                self.assertIs(error, raised.exception)
                error.close()
                self.assertEqual(["direct"], [call[0] for call in self.calls])

    def test_certificate_verification_stays_enabled_and_is_not_retried(self):
        error = URLError(ssl.SSLCertVerificationError("certificate rejected"))

        def rejected(route, request):
            raise error

        self.behavior = rejected
        with self.assertRaises(URLError) as raised:
            self.request()
        self.assertIs(error, raised.exception)
        self.assertEqual(["direct"], [call[0] for call in self.calls])

    def test_all_transport_failures_try_each_route_once(self):
        self.behavior = lambda route, request: self.fail_direct()
        with self.assertRaises(TimeoutError):
            self.request()
        self.assertEqual(["direct", PROXY["https"]], [call[0] for call in self.calls])

    def test_first_byte_timeout_closes_response_and_retries_range_without_losing_bytes(self):
        failed = Response()
        failed.read = Mock(side_effect=TimeoutError("first byte unavailable"))
        body = b"abcdefghij"
        successful = Response(body, 206, {
            "Content-Length": "10", "Content-Range": "bytes 100-109/900",
        })

        def open_request(route, request):
            self.assertEqual("bytes=100-109", request.get_header("Range"))
            self.assertEqual("https://www.bilibili.com/", request.get_header("Referer"))
            return failed if route == "direct" else successful

        self.behavior = open_request
        request = Request("https://cdn.invalid/audio", headers={
            "Range": "bytes=100-109", "Referer": "https://www.bilibili.com/",
        })
        with self.network.open(request, timeout=8) as response:
            self.assertTrue(failed.closed)
            self.assertEqual(1, successful.tell(), "only the first byte should be prefetched")
            self.assertEqual(206, response.status)
            self.assertEqual("10", response.headers["Content-Length"])
            self.assertEqual(b"", response.read(0))
            self.assertEqual(b"abc", response.read(3))
            self.assertEqual(b"defghij", response.read())
        self.assertTrue(successful.closed)

    def test_proxy_attempt_does_not_inherit_mutated_request(self):
        def open_request(route, request):
            if route == "direct":
                request.set_proxy("bad.invalid:8000", "https")
                raise URLError(ConnectionResetError("connection lost"))
            self.assertEqual("api.bilibili.com", request.host)
            return Response()

        self.behavior = open_request
        self.request()
        self.assertIsNot(self.calls[0][1], self.calls[1][1])

    def test_incomplete_first_byte_closes_response_before_proxy_fallback(self):
        failed = Response()
        failed.read = Mock(side_effect=http.client.IncompleteRead(b"", 1))
        successful = Response(b"complete")
        self.behavior = lambda route, request: failed if route == "direct" else successful
        self.assertEqual(b"complete", self.request())
        self.assertTrue(failed.closed)
        self.assertTrue(successful.closed)
        self.assertEqual(["direct", PROXY["https"]], [call[0] for call in self.calls])

    def test_route_preference_is_shared_between_http_threads(self):
        self.behavior = lambda route, request: (
            Response() if route != "direct" else self.fail_direct())
        self.request()
        self.calls.clear()
        result = []
        thread = threading.Thread(target=lambda: result.append(self.request()))
        thread.start()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual([b"body"], result)
        self.assertEqual([PROXY["https"]], [call[0] for call in self.calls])

    def test_provider_preserves_both_guest_cookies_across_network_fallback(self):
        provider = BilibiliProvider()
        provider._reset_session()
        for name in ("buvid3", "buvid4"):
            provider._session.cookies.set_cookie(http.cookiejar.Cookie(
                0, name, "guest-ticket", None, False, ".bilibili.com", True, True,
                "/", True, True, None, True, None, None, {},
            ))

        def open_request(route, request):
            self.assertIn("buvid3=guest-ticket", request.get_header("Cookie"))
            self.assertIn("buvid4=guest-ticket", request.get_header("Cookie"))
            self.assertNotIn("SESSDATA", request.get_header("Cookie"))
            if route == "direct":
                raise URLError(ConnectionRefusedError("direct unavailable"))
            return Response(json.dumps({"code": 0, "data": []}).encode())

        self.behavior = open_request
        with patch("lumen_service.providers.build_opener", side_effect=self.factory):
            self.assertEqual({"code": 0, "data": []}, provider.fetch_api(API, "pagelist"))
        self.assertEqual(["direct", PROXY["https"]], [call[0] for call in self.calls])

    def test_audio_proxy_head_and_nonzero_range_use_network_fallback(self):
        proxy = BilibiliAudioProxy(Mock())
        location = AudioLocation(("https://cdn.invalid/audio",), "audio/mp4")
        for head, byte_range, first, last in ((True, None, 0, 0), (False, "bytes=100-109", 100, 109)):
            with self.subTest(head=head):
                self.network = BilibiliNetwork(opener_factory=self.factory)

                def open_request(route, request):
                    self.assertEqual(f"bytes={first}-{last}", request.get_header("Range"))
                    if route == "direct":
                        raise URLError(ConnectionRefusedError("direct unavailable"))
                    length = last - first + 1
                    return Response(b"x" * length, 206, {
                        "Content-Length": str(length),
                        "Content-Range": f"bytes {first}-{last}/900",
                    })

                self.behavior = open_request
                with patch.object(proxy, "resolve", return_value=location), \
                        patch("lumen_service.network._media_network", self.network):
                    opened = proxy.open("bilibili:BV1xx411c7mD:p1", byte_range, head=head)
                self.assertEqual(900, opened.total_length)
                self.assertEqual(last - first + 1, len(opened.response.read()))
                opened.close()


if __name__ == "__main__":
    unittest.main()
