from __future__ import annotations

from dataclasses import dataclass
import re
from urllib.parse import urlsplit


_IMAGE_PATH = re.compile(r"\.(?:avif|gif|jpe?g|png|webp)(?:@[^/]*)?$", re.IGNORECASE)


def image_cover_uri(value: object) -> str | None:
    """Accept an HTTP image URL, including Bilibili's scheme-relative pic values."""
    if not isinstance(value, str):
        return None
    uri = value.strip()
    if uri.startswith("//"):
        uri = "https:" + uri
    try:
        parts = urlsplit(uri)
        if (parts.scheme not in ("http", "https") or not parts.hostname
                or not _IMAGE_PATH.search(parts.path)):
            return None
    except ValueError:
        return None
    return uri


def parse_view_count(value: object) -> int | None:
    if type(value) is int:
        return value if value >= 0 else None
    if isinstance(value, str) and value.isdecimal():
        return int(value)
    return None


@dataclass(frozen=True, slots=True)
class Track:
    id: str
    title: str
    artist: str
    duration_seconds: int
    source: str
    bvid: str | None = None
    page: int | None = None
    cover_uri: str | None = None
    part_count: int | None = None
    view_count: int | None = None
    source_track_id: str | None = None
    segment_start_millis: int | None = None
    segment_end_millis: int | None = None
    source_duration_seconds: int | None = None
