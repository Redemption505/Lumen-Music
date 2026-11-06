"""Synchronized lyrics from LRCLIB and public NetEase song metadata.

The upstream search result is only a candidate. Automatic application requires
an exact normalized title and artist plus a close duration match.
"""

from __future__ import annotations

import json
import math
import re
import threading
import time
import unicodedata
from dataclasses import dataclass
from datetime import timezone
from email.utils import parsedate_to_datetime
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .lyric_scripts import simplified, traditional, script_preference
from .providers import ProviderError


_ENDPOINT = "https://lrclib.net/api/search"
_NETEASE_SEARCH = "https://music.163.com/api/search/get"
_NETEASE_LYRIC = "https://music.163.com/api/song/lyric"
_MAX_BYTES = 2 * 1024 * 1024
_USER_AGENT = "LumenMusic/0.2.2 (https://github.com/Redemption505/Lumen-Music)"
_LRCLIB_MIN_INTERVAL = 0.5
_LRCLIB_RETRY_SPACING = 0.5
_LRCLIB_DEFAULT_COOLDOWN = 30.0
_lrclib_lock = threading.Lock()
_lrclib_next_allowed = 0.0
_lrclib_cooldown_until = 0.0
_TIMED_LINE = re.compile(r"\[\d{1,3}:([0-5]\d)(?:[.:]\d{1,3})?\].*\S")
_RISKY_TITLE = re.compile(
    r"(?:翻唱|cover|live|现场|混剪|混音|串烧|合辑|合集|playlist|mash[ -]?up|remix|"
    r"钢琴|琴谱|鼓谱|伴奏|教学|教程|演奏|翻弹|演唱会|音乐会)",
    re.IGNORECASE,
)
_SEPARATOR = re.compile(r"\s*(?:-|–|—|－|｜|\|)\s*")
_QUOTED_IDENTITY = re.compile(r"《([^》]{1,100})》\s*[-–—－|｜]\s*([^-–—－|｜]{1,100})")
_TITLE_DECORATION = re.compile(
    r"(?:【\s*(?:官方\s*)?(?:MV|音乐录影带|高清|中字|4K|1080P)\s*】|"
    r"\[\s*(?:official\s*)?(?:music video|mv|audio|hd|4k|1080p)\s*\])",
    re.IGNORECASE,
)


@dataclass(frozen=True, slots=True)
class LyricCandidate:
    id: int
    title: str
    artist: str
    duration_seconds: int
    synced_lyrics: str
    score: int
    reason: str
    duration_seconds_raw: float | None = None


@dataclass(frozen=True, slots=True)
class LyricLookup:
    candidates: tuple[LyricCandidate, ...]
    auto_id: int | None


def _key(value: str) -> str:
    normalized = simplified(unicodedata.normalize("NFKC", value)).casefold()
    return re.sub(r"[^\w\u3400-\u9fff]+", "", normalized)


def _lyric_key(value: str) -> str:
    normalized = simplified(value.replace("\r\n", "\n").replace("\r", "\n").strip())
    return "\n".join(line.rstrip() for line in normalized.split("\n"))


def _auto_choice(exact: list[LyricCandidate], duration_seconds: int) -> int | None:
    if not exact:
        return None
    if len(exact) == 1:
        return exact[0].id
    # When LRCLIB returns several records for the same recording, an exact
    # duration match is stronger evidence than a loose majority across nearby
    # versions. Only use it when all records at that exact duration agree on
    # the timed lyric content; same-duration version conflicts stay manual.
    exact_duration = [
        candidate for candidate in exact
        if candidate.duration_seconds == duration_seconds
    ]
    if exact_duration:
        exact_content = {_lyric_key(candidate.synced_lyrics) for candidate in exact_duration}
        if len(exact_content) == 1:
            same_recording = [candidate for candidate in exact
                              if _lyric_key(candidate.synced_lyrics) in exact_content]
            return min(same_recording, key=lambda candidate: (
                _candidate_script(candidate), abs(candidate.duration_seconds - duration_seconds), candidate.id,
            )).id
        # A strong majority across the full result set can still resolve a
        # stray same-duration record, while a balanced split remains manual.
    by_content: dict[str, list[LyricCandidate]] = {}
    for candidate in exact:
        by_content.setdefault(_lyric_key(candidate.synced_lyrics), []).append(candidate)
    consensus = max(by_content.values(), key=len)
    if len(consensus) < 2 or len(consensus) / len(exact) < 0.75:
        return None
    closest = min(consensus, key=lambda candidate: (
        _candidate_script(candidate), abs(candidate.duration_seconds - duration_seconds), candidate.id,
    ))
    return closest.id


