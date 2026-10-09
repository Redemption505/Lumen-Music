from __future__ import annotations

import re
import http.client
import socket
import ssl
import threading
import time
from dataclasses import dataclass
from contextlib import nullcontext
from http.client import HTTPResponse
from typing import Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request

from .network import TRANSIENT_HTTP_STATUSES, urlopen
from .providers import BilibiliProvider, ProviderError, _DEFAULT_HEADERS


_TRACK_ID = re.compile(r"bilibili:(BV[0-9A-Za-z]{10}):p([1-9][0-9]*)\Z")
_BYTE_RANGE = re.compile(r"bytes=(?:[0-9]+-[0-9]*|-[0-9]+)\Z")
_CONTENT_RANGE = re.compile(r"bytes ([0-9]+)-([0-9]+)/([0-9]+)\Z")
_PAGELIST_ENDPOINT = "https://api.bilibili.com/x/player/pagelist"
_PLAYURL_ENDPOINT = "https://api.bilibili.com/x/player/wbi/playurl"
_MEDIA_HEADERS = {
    **_DEFAULT_HEADERS,
    "Referer": "https://www.bilibili.com/",
    "Accept-Encoding": "identity",
}
_LOCATION_CACHE_SECONDS = 60
_MAX_AUDIO_SOURCES = 3


@dataclass(frozen=True)
class AudioLocation:
    urls: tuple[str, ...]
    content_type: str


@dataclass
class OpenAudio:
    response: HTTPResponse
    status: int
    content_type: str
    content_length: int
    content_range: str | None
    total_length: int

    def close(self) -> None:
        self.response.close()


class JsonFetcher(Protocol):
    def fetch_api(self, url: str, operation: str) -> object: ...
    def fetch_wbi_api(self, endpoint: str, params: dict[str, object], operation: str) -> object: ...


