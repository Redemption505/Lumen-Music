"""Versioned, atomically persisted personal music library."""

from __future__ import annotations

import copy
import json
import os
import threading
import uuid
from datetime import datetime, timedelta, timezone
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence
from urllib.parse import urlsplit, unquote

from .models import Track, image_cover_uri
from .smart_playlists import candidates, evaluate, normalize_rules, normalize_tags, utc_timestamp
from .home import defaults as home_defaults, normalize as normalize_home
from .playlist_metadata import description as playlist_description, cover_data_uri as playlist_cover


class LibraryError(Exception):
    """An invalid library operation or storage failure."""


class LibraryFormatError(LibraryError):
    """The existing library cannot be read safely and must be preserved."""


class LibraryStorageError(LibraryError):
    """A library write failed without publishing the new state."""


def _file_uri(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parts = urlsplit(value.strip())
        return (parts.scheme == "file" and parts.path.startswith("/")
                and len(parts.path) > 1 and not parts.query and not parts.fragment)
    except ValueError:
        return False


def _stored_cover_uri(value: object, source: str, media_uri: str | None) -> str | None:
    if not isinstance(value, str):
        return None
    uri = value.strip()
    try:
        parts = urlsplit(uri)
        if parts.scheme in ("http", "https"):
            return image_cover_uri(uri)
        if (source == "local" and _file_uri(media_uri) and _file_uri(uri)
                and PurePosixPath(unquote(parts.path)).suffix.lower()
                in {".avif", ".gif", ".jpg", ".jpeg", ".png", ".webp"}):
            return uri
    except ValueError:
        pass
    return None


@dataclass(frozen=True, slots=True)
class StoredTrack:
    id: str
    title: str
    artist: str
    duration_seconds: int
    source: str
    bvid: str | None = None
    page: int | None = None
    media_uri: str | None = None
    cover_uri: str | None = None
    part_count: int | None = None
    view_count: int | None = None
    source_track_id: str | None = None
    segment_start_millis: int | None = None
    segment_end_millis: int | None = None
    source_duration_seconds: int | None = None

    @classmethod
    def from_value(cls, value: Track | StoredTrack | Mapping[str, Any]) -> StoredTrack:
        if isinstance(value, cls):
            value = value.to_dict()
        elif isinstance(value, Track):
            value = {"id": value.id, "title": value.title, "artist": value.artist,
                     "durationSeconds": value.duration_seconds, "source": value.source,
                     "bvid": value.bvid, "page": value.page, "coverUri": value.cover_uri,
                     "partCount": value.part_count, "viewCount": value.view_count,
                     "sourceTrackId": value.source_track_id,
                     "segmentStartMillis": value.segment_start_millis,
                     "segmentEndMillis": value.segment_end_millis,
                     "sourceDurationSeconds": value.source_duration_seconds}
        if not isinstance(value, Mapping):
            raise LibraryError("曲目必须是 Track 或对象")

        def required_text(key: str) -> str:
            item = value.get(key)
            if not isinstance(item, str) or not item.strip():
                raise LibraryError(f"曲目 {key} 必须是非空字符串")
            return item.strip()

        duration = value.get("durationSeconds", value.get("duration_seconds"))
        if type(duration) is not int or duration < 0:
            raise LibraryError("曲目 durationSeconds 必须是非负整数")
        bvid = value.get("bvid")
        if bvid is not None and (not isinstance(bvid, str) or not bvid.strip()):
            raise LibraryError("曲目 bvid 必须是非空字符串或 null")
        page = value.get("page")
        if page is not None and (type(page) is not int or page < 1):
            raise LibraryError("曲目 page 必须是正整数或 null")
        media_uri = value.get("mediaUri", value.get("media_uri"))
        if media_uri is not None and (not isinstance(media_uri, str) or not media_uri.strip()):
            raise LibraryError("曲目 mediaUri 必须是非空字符串或 null")
        cover_uri = value.get("coverUri", value.get("cover_uri"))
        source = required_text("source")
        normalized_cover = _stored_cover_uri(cover_uri, source, media_uri)
        if cover_uri is not None and normalized_cover is None:
            raise LibraryError("曲目 coverUri 必须是 HTTP(S) 图片地址，或本地音频的 file 图片地址，或 null")
        part_count = value.get("partCount", value.get("part_count"))
        if part_count is not None and (type(part_count) is not int or part_count < 1):
            raise LibraryError("曲目 partCount 必须是正整数或 null")
        view_count = value.get("viewCount", value.get("view_count"))
        if view_count is not None and (type(view_count) is not int or view_count < 0):
            raise LibraryError("曲目 viewCount 必须是非负整数或 null")
        track_id = required_text("id")
        source_track_id = value.get("sourceTrackId", value.get("source_track_id"))
        start = value.get("segmentStartMillis", value.get("segment_start_millis"))
        end = value.get("segmentEndMillis", value.get("segment_end_millis"))
        source_duration = value.get("sourceDurationSeconds", value.get("source_duration_seconds"))
        segment_values = (source_track_id, start, end, source_duration)
        if track_id.startswith("segment:") or any(item is not None for item in segment_values):
            try:
                segment_uuid = uuid.UUID(track_id.removeprefix("segment:"))
            except ValueError as exc:
                raise LibraryError("片段 id 必须是 segment:<uuid>") from exc
            if track_id != f"segment:{segment_uuid}":
                raise LibraryError("片段 id 必须是规范小写 UUID")
            if (not isinstance(source_track_id, str) or not source_track_id.strip()
                    or source_track_id.startswith("segment:") or source_track_id == track_id):
                raise LibraryError("片段 sourceTrackId 必须是原始曲目 ID")
            if (type(start) is not int or start < 0 or type(end) is not int
                    or end <= start or type(source_duration) is not int
                    or source_duration < 0
                    or (source_duration > 0 and end > source_duration * 1000)):
                raise LibraryError("片段时间范围无效或超出已知原曲时长")
            if duration != (end - start + 999) // 1000:
                raise LibraryError("片段 durationSeconds 与时间范围不一致")
            source_track_id = source_track_id.strip()
        return cls(required_text("id"), required_text("title"),
                   required_text("artist"), duration, source,
                   bvid.strip() if bvid is not None else None, page,
                   media_uri.strip() if media_uri is not None else None,
                   normalized_cover, part_count, view_count,
                   source_track_id, start, end, source_duration)

    def to_dict(self) -> dict[str, Any]:
        result = {"id": self.id, "title": self.title, "artist": self.artist,
                "durationSeconds": self.duration_seconds, "source": self.source,
                "bvid": self.bvid, "page": self.page, "mediaUri": self.media_uri,
                "coverUri": self.cover_uri, "partCount": self.part_count}
        if self.view_count is not None:
            result["viewCount"] = self.view_count
        if self.source_track_id is not None:
            result.update(sourceTrackId=self.source_track_id,
                          segmentStartMillis=self.segment_start_millis,
                          segmentEndMillis=self.segment_end_millis,
                          sourceDurationSeconds=self.source_duration_seconds)
        return result


class LibraryStore:
    VERSION = 1

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.backup_path = self.path.with_name(self.path.name + ".bak")
        self._lock = threading.RLock()
        self._state: dict[str, Any] = self._empty()
        self._stored_bytes: bytes | None = None
        self._playlist_undo: dict[str, Any] | None = None
        if self.path.exists():
            try:
                contents = self.path.read_bytes()
                self._state = self._decode(contents.decode("utf-8"))
                self._stored_bytes = contents
            except (OSError, UnicodeError, json.JSONDecodeError, LibraryError) as exc:
                raise LibraryFormatError(f"曲库文件无法读取：{self.path}；原文件已保留。{exc}") from exc

    @classmethod
    def _empty(cls) -> dict[str, Any]:
        return {"version": cls.VERSION, "favorites": [], "playlists": [], "songs": [],
                "radioFeedback": [], "favoriteAt": {}, "trackTags": {},
                "smartPlaylists": [], "folders": [], "playlistFolders": {},
                "diaryNotes": [], "lyricFavorites": [], "homePreferences": home_defaults()}

    @classmethod
    def _decode(cls, contents: str) -> dict[str, Any]:
        payload = json.loads(contents)
        if not isinstance(payload, dict):
            raise LibraryFormatError("曲库根节点必须是对象")
        if type(payload.get("version")) is not int or payload["version"] != cls.VERSION:
            raise LibraryFormatError(f"不支持的曲库版本：{payload.get('version')!r}")
        favorites = payload.get("favorites")
        playlists = payload.get("playlists")
        if not isinstance(favorites, list) or not isinstance(playlists, list):
            raise LibraryFormatError("favorites 和 playlists 必须是数组")

        def tracks(items: list[Any]) -> list[dict[str, Any]]:
            result: list[dict[str, Any]] = []
            seen: set[str] = set()
            for item in items:
                try:
                    track = StoredTrack.from_value(item).to_dict()
                except LibraryError as exc:
                    raise LibraryFormatError(str(exc)) from exc
                if track["id"] in seen:
                    raise LibraryFormatError(f"重复曲目 ID：{track['id']}")
                seen.add(track["id"])
                result.append(track)
            return result

        decoded = cls._empty()
        decoded["favorites"] = tracks(favorites)
        seen_playlists: set[str] = set()
        for playlist in playlists:
            if not isinstance(playlist, dict):
                raise LibraryFormatError("歌单必须是对象")
            identifier, name, entries = (playlist.get("id"), playlist.get("name"),
                                         playlist.get("tracks"))
            if not isinstance(identifier, str) or not identifier.strip() or identifier in seen_playlists:
                raise LibraryFormatError("歌单 ID 缺失或重复")
            if not isinstance(name, str) or not name.strip():
                raise LibraryFormatError("歌单名称不能为空")
            if not isinstance(entries, list):
                raise LibraryFormatError("歌单 tracks 必须是数组")
            seen_playlists.add(identifier)
            try:
                description = playlist_description(playlist.get("description", ""))
                cover = playlist_cover(playlist.get("coverDataUri", ""))
            except ValueError as exc:
                raise LibraryFormatError(str(exc)) from exc
            decoded["playlists"].append({"id": identifier, "name": name,
                                          "description": description, "coverDataUri": cover,
                                          "tracks": tracks(entries)})
        songs = payload.get("songs", [])
        if not isinstance(songs, list):
            raise LibraryFormatError("songs 必须是数组")
        song_ids: set[str] = set()
        version_ids: set[str] = set()
        resource_ids: set[str] = set()
        for song in songs:
            if not isinstance(song, dict):
                raise LibraryFormatError("歌曲必须是对象")
            identifier = song.get("id")
            title = song.get("title")
            note = song.get("note")
            preferred = song.get("preferredVersionId")
            versions = song.get("versions")
            if not isinstance(identifier, str) or not identifier.strip() or identifier in song_ids:
                raise LibraryFormatError("歌曲 ID 缺失或重复")
            if not isinstance(title, str) or not title.strip() or not isinstance(note, str):
                raise LibraryFormatError("歌曲标题或备注无效")
            if not isinstance(versions, list) or not versions:
                raise LibraryFormatError("歌曲必须包含至少一个版本")
            song_ids.add(identifier)
            normalized_versions = []
            own_ids: set[str] = set()
            for version in versions:
                if not isinstance(version, dict):
                    raise LibraryFormatError("版本必须是对象")
                version_id = version.get("id")
                label = version.get("label")
                performer = version.get("performer")
                if (not isinstance(version_id, str) or not version_id.strip()
                        or version_id in version_ids):
                    raise LibraryFormatError("版本 ID 缺失或重复")
                if not isinstance(label, str) or not label.strip() or not isinstance(performer, str):
                    raise LibraryFormatError("版本名称或表演者无效")
                try:
                    track = StoredTrack.from_value(version.get("track")).to_dict()
                except LibraryError as exc:
                    raise LibraryFormatError(str(exc)) from exc
                if track["id"] in resource_ids:
                    raise LibraryFormatError(f"版本资源重复：{track['id']}")
                version_ids.add(version_id)
                own_ids.add(version_id)
                resource_ids.add(track["id"])
                normalized_versions.append({"id": version_id, "label": label,
                                            "performer": performer, "track": track})
            if preferred not in own_ids:
                raise LibraryFormatError("首选版本不属于歌曲")
            decoded["songs"].append({"id": identifier, "title": title, "note": note,
                                     "preferredVersionId": preferred,
                                     "versions": normalized_versions})
        favorite_at = payload.get("favoriteAt", {})
        tags = payload.get("trackTags", {})
        smart = payload.get("smartPlaylists", [])
        folders = payload.get("folders", [])
        assignments = payload.get("playlistFolders", {})
        if not isinstance(favorite_at, dict) or not isinstance(tags, dict) \
                or not isinstance(smart, list) or not isinstance(folders, list) \
                or not isinstance(assignments, dict):
            raise LibraryFormatError("曲库组织字段类型无效")
        favorite_ids = {track["id"] for track in decoded["favorites"]}
        try:
            for identifier, timestamp in favorite_at.items():
                if not isinstance(identifier, str) or identifier not in favorite_ids:
                    raise ValueError("收藏日期对应的曲目未收藏")
                decoded["favoriteAt"][identifier] = utc_timestamp(timestamp)
            for identifier, values in tags.items():
                if not isinstance(identifier, str) or not identifier.strip():
                    raise ValueError("标签曲目 ID 无效")
                decoded["trackTags"][identifier] = normalize_tags(values)
            ids = set(seen_playlists)
            for item in smart:
                if not isinstance(item, dict) or set(item) != {"id", "name", "rules"}:
                    raise ValueError("智能歌单格式无效")
                identifier = item["id"]
                if not isinstance(identifier, str) or not identifier.strip() or identifier in ids:
                    raise ValueError("智能歌单 ID 无效或重复")
                name = cls._name(item["name"])
                ids.add(identifier)
                decoded["smartPlaylists"].append({"id": identifier, "name": name,
                                                  "rules": normalize_rules(item["rules"])})
            folder_ids: set[str] = set()
            for item in folders:
                if not isinstance(item, dict) or set(item) != {"id", "name"}:
                    raise ValueError("文件夹格式无效")
                identifier = item["id"]
                if not isinstance(identifier, str) or not identifier.strip() or identifier in folder_ids:
                    raise ValueError("文件夹 ID 无效或重复")
                folder_ids.add(identifier)
                decoded["folders"].append({"id": identifier, "name": cls._name(item["name"])})
            for playlist_id, folder_id in assignments.items():
                if playlist_id not in ids or folder_id not in folder_ids:
                    raise ValueError("歌单文件夹分配指向不存在的对象")
                decoded["playlistFolders"][playlist_id] = folder_id
        except ValueError as exc:
            raise LibraryFormatError(str(exc)) from exc
        if "versionsUndo" in payload:
            undo = payload["versionsUndo"]
            required = {"favorites", "playlists", "songs"}
            if not isinstance(undo, dict) or not required.issubset(undo) \
                    or set(undo) - (required | {"favoriteAt", "trackTags"}):
                raise LibraryFormatError("版本撤销快照无效")
            previous = cls._decode(json.dumps({"version": cls.VERSION, **undo}))
            decoded["versionsUndo"] = {key: previous[key] for key in
                                       ("favorites", "playlists", "songs", "favoriteAt", "trackTags")}
        feedback = payload.get("radioFeedback", [])
        if not isinstance(feedback, list):
            raise LibraryFormatError("radioFeedback 必须是数组")
        feedback_ids: set[str] = set()
        for entry in feedback:
            if not isinstance(entry, dict) or entry.get("kind") not in ("tired", "version", "skip"):
                raise LibraryFormatError("电台反馈种类无效")
            try:
                track = StoredTrack.from_value(entry.get("track")).to_dict()
            except LibraryError as exc:
                raise LibraryFormatError(str(exc)) from exc
            until = entry.get("until", "")
            if not isinstance(until, str) or (entry["kind"] == "tired") != bool(until):
                raise LibraryFormatError("电台反馈期限无效")
            if until:
                try:
                    if datetime.fromisoformat(until.replace("Z", "+00:00")).tzinfo is None:
                        raise ValueError("缺少时区")
                except ValueError as exc:
                    raise LibraryFormatError("电台反馈期限无效") from exc
            if track["id"] in feedback_ids:
                raise LibraryFormatError("电台反馈资源重复")
            feedback_ids.add(track["id"])
            decoded["radioFeedback"].append({"kind": entry["kind"], "until": until,
                                              "track": track})
        notes = payload.get("diaryNotes", [])
        lyrics = payload.get("lyricFavorites", [])
        if not isinstance(notes, list) or not isinstance(lyrics, list):
            raise LibraryFormatError("日记字段必须是数组")
        note_ids: set[str] = set()
        lyric_ids: set[str] = set()
        for note in notes:
            if not isinstance(note, dict):
                raise LibraryFormatError("日记备注格式无效")
            try:
                track = StoredTrack.from_value(note.get("track")).to_dict()
                text = cls._diary_text(note.get("text"), "备注", allow_empty=False)
            except LibraryError as exc:
                raise LibraryFormatError(str(exc)) from exc
            if track["id"] in note_ids:
                raise LibraryFormatError("日记备注曲目重复")
            note_ids.add(track["id"])
            decoded_note = {"track": track, "text": text}
            for field in ("createdAt", "updatedAt"):
                if field in note:
                    try:
                        date = datetime.fromisoformat(note[field].replace("Z", "+00:00"))
                    except (AttributeError, ValueError) as exc:
                        raise LibraryFormatError("备注日期无效") from exc
                    if date.tzinfo is None:
                        raise LibraryFormatError("备注日期必须包含时区")
                    decoded_note[field] = date.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
            if "createdAt" in decoded_note and "updatedAt" in decoded_note \
                    and datetime.fromisoformat(decoded_note["updatedAt"].replace("Z", "+00:00")) \
                    < datetime.fromisoformat(decoded_note["createdAt"].replace("Z", "+00:00")):
                raise LibraryFormatError("备注修改日期不得早于创建日期")
            decoded["diaryNotes"].append(decoded_note)
        for lyric in lyrics:
            if not isinstance(lyric, dict):
                raise LibraryFormatError("歌词收藏格式无效")
            try:
                track = StoredTrack.from_value(lyric.get("track")).to_dict()
                text = cls._diary_text(lyric.get("text"), "歌词", allow_empty=False)
            except LibraryError as exc:
                raise LibraryFormatError(str(exc)) from exc
            identifier = lyric.get("id")
            position = lyric.get("positionMillis")
            if not isinstance(identifier, str) or not identifier.strip() or identifier in lyric_ids \
                    or type(position) is not int or position < 0:
                raise LibraryFormatError("歌词收藏 ID 或时间无效")
            try:
                created = datetime.fromisoformat(lyric.get("createdAt", "").replace("Z", "+00:00"))
            except (AttributeError, ValueError) as exc:
                raise LibraryFormatError("歌词收藏日期无效") from exc
            if created.tzinfo is None:
                raise LibraryFormatError("歌词收藏日期必须包含时区")
            lyric_ids.add(identifier)
            decoded["lyricFavorites"].append({"id": identifier, "track": track,
                                              "positionMillis": position,
                                              "createdAt": created.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                                              "text": text})
        try:
            decoded["homePreferences"] = normalize_home(payload.get("homePreferences", home_defaults()),
                                                       decoded["playlists"], decoded["smartPlaylists"])
        except ValueError as exc:
            raise LibraryFormatError(str(exc)) from exc
        return decoded

    @staticmethod
    def _diary_text(value: Any, field: str, *, allow_empty: bool = True) -> str:
        if not isinstance(value, str) or len(value) > 4000 or (not allow_empty and not value.strip()):
            raise LibraryError(f"{field}内容无效")
        return value

    def save_diary_note(self, track: Track | StoredTrack | Mapping[str, Any], text: str) -> dict[str, Any]:
        item = StoredTrack.from_value(track).to_dict()
        text = self._diary_text(text, "备注")
        with self._lock:
            existing = next((entry for entry in self._state["diaryNotes"]
                             if entry["track"]["id"] == item["id"]), None)
            if existing is not None and existing["text"] == text and existing["track"] == item:
                return self.snapshot()
            if existing is None and not text.strip():
                return self.snapshot()
            changed = copy.deepcopy(self._state)
            changed["diaryNotes"] = [entry for entry in changed["diaryNotes"]
                                     if entry["track"]["id"] != item["id"]]
            if text.strip():
                note = copy.deepcopy(existing) if existing else {}
                note.update(track=item, text=text)
                timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
                if existing is None:
                    note["createdAt"] = timestamp
                note["updatedAt"] = timestamp
                changed["diaryNotes"].append(note)
            return self._save(changed)

    def add_lyric_favorite(self, track: Track | StoredTrack | Mapping[str, Any],
                           position_millis: int, text: str) -> dict[str, Any]:
        item = StoredTrack.from_value(track).to_dict()
        text = self._diary_text(text, "歌词", allow_empty=False)
        if type(position_millis) is not int or position_millis < 0:
            raise LibraryError("歌词时间无效")
        with self._lock:
            if any(entry["track"]["id"] == item["id"]
                   and entry["positionMillis"] == position_millis and entry["text"] == text
                   for entry in self._state["lyricFavorites"]):
                return copy.deepcopy(self._state)
            changed = copy.deepcopy(self._state)
            changed["lyricFavorites"].append({
                "id": uuid.uuid4().hex, "track": item, "positionMillis": position_millis,
                "text": text,
                "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")})
            return self._save(changed)

    def delete_lyric_favorite(self, identifier: str) -> dict[str, Any]:
        if not identifier:
            raise LibraryError("歌词收藏 ID 不能为空")
        with self._lock:
            changed = copy.deepcopy(self._state)
            changed["lyricFavorites"] = [item for item in changed["lyricFavorites"]
                                         if item["id"] != identifier]
            if len(changed["lyricFavorites"]) == len(self._state["lyricFavorites"]):
                raise LibraryError("歌词收藏不存在")
            return self._save(changed)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return copy.deepcopy(self._state)

    def save_home_preferences(self, value: Mapping[str, Any]) -> dict[str, Any]:
        with self._lock:
            try:
                preferences = normalize_home(value, self._state["playlists"], self._state["smartPlaylists"])
            except ValueError as exc:
                raise LibraryError(str(exc)) from exc
            if preferences == self._state["homePreferences"]:
                return self.snapshot()
            changed = copy.deepcopy(self._state)
            changed["homePreferences"] = preferences
            return self._save(changed, preserve_catalog_undo=True)

    @staticmethod
    def _unpin(state: dict[str, Any], kind: str, identifier: str) -> None:
        state["homePreferences"]["pinned"] = [pin for pin in state["homePreferences"]["pinned"]
                                               if (pin["kind"], pin["id"]) != (kind, identifier)]

    @staticmethod
    def _all_tracks(state: dict[str, Any]) -> list[dict[str, Any]]:
        return state["favorites"] + [track for playlist in state["playlists"]
                                     for track in playlist["tracks"]]

    def source_track_id(self, track_id: str) -> str | None:
        with self._lock:
            catalog_tracks = [version["track"] for song in self._state["songs"]
                              for version in song["versions"]]
            for track in self._all_tracks(self._state) + catalog_tracks:
                if track["id"] == track_id:
                    return track.get("sourceTrackId")
        return None

    def edit_segment(self, track_id: str, updates: Mapping[str, Any]) -> dict[str, Any]:
        mutable = {"title", "artist", "segmentStartMillis", "segmentEndMillis"}
        immutable = {"id", "durationSeconds", "source", "bvid", "page",
                     "mediaUri", "coverUri", "partCount", "viewCount",
                     "sourceTrackId", "sourceDurationSeconds"}
        if not updates or set(updates) - mutable - immutable:
            raise LibraryError("片段编辑包含不支持的字段")
        with self._lock:
            changed = copy.deepcopy(self._state)
            catalog_tracks = [version["track"] for song in changed["songs"]
                              for version in song["versions"]]
            matches = [track for track in self._all_tracks(changed) + catalog_tracks
                       if track["id"] == track_id]
            if not matches or matches[0].get("sourceTrackId") is None:
                raise LibraryError("片段不存在或不是片段曲目")
            for key in set(updates) & (immutable - {"durationSeconds"}):
                if updates[key] != matches[0].get(key):
                    raise LibraryError(f"片段 {key} 不可修改")
            changes = {key: value for key, value in updates.items() if key in mutable}
            for track in matches:
                candidate = {**track, **changes}
                if (type(candidate["segmentStartMillis"]) is not int
                        or type(candidate["segmentEndMillis"]) is not int):
                    raise LibraryError("片段起止时间必须是整数毫秒")
                candidate["durationSeconds"] = (
                    candidate["segmentEndMillis"] - candidate["segmentStartMillis"] + 999) // 1000
                if "durationSeconds" in updates and updates["durationSeconds"] != candidate["durationSeconds"]:
                    raise LibraryError("片段 durationSeconds 与时间范围不一致")
                validated = StoredTrack.from_value(candidate).to_dict()
                track.clear()
                track.update(validated)
            return self._save(changed)

    def delete_segment(self, track_id: str) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            catalog_tracks = [version["track"] for song in changed["songs"]
                              for version in song["versions"]]
            matches = [track for track in self._all_tracks(changed) + catalog_tracks
                       if track["id"] == track_id]
            if not matches or matches[0].get("sourceTrackId") is None:
                raise LibraryError("片段不存在或不是片段曲目")
            changed["favorites"] = [track for track in changed["favorites"]
                                    if track["id"] != track_id]
            changed["favoriteAt"].pop(track_id, None)
            changed["trackTags"].pop(track_id, None)
            for playlist in changed["playlists"]:
                playlist["tracks"] = [track for track in playlist["tracks"]
                                      if track["id"] != track_id]
            self._detach_resource(changed, track_id)
            return self._save(changed)

    @staticmethod
    def _encode(state: dict[str, Any]) -> bytes:
        return (json.dumps(state, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

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

    def _save(self, changed: dict[str, Any], *, catalog_mutation: bool = False,
              preserve_catalog_undo: bool = False) -> dict[str, Any]:
        if catalog_mutation:
            changed["versionsUndo"] = {key: copy.deepcopy(self._state[key])
                                       for key in ("favorites", "playlists", "songs",
                                                   "favoriteAt", "trackTags")}
        elif not preserve_catalog_undo:
            changed.pop("versionsUndo", None)
        try:
            current = self.path.read_bytes() if self.path.exists() else None
            if current != self._stored_bytes:
                raise LibraryError("曲库文件在运行期间被外部修改；原文件已保留，请重新打开曲库")
            # Keep the last valid state before replacing the main file. The
            # first write backs up the valid empty v1 state.
            previous = self._encode(self._state)
            self._atomic_write(self.backup_path, previous)
            updated = self._encode(changed)
            self._atomic_write(self.path, updated)
        except OSError as exc:
            raise LibraryStorageError(f"写入曲库失败：{exc}") from exc
        previous_state = self._state
        self._state = changed
        self._stored_bytes = updated
        if self._playlist_undo is not None:
            playlist_id = self._playlist_undo["playlistId"]
            old = next((item for item in previous_state["playlists"]
                        if item["id"] == playlist_id), None)
            new = next((item for item in changed["playlists"]
                        if item["id"] == playlist_id), None)
            if old != new:
                self._playlist_undo["conflicted"] = True
        return copy.deepcopy(changed)

    @staticmethod
    def _name(value: str) -> str:
        if not isinstance(value, str) or not value.strip():
            raise LibraryError("歌单名称不能为空")
        return value.strip()

    @staticmethod
    def _playlist(state: dict[str, Any], identifier: str) -> dict[str, Any]:
        for playlist in state["playlists"]:
            if playlist["id"] == identifier:
                return playlist
        raise LibraryError(f"歌单不存在：{identifier}")

    @staticmethod
    def _move(items: list[Any], from_index: int, to_index: int) -> bool:
        if (type(from_index) is not int or type(to_index) is not int
                or not 0 <= from_index < len(items) or not 0 <= to_index < len(items)):
            raise LibraryError("排序索引超出范围")
        if from_index == to_index:
            return False
        items.insert(to_index, items.pop(from_index))
        return True

    @staticmethod
    def _catalog_text(value: str, field: str, *, required: bool = False) -> str:
        if not isinstance(value, str) or (required and not value.strip()):
            raise LibraryError(f"{field} 无效")
        return value.strip()

    @staticmethod
    def _song(state: dict[str, Any], song_id: str) -> dict[str, Any]:
        for song in state["songs"]:
            if song["id"] == song_id:
                return song
        raise LibraryError(f"歌曲不存在：{song_id}")

    @staticmethod
    def _version(song: dict[str, Any], version_id: str) -> dict[str, Any]:
        for version in song["versions"]:
            if version["id"] == version_id:
                return version
        raise LibraryError(f"版本不存在：{version_id}")

    @staticmethod
    def _resource_used(state: dict[str, Any], resource_id: str) -> bool:
        return any(version["track"]["id"] == resource_id for song in state["songs"]
                   for version in song["versions"])

    @staticmethod
    def _detach_resource(state: dict[str, Any], resource_id: str) -> None:
        for song in state["songs"]:
            song["versions"] = [version for version in song["versions"]
                                if version["track"]["id"] != resource_id]
            if song["versions"] and song["preferredVersionId"] not in {
                    version["id"] for version in song["versions"]}:
                song["preferredVersionId"] = song["versions"][0]["id"]
        state["songs"] = [song for song in state["songs"] if song["versions"]]

    def catalog(self) -> dict[str, Any]:
        return self.snapshot()

    def set_radio_feedback(self, track: Track | StoredTrack | Mapping[str, Any],
                           kind: str, *, now: datetime | None = None) -> dict[str, Any]:
        if kind not in ("tired", "version", "skip"):
            raise LibraryError("反馈 kind 必须是 tired、version 或 skip")
        item = StoredTrack.from_value(track).to_dict()
        when = now or datetime.now(timezone.utc)
        if when.tzinfo is None:
            raise LibraryError("反馈时间必须包含时区")
        until = ((when + timedelta(days=7)).astimezone(timezone.utc)
                 .isoformat().replace("+00:00", "Z") if kind == "tired" else "")
        with self._lock:
            existing = next((entry for entry in self._state["radioFeedback"]
                             if entry["track"]["id"] == item["id"]), None)
            if kind == "skip" and existing is not None and existing["kind"] in ("tired", "version"):
                return copy.deepcopy(self._state)
            changed = copy.deepcopy(self._state)
            changed["radioFeedback"] = [entry for entry in changed["radioFeedback"]
                                        if entry["track"]["id"] != item["id"]]
            changed["radioFeedback"].append({"kind": kind, "until": until, "track": item})
            return self._save(changed)

    def delete_radio_feedback(self, track_id: str) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            changed["radioFeedback"] = [entry for entry in changed["radioFeedback"]
                                        if entry["track"]["id"] != track_id]
            if len(changed["radioFeedback"]) == len(self._state["radioFeedback"]):
                raise LibraryError("反馈资源不存在")
            return self._save(changed)

    def create_song(self, title: str, note: str,
                    versions: Sequence[tuple[str, str, Mapping[str, Any]]]) -> dict[str, Any]:
        title = self._catalog_text(title, "歌曲标题", required=True)
        note = self._catalog_text(note, "歌曲备注")
        if not versions:
            raise LibraryError("歌曲至少需要一个版本")
        entries = []
        seen: set[str] = set()
        for label, performer, raw_track in versions:
            label = self._catalog_text(label, "版本名称", required=True)
            performer = self._catalog_text(performer, "表演者")
            track = StoredTrack.from_value(raw_track).to_dict()
            if track["id"] in seen:
                raise LibraryError("同一资源不能重复加入版本目录")
            seen.add(track["id"])
            entries.append({"id": uuid.uuid4().hex, "label": label,
                            "performer": performer, "track": track})
        with self._lock:
            changed = copy.deepcopy(self._state)
            if any(self._resource_used(changed, resource_id) for resource_id in seen):
                raise LibraryError("资源已属于其他版本")
            changed["songs"].append({"id": uuid.uuid4().hex, "title": title,
                                     "note": note, "preferredVersionId": entries[0]["id"],
                                     "versions": entries})
            return self._save(changed, catalog_mutation=True)

    def edit_song(self, song_id: str, updates: Mapping[str, str]) -> dict[str, Any]:
        if not updates or set(updates) - {"title", "note", "preferredVersionId"}:
            raise LibraryError("歌曲编辑字段无效")
        with self._lock:
            changed = copy.deepcopy(self._state)
            song = self._song(changed, song_id)
            for key, value in updates.items():
                if key == "preferredVersionId":
                    if value not in {version["id"] for version in song["versions"]}:
                        raise LibraryError("首选版本不属于歌曲")
                    song[key] = value
                else:
                    song[key] = self._catalog_text(value, key, required=key == "title")
            return self.snapshot() if changed == self._state else self._save(changed, catalog_mutation=True)

    def add_version(self, song_id: str, label: str, performer: str,
                    track: Mapping[str, Any]) -> dict[str, Any]:
        label = self._catalog_text(label, "版本名称", required=True)
        performer = self._catalog_text(performer, "表演者")
        item = StoredTrack.from_value(track).to_dict()
        with self._lock:
            changed = copy.deepcopy(self._state)
            song = self._song(changed, song_id)
            if self._resource_used(changed, item["id"]):
                raise LibraryError("资源已属于其他版本")
            song["versions"].append({"id": uuid.uuid4().hex, "label": label,
                                     "performer": performer, "track": item})
            return self._save(changed, catalog_mutation=True)

    def edit_version(self, song_id: str, version_id: str,
                     updates: Mapping[str, str]) -> dict[str, Any]:
        if not updates or set(updates) - {"label", "performer"}:
            raise LibraryError("版本编辑字段无效")
        with self._lock:
            changed = copy.deepcopy(self._state)
            version = self._version(self._song(changed, song_id), version_id)
            for key, value in updates.items():
                version[key] = self._catalog_text(value, key, required=key == "label")
            return self.snapshot() if changed == self._state else self._save(changed, catalog_mutation=True)

    def merge_songs(self, target_id: str, source_id: str) -> dict[str, Any]:
        if target_id == source_id:
            raise LibraryError("不能将歌曲合并到自身")
        with self._lock:
            changed = copy.deepcopy(self._state)
            target = self._song(changed, target_id)
            source = self._song(changed, source_id)
            if source["note"] and source["note"] != target["note"]:
                target["note"] = (target["note"] + "\n" if target["note"] else "") + source["note"]
            target["versions"].extend(source["versions"])
            changed["songs"] = [song for song in changed["songs"] if song["id"] != source_id]
            return self._save(changed, catalog_mutation=True)

    def detach_version(self, song_id: str, version_id: str) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            song = self._song(changed, song_id)
            self._version(song, version_id)
            song["versions"] = [version for version in song["versions"]
                                if version["id"] != version_id]
            if not song["versions"]:
                changed["songs"] = [item for item in changed["songs"] if item["id"] != song_id]
            elif song["preferredVersionId"] == version_id:
                song["preferredVersionId"] = song["versions"][0]["id"]
            return self._save(changed, catalog_mutation=True)

    def replace_version(self, song_id: str, version_id: str,
                        track: Mapping[str, Any]) -> dict[str, Any]:
        item = StoredTrack.from_value(track).to_dict()
        with self._lock:
            changed = copy.deepcopy(self._state)
            song = self._song(changed, song_id)
            version = self._version(song, version_id)
            old_id = version["track"]["id"]
            if item["id"] == old_id or self._resource_used(changed, item["id"]):
                raise LibraryError("替换资源必须有未入目录的新 ID")
            version["track"] = item
            if old_id in changed["favoriteAt"]:
                old_date = changed["favoriteAt"].pop(old_id)
                changed["favoriteAt"].setdefault(item["id"], old_date)
            if old_id in changed["trackTags"]:
                old_tags = changed["trackTags"].pop(old_id)
                changed["trackTags"][item["id"]] = list(dict.fromkeys(
                    changed["trackTags"].get(item["id"], []) + old_tags))
            for entries in [changed["favorites"], *[playlist["tracks"] for playlist in changed["playlists"]]]:
                if not any(entry["id"] == old_id for entry in entries):
                    continue
                replaced = []
                for entry in entries:
                    if entry["id"] == old_id:
                        replaced.append(copy.deepcopy(item))
                    elif entry["id"] != item["id"]:
                        replaced.append(entry)
                entries[:] = replaced
            return self._save(changed, catalog_mutation=True)

    def undo_catalog(self) -> dict[str, Any]:
        with self._lock:
            undo = self._state.get("versionsUndo")
            if undo is None:
                raise LibraryError("没有可撤销的版本操作")
            changed = copy.deepcopy(self._state)
            changed.update(copy.deepcopy(undo))
            return self._save(changed)

    def favorite(self, track: Track | StoredTrack | Mapping[str, Any]) -> dict[str, Any]:
        item = StoredTrack.from_value(track).to_dict()
        with self._lock:
            changed = copy.deepcopy(self._state)
            existing = next((entry for entry in changed["favorites"]
                             if entry["id"] == item["id"]), None)
            if existing is not None:
                if existing.get("coverUri") or not item.get("coverUri"):
                    return self.snapshot()
                existing["coverUri"] = item["coverUri"]
                return self._save(changed)
            changed["favorites"].append(item)
            changed["favoriteAt"][item["id"]] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            return self._save(changed)

    def save_segments(self, tracks: Sequence[Track | StoredTrack | Mapping[str, Any]],
                      playlist_name: str | None, batch_id: str) -> tuple[dict[str, Any], int, int, str | None]:
        """Validate a complete single-source draft and publish at most one atomic library write."""
        if isinstance(tracks, (str, bytes)) or not isinstance(tracks, Sequence) or not 1 <= len(tracks) <= 200:
            raise LibraryError("每批片段必须为 1 至 200 条")
        if not isinstance(batch_id, str):
            raise LibraryError("批次 ID 必须是 UUID")
        try:
            batch_uuid = uuid.UUID(batch_id)
        except ValueError as exc:
            raise LibraryError("批次 ID 必须是 UUID") from exc
        if batch_id != str(batch_uuid):
            raise LibraryError("批次 ID 必须是规范小写 UUID")
        name = None if playlist_name is None else self._name(playlist_name)
        entries = [StoredTrack.from_value(track).to_dict() for track in tracks]
        if any(entry.get("sourceTrackId") is None for entry in entries):
            raise LibraryError("批量保存仅接受片段曲目")

        def source_key(entry: dict[str, Any]) -> tuple:
            return tuple(entry.get(key) for key in ("sourceTrackId", "source", "bvid", "page", "mediaUri"))

        def range_key(entry: dict[str, Any]) -> tuple:
            return (entry.get("sourceTrackId"), entry.get("segmentStartMillis"), entry.get("segmentEndMillis"))

        def resource_key(entry: dict[str, Any]) -> tuple:
            return source_key(entry) + range_key(entry)[1:]

        first = entries[0]
        if any(source_key(entry) != source_key(first)
               or entry.get("sourceDurationSeconds") != first.get("sourceDurationSeconds") for entry in entries):
            raise LibraryError("每批片段必须来自同一原始音源")
        ordered = sorted(entries, key=lambda entry: entry["segmentStartMillis"])
        if any(previous["segmentEndMillis"] > current["segmentStartMillis"]
               for previous, current in zip(ordered, ordered[1:])):
            raise LibraryError("批次包含重复范围或重叠片段")
        if len({entry["id"] for entry in entries}) != len(entries):
            raise LibraryError("批次片段 ID 不得重复")

        with self._lock:
            existing_tracks = self._all_tracks(self._state) + [
                version["track"] for song in self._state["songs"] for version in song["versions"]]
            for field in ("radioFeedback", "diaryNotes", "lyricFavorites"):
                existing_tracks.extend(item["track"] for item in self._state[field])
            incoming = {entry["id"]: entry for entry in entries}
            for existing in existing_tracks:
                candidate = incoming.get(existing["id"])
                if candidate is not None and resource_key(existing) != resource_key(candidate):
                    raise LibraryError(f"片段 ID 已指向其他资源：{existing['id']}")
            if name is not None:
                playlist_id = batch_uuid.hex
                expected = {"id": playlist_id, "name": name, "tracks": entries,
                            "description": "", "coverDataUri": ""}
                existing = next((item for item in self._state["playlists"] if item["id"] == playlist_id), None)
                if existing is not None:
                    def content(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
                        return [{key: value for key, value in item.items() if key != "id"} for item in items]
                    if existing["name"] != name or content(existing["tracks"]) != content(entries):
                        raise LibraryError("该批次歌单已修改，不能重复保存")
                    return self.snapshot(), 0, len(entries), playlist_id
                if any(item["id"] == playlist_id for item in self._state["smartPlaylists"]):
                    raise LibraryError("批次歌单 ID 已被智能歌单使用")
                changed = copy.deepcopy(self._state)
                changed["playlists"].append(expected)
                return self._save(changed), len(entries), 0, playlist_id

            by_range = {range_key(entry): entry for entry in self._state["favorites"]
                        if entry.get("sourceTrackId") is not None}
            additions = []
            for entry in entries:
                duplicate = by_range.get(range_key(entry))
                if duplicate is not None:
                    if resource_key(duplicate) != resource_key(entry):
                        raise LibraryError("同范围片段的原始音源身份冲突")
                else:
                    additions.append(entry)
            if not additions:
                return self.snapshot(), 0, len(entries), None
            changed = copy.deepcopy(self._state)
            timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            changed["favorites"].extend(additions)
            for entry in additions:
                changed["favoriteAt"][entry["id"]] = timestamp
            return self._save(changed), len(additions), len(entries) - len(additions), None

    def unfavorite(self, track_id: str) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            changed["favorites"] = [item for item in changed["favorites"] if item["id"] != track_id]
            changed["favoriteAt"].pop(track_id, None)
            return self.snapshot() if len(changed["favorites"]) == len(self._state["favorites"]) else self._save(changed)

    def move_favorite(self, from_index: int, to_index: int) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            return self._save(changed) if self._move(changed["favorites"], from_index, to_index) else self.snapshot()

    def create_playlist(self, name: str) -> dict[str, Any]:
        name = self._name(name)
        with self._lock:
            changed = copy.deepcopy(self._state)
            changed["playlists"].append({"id": uuid.uuid4().hex, "name": name, "tracks": [],
                                         "description": "", "coverDataUri": ""})
            return self._save(changed)

    def import_playlist(self, name: str,
                        tracks: Sequence[Track | StoredTrack | Mapping[str, Any]]) -> dict[str, Any]:
        """Validate every item before writing one complete imported playlist."""
        name = self._name(name)
        entries: list[dict[str, Any]] = []
        seen: dict[str, dict[str, Any]] = {}
        for track in tracks:
            item = StoredTrack.from_value(track).to_dict()
            if item["id"] not in seen:
                seen[item["id"]] = item
                entries.append(item)
            elif not seen[item["id"]].get("coverUri") and item.get("coverUri"):
                seen[item["id"]]["coverUri"] = item["coverUri"]
        with self._lock:
            changed = copy.deepcopy(self._state)
            changed["playlists"].append({"id": uuid.uuid4().hex, "name": name,
                                         "description": "", "coverDataUri": "",
                                         "tracks": entries})
            return self._save(changed)

    def rename_playlist(self, playlist_id: str, name: str) -> dict[str, Any]:
        return self.update_playlist_metadata(playlist_id, name=name)

    def update_playlist_metadata(self, playlist_id: str, *, name: str | None = None,
                                 description: str | None = None,
                                 cover_data_uri: str | None = None) -> dict[str, Any]:
        updates = {}
        if name is not None:
            updates["name"] = self._name(name)
        try:
            if description is not None:
                updates["description"] = playlist_description(description)
            if cover_data_uri is not None:
                updates["coverDataUri"] = playlist_cover(cover_data_uri)
        except ValueError as exc:
            raise LibraryError(str(exc)) from exc
        if not updates:
            raise LibraryError("歌单更新需要 name、description 或 coverDataUri")
        with self._lock:
            changed = copy.deepcopy(self._state)
            playlist = self._playlist(changed, playlist_id)
            if all(playlist.get(key, "") == value for key, value in updates.items()):
                return self.snapshot()
            playlist.update(updates)
            return self._save(changed)

    def move_playlist(self, from_index: int, to_index: int) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            return self._save(changed) if self._move(changed["playlists"], from_index, to_index) else self.snapshot()

    def delete_playlist(self, playlist_id: str) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            self._playlist(changed, playlist_id)
            changed["playlists"] = [item for item in changed["playlists"] if item["id"] != playlist_id]
            changed["playlistFolders"].pop(playlist_id, None)
            self._unpin(changed, "playlist", playlist_id)
            return self._save(changed)

    def add_to_playlist(self, playlist_id: str,
                        track: Track | StoredTrack | Mapping[str, Any]) -> dict[str, Any]:
        item = StoredTrack.from_value(track).to_dict()
        with self._lock:
            changed = copy.deepcopy(self._state)
            playlist = self._playlist(changed, playlist_id)
            existing = next((entry for entry in playlist["tracks"]
                             if entry["id"] == item["id"]), None)
            if existing is not None:
                if existing.get("coverUri") or not item.get("coverUri"):
                    return self.snapshot()
                existing["coverUri"] = item["coverUri"]
                return self._save(changed)
            playlist["tracks"].append(item)
            return self._save(changed)

    def remove_from_playlist(self, playlist_id: str, track_id: str) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            playlist = self._playlist(changed, playlist_id)
            playlist["tracks"] = [item for item in playlist["tracks"] if item["id"] != track_id]
            return (self.snapshot() if len(playlist["tracks"]) == len(self._playlist(self._state, playlist_id)["tracks"])
                    else self._save(changed))

    def remove_tracks_from_playlist(self, playlist_id: str,
                                    track_ids: Sequence[str]) -> tuple[dict[str, Any], str]:
        if isinstance(track_ids, (str, bytes)) or not track_ids:
            raise LibraryError("请选择要移除的曲目")
        if any(not isinstance(track_id, str) or not track_id.strip() for track_id in track_ids):
            raise LibraryError("曲目 ID 无效")
        ids = set(track_ids)
        with self._lock:
            changed = copy.deepcopy(self._state)
            playlist = self._playlist(changed, playlist_id)
            original = copy.deepcopy(playlist["tracks"])
            present = {item["id"] for item in original}
            if not ids <= present:
                raise LibraryError("所选曲目已不在歌单中，请刷新后重试")
            playlist["tracks"] = [item for item in original if item["id"] not in ids]
            token = uuid.uuid4().hex
            snapshot = self._save(changed)
            self._playlist_undo = {"token": token, "playlistId": playlist_id,
                                   "tracks": original, "after": copy.deepcopy(playlist),
                                   "conflicted": False}
            return snapshot, token

    def undo_playlist_removal(self, playlist_id: str, token: str) -> dict[str, Any]:
        with self._lock:
            undo = self._playlist_undo
            if (undo is None or undo["token"] != token
                    or undo["playlistId"] != playlist_id):
                raise LibraryError("没有匹配的歌单撤销操作")
            current = next((item for item in self._state["playlists"]
                            if item["id"] == playlist_id), None)
            if undo["conflicted"] or current != undo["after"]:
                raise LibraryError("歌单已发生后续修改，无法撤销以免覆盖新内容")
            changed = copy.deepcopy(self._state)
            self._playlist(changed, playlist_id)["tracks"] = copy.deepcopy(undo["tracks"])
            snapshot = self._save(changed)
            self._playlist_undo = None
            return snapshot

    def move_in_playlist(self, playlist_id: str, from_index: int, to_index: int) -> dict[str, Any]:
        with self._lock:
            changed = copy.deepcopy(self._state)
            playlist = self._playlist(changed, playlist_id)
            return self._save(changed) if self._move(playlist["tracks"], from_index, to_index) else self.snapshot()

    def organization(self, action: str, options: Mapping[str, Any],
                     history: Mapping[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]], str | None]:
        """Apply one organization request as one validated atomic library write."""
        with self._lock:
            changed = copy.deepcopy(self._state)
            results: list[dict[str, Any]] = []
            created: str | None = None
            identifier = options.get("id", "")
            folder_id = options.get("folderId", "")

            def folder_exists(value: str) -> bool:
                return any(folder["id"] == value for folder in changed["folders"])

            def smart_item(value: str) -> dict[str, Any]:
                item = next((item for item in changed["smartPlaylists"] if item["id"] == value), None)
                if item is None:
                    raise LibraryError(f"智能歌单不存在：{value}")
                return item

            if action in ("load", "preview", "snapshot"):
                if action != "load":
                    raw_rules = options.get("rules")
                    if raw_rules is None:
                        raw_rules = smart_item(identifier)["rules"] if identifier else None
                    if raw_rules is None:
                        raise LibraryError("预览或快照需要规则或智能歌单 ID")
                    try:
                        rules = normalize_rules(raw_rules)
                    except ValueError as exc:
                        raise LibraryError(str(exc)) from exc
                    if rules["cached"] != "any" and "cachedIds" not in options:
                        raise LibraryError("缓存规则需要 cachedIds 上下文")
                    results = evaluate(changed, history, rules, set(options.get("cachedIds", [])))
                    if action == "snapshot":
                        name = self._name(options.get("name", ""))
                        if folder_id and not folder_exists(folder_id):
                            raise LibraryError("目标文件夹不存在")
                        created = uuid.uuid4().hex
                        changed["playlists"].append({"id": created, "name": name,
                                                     "description": "", "coverDataUri": "",
                                                     "tracks": copy.deepcopy(results)})
                        if folder_id:
                            changed["playlistFolders"][created] = folder_id
            elif action == "save":
                name = self._name(options.get("name", ""))
                try:
                    rules = normalize_rules(options.get("rules"))
                except ValueError as exc:
                    raise LibraryError(str(exc)) from exc
                if folder_id and not folder_exists(folder_id):
                    raise LibraryError("目标文件夹不存在")
                if identifier:
                    item = smart_item(identifier)
                    item.update(name=name, rules=rules)
                else:
                    identifier = uuid.uuid4().hex
                    changed["smartPlaylists"].append({"id": identifier, "name": name,
                                                      "rules": rules})
                if "folderId" in options:
                    if folder_id:
                        changed["playlistFolders"][identifier] = folder_id
                    else:
                        changed["playlistFolders"].pop(identifier, None)
            elif action == "delete":
                smart_item(identifier)
                changed["smartPlaylists"] = [item for item in changed["smartPlaylists"]
                                             if item["id"] != identifier]
                changed["playlistFolders"].pop(identifier, None)
                self._unpin(changed, "smart", identifier)
            elif action == "tags":
                mode = options.get("mode")
                if mode not in ("add", "remove", "replace"):
                    raise LibraryError("标签操作 mode 无效")
                try:
                    tags = normalize_tags(options.get("tags", []))
                except ValueError as exc:
                    raise LibraryError(str(exc)) from exc
                ids = options.get("ids", [])
                known = {track["id"] for track in candidates(changed)}
                if not isinstance(ids, list) or not ids or any(
                        not isinstance(value, str) or value not in known for value in ids):
                    raise LibraryError("标签曲目 ID 不存在")
                for track_id in dict.fromkeys(ids):
                    before = changed["trackTags"].get(track_id, [])
                    after = (list(dict.fromkeys(before + tags)) if mode == "add" else
                             [tag for tag in before if tag not in tags] if mode == "remove" else tags)
                    if after:
                        changed["trackTags"][track_id] = after
                    else:
                        changed["trackTags"].pop(track_id, None)
            elif action == "folderSave":
                name = self._name(options.get("name", ""))
                if identifier:
                    folder = next((item for item in changed["folders"] if item["id"] == identifier), None)
                    if folder is None:
                        raise LibraryError("文件夹不存在")
                    folder["name"] = name
                else:
                    changed["folders"].append({"id": uuid.uuid4().hex, "name": name})
            elif action == "folderDelete":
                if not folder_exists(identifier):
                    raise LibraryError("文件夹不存在")
                changed["folders"] = [item for item in changed["folders"] if item["id"] != identifier]
                changed["playlistFolders"] = {key: value for key, value in
                                               changed["playlistFolders"].items() if value != identifier}
            elif action == "folderAssign":
                playlist_id = options.get("playlistId", "")
                known = {item["id"] for item in changed["playlists"] + changed["smartPlaylists"]}
                if playlist_id not in known or (folder_id and not folder_exists(folder_id)):
                    raise LibraryError("歌单或文件夹不存在")
                if folder_id:
                    changed["playlistFolders"][playlist_id] = folder_id
                else:
                    changed["playlistFolders"].pop(playlist_id, None)
            else:
                raise LibraryError("organization action 无效")
            if action not in ("load", "preview"):
                return self._save(changed), results, created
            return copy.deepcopy(changed), results, created
