"""Qualified listening sessions and a versioned, atomic local history store."""

from __future__ import annotations

import copy
import json
import math
import os
import threading
import time
import uuid
from collections import deque
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

from .library import LibraryError, StoredTrack
from .models import Track


SHORT_TRACK_LIMIT_SECONDS = 60
SEEK_TOLERANCE_SECONDS = 0.25


class HistoryError(Exception):
    """Invalid listening history operation or storage failure."""


class HistoryFormatError(HistoryError):
    """Existing history is invalid; the original file is preserved."""


class HistoryStorageError(HistoryError):
    """The history cannot currently be written; its existing file is preserved."""


def required_listening_seconds(duration_seconds: int) -> float | None:
    """Return short-track media progress or long-track real-time threshold."""
    if type(duration_seconds) is not int or duration_seconds < 0:
        raise HistoryError("曲目时长必须是非负整数")
    if duration_seconds == 0:
        return None
    if duration_seconds < SHORT_TRACK_LIMIT_SECONDS:
        return duration_seconds * 0.9
    return 30.0


def track_playback_duration_seconds(track: StoredTrack) -> float:
    if track.segment_start_millis is None or track.segment_end_millis is None:
        return track.duration_seconds
    return (track.segment_end_millis - track.segment_start_millis) / 1000


def required_track_listening_seconds(track: StoredTrack) -> float | None:
    duration = track_playback_duration_seconds(track)
    if duration == 0:
        return None
    return duration * 0.9 if duration < SHORT_TRACK_LIMIT_SECONDS else 30.0


class ListeningSession:
    """Credit verified playback progress at most once per physical play session.

    The caller supplies a monotonic timestamp, media position and playing state
    whenever the player changes state and at regular intervals while playing.
    Calls after a seek must set ``seeked=True``. Large unannounced position jumps
    are also discarded as seeks. A new session is needed for a replay.
    Short tracks qualify by verified media progress; stored listening time is
    always real playback time, including when speed changes.
    """

    def __init__(self, track_id: str, duration_seconds: int,
                 *, threshold_seconds: float | None = None,
                 media_progress_required: bool | None = None) -> None:
        if not isinstance(track_id, str) or not track_id.strip():
            raise HistoryError("曲目 ID 不能为空")
        self.track_id = track_id.strip()
        self.duration_seconds = duration_seconds
        self.threshold_seconds = (required_listening_seconds(duration_seconds)
                                  if threshold_seconds is None else threshold_seconds)
        self.played_seconds = 0.0
        self.media_played_seconds = 0.0
        self._media_progress_required = (duration_seconds < SHORT_TRACK_LIMIT_SECONDS
                                         if media_progress_required is None else media_progress_required)
        self.qualified = False
        self._last_time: float | None = None
        self._last_position: float | None = None
        self._playing = False
        self._playback_rate = 1.0
        self._closed = False

    def observe(self, position_seconds: float, monotonic_seconds: float,
                playing: bool, *, seeked: bool = False, playback_rate: float = 1.0) -> bool:
        """Observe playback; return True only when the threshold is first met."""
        if self._closed:
            raise HistoryError("播放会话已结束")
        if (not isinstance(playing, bool) or not isinstance(seeked, bool)
                or isinstance(position_seconds, bool) or isinstance(monotonic_seconds, bool)
                or not isinstance(position_seconds, (int, float))
                or not isinstance(monotonic_seconds, (int, float))
                or isinstance(playback_rate, bool)
                or not isinstance(playback_rate, (int, float))
                or not math.isfinite(playback_rate) or playback_rate <= 0
                or not math.isfinite(position_seconds) or position_seconds < 0
                or not math.isfinite(monotonic_seconds) or monotonic_seconds < 0):
            raise HistoryError("播放采样参数无效")
        if self._last_time is not None and monotonic_seconds < self._last_time:
            raise HistoryError("单调时钟不能倒退")

        if (not seeked and self._playing and self._last_time is not None
                and self._last_position is not None):
            wall_delta = monotonic_seconds - self._last_time
            position_delta = position_seconds - self._last_position
            # The previous sample's rate describes this interval. State/rate changes
            # must be observed at the transition; a seek never contributes progress.
            progress_seconds = position_delta / self._playback_rate
            if 0 < progress_seconds <= wall_delta + SEEK_TOLERANCE_SECONDS:
                credited_seconds = min(wall_delta, progress_seconds)
                self.played_seconds += credited_seconds
                self.media_played_seconds += position_delta

        self._last_time = float(monotonic_seconds)
        self._last_position = float(position_seconds)
        self._playing = playing
        self._playback_rate = float(playback_rate)
        qualifying_progress = (self.media_played_seconds if self._media_progress_required
                               else self.played_seconds)
        if (not self.qualified and self.threshold_seconds is not None
                and qualifying_progress + 1e-9 >= self.threshold_seconds):
            self.qualified = True
            return True
        return False

    def finish(self, position_seconds: float, monotonic_seconds: float,
               *, playback_rate: float = 1.0) -> bool:
        """Credit the last playing interval, then prevent any further sampling."""
        newly_qualified = self.observe(position_seconds, monotonic_seconds, False,
                                       playback_rate=playback_rate)
        self._closed = True
        return newly_qualified