def _candidate_script(candidate: LyricCandidate) -> int:
    # Source metadata may be traditional even when the actual LRC body is
    # simplified. Ignore untimed headers and inline LRC metadata tags.
    body = "\n".join(
        re.sub(r"\[[a-z]+:[^\]]*\]", "", line, flags=re.IGNORECASE)
        for line in candidate.synced_lyrics.splitlines() if _TIMED_LINE.search(line)
    )
    return script_preference(body)


def _candidate_order(candidate: LyricCandidate) -> tuple[int, int, int]:
    return (-candidate.score, _candidate_script(candidate), candidate.id)


def _identity_in_title(video_title: str, song: str, artist: str) -> bool:
    """Require complete bounded fields, avoiding e.g. 晴天 inside 大晴天."""
    value = simplified(unicodedata.normalize("NFKC", video_title)).casefold()

    def present(field: str) -> bool:
        field = simplified(unicodedata.normalize("NFKC", field)).casefold().strip()
        return bool(field) and bool(re.search(r"(?<!\w)" + re.escape(field) + r"(?!\w)", value))
    return present(song) and present(artist)


def _grounded_lookup(result: LyricLookup, video_title: str, duration_seconds: int,
                     quoted_song: str = "") -> LyricLookup:
    grounded = []
    for candidate in result.candidates:
        explicit = (_identity_in_title(video_title, candidate.title, candidate.artist)
                    or (bool(quoted_song) and _key(candidate.title) == _key(quoted_song)
                        and len(_key(candidate.artist)) >= 2 and _key(candidate.artist) in _key(video_title)))
        if (explicit and duration_seconds > 0
                and abs((candidate.duration_seconds_raw if candidate.duration_seconds_raw is not None
                         else candidate.duration_seconds) - duration_seconds) <= 5):
            reason = "标题中的曲名、歌手及长度匹配"
            if "（网易云）" in candidate.reason:
                reason += "（网易云）"
            candidate = LyricCandidate(candidate.id, candidate.title, candidate.artist,
                                       candidate.duration_seconds, candidate.synced_lyrics, 100, reason,
                                       candidate.duration_seconds_raw)
        grounded.append(candidate)
    grounded.sort(key=_candidate_order)
    return LyricLookup(tuple(grounded), _auto_choice(
        [candidate for candidate in grounded if candidate.score == 100], duration_seconds))


def _strip_title_decorations(value: str) -> str:
    value = _TITLE_DECORATION.sub("", value)
    return value.strip(" \t\r\n-–—－|｜:：·•「」『』《》()（）[]【】")


