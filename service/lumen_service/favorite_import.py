"""Import public Bilibili video favorites using the anonymous provider session."""

from __future__ import annotations

import html
import re
from dataclasses import dataclass
from urllib.parse import parse_qs, urlencode, urlsplit

from .models import Track, image_cover_uri, parse_view_count
from .providers import BilibiliProvider, ProviderError, _plain_text


_LIST_ENDPOINT = "https://api.bilibili.com/x/v3/fav/resource/list"
_BVID = re.compile(r"BV[0-9A-Za-z]{10}\Z")
_MEDIA_PATH = re.compile(r"/(?:medialist/(?:detail|play)/|list/)ml([1-9][0-9]*)/?\Z")
_SPACE_PATH = re.compile(r"/[1-9][0-9]*/favlist/?\Z")
_MAX_PAGES = 500
_MAX_TRACKS = 200
_IMPORT_TIMEOUT_SECONDS = 120.0


@dataclass(frozen=True, slots=True)
class FavoriteImport:
    name: str
    tracks: tuple[Track, ...]
    skipped_count: int
    duplicate_count: int
    truncated: bool = False


def parse_favorite_id(value: str) -> str:
    """Accept canonical public favorite links, not arbitrary URLs or short links."""
    candidate = value.strip()
    if re.fullmatch(r"[1-9][0-9]*", candidate):
        return candidate
    try:
        parts = urlsplit(candidate)
        host = (parts.hostname or "").lower()
    except ValueError as error:
        raise ProviderError("INVALID_FAVORITE_URL", "请输入有效的 B 站收藏夹链接", 400) from error
    if parts.scheme not in ("http", "https"):
        raise ProviderError("INVALID_FAVORITE_URL", "请输入有效的 B 站收藏夹链接", 400)
    if host in ("www.bilibili.com", "bilibili.com"):
        match = _MEDIA_PATH.fullmatch(parts.path)
        if match:
            return match.group(1)
    if host == "space.bilibili.com" and _SPACE_PATH.fullmatch(parts.path):
        values = parse_qs(parts.query).get("fid", ())
        if len(values) == 1 and re.fullmatch(r"[1-9][0-9]*", values[0]):
            return values[0]
    raise ProviderError("INVALID_FAVORITE_URL", "请输入有效的 B 站收藏夹链接", 400)


def _valid_track(item: object) -> Track | None:
    if not isinstance(item, dict) or item.get("type") != 2 or item.get("attr") != 0:
        return None
    bvid = item.get("bvid") or item.get("bv_id")
    if not isinstance(bvid, str) or not _BVID.fullmatch(bvid):
        return None
    title = _plain_text(item.get("title"))
    duration = item.get("duration")
    if isinstance(duration, str) and duration.isdecimal():
        duration = int(duration)
    if not title or type(duration) is not int or duration < 0:
        return None
    upper = item.get("upper")
    artist = _plain_text(upper.get("name")) if isinstance(upper, dict) else ""
    part_count = item.get("page")
    if type(part_count) is not int or part_count < 1:
        part_count = None
    counts = item.get("cnt_info")
    view_count = parse_view_count(counts.get("play")) if isinstance(counts, dict) else None
    return Track(
        id=f"bilibili:{bvid}:p1", title=title, artist=artist or "B站 UP 主",
        duration_seconds=duration, source="bilibili", bvid=bvid, page=1,
        cover_uri=image_cover_uri(html.unescape(str(item.get("cover") or ""))),
        part_count=part_count,
        view_count=view_count,
    )


def fetch_public_favorite(provider: BilibiliProvider, value: str) -> FavoriteImport:
    """Read at most 200 valid unique videos before the caller writes a playlist."""
    media_id = parse_favorite_id(value)
    with provider.request_budget(_IMPORT_TIMEOUT_SECONDS):
        return _fetch_favorite_pages(provider, media_id)


def _fetch_favorite_pages(provider: BilibiliProvider, media_id: str) -> FavoriteImport:
    tracks: list[Track] = []
    seen: set[str] = set()
    skipped = 0
    duplicates = 0
    name = ""

    for page in range(1, _MAX_PAGES + 1):
        url = f"{_LIST_ENDPOINT}?{urlencode({'media_id': media_id, 'pn': page, 'ps': 20, 'platform': 'web'})}"
        payload = provider.fetch_api(url, "favorite list")
        if not isinstance(payload, dict):
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili returned invalid favorite data")
        code = payload.get("code")
        if code in (-403, -101):
            raise ProviderError("FAVORITE_INACCESSIBLE", "收藏夹不是公开状态或游客无权读取", 403)
        if code in (-400, -404):
            raise ProviderError("FAVORITE_NOT_FOUND", "收藏夹不存在或链接已失效", 404)
        if code in (-412, -352):
            raise ProviderError("UPSTREAM_REJECTED", "Bilibili 拒绝了游客收藏夹请求")
        if code != 0:
            raise ProviderError("UPSTREAM_API_ERROR", f"Bilibili 收藏夹接口返回错误码 {code}")
        data = payload.get("data")
        info = data.get("info") if isinstance(data, dict) else None
        medias = data.get("medias") if isinstance(data, dict) else None
        has_more = data.get("has_more") if isinstance(data, dict) else None
        if not isinstance(info, dict) or not isinstance(medias, (list, type(None))) or type(has_more) is not bool:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili 返回了无效的收藏夹列表")
        if str(info.get("id")) != media_id:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili 返回了不匹配的收藏夹")
        page_name = _plain_text(info.get("title"))
        if not page_name:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "Bilibili 返回的收藏夹没有名称")
        if name and name != page_name:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "导入期间收藏夹名称发生变化，请重试")
        name = page_name
        rows = medias or ()
        for index, item in enumerate(rows):
            track = _valid_track(item)
            if track is None:
                skipped += 1
            elif track.id in seen:
                duplicates += 1
            else:
                seen.add(track.id)
                tracks.append(track)
                if len(tracks) == _MAX_TRACKS:
                    return FavoriteImport(name, tuple(tracks), skipped, duplicates,
                                          has_more or index + 1 < len(rows))
        if not has_more:
            return FavoriteImport(name, tuple(tracks), skipped, duplicates)
        if not medias:
            raise ProviderError("UPSTREAM_INVALID_RESPONSE", "收藏夹分页无进展，请稍后重试")
    raise ProviderError("FAVORITE_TOO_LARGE", "收藏夹超过 10000 条，暂无法完整导入", 400)
