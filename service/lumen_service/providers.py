from __future__ import annotations

import html
import http.client
import http.cookiejar
import json
import re
import socket
import ssl
import threading
import time
from collections.abc import Callable
from contextlib import contextmanager
from typing import Any, Protocol, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener

from .models import Track, image_cover_uri, parse_view_count
from .network import BilibiliNetwork, TRANSIENT_HTTP_STATUSES, is_transient_transport
from .wbi import mixin_key, sign_query


class TrackProvider(Protocol):
    @property
    def name(self) -> str:
        """Return the stable provider identifier."""

    def search(self, query: str) -> Sequence[Track]:
        """Return metadata matching the query."""

    def music_popular(self) -> Sequence[Track]:
        """Return the provider's real music ranking in upstream order."""


_ERROR_REASONS = {
    "INVALID_BV": "请输入有效的 BV 号或 B 站视频链接",
    "INVALID_TRACK_ID": "曲目标识无效",
    "INVALID_RANGE": "音频请求只能包含一个字节范围",
    "VIDEO_NOT_FOUND": "视频已删除或不存在",
    "VIDEO_ACCESS_DENIED": "视频不可公开播放或当前游客无权访问",
    "TRACK_PAGE_NOT_FOUND": "视频中不存在所选分 P",
    "NOT_A_COLLECTION": "该视频没有多个分 P",
    "AUDIO_UNAVAILABLE": "该视频没有可播放的音轨",
    "AUDIO_FORMAT_UNSUPPORTED": "该视频没有支持的 AAC-LC 音轨",
    "AUDIO_SOURCE_EXPIRED": "音频地址已失效，重新解析后仍不可访问",
    "AUDIO_SOURCE_ERROR": "音频服务器返回错误",
    "RANGE_NOT_SATISFIABLE": "所请求的音频范围不存在",
    "UPSTREAM_RANGE_UNSUPPORTED": "音频服务器未按请求返回字节范围",
    "UPSTREAM_REJECTED": "B 站拒绝游客请求或要求验证，请稍后重试",
    "UPSTREAM_TIMEOUT": "B 站请求超时",
    "UPSTREAM_UNAVAILABLE": "无法连接 B 站",
    "UPSTREAM_CONNECTION_LOST": "与 B 站的连接中断",
    "UPSTREAM_TLS_ERROR": "B 站连接的证书校验失败",
    "UPSTREAM_HTTP_ERROR": "B 站接口返回 HTTP 错误",
    "UPSTREAM_API_ERROR": "B 站接口返回业务错误",
    "UPSTREAM_INVALID_RESPONSE": "B 站返回的数据无效或不完整",
}


class ProviderError(RuntimeError):
    """A provider failure that can be safely exposed through the local API."""

    def __init__(self, code: str, message: str, http_status: int = 502) -> None:
        if code in _ERROR_REASONS and not re.search(r"[\u4e00-\u9fff]", message):
            detail = re.search(r"(?:HTTP |(?:API )?code )(-?[0-9]+)", message)
            message = _ERROR_REASONS[code]
            if detail:
                kind = "HTTP" if detail.group(0).startswith("HTTP") else "接口错误码"
                message += f"（{kind} {detail.group(1)}）"
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status


class DemoProvider:
    """In-memory fictional metadata for offline development and tests."""

    _TRACKS = (
        Track("demo-1", "月光航线", "林间回声", 8, "demo", part_count=1),
        Track("demo-2", "星尘与海 <序曲>", "安和 & 乐团", 8, "demo", part_count=1),
        Track("demo-3", "雨巷信号", "南窗", 8, "demo", part_count=1),
    )

    @property
    def name(self) -> str:
        return "demo"

    def search(self, query: str) -> Sequence[Track]:
        normalized = query.strip().casefold()
        if not normalized:
            return self._TRACKS
        return tuple(
            track
            for track in self._TRACKS
            if normalized in track.title.casefold()
            or normalized in track.artist.casefold()
        )

    def music_popular(self) -> Sequence[Track]:
        return self._TRACKS