def _structured_identities(title: str) -> tuple[tuple[str, str], ...]:
    """Extract an explicit song/artist pair, never infer it from an uploader."""
    if _RISKY_TITLE.search(simplified(title)):
        return ()
    # Bilibili uploads often put sound-quality and lyric snippets around a
    # precise 《song》-artist pair. The quoted title and adjacent artist are
    # stronger evidence than the surrounding editorial text.
    if title.count("《") == 1 and title.count("》") == 1:
        quoted = _QUOTED_IDENTITY.search(title)
        if quoted:
            song = _strip_title_decorations(quoted.group(1))
            adjacent = _SEPARATOR.split(quoted.group(2), maxsplit=1)[0]
            performer = _strip_title_decorations(adjacent)
            if song and performer and len(song) <= 100 and len(performer) <= 100:
                return ((song, performer),)
        # Explicit artist immediately before a quoted song is common, including
        # “【官方】郁可唯《路过人间》MV” and editorial prefixes separated by spaces.
        song = re.search(r"《([^》]{1,100})》", title)
        if song is None:
            return ()
        prefix = re.sub(r"【[^】]*】|\[[^\]]*\]", " ", title[:song.start()]).strip(" -–—－|｜")
        adjacent = re.split(r"\s+|[-–—－|｜]", prefix)[-1]
        if re.fullmatch(r"[\u3400-\u9fff]{2,8}", adjacent):
            return ((song.group(1).strip(), adjacent),)
        suffix = title[song.end():].strip(" -–—－|｜")
        if re.fullmatch(r"[\u3400-\u9fff]{2,8}", suffix):
            return ((song.group(1).strip(), suffix),)
        # Search the complete quoted song even when editorial text prevents
        # extracting an artist. Upstream artist fields must occur explicitly
        # in the video title before this can become an automatic match.
        return ((song.group(1).strip(), ""),)
    cleaned = _strip_title_decorations(title)
    parts = _SEPARATOR.split(cleaned)
    if len(parts) != 2:
        return ()
    first, second = (_strip_title_decorations(part) for part in parts)
    if not first or not second or len(first) > 100 or len(second) > 100:
        return ()
    # Either order is common in video titles. LRCLIB must confirm exact title
    # and artist fields before either interpretation can be auto-applied.
    return ((second, first), (first, second))


def _retry_after_seconds(value: str | None) -> float | None:
    if not value:
        return None
    value = value.strip()
    if re.fullmatch(r"\d+", value):
        # Avoid unbounded integer conversion; infinity is a safe monotonic
        # deadline for a valid, extremely large Retry-After value.
        if len(value) > 300:
            return math.inf
        return float(int(value))
    try:
        when = parsedate_to_datetime(value)
        if when.tzinfo is None:
            when = when.replace(tzinfo=timezone.utc)
        return max(0.0, when.timestamp() - time.time())
    except (TypeError, ValueError, OverflowError):
        return None


def _fetch(url: str, timeout_seconds: float) -> Any:
    body: bytes | None = None
    is_lrclib = url.startswith("https://lrclib.net/")
    is_netease = url.startswith("https://music.163.com/")
    headers = {"User-Agent": _USER_AGENT, "Accept": "application/json"}
    if is_netease:
        headers["Referer"] = "https://music.163.com/"
    request = Request(url, headers=headers)
    if is_lrclib:
        global _lrclib_next_allowed, _lrclib_cooldown_until
        # Do not queue service workers behind an in-flight request.
        if not _lrclib_lock.acquire(blocking=False):
            raise ProviderError("LYRICS_RATE_LIMITED", "歌词服务繁忙，请稍后再试", 503)
    try:
        for attempt in range(2):
            if is_lrclib:
                now = time.monotonic()
                if now < _lrclib_cooldown_until:
                    raise ProviderError("LYRICS_RATE_LIMITED", "歌词服务繁忙，请稍后再试", 503)
                spacing = _lrclib_next_allowed - now
                if spacing > 0:
                    time.sleep(min(spacing, _LRCLIB_RETRY_SPACING))
                _lrclib_next_allowed = time.monotonic() + _LRCLIB_MIN_INTERVAL
            try:
                with urlopen(request, timeout=timeout_seconds) as response:
                    body = response.read(_MAX_BYTES + 1)
                break
            except HTTPError as error:
                error.close()
                if error.code in (429, 503):
                    if is_lrclib:
                        retry = _retry_after_seconds((error.headers or {}).get("Retry-After"))
                        delay = _LRCLIB_DEFAULT_COOLDOWN if retry is None else retry
                        _lrclib_cooldown_until = time.monotonic() + delay
                        _lrclib_next_allowed = max(_lrclib_next_allowed, _lrclib_cooldown_until)
                    if error.code == 429:
                        provider = "歌词服务" if is_lrclib else "华语歌词服务"
                        raise ProviderError("LYRICS_RATE_LIMITED", f"{provider}繁忙，请稍后再试", 503) from error
                    if is_lrclib:
                        raise ProviderError("LYRICS_RATE_LIMITED", "歌词服务繁忙，请稍后再试", 503) from error
                if error.code in (502, 503, 504) and attempt == 0:
                    time.sleep(0.25)
                    continue
                provider = "歌词服务" if is_lrclib else "华语歌词服务"
                raise ProviderError("LYRICS_UPSTREAM_ERROR", f"{provider}返回 HTTP {error.code}") from error
            except (TimeoutError, URLError) as error:
                provider = "歌词服务" if is_lrclib else "华语歌词服务"
                raise ProviderError("LYRICS_UNAVAILABLE", f"{provider}暂不可用", 502) from error
    finally:
        if is_lrclib:
            # Space the next request after completion as well as after its start;
            # a slow response must not let queued user actions fire back-to-back.
            _lrclib_next_allowed = max(_lrclib_next_allowed,
                                       time.monotonic() + _LRCLIB_MIN_INTERVAL)
            _lrclib_lock.release()
    assert body is not None
    if len(body) > _MAX_BYTES:
        raise ProviderError("LYRICS_INVALID_RESPONSE", "歌词响应过大")
    try:
        return json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as error:
        raise ProviderError("LYRICS_INVALID_RESPONSE", "歌词服务返回无效数据") from error


