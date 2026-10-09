"""Deterministic search ranking and recommendations over real provider results."""

from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass
from typing import Callable, Mapping, Sequence

from .models import Track
from .providers import TrackProvider


_SPACE = re.compile(r"\s+")
_PUNCT = re.compile(r"[^\w\u4e00-\u9fff]+", re.UNICODE)
_VERSION = re.compile(
    r"翻唱|cover|现场|live|伴奏|纯音乐|remix|混音|加速|慢速|演唱会|reaction|教学|教程|完整版|合集|串烧",
    re.IGNORECASE,
)
_ORIGINAL = re.compile(r"原唱|原曲|官方|official|studio|录音室", re.IGNORECASE)
_COLLECTION = re.compile(r"合集|串烧|歌单|专辑|collection|playlist|album", re.IGNORECASE)
_SINGLE_VERSION = re.compile(
    r"翻唱|cover|现场|live|伴奏|纯音乐|remix|混音|加速|慢速|演唱会|reaction|教学|教程|完整版",
    re.IGNORECASE,
)
MAX_HINT_CHARS = 160
_MUSIC_TAGS = (
    "钢琴", "吉他", "小提琴", "古筝", "爵士", "摇滚", "民谣", "电子", "古典",
    "纯音乐", "轻音乐", "华语", "粤语", "日语", "韩语", "英语", "动漫", "游戏",
    "安静", "舒缓", "治愈", "悲伤", "欢快", "放松", "学习", "睡眠", "运动",
)
_HINT_ALIASES = {"piano": "钢琴", "jazz": "爵士", "rock": "摇滚",
                 "安静一点": "安静", "睡觉": "睡眠", "助眠": "睡眠"}


def normalize_hint(value: str) -> str:
    if not isinstance(value, str) or re.search(r"[\x00-\x1f\x7f]", value):
        raise ValueError("本次推荐引导不能包含控制字符")
    hint = _SPACE.sub(" ", value.strip())
    if len(hint) > MAX_HINT_CHARS:
        raise ValueError(f"本次推荐引导最多 {MAX_HINT_CHARS} 个字符")
    return hint


def local_hint_intents(hint: str) -> tuple[str, ...]:
    """Turn common music wishes into search terms without persisting the wish."""
    hint = normalize_hint(hint)
    if not hint:
        return ()
    normalized = hint.casefold()
    for original, replacement in _HINT_ALIASES.items():
        normalized = normalized.replace(original, replacement)
    tags = [tag for tag in _MUSIC_TAGS if tag in normalized]
    if "钢琴" in tags and any(tag in tags for tag in ("安静", "舒缓", "睡眠", "学习")):
        tags.append("纯音乐")
    if tags:
        return (" ".join(dict.fromkeys(tags)),)
    query = re.sub(r"^(?:请|帮我|给我|我想|想|推荐|找|听|一些|一首|一点|要|来点|来一些)+", "", hint)
    query = query.strip(" ，。！？,.!?")
    return ((query or hint)[:64],)


def _compact(value: str) -> str:
    return _PUNCT.sub("", value.casefold())


def _terms(value: str) -> tuple[str, ...]:
    return tuple(dict.fromkeys(
        _compact(part) for part in _SPACE.split(value.strip()) if _compact(part)
    ))


def _track_key(track: Track) -> str:
    if track.bvid:
        return f"bilibili:{track.bvid.casefold()}:p{track.page or 1}"
    return track.id.casefold()


def relevance_score(query: str, track: Track, *, kind: str = "all") -> float:
    """Score metadata only; version words in the query disable version demotion."""
    terms = _terms(query)
    if not terms:
        return 0
    title = _compact(track.title)
    artist = _compact(track.artist)
    whole = "".join(terms)
    score = 240 * sum(term in title or term in artist for term in terms) / len(terms)
    if whole == title:
        score += 120
    elif whole and whole in title:
        score += 55
    if whole == artist:
        score += 50
    term_score = 0
    for term in terms:
        if term == title:
            term_score += 100
        elif term in title:
            term_score += 22
        if term == artist:
            term_score += 34
        elif term in artist:
            term_score += 15
    score += term_score / len(terms)
    collection_requested = kind == "collection" or bool(_COLLECTION.search(query))
    if not collection_requested:
        if 90 <= track.duration_seconds <= 480:
            score += 12
        elif track.duration_seconds > 900:
            score -= 20
    if not _VERSION.search(query):
        version = _SINGLE_VERSION if collection_requested else _VERSION
        if version.search(track.title):
            score -= 32
        if _ORIGINAL.search(track.title):
            score += 10
    return score