_TAG_PATTERN = re.compile(r"<[^>]*>")
_MAX_RESPONSE_BYTES = 5 * 1024 * 1024
_SEARCH_ENDPOINT = "https://api.bilibili.com/x/web-interface/search/type"
_MUSIC_RANKING_ENDPOINT = "https://api.bilibili.com/x/web-interface/ranking/v2"
_VIEW_ENDPOINT = "https://api.bilibili.com/x/web-interface/view"
_PAGELIST_ENDPOINT = "https://api.bilibili.com/x/player/pagelist"
_BVID_PATTERN = re.compile(r"BV[0-9A-Za-z]{10}")
_BOOTSTRAP_URL = "https://www.bilibili.com/"
_SPI_ENDPOINT = "https://api.bilibili.com/x/frontend/finger/spi"
_NAV_ENDPOINT = "https://api.bilibili.com/x/web-interface/nav"
_ANONYMOUS_STREAM_PATHS = frozenset((
    "/x/player/pagelist", "/x/web-interface/nav", "/x/player/wbi/playurl",
))
_DEFAULT_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Referer": "https://search.bilibili.com/",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    ),
}


def _plain_text(value: object) -> str:
    return html.unescape(_TAG_PATTERN.sub("", str(value or ""))).strip()


def _duration_seconds(value: object) -> int | None:
    if isinstance(value, int):
        return value if value >= 0 else None
    parts = str(value or "").strip().split(":")
    if len(parts) not in (2, 3) or not all(part.isdigit() for part in parts):
        return None
    numbers = [int(part) for part in parts]
    if numbers[-1] >= 60 or (len(numbers) == 3 and numbers[-2] >= 60):
        return None
    if len(numbers) == 2:
        return numbers[0] * 60 + numbers[1]
    return numbers[0] * 3600 + numbers[1] * 60 + numbers[2]


def parse_bv(value: str) -> str | None:
    """Extract a BV ID from a bare ID or a canonical Bilibili video URL."""
    candidate = value.strip()
    if _BVID_PATTERN.fullmatch(candidate):
        return candidate
    try:
        parts = urlsplit(candidate)
    except ValueError:
        return None
    host = (parts.hostname or "").lower()
    if parts.scheme not in ("http", "https") or host not in (
        "bilibili.com", "www.bilibili.com", "m.bilibili.com"
    ):
        return None
    path = parts.path.rstrip("/")
    match = re.fullmatch(r"/video/(BV[0-9A-Za-z]{10})", path)
    return match.group(1) if match else None