def _lookup_identity(
    title: str,
    artist: str = "",
    duration_seconds: int = 0,
    *,
    fetch_json: Callable[[str], Any] | None = None,
    fetch_supplemental_json: Callable[[str], Any] | None = None,
    timeout_seconds: float = 8.0,
    allow_auto: bool = True,
) -> LyricLookup:
    title = title.strip()
    artist = artist.strip()
    if not title or len(title) > 180 or len(artist) > 180 or duration_seconds < 0:
        raise ProviderError("LYRICS_INVALID_QUERY", "歌词查询条件无效", 400)
    # LRCLIB caps search results at 20. Including the artist in the primary
    # query prevents popular/shared titles from crowding the requested artist
    # out of that window. Keep a title-only fallback for incomplete metadata
    # and for artist aliases that LRCLIB indexes differently.
    queries: list[dict[str, str]] = []
    for script_title, script_artist in ((title, artist), (traditional(title), traditional(artist)),
                                        (simplified(title), simplified(artist))):
        if artist:
            query = {"track_name": script_title, "artist_name": script_artist}
            if query not in queries:
                queries.append(query)
        query = {"track_name": script_title}
        if query not in queries:
            queries.append(query)
    payload: list[Any] = []
    seen_ids: set[int] = set()
    errors: list[ProviderError] = []
    for query in queries:
        url = f"{_ENDPOINT}?{urlencode(query)}"
        try:
            rows = fetch_json(url) if fetch_json is not None else _fetch(url, timeout_seconds)
        except ProviderError as error:
            errors.append(error)
            # A transient LRCLIB failure must not block the supplemental source.
            break
        except (TimeoutError, OSError) as error:
            errors.append(ProviderError("LYRICS_UNAVAILABLE", "歌词服务暂不可用", 502))
            break
        if not isinstance(rows, list):
            raise ProviderError("LYRICS_INVALID_RESPONSE", "歌词服务返回无效候选")
        for item in rows[:20]:
            if isinstance(item, dict) and isinstance(item.get("id"), int) and item["id"] not in seen_ids:
                seen_ids.add(item["id"])
                payload.append(item)
        if any(isinstance(item, dict) and _key(str(item.get("trackName", ""))) == _key(title)
               and (not artist or _key(str(item.get("artistName", ""))) == _key(artist))
               and isinstance(item.get("syncedLyrics"), str) and _TIMED_LINE.search(item["syncedLyrics"])
               for item in rows):
            break
    # The public Chinese catalogue fills LRCLIB gaps and supplies simplified
    # transcriptions. Tests injecting just LRCLIB remain fully offline; callers
    # can inject the supplemental transport independently.
    if fetch_supplemental_json is not None or (fetch_json is None and re.search(r"[\u3400-\u9fff]", title)):
        supplement = fetch_supplemental_json or (lambda url: _fetch(url, timeout_seconds))
        try:
            payload.extend(_netease_rows(title, artist, duration_seconds, supplement, seen_ids))
        except ProviderError as error:
            errors.append(error)
        except (TimeoutError, OSError):
            errors.append(ProviderError("LYRICS_UNAVAILABLE", "华语歌词服务暂不可用", 502))
    candidates: list[LyricCandidate] = []
    for item in payload:
        if not isinstance(item, dict):
            continue
        identity = item.get("id")
        candidate_title = item.get("trackName") or item.get("name")
        candidate_artist = item.get("artistName")
        candidate_duration = item.get("duration")
        synced = item.get("syncedLyrics")
        if (isinstance(identity, bool) or not isinstance(identity, int) or identity <= 0
                or not isinstance(candidate_title, str) or not candidate_title.strip()
                or not isinstance(candidate_artist, str)
                or not isinstance(candidate_duration, (int, float))
                or isinstance(candidate_duration, bool)
                or not math.isfinite(candidate_duration) or candidate_duration < 0
                or not isinstance(synced, str) or not _TIMED_LINE.search(synced)
                or len(synced) > 250_000):
            continue
        exact_title = _key(candidate_title) == _key(title)
        exact_artist = bool(artist) and _key(candidate_artist) == _key(artist)
        close_duration = duration_seconds > 0 and abs(candidate_duration - duration_seconds) <= 5
        score = 50 * exact_title + 30 * exact_artist + 20 * close_duration
        if exact_title and exact_artist and close_duration:
            reason = "曲名、歌手及长度匹配"
        elif not exact_title:
            reason = "曲名不同，仅供手动确认"
        elif not exact_artist:
            reason = "歌手不同，仅供手动确认"
        else:
            reason = "时长不同，仅供手动确认"
        if item.get("_source") == "netease":
            reason += "（网易云）"
        candidates.append(LyricCandidate(
            identity, candidate_title.strip(), candidate_artist.strip(),
            round(candidate_duration), synced, score, reason, candidate_duration,
        ))
    if not candidates and errors:
        raise errors[0]
    candidates.sort(key=_candidate_order)
    exact = [candidate for candidate in candidates if candidate.score == 100]
    auto_id = _auto_choice(exact, duration_seconds) if allow_auto else None
    return LyricLookup(tuple(candidates), auto_id)