class BilibiliAudioProxy:
    """Resolve fresh DASH AAC URLs and forward only the audio bytes."""

    def __init__(self, provider: JsonFetcher, timeout_seconds: float = 12.0) -> None:
        self._provider = provider
        self._timeout_seconds = timeout_seconds
        self._cache_lock = threading.Lock()
        self._cached_locations: dict[str, tuple[float, AudioLocation]] = {}

    def resolve(self, track_id: str) -> AudioLocation:
        with self._cache_lock:
            cached = self._cached_locations.get(track_id)
            if cached is not None and time.monotonic() - cached[0] < _LOCATION_CACHE_SECONDS:
                return cached[1]
        location = self._resolve_fresh(track_id)
        with self._cache_lock:
            self._cached_locations[track_id] = (time.monotonic(), location)
        return location

    def _resolve_fresh(self, track_id: str) -> AudioLocation:
        match = _TRACK_ID.fullmatch(track_id)
        if match is None:
            raise ProviderError("INVALID_TRACK_ID", "Invalid Bilibili track ID", 400)
        bvid, page_text = match.groups()
        page = int(page_text)

        pages = self._provider.fetch_api(
            f"{_PAGELIST_ENDPOINT}?{urlencode({'bvid': bvid})}", "pagelist"
        )
        data = self._api_data(pages, "pagelist")
        if not isinstance(data, list):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid page data")
        selected = next(
            (item for item in data if isinstance(item, dict) and item.get("page") == page),
            None,
        )
        if selected is None:
            raise ProviderError("TRACK_PAGE_NOT_FOUND", "Bilibili video page was not found", 404)
        cid = selected.get("cid")
        if not isinstance(cid, int) or cid <= 0:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned an invalid cid")

        playurl = self._provider.fetch_wbi_api(
            _PLAYURL_ENDPOINT,
            {'bvid': bvid, 'cid': cid, 'qn': 0, 'fnval': 16, 'fnver': 0, 'fourk': 0},
            "playurl",
        )
        data = self._api_data(playurl, "playurl")
        dash = data.get("dash") if isinstance(data, dict) else None
        audio = dash.get("audio") if isinstance(dash, dict) else None
        if not isinstance(audio, list) or not audio:
            raise ProviderError("AUDIO_UNAVAILABLE", "Bilibili did not provide a DASH audio stream", 422)

        # JavaFX 21 accepts AAC-LC in MP4. HE-AAC (mp4a.40.5) is less portable.
        candidates = [
            item for item in audio
            if isinstance(item, dict)
            and item.get("mimeType") == "audio/mp4"
            and item.get("codecs") == "mp4a.40.2"
        ]
        if not candidates:
            raise ProviderError("AUDIO_FORMAT_UNSUPPORTED", "Bilibili has no playable AAC-LC audio", 422)
        selected_audio = max(
            candidates,
            key=lambda item: item.get("bandwidth") if isinstance(item.get("bandwidth"), int) else 0,
        )
        raw_urls = [selected_audio.get("baseUrl"), *(selected_audio.get("backupUrl") or [])]
        urls = tuple(dict.fromkeys(url for url in raw_urls if self._is_media_url(url)))[:_MAX_AUDIO_SOURCES]
        if not urls:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned no valid audio URL")
        return AudioLocation(urls=urls, content_type="audio/mp4")

    def open(self, track_id: str, requested_range: str | None = None, head: bool = False) -> OpenAudio:
        if requested_range is not None and _BYTE_RANGE.fullmatch(requested_range) is None:
            raise ProviderError("INVALID_RANGE", "Use a single bytes range", 416)
        deadline = time.monotonic() + self._timeout_seconds
        budget = (self._provider.request_budget(self._timeout_seconds)
                  if isinstance(self._provider, BilibiliProvider) else nullcontext())
        with budget:
            location = self.resolve(track_id)
            actual_range = "bytes=0-0" if head else requested_range
            try:
                return self._open_location(location, actual_range, deadline)
            except ProviderError as error:
                if error.code != "AUDIO_SOURCE_EXPIRED":
                    raise
                with self._cache_lock:
                    self._cached_locations.pop(track_id, None)
                if time.monotonic() >= deadline:
                    raise ProviderError("UPSTREAM_TIMEOUT", "音频解析与连接超时，请稍后重试", 504) from error
                return self._open_location(self.resolve(track_id), actual_range, deadline)

    def _open_location(self, location: AudioLocation, actual_range: str | None,
                       deadline: float | None = None) -> OpenAudio:
        deadline = deadline if deadline is not None else time.monotonic() + self._timeout_seconds
        last_error: ProviderError | None = None
        urls = tuple(dict.fromkeys(location.urls))[:_MAX_AUDIO_SOURCES]
        for index, url in enumerate(urls):
            headers = dict(_MEDIA_HEADERS)
            if actual_range is not None:
                headers["Range"] = actual_range
            for attempt in range(2):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ProviderError("UPSTREAM_TIMEOUT", "音频解析与连接超时，请稍后重试", 504)
                # Reserve time for one retry and the remaining backup sources.
                timeout = remaining / ((len(urls) - index) * 2 - attempt)
                transient = False
                try:
                    response = urlopen(Request(url, headers=headers), timeout=timeout)
                except HTTPError as error:
                    error.close()
                    if error.code == 416:
                        raise ProviderError(
                            "RANGE_NOT_SATISFIABLE", "The requested audio range is unavailable", 416
                        ) from error
                    last_error = ProviderError(
                        "AUDIO_SOURCE_EXPIRED" if error.code in (403, 404, 410) else "AUDIO_SOURCE_ERROR",
                        f"Bilibili audio source returned HTTP {error.code}",
                    )
                    transient = error.code in TRANSIENT_HTTP_STATUSES
                except (socket.timeout, TimeoutError) as error:
                    last_error = ProviderError("UPSTREAM_TIMEOUT", "Bilibili audio source timed out", 504)
                    transient = True
                except (URLError, OSError, http.client.HTTPException) as error:
                    reason = error.reason if isinstance(error, URLError) else error
                    if isinstance(reason, ssl.SSLCertVerificationError):
                        raise ProviderError("UPSTREAM_TLS_ERROR", "音频连接的证书校验失败") from error
                    if isinstance(reason, (socket.timeout, TimeoutError)):
                        last_error = ProviderError("UPSTREAM_TIMEOUT", "Bilibili audio source timed out", 504)
                    elif isinstance(reason, (ConnectionError, http.client.HTTPException)):
                        last_error = ProviderError("UPSTREAM_CONNECTION_LOST", "音频连接中断，有限重试后仍未恢复")
                    else:
                        last_error = ProviderError("UPSTREAM_UNAVAILABLE", "Bilibili audio source is unavailable")
                    transient = True
                else:
                    try:
                        return self._validate_response(response, location.content_type, actual_range)
                    except ProviderError as error:
                        response.close()
                        last_error = error
                if not transient or attempt == 1:
                    break
                if deadline - time.monotonic() <= 0.1:
                    break
                time.sleep(0.1)
        raise last_error or ProviderError("AUDIO_UNAVAILABLE", "No audio source is available", 502)

    @staticmethod
    def _api_data(payload: object, operation: str) -> object:
        if not isinstance(payload, dict):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", f"Invalid Bilibili {operation} response")
        code = payload.get("code")
        if code in (-412, -352):
            raise ProviderError("UPSTREAM_REJECTED", f"Bilibili rejected {operation}")
        if code == -404:
            raise ProviderError("VIDEO_NOT_FOUND", "视频已删除或不存在", 404)
        if code in (-403, -101, -10403):
            raise ProviderError("VIDEO_ACCESS_DENIED", "视频不可公开播放或当前游客无权访问", 403)
        if code != 0:
            raise ProviderError("UPSTREAM_API_ERROR", f"Bilibili {operation} failed with code {code}")
        return payload.get("data")

    @staticmethod
    def _is_media_url(value: object) -> bool:
        if not isinstance(value, str):
            return False
        try:
            parts = urlsplit(value)
        except ValueError:
            return False
        return parts.scheme == "https" and bool(parts.netloc)

    @staticmethod
    def _validate_response(response: HTTPResponse, content_type: str, requested_range: str | None) -> OpenAudio:
        status = response.status
        if requested_range is not None and status != 206:
            raise ProviderError("UPSTREAM_RANGE_UNSUPPORTED", "Bilibili audio source did not honor Range")
        if requested_range is None and status != 200:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili audio source returned an unexpected status")
        raw_length = response.headers.get("Content-Length")
        if raw_length is None or not raw_length.isdigit():
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili audio source omitted Content-Length")
        length = int(raw_length)
        if length <= 0:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili audio source has no content")
        content_range = response.headers.get("Content-Range") if status == 206 else None
        total = length
        if status == 206:
            match = _CONTENT_RANGE.fullmatch(content_range or "")
            if match is None:
                raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili audio source omitted Content-Range")
            first, last, total = map(int, match.groups())
            if total <= 0 or last < first or last - first + 1 != length or last >= total:
                raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili audio source returned invalid Content-Range")
        return OpenAudio(response, status, content_type, length, content_range, total)