def _positive_int(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value > 0 else None
    if isinstance(value, str) and value.isdecimal():
        number = int(value)
        return number if number > 0 else None
    return None


class BilibiliProvider:
    """Anonymous Bilibili video metadata search using the public web endpoint."""

    def __init__(
        self,
        timeout_seconds: float = 8.0,
        fetch_json: Callable[[str], Any] | None = None,
    ) -> None:
        self._timeout_seconds = timeout_seconds
        self._injected_fetch_json = fetch_json
        self._session = threading.local()
        self._network = BilibiliNetwork(
            lambda: self._session.cookies,
            lambda *handlers: build_opener(*handlers),
        )
        self._cache_lock = threading.Lock()
        self._resolved_cache: dict[str, tuple[float, tuple[Track, ...]]] = {}
        self._wbi_lock = threading.Lock()
        self._wbi_cache: tuple[float, str] | None = None

    @property
    def name(self) -> str:
        return "bilibili"

    @contextmanager
    def request_budget(self, timeout_seconds: float):
        """Share one time budget across nested metadata and audio requests."""
        previous = getattr(self._session, "deadline", None)
        deadline = time.monotonic() + timeout_seconds
        self._session.deadline = min(previous, deadline) if previous is not None else deadline
        try:
            yield
        finally:
            self._session.deadline = previous

    def _request_timeout(self) -> float:
        deadline = getattr(self._session, "deadline", None)
        remaining = self._timeout_seconds if deadline is None else deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("request budget exhausted")
        return min(self._timeout_seconds, remaining)

    def _wait_for_retry(self) -> bool:
        deadline = getattr(self._session, "deadline", None)
        if deadline is not None and deadline - time.monotonic() <= 0.25:
            return False
        time.sleep(0.25)
        return True

    def search(self, query: str) -> Sequence[Track]:
        normalized = query.strip()
        if not normalized:
            return ()

        url = f"{_SEARCH_ENDPOINT}?{urlencode({'search_type': 'video', 'keyword': normalized, 'page': 1})}"
        payload = self._fetch_payload(url)
        if not isinstance(payload, dict):
            raise ProviderError(
                "UPSTREAM_INVALID_RESPONSE", "Bilibili returned an invalid search response"
            )
        upstream_code = payload.get("code")
        if upstream_code in (-412, -352):
            raise ProviderError(
                "UPSTREAM_REJECTED",
                f"Bilibili rejected the anonymous search request (API code {upstream_code})",
            )
        if upstream_code != 0:
            upstream_code = upstream_code if upstream_code is not None else "unknown"
            raise ProviderError(
                "UPSTREAM_API_ERROR",
                f"Bilibili search failed with API code {upstream_code}",
            )

        data = payload.get("data")
        if isinstance(data, dict) and data.get("v_voucher") is not None:
            raise ProviderError("UPSTREAM_REJECTED", "Bilibili requested anonymous search verification")
        results = data.get("result") if isinstance(data, dict) else None
        if not isinstance(results, list):
            raise ProviderError(
                "UPSTREAM_INVALID_RESPONSE", "Bilibili returned an invalid result list"
            )

        tracks = tuple(track for item in results if (track := self._parse_track(item)) is not None)
        if results and not tracks:
            raise ProviderError(
                "UPSTREAM_INVALID_RESPONSE", "Bilibili results contained no usable video metadata"
            )
        return tracks

    def music_popular(self) -> Sequence[Track]:
        """Read the music partition ranking, rather than search a popular keyword."""
        payload = self._fetch_payload(
            f"{_MUSIC_RANKING_ENDPOINT}?{urlencode({'rid': 3, 'type': 'all'})}",
            "music ranking",
        )
        if not isinstance(payload, dict):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid music ranking")
        code = payload.get("code")
        if code in (-412, -352):
            raise ProviderError("UPSTREAM_REJECTED", "Bilibili rejected anonymous music ranking")
        if code != 0:
            raise ProviderError("UPSTREAM_API_ERROR", f"Bilibili music ranking failed with API code {code}")
        data = payload.get("data")
        rows = data.get("list") if isinstance(data, dict) else None
        if isinstance(data, dict) and data.get("v_voucher") is not None:
            raise ProviderError("UPSTREAM_REJECTED", "Bilibili requested music ranking verification")
        if not isinstance(rows, list) or not rows:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned no music ranking entries")
        tracks: list[Track] = []
        seen: set[str] = set()
        for item in rows:
            if not isinstance(item, dict):
                continue
            owner = item.get("owner")
            normalized = dict(item)
            normalized["author"] = owner.get("name", "") if isinstance(owner, dict) else ""
            track = self._parse_track(normalized)
            if track is not None and track.id not in seen:
                tracks.append(track)
                seen.add(track.id)
        if not tracks:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili music ranking contained no usable tracks")
        return tuple(tracks)

    def resolve_bv(self, value: str) -> Sequence[Track]:
        """Resolve a video to one independently playable Track per page."""
        bvid = parse_bv(value)
        if bvid is None:
            raise ProviderError("INVALID_BV", "Enter a valid BV ID or Bilibili video URL", 400)
        cached = self._cached_tracks(bvid)
        if cached is not None:
            return cached
        tracks = tuple(self._resolve_bv_fresh(bvid))
        with self._cache_lock:
            self._resolved_cache.pop(bvid, None)
            self._resolved_cache[bvid] = (time.monotonic(), tracks)
            while len(self._resolved_cache) > 64:
                self._resolved_cache.pop(next(iter(self._resolved_cache)))
        return tracks

    def _cached_tracks(self, bvid: str) -> tuple[Track, ...] | None:
        with self._cache_lock:
            cached = self._resolved_cache.get(bvid)
            if cached is None:
                return None
            if time.monotonic() - cached[0] >= 300:
                self._resolved_cache.pop(bvid, None)
                return None
            return cached[1]

    def _resolve_bv_fresh(self, bvid: str) -> Sequence[Track]:
        try:
            payload = self._fetch_payload(
                f"{_VIEW_ENDPOINT}?{urlencode({'bvid': bvid})}", "video details"
            )
        except ProviderError as error:
            if error.code not in ("UPSTREAM_REJECTED", "UPSTREAM_HTTP_ERROR",
                                  "UPSTREAM_TIMEOUT", "UPSTREAM_UNAVAILABLE", "UPSTREAM_CONNECTION_LOST"):
                raise
            return self._resolve_from_page_list(bvid)
        if not isinstance(payload, dict):
            raise ProviderError(
                "UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid video details"
            )
        code = payload.get("code")
        if code == -404:
            raise ProviderError("VIDEO_NOT_FOUND", "Bilibili video was not found", 404)
        if code in (-403, -101, -10403):
            raise ProviderError("VIDEO_ACCESS_DENIED", "视频不可公开播放或当前游客无权访问", 403)
        if code in (-412, -352):
            return self._resolve_from_page_list(bvid)
        if code != 0:
            raise ProviderError(
                "UPSTREAM_API_ERROR",
                f"Bilibili video details failed with API code {code if code is not None else 'unknown'}",
            )
        data = payload.get("data")
        pages = data.get("pages") if isinstance(data, dict) else None
        if not isinstance(pages, list) or not pages:
            raise ProviderError(
                "UPSTREAM_INVALID_RESPONSE", "Bilibili returned no usable video pages"
            )
        if data.get("bvid") != bvid:
            raise ProviderError(
                "UPSTREAM_INVALID_RESPONSE", "Bilibili returned mismatched video identity"
            )
        video_title = _plain_text(data.get("title"))
        owner = data.get("owner")
        artist = _plain_text(owner.get("name")) if isinstance(owner, dict) else ""
        cover_uri = image_cover_uri(html.unescape(str(data.get("pic") or "")))
        stat = data.get("stat")
        view_count = parse_view_count(stat.get("view")) if isinstance(stat, dict) else None
        if cover_uri is None:
            cover_uri = self._search_cover_for_bvid(bvid)
        tracks: list[Track] = []
        page_numbers: set[int] = set()
        for item in pages:
            if not isinstance(item, dict):
                raise ProviderError(
                    "UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid video page metadata"
                )
            page = _positive_int(item.get("page"))
            duration = _positive_int(item.get("duration"))
            title = _plain_text(item.get("part")) if len(pages) > 1 else video_title
            if not title:
                title = video_title
            if page is None or page in page_numbers or duration is None or not title:
                raise ProviderError(
                    "UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid video page metadata"
                )
            page_numbers.add(page)
            tracks.append(Track(
                id=f"bilibili:{bvid}:p{page}",
                title=title,
                artist=artist,
                duration_seconds=duration,
                source="bilibili",
                bvid=bvid,
                page=page,
                cover_uri=cover_uri,
                part_count=len(pages),
                view_count=view_count,
            ))
        tracks.sort(key=lambda track: track.page or 0)
        return tuple(tracks)

    def resolve_collection(self, value: str) -> Sequence[Track]:
        """Resolve a multi-page video to its complete, page-ordered track list."""
        tracks = self.resolve_bv(value)
        if len(tracks) < 2:
            raise ProviderError(
                "NOT_A_COLLECTION", "The selected video has no additional parts", 422
            )
        return tracks

    def _page_list(self, bvid: str) -> list[dict]:
        if parse_bv(bvid) != bvid:
            raise ProviderError("INVALID_BV", "Enter a valid BV ID", 400)
        payload = self._fetch_payload(
            f"{_PAGELIST_ENDPOINT}?{urlencode({'bvid': bvid})}", "page list"
        )
        if not isinstance(payload, dict):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Invalid page list")
        code = payload.get("code")
        if code in (-412, -352):
            raise ProviderError("UPSTREAM_REJECTED", "Bilibili rejected page list")
        if code == -404:
            raise ProviderError("VIDEO_NOT_FOUND", "视频已删除或不存在", 404)
        if code in (-403, -101, -10403):
            raise ProviderError("VIDEO_ACCESS_DENIED", "视频不可公开播放或当前游客无权访问", 403)
        if code != 0 or not isinstance(payload.get("data"), list) or not payload["data"]:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "No usable video pages")
        pages = payload["data"]
        if any(not isinstance(page, dict) or _positive_int(page.get("page")) is None
               for page in pages):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Invalid video pages")
        return pages

    def page_count(self, bvid: str) -> int:
        """Read the lightweight page-list endpoint for an exact filter decision."""
        cached = self._cached_tracks(bvid)
        if cached is not None:
            return len(cached)
        return len(self._page_list(bvid))

    def _resolve_from_page_list(self, bvid: str) -> Sequence[Track]:
        """Keep BV direct playable when the richer video-detail endpoint is rejected."""
        pages = self._page_list(bvid)
        cover_uri = self._search_cover_for_bvid(bvid)
        tracks: list[Track] = []
        for item in pages:
            page = _positive_int(item.get("page"))
            duration = _positive_int(item.get("duration"))
            if page is None or duration is None:
                raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Invalid video pages")
            tracks.append(Track(
                id=f"bilibili:{bvid}:p{page}",
                title=_plain_text(item.get("part")) or f"{bvid} · P{page}",
                artist="B站视频", duration_seconds=duration, source="bilibili",
                bvid=bvid, page=page, cover_uri=cover_uri, part_count=len(pages),
            ))
        tracks.sort(key=lambda track: track.page or 0)
        return tuple(tracks)

    def _search_cover_for_bvid(self, bvid: str) -> str | None:
        """Recover a missing cover from public search metadata for this exact BV only."""
        url = f"{_SEARCH_ENDPOINT}?{urlencode({'search_type': 'video', 'keyword': bvid, 'page': 1})}"
        try:
            payload = self._fetch_payload(url, "cover lookup")
        except ProviderError:
            return None
        data = payload.get("data") if isinstance(payload, dict) and payload.get("code") == 0 else None
        results = data.get("result") if isinstance(data, dict) else None
        if not isinstance(results, list):
            return None
        for item in results:
            track = self._parse_track(item)
            if track is not None and track.bvid == bvid and track.cover_uri:
                return track.cover_uri
        return None

    def fetch_api(self, url: str, operation: str) -> Any:
        """Fetch a Bilibili JSON endpoint with this provider's anonymous session."""
        return self._fetch_payload(url, operation)

    def fetch_wbi_api(self, endpoint: str, params: dict[str, object], operation: str) -> Any:
        """Sign each attempt and refresh rejected keys once without a login cookie."""
        for attempt in range(2):
            key = self._wbi_key(force_refresh=attempt > 0)
            try:
                payload = self._fetch_payload(
                    f"{endpoint}?{sign_query(params, key, int(time.time()))}", operation
                )
            except ProviderError as error:
                if error.code == "UPSTREAM_REJECTED" and attempt == 0:
                    self._reset_session()
                    continue
                raise
            data = payload.get("data") if isinstance(payload, dict) else None
            rejected = isinstance(payload, dict) and (
                payload.get("code") in (-403, -412, -352)
                or isinstance(data, dict) and data.get("v_voucher") is not None
            )
            if not rejected:
                return payload
            if attempt == 0:
                self._reset_session()
        raise ProviderError("UPSTREAM_REJECTED", f"Bilibili rejected anonymous {operation}")

    def _wbi_key(self, *, force_refresh: bool = False) -> str:
        try:
            timeout = self._request_timeout()
        except TimeoutError as error:
            raise ProviderError("UPSTREAM_TIMEOUT", "音频解析超时，请稍后重试", 504) from error
        if not self._wbi_lock.acquire(timeout=timeout):
            raise ProviderError("UPSTREAM_TIMEOUT", "等待音频签名信息超时，请稍后重试", 504)
        try:
            if not force_refresh and self._wbi_cache is not None:
                fetched_at, key = self._wbi_cache
                if time.monotonic() - fetched_at < 3600:
                    return key
            self._wbi_cache = None
            payload = self._fetch_payload(_NAV_ENDPOINT, "WBI keys")
            data = payload.get("data") if isinstance(payload, dict) else None
            image = data.get("wbi_img") if isinstance(data, dict) else None
            if not isinstance(payload, dict) or payload.get("code") not in (0, -101):
                raise ProviderError("UPSTREAM_API_ERROR", "Bilibili did not provide WBI keys")
            try:
                key = mixin_key(image["img_url"], image["sub_url"])
            except (TypeError, KeyError, ValueError, AttributeError) as error:
                raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid WBI keys") from error
            self._wbi_cache = (time.monotonic(), key)
            return key
        finally:
            self._wbi_lock.release()

    def _fetch_payload(self, url: str, operation: str = "search") -> Any:
        if self._injected_fetch_json is not None:
            try:
                return self._injected_fetch_json(url)
            except ProviderError:
                raise
            except TimeoutError as error:
                raise ProviderError(
                    "UPSTREAM_TIMEOUT", f"Bilibili {operation} timed out", 504
                ) from error
            except (ValueError, TypeError, json.JSONDecodeError) as error:
                raise ProviderError(
                    "UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid JSON"
                ) from error

        with self.request_budget(min(12.0, self._timeout_seconds * 2 + 0.25)):
            return self._fetch_network_payload(url, operation)

    def _fetch_network_payload(self, url: str, operation: str) -> Any:

        parts = urlsplit(url)
        anonymous_stream = (
            parts.hostname == "api.bilibili.com" and parts.path in _ANONYMOUS_STREAM_PATHS
        )
        require_guest_identity = not anonymous_stream
        reset_session = False
        retried_transient = False
        for _ in range(3):
            try:
                self._request_timeout()
                if require_guest_identity:
                    self._bootstrap()
                elif not hasattr(self._session, "opener"):
                    self._reset_session()
                payload = self._request_json(url)
                data = payload.get("data") if isinstance(payload, dict) else None
                rejected = isinstance(payload, dict) and (
                    payload.get("code") in (-412, -352)
                    or anonymous_stream and payload.get("code") == -403
                    or isinstance(data, dict) and data.get("v_voucher") is not None
                )
                if rejected and not reset_session:
                    reset_session = True
                    require_guest_identity = True
                    self._reset_session()
                    continue
                if rejected:
                    raise ProviderError(
                        "UPSTREAM_REJECTED", f"Bilibili rejected the anonymous {operation} request"
                    )
                return payload
            except HTTPError as error:
                error.close()
                if parts.path in ("/x/web-interface/view", "/x/player/pagelist", "/x/player/wbi/playurl"):
                    if error.code in (404, 410):
                        raise ProviderError("VIDEO_NOT_FOUND", "视频已删除或不存在", 404) from error
                    if error.code == 403 and not anonymous_stream:
                        raise ProviderError("VIDEO_ACCESS_DENIED", "视频不可公开播放或当前游客无权访问", 403) from error
                rejected = error.code == 412 or anonymous_stream and error.code == 403
                if rejected and not reset_session:
                    reset_session = True
                    require_guest_identity = True
                    self._reset_session()
                    continue
                if rejected:
                    raise ProviderError(
                        "UPSTREAM_REJECTED",
                        f"Bilibili rejected the anonymous {operation} request",
                    ) from error
                if error.code in TRANSIENT_HTTP_STATUSES and not retried_transient and self._wait_for_retry():
                    retried_transient = True
                    continue
                raise ProviderError(
                    "UPSTREAM_HTTP_ERROR",
                    f"Bilibili {operation} returned HTTP {error.code}",
                ) from error
            except (socket.timeout, TimeoutError) as error:
                if not retried_transient and self._wait_for_retry():
                    retried_transient = True
                    continue
                raise ProviderError(
                    "UPSTREAM_TIMEOUT", f"Bilibili {operation} timed out", 504
                ) from error
            except (URLError, OSError, http.client.HTTPException) as error:
                reason = error.reason if isinstance(error, URLError) else error
                if isinstance(reason, ssl.SSLCertVerificationError):
                    raise ProviderError("UPSTREAM_TLS_ERROR", "B 站连接的证书校验失败") from error
                if is_transient_transport(error) and not retried_transient and self._wait_for_retry():
                    retried_transient = True
                    continue
                if isinstance(reason, (socket.timeout, TimeoutError)):
                    raise ProviderError(
                        "UPSTREAM_TIMEOUT", f"Bilibili {operation} timed out", 504
                    ) from error
                if isinstance(reason, (ConnectionError, http.client.HTTPException)):
                    raise ProviderError("UPSTREAM_CONNECTION_LOST", "与 B 站的连接中断，有限重试后仍未恢复") from error
                raise ProviderError(
                    "UPSTREAM_UNAVAILABLE", f"Bilibili {operation} is unavailable"
                ) from error
            except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
                raise ProviderError(
                    "UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid JSON"
                ) from error
        raise ProviderError("UPSTREAM_UNAVAILABLE", f"Bilibili {operation} is unavailable")

    def _reset_session(self) -> None:
        self._session.cookies = http.cookiejar.CookieJar()
        self._session.opener = self._network

    def _bootstrap(self) -> None:
        if not hasattr(self._session, "opener"):
            self._reset_session()
        if self._has_guest_identity():
            return
        request = Request(_BOOTSTRAP_URL, headers=_DEFAULT_HEADERS)
        try:
            with self._session.opener.open(request, timeout=self._request_timeout()) as response:
                response.read(1024)
        except (URLError, OSError, http.client.HTTPException) as error:
            # SPI can issue both guest tickets even when the homepage is unavailable.
            if isinstance(error, HTTPError):
                error.close()
            if not isinstance(error, HTTPError) and not is_transient_transport(error):
                raise
        if self._has_guest_identity():
            return
        payload = self._request_json(_SPI_ENDPOINT)
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(payload, dict) or payload.get("code") != 0 or not isinstance(data, dict):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid guest identity")
        existing = {cookie.name for cookie in self._session.cookies
                    if cookie.value and not cookie.is_expired()}
        for name, field in (("buvid3", "b_3"), ("buvid4", "b_4")):
            value = data.get(field)
            if name not in existing and isinstance(value, str) and value.strip():
                self._session.cookies.set_cookie(http.cookiejar.Cookie(
                    version=0, name=name, value=value, port=None, port_specified=False,
                    domain=".bilibili.com", domain_specified=True, domain_initial_dot=True,
                    path="/", path_specified=True, secure=True, expires=None,
                    discard=True, comment=None, comment_url=None, rest={},
                ))
        if not self._has_guest_identity():
            raise ProviderError("UPSTREAM_REJECTED", "Bilibili did not issue both guest buvid tickets")

    def _has_guest_identity(self) -> bool:
        names = {cookie.name for cookie in self._session.cookies
                 if cookie.value and not cookie.is_expired()}
        return {"buvid3", "buvid4"} <= names

    def _request_json(self, url: str) -> Any:
        request = Request(url, headers=_DEFAULT_HEADERS)
        with self._session.opener.open(request, timeout=self._request_timeout()) as response:
            body = response.read(_MAX_RESPONSE_BYTES + 1)
        if len(body) > _MAX_RESPONSE_BYTES:
            raise ValueError("response exceeds size limit")
        return json.loads(body.decode("utf-8"))

    @staticmethod
    def _parse_track(item: object) -> Track | None:
        if not isinstance(item, dict):
            return None
        bvid = str(item.get("bvid") or "").strip()
        title = _plain_text(item.get("title"))
        duration = _duration_seconds(item.get("duration"))
        if not _BVID_PATTERN.fullmatch(bvid) or not title or duration is None:
            return None
        page = 1
        stat = item.get("stat")
        play = item.get("play")
        if play is None and isinstance(stat, dict):
            play = stat.get("view")
        return Track(
            id=f"bilibili:{bvid}:p{page}",
            title=title,
            artist=_plain_text(item.get("author")),
            duration_seconds=duration,
            source="bilibili",
            bvid=bvid,
            page=page,
            cover_uri=image_cover_uri(html.unescape(str(item.get("pic") or ""))),
            part_count=_positive_int(item.get("videos")),
            view_count=parse_view_count(play),
        )
