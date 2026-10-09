"""Private radio selection over verified provider and library tracks."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Callable, Mapping, Sequence

from .discovery import local_hint_intents, normalize_hint, relevance_score
from .library import StoredTrack
from .models import Track
from .providers import TrackProvider


RADIO_LIMIT = 20
RADIO_MIN_READY = 10


@dataclass(frozen=True)
class RadioResult:
    items: tuple[tuple[StoredTrack, str], ...]
    intents: tuple[str, ...]
    failed_intents: tuple[str, ...]
    status: str
    message: str


def library_tracks(library: Mapping) -> dict[str, StoredTrack]:
    result: dict[str, StoredTrack] = {}
    for raw in library.get("favorites", ()):
        item = StoredTrack.from_value(raw)
        result[item.id] = item
    for playlist in library.get("playlists", ()):
        for raw in playlist.get("tracks", ()):
            item = StoredTrack.from_value(raw)
            result[item.id] = item
    for song in library.get("songs", ()):
        for version in song.get("versions", ()):
            item = StoredTrack.from_value(version["track"])
            result[item.id] = item
    return result


def _groups(library: Mapping) -> dict[str, frozenset[str]]:
    groups: dict[str, frozenset[str]] = {}
    for song in library.get("songs", ()):
        ids = frozenset(version["track"]["id"] for version in song.get("versions", ()))
        for identifier in ids:
            groups[identifier] = ids
    return groups


def _performers(library: Mapping) -> dict[str, str]:
    result = {}
    for song in library.get("songs", ()):
        for version in song.get("versions", ()):
            name = version.get("performer", "").strip()
            if name:
                result[version["track"]["id"]] = name.casefold()
    return result


def _queries(mode: str, hint: str, seeds: Sequence[StoredTrack],
             library: Mapping, history: Mapping, neutral: bool) -> tuple[str, ...]:
    queries = list(local_hint_intents(hint))
    queries.extend(seed.title for seed in seeds[:3])
    if not neutral:
        favorites = library.get("favorites", ())
        recent = sorted(history.get("entries", ()),
                        key=lambda entry: entry.get("lastPlayedAt", ""), reverse=True)
        ordered = ([entry["track"] for entry in recent[:4]] + favorites
                   if mode == "familiar" else favorites +
                   [entry["track"] for entry in recent[:4]])
        queries.extend(item["title"] for item in ordered[:4])
    if not queries:
        queries.append("热门音乐")
    return tuple(dict.fromkeys(query[:64] for query in queries if query.strip()))[:4]


def make_radio(provider: TrackProvider, library: Mapping, history: Mapping, *,
               mode: str, hint: str, seeds: Sequence[StoredTrack],
               exclude: set[str], neutral: bool, now: datetime,
               intents: Sequence[str] | None = None,
               candidate_ranker: Callable[[Sequence[StoredTrack]], Sequence[str]] | None = None) -> RadioResult:
    if mode not in ("familiar", "discover"):
        raise ValueError("mode 必须是 familiar 或 discover")
    hint = normalize_hint(hint)
    queries = tuple(intents) if intents else _queries(mode, hint, seeds, library, history, neutral)
    local = {} if neutral else library_tracks(library)
    candidates: dict[str, StoredTrack] = dict(local)
    provider_ids: set[str] = set()
    failed: list[str] = []
    first_error: Exception | None = None
    for query in queries:
        try:
            popular = getattr(provider, "music_popular", None)
            fetched = (popular() if query == "热门音乐" and not hint and not seeds
                       and popular is not None else provider.search(query))
            for track in fetched:
                if isinstance(track, Track):
                    candidates.setdefault(track.id, StoredTrack.from_value(track))
                    provider_ids.add(track.id)
        except Exception as error:
            failed.append(query)
            first_error = first_error or error
    if not candidates and first_error is not None and len(failed) == len(queries):
        raise first_error

    groups = _groups(library)
    performers = _performers(library)

    def artist_key(track: StoredTrack) -> str:
        performer = performers.get(track.id)
        return "performer:" + performer if performer else "author:" + track.artist.casefold()

    favorites = {item["id"] for item in library.get("favorites", ())}
    played = {} if neutral else {entry["track"]["id"]: entry for entry in history.get("entries", ())}
    recent = sorted(() if neutral else history.get("entries", ()),
                    key=lambda entry: entry.get("lastPlayedAt", ""), reverse=True)
    recent_ids = {entry["track"]["id"] for entry in recent
                  if datetime.fromisoformat(entry["lastPlayedAt"].replace("Z", "+00:00"))
                  >= now - timedelta(days=7)}
    recent_ids = {entry["track"]["id"] for entry in recent[:10]
                  if entry["track"]["id"] in recent_ids}
    seed_ids = {seed.id for seed in seeds}
    blocked = exclude | seed_ids
    feedback = () if neutral else library.get("radioFeedback", ())
    tired_ids = set()
    weights: dict[str, int] = {}
    for entry in feedback:
        identifier = entry["track"]["id"]
        if entry["kind"] == "tired":
            if datetime.fromisoformat(entry["until"].replace("Z", "+00:00")) > now:
                tired_ids.update(groups.get(identifier, frozenset((identifier,))))
        elif entry["kind"] == "version":
            weights[identifier] = -60
        else:
            weights[identifier] = -10
    blocked.update(tired_ids)
    ai_ids: Sequence[str] = ()
    if candidate_ranker is not None:
        shareable = [item for item in candidates.values()
                     if item.id in provider_ids and item.id not in blocked]
        try:
            ai_ids = candidate_ranker(tuple(shareable[:20]))
        except Exception:
            pass
    ranked = []
    ai_order = {identifier: index for index, identifier in enumerate(ai_ids)}
    for index, item in enumerate(candidates.values()):
        if item.id in blocked:
            continue
        relevance = max((relevance_score(query, Track(item.id, item.title, item.artist,
                         item.duration_seconds, item.source), kind="all") for query in queries),
                        default=0)
        familiar = 45 if item.id in favorites else 0
        familiar += 18 if item.id in played else 0
        if neutral:
            familiar = 0
        score = relevance + (familiar if mode == "familiar" else -familiar)
        if item.id in recent_ids:
            score -= 80
        score += weights.get(item.id, 0)
        if item.id in ai_order:
            score += max(0, 20 - ai_order[item.id])
        ranked.append((score, index, item))
    ranked.sort(key=lambda row: (-row[0], row[1]))
    chosen: list[tuple[StoredTrack, str]] = []
    remaining = list(ranked)
    while remaining and len(chosen) < RADIO_LIMIT:
        previous = (artist_key(chosen[-1][0]) if chosen
                    else artist_key(seeds[-1]) if seeds else None)
        pick = next((index for index, (_, _, item) in enumerate(remaining)
                     if not previous or artist_key(item) != previous), 0)
        _, _, item = remaining.pop(pick)
        reasons = []
        if item.id in favorites and not neutral:
            reasons.append("来自收藏")
        if not neutral:
            playlist_names = [playlist["name"] for playlist in library.get("playlists", ())
                              if any(track["id"] == item.id for track in playlist["tracks"])]
            if playlist_names:
                reasons.append("来自歌单：" + playlist_names[0])
        if item.id in recent_ids:
            reasons.append("近期听过，已降权")
        if item.id in weights:
            reasons.append("已参考该资源反馈")
        if not reasons:
            reasons.append("符合本次检索" if item.id not in local else "来自本地曲库")
        chosen.append((item, "；".join(reasons)))
    status = "ready" if len(chosen) >= RADIO_MIN_READY else "insufficient"
    message = "" if status == "ready" else f"可用候选不足：仅找到 {len(chosen)} 首，建议调整种子或提示"
    return RadioResult(tuple(chosen), queries, tuple(failed), status, message)