def rank_tracks(query: str, tracks: Sequence[Track], *, rerank: bool = True,
                kind: str = "all") -> tuple[Track, ...]:
    """Return every candidate in stable descending relevance order."""
    if type(rerank) is not bool:
        raise ValueError("rerank must be a boolean")
    if kind not in ("all", "single", "collection"):
        raise ValueError("kind must be all, single or collection")
    if not rerank:
        return tuple(tracks)
    rows = sorted(enumerate(tracks), key=lambda row: -(
        relevance_score(query, row[1], kind=kind) + 4 / (row[0] + 1)
    ))
    return tuple(track for _, track in rows)


@dataclass(frozen=True, slots=True)
class TasteProfile:
    artists: tuple[str, ...]
    queries: tuple[str, ...]
    artist_weights: Mapping[str, int]
    heard_ids: frozenset[str]
    cold_start: bool
    summary: Mapping[str, tuple[str, ...]]


@dataclass(frozen=True, slots=True)
class RecommendationResult:
    tracks: tuple[Track, ...]
    intents: tuple[str, ...]
    failed_intents: tuple[str, ...]
    cold_start: bool


def build_taste_profile(library_snapshot: Mapping, history_snapshot: Mapping) -> TasteProfile:
    """Extract weighted taste without changing persisted library/history data."""
    artists: Counter[str] = Counter()
    titles: Counter[str] = Counter()
    artist_titles: dict[str, Counter[str]] = {}
    heard: set[str] = set()
    themes: Counter[str] = Counter()
    shared_artists: Counter[str] = Counter()
    shared_titles: Counter[str] = Counter()
    shared_themes: Counter[str] = Counter()

    def add(item: Mapping, weight: int) -> None:
        artist = item.get("artist")
        title = item.get("title")
        if isinstance(artist, str) and artist.strip():
            artists[artist.strip()] += weight
        if isinstance(title, str) and title.strip():
            titles[title.strip()] += weight
            for tag in _MUSIC_TAGS:
                if tag in title:
                    themes[tag] += weight
            if isinstance(artist, str) and artist.strip():
                artist_titles.setdefault(artist.strip(), Counter())[title.strip()] += weight

    for item in library_snapshot.get("favorites", []):
        if isinstance(item, Mapping):
            add(item, 4)
    for playlist in library_snapshot.get("playlists", []):
        if isinstance(playlist, Mapping):
            name = playlist.get("name", "")
            if isinstance(name, str):
                for tag in _MUSIC_TAGS:
                    if tag in name:
                        themes[tag] += 2
            for item in playlist.get("tracks", []):
                if isinstance(item, Mapping):
                    add(item, 2)
    # Listening history affects local ranking only; the AI summary is derived
    # from favorites and playlists before history is folded into the profile.
    shared_artists.update(artists)
    shared_titles.update(titles)
    shared_themes.update(themes)
    for entry in history_snapshot.get("entries", []):
        if not isinstance(entry, Mapping) or not isinstance(entry.get("track"), Mapping):
            continue
        item = entry["track"]
        count = entry.get("playCount", 1)
        weight = min(count, 5) if type(count) is int and count > 0 else 1
        add(item, 3 * weight)
        if isinstance(item.get("id"), str):
            heard.add(item["id"].casefold())

    sorted_artists = tuple(name for name, _ in artists.most_common())
    queries: list[str] = []
    # Artist + title queries bring back playable alternatives, while artist-only
    # queries leave room for discovery beyond the user's existing tracks.
    for name in sorted_artists[:3]:
        matching_titles = artist_titles.get(name)
        matching_title = matching_titles.most_common(1)[0][0] if matching_titles else None
        if matching_title:
            queries.append(f"{name} {matching_title}")
        queries.append(name)
    if not queries:
        queries = [name for name, _ in titles.most_common(3)]
        queries.extend(name for name, _ in themes.most_common(2))
    if not queries:
        queries = ["热门音乐", "华语音乐"]
    summary = {"artists": tuple(name for name, _ in shared_artists.most_common(8)),
               "titles": tuple(name for name, _ in shared_titles.most_common(10)),
               "musicTags": tuple(name for name, _ in shared_themes.most_common(8))}
    return TasteProfile(sorted_artists, tuple(dict.fromkeys(queries)),
                        dict(artists), frozenset(heard), not bool(artists or titles or themes), summary)


