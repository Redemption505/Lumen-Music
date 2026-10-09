from __future__ import annotations

import http.cookiejar
import io
import json
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request

from lumen_service.providers import BilibiliProvider, ProviderError
from lumen_service.streaming import BilibiliAudioProxy
from lumen_service.wbi import mixin_key, sign_query


_IMG = "7cd084941338484aae1ad9425b84077c"
_SUB = "4932caff0ff746eab6f01bf08b70ac45"


def nav_payload():
    return {"code": -101, "data": {"wbi_img": {
        "img_url": f"https://i0.hdslb.com/bfs/wbi/{_IMG}.png",
        "sub_url": f"https://i0.hdslb.com/bfs/wbi/{_SUB}.png",
    }}}


def cookie(name, value="ticket", expires=None):
    return http.cookiejar.Cookie(
        0, name, value, None, False, ".bilibili.com", True, True,
        "/", True, True, expires, expires is None, None, None, {},
    )


class GuestIdentityTest(unittest.TestCase):
    def setUp(self):
        proxies = patch("urllib.request.getproxies", return_value={})
        proxies.start()
        self.addCleanup(proxies.stop)

    def provider(self, names):
        provider = BilibiliProvider()
        provider._reset_session()
        for name in names:
            provider._session.cookies.set_cookie(cookie(name, f"existing-{name}"))
        provider._session.opener = Mock()
        provider._session.opener.open.side_effect = lambda *args, **kwargs: io.BytesIO(b"home")
        return provider

    def test_either_missing_ticket_is_issued_and_existing_ticket_preserved(self):
        for names in ((), ("buvid3",), ("buvid4",)):
            with self.subTest(names=names):
                provider = self.provider(names)
                with patch.object(provider, "_request_json", return_value={
                    "code": 0, "data": {"b_3": "spi3", "b_4": "spi4"},
                }) as request:
                    provider._bootstrap()
                    provider._bootstrap()
                request.assert_called_once_with("https://api.bilibili.com/x/frontend/finger/spi")
                provider._session.opener.open.assert_called_once()
                values = {item.name: item.value for item in provider._session.cookies}
                for name in names:
                    self.assertEqual(f"existing-{name}", values[name])
                self.assertEqual({"buvid3", "buvid4"}, set(values))
                outgoing = Request(
                    "https://api.bilibili.com/x/player/wbi/playurl")
                provider._session.cookies.add_cookie_header(outgoing)
                self.assertIn("buvid3=", outgoing.get_header("Cookie"))
                self.assertIn("buvid4=", outgoing.get_header("Cookie"))
                self.assertNotIn("SESSDATA", outgoing.get_header("Cookie"))

    def test_complete_identity_needs_no_bootstrap_request(self):
        provider = self.provider(("buvid3", "buvid4"))
        with patch.object(provider, "_request_json") as request:
            provider._bootstrap()
        request.assert_not_called()
        provider._session.opener.open.assert_not_called()

    def test_expired_or_empty_ticket_does_not_count_as_complete(self):
        provider = self.provider(("buvid3",))
        for ticket in (cookie("buvid4", expires=1), cookie("buvid4", value="")):
            provider._session.cookies.set_cookie(ticket)
            self.assertFalse(provider._has_guest_identity())

    def test_partial_spi_identity_is_explicit_failure(self):
        provider = self.provider(())
        with patch.object(provider, "_request_json", return_value={"code": 0, "data": {"b_3": "spi3"}}):
            with self.assertRaises(ProviderError) as raised:
                provider._bootstrap()
        self.assertEqual("UPSTREAM_REJECTED", raised.exception.code)

    def test_homepage_timeout_still_searches_with_both_spi_tickets(self):
        provider = BilibiliProvider()
        paths = []

        def open_request(request, **kwargs):
            path = urlsplit(request.full_url).path
            paths.append(path)
            provider._session.cookies.add_cookie_header(request)
            if path == "/":
                raise TimeoutError("homepage unavailable")
            if path == "/x/frontend/finger/spi":
                payload = {"code": 0, "data": {"b_3": "spi3", "b_4": "spi4"}}
            else:
                self.assertEqual("/x/web-interface/search/type", path)
                self.assertIn("buvid3=spi3", request.get_header("Cookie"))
                self.assertIn("buvid4=spi4", request.get_header("Cookie"))
                self.assertNotIn("SESSDATA", request.get_header("Cookie"))
                payload = {"code": 0, "data": {"result": []}}
            return io.BytesIO(json.dumps(payload).encode())

        opener = Mock()
        opener.open.side_effect = open_request
        with patch("lumen_service.providers.build_opener", return_value=opener):
            self.assertEqual((), provider.search("song"))
        self.assertEqual(["/", "/x/frontend/finger/spi", "/x/web-interface/search/type"], paths)
        self.assertTrue(provider._has_guest_identity())

    def test_anonymous_stream_never_requests_unavailable_homepage(self):
        provider = BilibiliProvider()
        paths = []

        def open_request(request, **kwargs):
            path = urlsplit(request.full_url).path
            paths.append(path)
            if path == "/":
                raise TimeoutError("homepage unavailable")
            self.assertFalse(provider._has_guest_identity())
            if path == "/x/player/pagelist":
                payload = {"code": 0, "data": [{"page": 1, "cid": 100}]}
            elif path == "/x/web-interface/nav":
                payload = nav_payload()
            else:
                self.assertEqual("/x/player/wbi/playurl", path)
                signed = parse_qs(urlsplit(request.full_url).query)
                self.assertIn("w_rid", signed)
                self.assertEqual(["16"], signed["fnval"])
                payload = {"code": 0, "data": {"dash": {"audio": [{
                    "mimeType": "audio/mp4", "codecs": "mp4a.40.2",
                    "baseUrl": "https://cdn.invalid/audio.m4s",
                }]}}}
            return io.BytesIO(json.dumps(payload).encode())

        opener = Mock()
        opener.open.side_effect = open_request
        with patch("lumen_service.providers.build_opener", return_value=opener):
            location = BilibiliAudioProxy(provider).resolve("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(("https://cdn.invalid/audio.m4s",), location.urls)
        self.assertEqual([
            "/x/player/pagelist", "/x/web-interface/nav", "/x/player/wbi/playurl",
        ], paths)

    def test_anonymous_rejection_retries_with_guest_identity_once(self):
        for rejection in (-412, -352, -403, "voucher", "http412", "http403"):
            with self.subTest(rejection=rejection):
                provider = BilibiliProvider()
                paths = []

                def open_request(request, **kwargs):
                    path = urlsplit(request.full_url).path
                    paths.append(path)
                    provider._session.cookies.add_cookie_header(request)
                    if path == "/":
                        return io.BytesIO(b"home")
                    if path == "/x/frontend/finger/spi":
                        payload = {"code": 0, "data": {"b_3": "spi3", "b_4": "spi4"}}
                    elif paths.count(path) == 1:
                        self.assertFalse(provider._has_guest_identity())
                        if rejection in ("http412", "http403"):
                            raise HTTPError(request.full_url, int(rejection[4:]),
                                            "rejected", {}, io.BytesIO())
                        payload = ({"code": 0, "data": {"v_voucher": "verify"}}
                                   if rejection == "voucher" else {"code": rejection})
                    else:
                        self.assertTrue(provider._has_guest_identity())
                        self.assertIn("buvid3=spi3", request.get_header("Cookie"))
                        self.assertIn("buvid4=spi4", request.get_header("Cookie"))
                        payload = {"code": 0, "data": [{"page": 1, "cid": 100}]}
                    return io.BytesIO(json.dumps(payload).encode())

                opener = Mock()
                opener.open.side_effect = open_request
                with patch("lumen_service.providers.build_opener", return_value=opener):
                    payload = provider.fetch_api(
                        "https://api.bilibili.com/x/player/pagelist?bvid=BV1xx411c7mD",
                        "pagelist",
                    )
                self.assertEqual(0, payload["code"])
                self.assertEqual([
                    "/x/player/pagelist", "/", "/x/frontend/finger/spi", "/x/player/pagelist",
                ], paths)

    def test_persistent_anonymous_rejection_is_bounded(self):
        provider = self.provider(())
        paths = []

        def request(url):
            path = urlsplit(url).path
            paths.append(path)
            if path == "/x/frontend/finger/spi":
                return {"code": 0, "data": {"b_3": "spi3", "b_4": "spi4"}}
            return {"code": -412}

        opener = provider._session.opener
        with patch("lumen_service.providers.build_opener", return_value=opener), \
                patch.object(provider, "_request_json", side_effect=request):
            with self.assertRaises(ProviderError) as raised:
                provider.fetch_api(
                    "https://api.bilibili.com/x/player/pagelist?bvid=BV1xx411c7mD",
                    "pagelist",
                )
        self.assertEqual("UPSTREAM_REJECTED", raised.exception.code)
        self.assertEqual([
            "/x/player/pagelist", "/x/frontend/finger/spi", "/x/player/pagelist",
        ], paths)
        opener.open.assert_called_once()

    def test_voucher_refreshes_identity_once_and_persistent_voucher_is_not_empty(self):
        provider = BilibiliProvider()
        voucher = {"code": 0, "data": {"v_voucher": "challenge"}}
        good = {"code": 0, "data": {"result": []}}
        with patch.object(provider, "_bootstrap"), patch.object(provider, "_reset_session") as reset, \
                patch.object(provider, "_request_json", side_effect=[voucher, good]) as request:
            self.assertEqual((), provider.search("song"))
            self.assertEqual(2, request.call_count)
            reset.assert_called_once()
        with patch.object(provider, "_bootstrap"), patch.object(provider, "_reset_session") as reset, \
                patch.object(provider, "_request_json", return_value=voucher) as request:
            with self.assertRaises(ProviderError) as raised:
                provider.search("song")
            self.assertEqual("UPSTREAM_REJECTED", raised.exception.code)
            self.assertEqual(2, request.call_count)
            reset.assert_called_once()


class WbiTest(unittest.TestCase):
    def test_documented_signature_vector_and_url_encoding(self):
        key = mixin_key(nav_payload()["data"]["wbi_img"]["img_url"],
                        nav_payload()["data"]["wbi_img"]["sub_url"])
        self.assertEqual("ea1db124af3c7062474693fa704f4ff8", key)
        self.assertEqual(
            "bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            sign_query({"foo": 114, "bar": 514, "zab": 1919810}, key, 1702204169),
        )
        signed = sign_query({"q": "song 中文!'()*", "w_rid": "old", "wts": 0}, key, 1)
        self.assertIn("q=song%20%E4%B8%AD%E6%96%87&wts=1&", signed)
        self.assertEqual(1, signed.count("w_rid="))

    def test_signed_playurl_preserves_page_and_reuses_keys_and_location(self):
        urls = []
        def fetch(url):
            urls.append(url)
            if urlsplit(url).path.endswith("/nav"):
                return nav_payload()
            if urlsplit(url).path.endswith("/pagelist"):
                return {"code": 0, "data": [{"page": 1, "cid": 100}, {"page": 2, "cid": 200}]}
            query = parse_qs(urlsplit(url).query)
            self.assertEqual("/x/player/wbi/playurl", urlsplit(url).path)
            self.assertIn("w_rid", query)
            self.assertIn("wts", query)
            return {"code": 0, "data": {"dash": {"audio": [{
                "mimeType": "audio/mp4", "codecs": "mp4a.40.2",
                "baseUrl": "https://cdn.invalid/audio.m4s",
            }]}}}
        provider = BilibiliProvider(fetch_json=fetch)
        proxy = BilibiliAudioProxy(provider)
        first = proxy.resolve("bilibili:BV1xx411c7mD:p2")
        self.assertIs(first, proxy.resolve("bilibili:BV1xx411c7mD:p2"))
        proxy.resolve("bilibili:BV1xx411c7mD:p1")
        self.assertEqual(1, sum(urlsplit(url).path.endswith("/nav") for url in urls))
        signed = [url for url in urls if "/wbi/playurl" in url]
        self.assertEqual(["200", "100"], [parse_qs(urlsplit(url).query)["cid"][0] for url in signed])
        with patch("lumen_service.providers.time.monotonic", return_value=10**12):
            provider._wbi_key()
        self.assertEqual(2, sum(urlsplit(url).path.endswith("/nav") for url in urls))

    def test_rejected_signature_refreshes_once_and_never_loops(self):
        for failures in (1, 2):
            calls = []
            def fetch(url):
                calls.append(url)
                if urlsplit(url).path.endswith("/nav"):
                    return nav_payload()
                attempts = sum("/wbi/playurl" in item for item in calls)
                return {"code": -403 if attempts <= failures else 0, "data": {}}
            provider = BilibiliProvider(fetch_json=fetch)
            if failures == 1:
                self.assertEqual(0, provider.fetch_wbi_api("https://api.bilibili.com/x/player/wbi/playurl", {}, "playurl")["code"])
            else:
                with self.assertRaises(ProviderError) as raised:
                    provider.fetch_wbi_api("https://api.bilibili.com/x/player/wbi/playurl", {}, "playurl")
                self.assertEqual("UPSTREAM_REJECTED", raised.exception.code)
            self.assertEqual(4, len(calls))

    def test_bad_nav_does_not_cache_or_request_unsigned_playurl(self):
        calls = []
        def fetch(url):
            calls.append(url)
            return {"code": -101, "data": {"wbi_img": {"img_url": "bad", "sub_url": "bad"}}}
        provider = BilibiliProvider(fetch_json=fetch)
        for _ in range(2):
            with self.assertRaises(ProviderError) as raised:
                provider.fetch_wbi_api("https://api.bilibili.com/x/player/wbi/playurl", {}, "playurl")
            self.assertEqual("UPSTREAM_INVALID_RESPONSE", raised.exception.code)
        self.assertEqual(2, len(calls))
        self.assertTrue(all(urlsplit(url).path.endswith("/nav") for url in calls))

    def test_http_rejection_refreshes_keys_but_other_errors_do_not(self):
        for code, expected_calls in (("UPSTREAM_REJECTED", 4), ("UPSTREAM_TIMEOUT", 2)):
            calls = []
            def fetch(url):
                calls.append(url)
                if urlsplit(url).path.endswith("/nav"):
                    return nav_payload()
                if len(calls) == 2:
                    raise ProviderError(code, "failure")
                return {"code": 0}
            provider = BilibiliProvider(fetch_json=fetch)
            if code == "UPSTREAM_REJECTED":
                self.assertEqual(0, provider.fetch_wbi_api("https://api.bilibili.com/x/player/wbi/playurl", {}, "playurl")["code"])
            else:
                with self.assertRaises(ProviderError) as raised:
                    provider.fetch_wbi_api("https://api.bilibili.com/x/player/wbi/playurl", {}, "playurl")
                self.assertEqual(code, raised.exception.code)
            self.assertEqual(expected_calls, len(calls))
