from __future__ import annotations

import http.cookiejar
import http.client
import ssl
import threading
import time
import urllib.request
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request


TRANSIENT_HTTP_STATUSES = frozenset((502, 503, 504))


def is_transient_transport(error: BaseException) -> bool:
    reason = error.reason if isinstance(error, URLError) else error
    if isinstance(reason, ssl.SSLCertVerificationError):
        return False
    return isinstance(error, (URLError, OSError, http.client.HTTPException))


class _PrefetchedResponse:
    def __init__(self, response, first_byte: bytes) -> None:
        self._response = response
        self._first_byte = first_byte

    def __getattr__(self, name):
        return getattr(self._response, name)

    def read(self, size: int = -1) -> bytes:
        if size == 0:
            return b""
        prefix, self._first_byte = self._first_byte, b""
        if size < 0:
            return prefix + self._response.read()
        return prefix + self._response.read(size - len(prefix))

    def close(self) -> None:
        self._response.close()

    def __enter__(self):
        return self

    def __exit__(self, *args) -> None:
        self.close()


class BilibiliNetwork:
    """Prefer direct access, with current system proxies as a bounded fallback."""

    def __init__(self, cookies: http.cookiejar.CookieJar | Callable | None = None,
                 opener_factory: Callable | None = None) -> None:
        self._cookies = cookies
        self._opener_factory = opener_factory or urllib.request.build_opener
        self._lock = threading.Lock()
        self._session = threading.local()
        self._proxy_signature: tuple | None = None
        self._preferred: dict[tuple[str, str], str] = {}

    def open(self, request: Request, timeout: float):
        proxies = urllib.request.getproxies()
        signature = tuple(sorted(proxies.items()))
        parts = urlsplit(request.full_url)
        origin = (parts.scheme, parts.hostname or "")
        cookies = self._cookies() if callable(self._cookies) else self._cookies
        if (getattr(self._session, "signature", None) != signature
                or getattr(self._session, "cookies", None) is not cookies):
            self._session.signature = signature
            self._session.cookies = cookies
            self._session.openers = {}
        with self._lock:
            if signature != self._proxy_signature:
                self._proxy_signature = signature
                self._preferred.clear()
            routes = ["direct"]
            if proxies.get(parts.scheme):
                routes.append("proxy")
            if self._preferred.get(origin) == "proxy" and len(routes) == 2:
                routes.reverse()
        openers = []
        for route in routes:
            if route not in self._session.openers:
                handlers = [ProxyHandler({} if route == "direct" else proxies)]
                if cookies is not None:
                    handlers.append(HTTPCookieProcessor(cookies))
                self._session.openers[route] = self._opener_factory(*handlers)
            openers.append(self._session.openers[route])

        deadline = time.monotonic() + timeout
        for index, (route, opener) in enumerate(zip(routes, openers)):
            # ProxyHandler mutates Request while opening a tunnel; retry a fresh copy.
            attempt = Request(request.full_url, data=request.data,
                              headers=dict(request.header_items()),
                              method=request.get_method())
            remaining = max(0.001, deadline - time.monotonic())
            attempt_timeout = remaining / (len(routes) - index)
            response = None
            try:
                response = opener.open(attempt, timeout=attempt_timeout)
                first_byte = response.read(1)
            except HTTPError:
                raise
            except (URLError, OSError, http.client.HTTPException) as error:
                if response is not None:
                    response.close()
                if (not is_transient_transport(error)
                        or index + 1 == len(routes) or time.monotonic() >= deadline):
                    if isinstance(error, URLError) or isinstance(error, TimeoutError):
                        raise
                    raise URLError(error) from error
                continue
            with self._lock:
                if self._proxy_signature == signature:
                    self._preferred[origin] = route
                    while len(self._preferred) > 64:
                        self._preferred.pop(next(iter(self._preferred)))
            return _PrefetchedResponse(response, first_byte)


_media_network = BilibiliNetwork()


def urlopen(request: Request, timeout: float):
    return _media_network.open(request, timeout)