class HistoryStore:
    VERSION = 1

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.backup_path = self.path.with_name(self.path.name + ".bak")
        self._lock = threading.RLock()
        self._state: dict[str, Any] = self._empty()
        self._stored_bytes: bytes | None = None
        if self.path.exists():
            try:
                contents = self.path.read_bytes()
                self._state = self._decode(contents.decode("utf-8"))
                self._stored_bytes = contents
            except (OSError, UnicodeError, ValueError, HistoryError) as exc:
                raise HistoryFormatError(
                    f"听歌记录无法读取：{self.path}；原文件已保留。{exc}") from exc

    @classmethod
    def _empty(cls) -> dict[str, Any]:
        return {"version": cls.VERSION, "entries": [], "events": []}

    @classmethod
    def _decode(cls, contents: str) -> dict[str, Any]:
        payload = json.loads(contents)
        if not isinstance(payload, dict) or type(payload.get("version")) is not int \
                or payload["version"] != cls.VERSION:
            raise HistoryFormatError("听歌记录版本无效")
        entries = payload.get("entries")
        if not isinstance(entries, list):
            raise HistoryFormatError("entries 必须是数组")
        result = cls._empty()
        seen: set[str] = set()
        seen_sessions: set[str] = set()
        for item in entries:
            if not isinstance(item, dict):
                raise HistoryFormatError("听歌记录条目必须是对象")
            try:
                track = StoredTrack.from_value(item.get("track"))
            except LibraryError as exc:
                raise HistoryFormatError(str(exc)) from exc
            count = item.get("playCount")
            total = item.get("totalQualifiedSeconds")
            timestamp = item.get("lastPlayedAt")
            if track.id in seen or type(count) is not int or count < 1 \
                    or isinstance(total, bool) or not isinstance(total, (int, float)) \
                    or not math.isfinite(total) or total < 0 \
                    or not isinstance(timestamp, str):
                raise HistoryFormatError("听歌记录条目无效或曲目 ID 重复")
            try:
                parsed = datetime.fromisoformat(timestamp)
            except ValueError as exc:
                raise HistoryFormatError("lastPlayedAt 格式无效") from exc
            if parsed.tzinfo is None:
                raise HistoryFormatError("lastPlayedAt 必须包含时区")
            seen.add(track.id)
            result["entries"].append({"track": track.to_dict(), "playCount": count,
                                      "lastPlayedAt": timestamp,
                                      "totalQualifiedSeconds": float(total),
                                      **({"firstPlayedAt": cls._timestamp(item["firstPlayedAt"])}
                                         if "firstPlayedAt" in item else {})})
        events = payload.get("events", [])
        if not isinstance(events, list):
            raise HistoryFormatError("events 必须是数组")
        for event in events:
            if not isinstance(event, dict):
                raise HistoryFormatError("听歌事件必须是对象")
            try:
                track = StoredTrack.from_value(event.get("track"))
                played_at = cls._timestamp(event.get("playedAt"))
                playlist_id = cls._optional_text(event.get("playlistId"), "playlistId")
                playlist_name = cls._optional_text(event.get("playlistName"), "playlistName")
                song_id = cls._optional_text(event.get("songId"), "songId")
                previous_at = (cls._timestamp(event["previousPlayedAt"])
                               if event.get("previousPlayedAt") is not None else None)
                session_id = cls._optional_text(event.get("sessionId"), "sessionId")
            except LibraryError as exc:
                raise HistoryFormatError(str(exc)) from exc
            if bool(playlist_id) != bool(playlist_name):
                raise HistoryFormatError("听歌事件的歌单 ID 和名称必须同时存在")
            if session_id is not None:
                try:
                    if str(uuid.UUID(session_id)) != session_id or session_id in seen_sessions:
                        raise ValueError("重复或不规范")
                except ValueError as exc:
                    raise HistoryFormatError("听歌会话 ID 无效") from exc
                seen_sessions.add(session_id)
            result["events"].append({"track": track.to_dict(), "playedAt": played_at,
                                     "playlistId": playlist_id, "playlistName": playlist_name,
                                     "songId": song_id, "previousPlayedAt": previous_at,
                                     "sessionId": session_id})
        return result

    @staticmethod
    def _timestamp(value: Any) -> str:
        if not isinstance(value, str):
            raise HistoryFormatError("听歌日期必须是字符串")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise HistoryFormatError("听歌日期格式无效") from exc
        if parsed.tzinfo is None:
            raise HistoryFormatError("听歌日期必须包含时区")
        return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _optional_text(value: Any, field: str) -> str | None:
        if value is None:
            return None
        if not isinstance(value, str) or not value.strip() or len(value) > 256:
            raise HistoryFormatError(f"{field} 无效")
        return value.strip()

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return copy.deepcopy(self._state)

    @staticmethod
    def _encode(state: dict[str, Any]) -> bytes:
        return (json.dumps(state, ensure_ascii=False, indent=2, allow_nan=False)
                + "\n").encode("utf-8")

    @staticmethod
    def _atomic_write(path: Path, contents: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
        created = False
        try:
            descriptor = os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created = True
            with os.fdopen(descriptor, "wb") as output:
                output.write(contents)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temp_path, path)
            created = False
        finally:
            if created:
                temp_path.unlink(missing_ok=True)

    def record(self, track: Track | StoredTrack | Mapping[str, Any],
               played_seconds: float, *, played_at: datetime | None = None,
               playlist_id: str | None = None, playlist_name: str | None = None,
               song_id: str | None = None, session_id: str | None = None,
               verified_media_seconds: float | None = None) -> dict[str, Any]:
        """Persist one already qualified play; never count an incomplete session."""
        try:
            item = StoredTrack.from_value(track)
        except LibraryError as exc:
            raise HistoryError(str(exc)) from exc
        threshold = required_track_listening_seconds(item)
        media_seconds = played_seconds if verified_media_seconds is None else verified_media_seconds
        qualifying_progress = (media_seconds if track_playback_duration_seconds(item) < SHORT_TRACK_LIMIT_SECONDS
                               else played_seconds)
        if (threshold is None or isinstance(played_seconds, bool)
                or not isinstance(played_seconds, (int, float))
                or not math.isfinite(played_seconds) or played_seconds < 0
                or isinstance(media_seconds, bool) or not isinstance(media_seconds, (int, float))
                or not math.isfinite(media_seconds) or media_seconds < 0
                or qualifying_progress + 1e-9 < threshold):
            raise HistoryError("播放时间未达到有效听歌阈值")
        when = played_at or datetime.now(timezone.utc)
        if not isinstance(when, datetime) or when.tzinfo is None:
            raise HistoryError("played_at 必须是含时区时间")
        timestamp = when.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        playlist_id = self._optional_text(playlist_id, "playlistId")
        playlist_name = self._optional_text(playlist_name, "playlistName")
        song_id = self._optional_text(song_id, "songId")
        if bool(playlist_id) != bool(playlist_name):
            raise HistoryError("playlistId 和 playlistName 必须同时存在")
        if session_id is not None:
            try:
                if str(uuid.UUID(session_id)) != session_id:
                    raise ValueError("不规范")
            except ValueError as exc:
                raise HistoryError("sessionId 必须是规范小写 UUID") from exc
        with self._lock:
            if session_id is not None and any(event.get("sessionId") == session_id
                                              for event in self._state["events"]):
                raise HistoryError("播放会话已记录")
            changed = copy.deepcopy(self._state)
            entry = next((entry for entry in changed["entries"]
                          if entry["track"]["id"] == item.id), None)
            previous_at = entry["lastPlayedAt"] if entry is not None else None
            if entry is None:
                entry = {"track": item.to_dict(), "playCount": 0,
                         "firstPlayedAt": timestamp, "lastPlayedAt": timestamp,
                         "totalQualifiedSeconds": 0.0}
                changed["entries"].append(entry)
            entry["track"] = item.to_dict()
            entry["playCount"] += 1
            entry["lastPlayedAt"] = timestamp
            entry["totalQualifiedSeconds"] = round(
                entry["totalQualifiedSeconds"] + float(played_seconds), 3)
            changed["events"].append({"track": item.to_dict(), "playedAt": timestamp,
                                      "playlistId": playlist_id, "playlistName": playlist_name,
                                      "songId": song_id, "previousPlayedAt": previous_at,
                                      "sessionId": session_id})
            try:
                current = self.path.read_bytes() if self.path.exists() else None
                if current != self._stored_bytes:
                    raise HistoryStorageError("听歌记录被外部修改；请重新加载，原文件已保留")
                self._atomic_write(self.backup_path, self._encode(self._state))
                updated = self._encode(changed)
                self._atomic_write(self.path, updated)
            except OSError as exc:
                raise HistoryStorageError(f"写入听歌记录失败：{exc}") from exc
            self._state = changed
            self._stored_bytes = updated
            return copy.deepcopy(changed)