def _netease_rows(title: str, artist: str, duration_seconds: int,
                  fetch: Callable[[str], Any], used_ids: set[int]) -> list[dict[str, Any]]:
    query = {"s": " ".join(filter(None, (simplified(title), simplified(artist)))),
             "type": 1, "limit": 12}
    data = fetch(f"{_NETEASE_SEARCH}?{urlencode(query)}")
    if not isinstance(data, dict) or data.get("code") != 200 or not isinstance(data.get("result", {}), dict):
        raise ProviderError("LYRICS_UPSTREAM_ERROR", "华语歌词服务返回无效候选")
    songs = data.get("result", {}).get("songs", [])
    if not isinstance(songs, list):
        raise ProviderError("LYRICS_INVALID_RESPONSE", "华语歌词服务返回无效候选")
    eligible: list[dict[str, Any]] = []
    for song in songs:
        if not isinstance(song, dict) or not isinstance(song.get("name"), str):
            continue
        identifier, milliseconds = song.get("id"), song.get("duration")
        artists = song.get("artists")
        if (isinstance(identifier, bool) or not isinstance(identifier, int) or identifier <= 0
                or isinstance(milliseconds, bool) or not isinstance(milliseconds, (int, float))
                or not math.isfinite(milliseconds) or milliseconds <= 0 or not isinstance(artists, list)):
            continue
        names = [person.get("name") for person in artists if isinstance(person, dict)
                 and isinstance(person.get("name"), str) and person["name"].strip()]
        if not names:
            continue
        if _key(song["name"]) != _key(title) and not _identity_in_title(title, song["name"], " / ".join(names)):
            continue
        candidate = dict(song, _artist=" / ".join(names))
        eligible.append(candidate)
    exact_artist = [song for song in eligible if artist and _key(song["_artist"]) == _key(artist)]
    if exact_artist:
        eligible = exact_artist
    eligible.sort(key=lambda song: (bool(artist) and _key(song["_artist"]) != _key(artist),
                                   abs(song["duration"] / 1000 - duration_seconds)))
    result = []
    lyric_errors: list[Exception] = []
    for song in eligible[:4]:
        try:
            data = fetch(f"{_NETEASE_LYRIC}?{urlencode({'id': song['id'], 'lv': -1, 'kv': -1, 'tv': -1})}")
            if not isinstance(data, dict) or data.get("code") != 200:
                raise ProviderError("LYRICS_UPSTREAM_ERROR", "华语歌词服务暂不可用")
        except (ProviderError, TimeoutError, OSError) as error:
            lyric_errors.append(error)
            continue
        lrc = data.get("lrc")
        synced = lrc.get("lyric") if isinstance(lrc, dict) else None
        if not isinstance(synced, str) or not _TIMED_LINE.search(synced):
            continue
        # Keep the existing positive Java int API. Song IDs now exceed int32;
        # map them into a bounded range and resolve collisions in this response.
        identifier = 1_000_000_000 + song["id"] % 1_000_000_000
        while identifier in used_ids:
            identifier = 1_000_000_000 + (identifier - 999_999_999) % 1_000_000_000
        used_ids.add(identifier)
        result.append({"id": identifier, "trackName": song["name"], "artistName": song["_artist"],
                       "duration": song["duration"] / 1000, "syncedLyrics": synced, "_source": "netease"})
    if not result and lyric_errors:
        raise lyric_errors[0]
    return result