def order_candidate_ids(tracks: Sequence[Track], identifiers: Sequence[str]) -> tuple[Track, ...]:
    """Accept only existing identities and append every omitted real candidate."""
    by_id = {track.id: track for track in tracks}
    chosen: list[Track] = []
    seen: set[str] = set()
    for identifier in identifiers:
        if isinstance(identifier, str) and identifier in by_id and identifier not in seen:
            chosen.append(by_id[identifier])
            seen.add(identifier)
    chosen.extend(track for track in tracks if track.id not in seen)
    return tuple(chosen)


def recommend(
    provider: TrackProvider,
    library_snapshot: Mapping,
    history_snapshot: Mapping,
    *,
    intent_generator: Callable[[TasteProfile], str | Sequence[str]] | None = None,
    candidate_ranker: Callable[[TasteProfile, Sequence[Track]], Sequence[str]] | None = None,
    hint: str = "",
    limit: int = 20,
) -> RecommendationResult:
    """Generate intent, search the provider, then rerank real candidates locally.

    A failing optional generator falls back to deterministic local intents. Provider
    errors are preserved when every intent fails; partial success reports failures.
    """
    if type(limit) is not int or limit < 1:
        raise ValueError("limit must be a positive integer")
    profile = build_taste_profile(library_snapshot, history_snapshot)
    hint = normalize_hint(hint)
    intents = local_hint_intents(hint) if hint else profile.queries
    if intent_generator is not None:
        try:
            proposed = intent_generator(profile)
            if isinstance(proposed, str):
                proposed = (proposed,)
            normalized = tuple(dict.fromkeys(
                item.strip() for item in proposed
                if isinstance(item, str) and item.strip()
            ))[:5]
            if normalized:
                intents = normalized
        except Exception:
            # The optional intent generator must never make discovery unavailable.
            pass

    candidates: dict[str, tuple[Track, float, int]] = {}
    failed: list[str] = []
    first_error: Exception | None = None
    for intent_index, intent in enumerate(intents):
        try:
            fetched = provider.search(intent)
        except Exception as exc:
            failed.append(intent)
            if first_error is None:
                first_error = exc
            continue
        for original_index, track in enumerate(fetched):
            key = _track_key(track)
            artist_bonus = min(profile.artist_weights.get(track.artist, 0), 20) * (0.5 if hint else 2)
            novelty = -12 if track.id.casefold() in profile.heard_ids else 0
            score = (relevance_score(intent, track) + artist_bonus + novelty
                     + 4 / (original_index + 1))
            if key not in candidates or score > candidates[key][1]:
                candidates[key] = (track, score, intent_index)
    if not candidates and first_error is not None and len(failed) == len(intents):
        raise first_error

    ordered = sorted(candidates.values(), key=lambda row: (-row[1], row[2]))
    # Greedy artist balancing keeps a strong top result while avoiding long blocks
    # from one artist. The second pass fills any remaining slots without dropping.
    chosen: list[Track] = []
    deferred: list[Track] = []
    per_artist: Counter[str] = Counter()
    for track, _, _ in ordered:
        key = _compact(track.artist)
        if per_artist[key] >= 2:
            deferred.append(track)
        else:
            chosen.append(track)
            per_artist[key] += 1
    chosen.extend(deferred)
    ranked = tuple(chosen[:max(limit * 3, 40)])
    if candidate_ranker is not None and ranked:
        try:
            ranked = order_candidate_ids(ranked, candidate_ranker(profile, ranked))
        except Exception:
            pass
    return RecommendationResult(ranked[:limit], intents, tuple(failed),
                                profile.cold_start)