class HistoryRuntime:
    """Thread-safe HTTP session coordinator for monotonic playback observations."""

    def __init__(self, store: HistoryStore, clock: Callable[[], float] = time.monotonic,
                 *, library: Any = None,
                 calendar_clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc)) -> None:
        self.store = store
        self.clock = clock
        self.library = library
        self.calendar_clock = calendar_clock
        self._lock = threading.RLock()
        self._sessions: dict[str, tuple[ListeningSession, StoredTrack, bool, str | None, dict | None, bool]] = {}
        self._finished: set[str] = set()
        self._finished_order: deque[str] = deque()

    def sample(self, session_id: str, track: Mapping[str, Any],
               position_seconds: float, playing: bool, *, seeked: bool = False,
               finished: bool = False, playlist_id: str | None = None,
               playlist_name: str | None = None, playback_rate: float = 1.0,
               elapsed_seconds: float | None = None) -> dict[str, Any]:
        try:
            canonical_id = str(uuid.UUID(session_id))
        except (ValueError, AttributeError, TypeError) as exc:
            raise HistoryError("sessionId 必须是 UUID") from exc
        if canonical_id != session_id:
            raise HistoryError("sessionId 必须是规范小写 UUID")
        try:
            item = StoredTrack.from_value(track)
        except LibraryError as exc:
            raise HistoryError(str(exc)) from exc
        if not isinstance(finished, bool):
            raise HistoryError("finished 必须是布尔值")
        client_timed = elapsed_seconds is not None
        if client_timed and (isinstance(elapsed_seconds, bool)
                             or not isinstance(elapsed_seconds, (int, float))
                             or not math.isfinite(elapsed_seconds) or elapsed_seconds < 0):
            raise HistoryError("elapsedSeconds 必须是有限非负数")
        playlist_id = HistoryStore._optional_text(playlist_id, "playlistId")
        playlist_name = HistoryStore._optional_text(playlist_name, "playlistName")
        if playlist_name and not playlist_id:
            raise HistoryError("playlistName 需要 playlistId")
        with self._lock:
            if session_id in self._finished:
                raise HistoryError("播放会话已结束")
            current = self._sessions.get(session_id)
            if current is None:
                if any(event.get("sessionId") == session_id
                       for event in self.store.snapshot()["events"]):
                    raise HistoryError("播放会话已记录")
                session = ListeningSession(
                    item.id, item.duration_seconds,
                    threshold_seconds=required_track_listening_seconds(item),
                    media_progress_required=track_playback_duration_seconds(item) < SHORT_TRACK_LIMIT_SECONDS)
                recorded = False
                origin = self.library.snapshot() if self.library is not None else None
                self._sessions[session_id] = (session, item, recorded, playlist_id, origin, client_timed)
            else:
                session, original, recorded, original_playlist, origin, original_client_timed = current
                if client_timed != original_client_timed:
                    raise HistoryError("播放会话的采样时钟来源不能变化")
                if (item.id != original.id or item.duration_seconds != original.duration_seconds
                        or item.bvid != original.bvid or item.page != original.page
                        or item.source_track_id != original.source_track_id
                        or item.segment_start_millis != original.segment_start_millis
                        or item.segment_end_millis != original.segment_end_millis):
                    raise HistoryError("播放会话的曲目身份或时长发生变化")
                if playlist_id is not None and original_playlist != playlist_id:
                    raise HistoryError("播放会话的歌单上下文发生变化")
                playlist_id = original_playlist
            # Local playback observations are queued; receipt time cannot describe
            # the interval in which the audio actually advanced.
            now = elapsed_seconds if client_timed else self.clock()
            session.observe(position_seconds, now, playing and not finished, seeked=seeked,
                            playback_rate=playback_rate)
            just_recorded = False
            revisit = False
            if session.qualified and not recorded:
                library = self.library.snapshot() if self.library is not None else None
                recorded_playlist = None
                song_id = None
                played_at = self.calendar_clock()
                if library is not None:
                    song_id = next((song["id"] for song in (origin or library)["songs"]
                                    for version in song["versions"]
                                    if version["track"]["id"] == item.id), None)
                    for playlist in (origin or library)["playlists"]:
                        if playlist["id"] != playlist_id:
                            continue
                        valid_ids = {item.id}
                        if song_id:
                            valid_ids.update(version["track"]["id"]
                                             for song in (origin or library)["songs"]
                                             if song["id"] == song_id for version in song["versions"])
                        if any(track["id"] in valid_ids for track in playlist["tracks"]):
                            recorded_playlist = playlist
                        break
                    favorite_at = library["favoriteAt"].get(item.id)
                    old = next((entry for entry in self.store.snapshot()["entries"]
                                if entry["track"]["id"] == item.id), None)
                    if favorite_at and old:
                        try:
                            favorite_date = datetime.fromisoformat(favorite_at.replace("Z", "+00:00"))
                            last_date = datetime.fromisoformat(old["lastPlayedAt"].replace("Z", "+00:00"))
                            revisit = favorite_date <= last_date and played_at - last_date >= timedelta(days=30)
                        except ValueError:
                            pass
                self.store.record(item, session.played_seconds, played_at=played_at,
                                  playlist_id=recorded_playlist["id"] if recorded_playlist else None,
                                  playlist_name=recorded_playlist["name"] if recorded_playlist else None,
                                  song_id=song_id, session_id=session_id,
                                  verified_media_seconds=session.media_played_seconds)
                recorded = True
                just_recorded = True
                self._sessions[session_id] = (session, item, recorded, playlist_id, origin, client_timed)
            if finished:
                session.finish(position_seconds, now, playback_rate=playback_rate)
                del self._sessions[session_id]
                self._finished.add(session_id)
                self._finished_order.append(session_id)
                while len(self._finished_order) > 4096:
                    self._finished.remove(self._finished_order.popleft())
            return {"sessionId": session_id, "qualified": session.qualified,
                    "recorded": recorded, "playedSeconds": round(session.played_seconds, 3),
                    "mediaPlayedSeconds": round(session.media_played_seconds, 3),
                    "thresholdSeconds": session.threshold_seconds,
                    "finished": finished, "justRecorded": just_recorded,
                    "revisitOldFavorite": revisit}