def lookup_lyrics(
    title: str,
    artist: str = "",
    duration_seconds: int = 0,
    *,
    fetch_json: Callable[[str], Any] | None = None,
    fetch_supplemental_json: Callable[[str], Any] | None = None,
    timeout_seconds: float = 8.0,
) -> LyricLookup:
    """Find synchronized lyrics while treating video uploader metadata cautiously.

    Confirm complete song and performer fields against source metadata, and
    allow at most five seconds of duration difference. Risk-marked versions
    remain manually selectable.
    """
    title = title.strip()
    artist = artist.strip()
    if not title or len(title) > 180 or len(artist) > 180 or duration_seconds < 0:
        raise ProviderError("LYRICS_INVALID_QUERY", "歌词查询条件无效", 400)

    risky = bool(_RISKY_TITLE.search(simplified(title)))
    ambiguous = (title.count("《") > 1 or title.count("》") > 1
                 or ("《" not in title and len(_SEPARATOR.split(_strip_title_decorations(title))) > 2))
    identities = _structured_identities(title)
    if len(identities) == 1 and not identities[0][1] and len(_key(artist)) >= 2 and _key(artist) in _key(title):
        identities = ((identities[0][0], artist),)
    if not identities:
        result = _lookup_identity(
            title, artist, duration_seconds, fetch_json=fetch_json,
            fetch_supplemental_json=fetch_supplemental_json,
            timeout_seconds=timeout_seconds, allow_auto=not (risky or ambiguous),
        )
        return result if risky or ambiguous else _grounded_lookup(result, title, duration_seconds)

    results: list[LyricLookup] = []
    for track_title, track_artist in identities:
        result = _lookup_identity(track_title, track_artist, duration_seconds,
                                  fetch_json=fetch_json, fetch_supplemental_json=fetch_supplemental_json,
                                  timeout_seconds=timeout_seconds)
        if not track_artist:
            result = _grounded_lookup(result, title, duration_seconds, track_title)
        if result.auto_id is not None:
            # Once the explicit identity produces a safe match, the reverse
            # interpretation could introduce a different song with the same words.
            return result
        results.append(result)
    by_id: dict[int, LyricCandidate] = {}
    for result in results:
        for candidate in result.candidates:
            previous = by_id.get(candidate.id)
            if previous is None or candidate.score > previous.score:
                by_id[candidate.id] = candidate
    candidates = sorted(by_id.values(), key=_candidate_order)
    return LyricLookup(tuple(candidates), None)
