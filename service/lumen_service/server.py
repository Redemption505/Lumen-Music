from __future__ import annotations

from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import json
import re
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import replace
from typing import Callable, Sequence
from urllib.parse import parse_qs, quote, unquote, urlsplit
from xml.etree import ElementTree

from .models import Track
from .library import LibraryStore, LibraryError, LibraryFormatError, LibraryStorageError, StoredTrack
from .history import HistoryError, HistoryFormatError, HistoryRuntime, HistoryStorageError, HistoryStore
from .providers import BilibiliProvider, ProviderError, TrackProvider
from .streaming import BilibiliAudioProxy
from .providers import parse_bv
from .discovery import build_taste_profile, local_hint_intents, normalize_hint, rank_tracks, recommend
from .ai_intent import generate_search_intents, rerank_candidate_ids
from .ai_settings import AiSettingsError, AiSettingsStore
from .favorite_import import fetch_public_favorite
from .backup import BackupError, BackupPreview, export_backup, inspect_backup, restore_backup
from .lyrics import lookup_lyrics
from .radio import make_radio
from .smart_playlists import candidates, normalize_rules, suggested_tags
from .diary import diary_view, diary_xml
from .home import from_xml as home_input, to_xml as home_xml
from .playlist_metadata import MAX_METADATA_BODY_BYTES


def _xml_bytes(element: ElementTree.Element) -> bytes:
    return ElementTree.tostring(element, encoding="utf-8", xml_declaration=True)


def _tracks_xml(tracks: Sequence[Track], **metadata: str) -> bytes:
    root = ElementTree.Element("tracks", metadata)
    for track in tracks:
        attributes = {
            "id": track.id,
            "title": track.title,
            "artist": track.artist,
            "durationSeconds": str(track.duration_seconds),
            "source": track.source,
        }
        if track.bvid is not None:
            attributes["bvid"] = track.bvid
        if track.page is not None:
            attributes["page"] = str(track.page)
        if track.cover_uri is not None:
            attributes["coverUri"] = track.cover_uri
        if track.part_count is not None:
            attributes["partCount"] = str(track.part_count)
        if track.view_count is not None:
            attributes["viewCount"] = str(track.view_count)
        for key, value in (("sourceTrackId", track.source_track_id),
                           ("segmentStartMillis", track.segment_start_millis),
                           ("segmentEndMillis", track.segment_end_millis),
                           ("sourceDurationSeconds", track.source_duration_seconds)):
            if value is not None:
                attributes[key] = str(value)
        ElementTree.SubElement(root, "track", attributes)
    return _xml_bytes(root)


def _error_xml(code: str, message: str) -> bytes:
    return _xml_bytes(ElementTree.Element("error", {"code": code, "message": message}))


def _stored_track_element(parent: ElementTree.Element, track: StoredTrack) -> None:
    item = track.to_dict()
    attributes = {"id": item["id"], "title": item["title"],
                  "artist": item["artist"], "durationSeconds": str(item["durationSeconds"]),
                  "source": item["source"]}
    for key in ("bvid", "page", "mediaUri", "coverUri", "partCount", "viewCount",
                "sourceTrackId", "segmentStartMillis", "segmentEndMillis",
                "sourceDurationSeconds"):
        if item.get(key) is not None:
            attributes[key] = str(item[key])
    ElementTree.SubElement(parent, "track", attributes)


def _radio_feedback_xml(snapshot: dict) -> bytes:
    root = ElementTree.Element("feedback")
    for entry in snapshot["radioFeedback"]:
        node = ElementTree.SubElement(root, "entry", {
            "id": entry["track"]["id"], "kind": entry["kind"], "until": entry["until"]})
        _stored_track_element(node, StoredTrack.from_value(entry["track"]))
    return _xml_bytes(root)


def _backup_preview_xml(preview: BackupPreview) -> bytes:
    return _xml_bytes(ElementTree.Element("backup-preview", {
        "favorites": str(preview.favorite_count),
        "playlists": str(preview.playlist_count),
        "history": str(preview.history_count if preview.history_count is not None else 0),
        "backgroundBytes": str(preview.background_bytes or 0),
        "members": " | ".join(preview.members),
    }))


def _library_xml(snapshot: dict, undo_token: str | None = None, *,
                 batch_result: tuple[int, int, str | None] | None = None) -> bytes:
    attributes = {"version": str(snapshot["version"])}
    if undo_token is not None:
        attributes["undoToken"] = undo_token
    if batch_result is not None:
        saved, skipped, playlist_id = batch_result
        attributes.update(savedCount=str(saved), skippedCount=str(skipped), playlistId=playlist_id or "")
    root = ElementTree.Element("library", attributes)
    favorites = ElementTree.SubElement(root, "favorites")
    playlists = ElementTree.SubElement(root, "playlists")

    def append_track(parent: ElementTree.Element, track: dict) -> None:
        attributes = {
            "id": track["id"], "title": track["title"], "artist": track["artist"],
            "durationSeconds": str(track["durationSeconds"]), "source": track["source"],
        }
        for optional in ("bvid", "page", "mediaUri", "coverUri", "partCount", "viewCount",
                         "sourceTrackId", "segmentStartMillis", "segmentEndMillis",
                         "sourceDurationSeconds"):
            if track.get(optional) is not None:
                attributes[optional] = str(track[optional])
        ElementTree.SubElement(parent, "track", attributes)

    for track in snapshot["favorites"]:
        append_track(favorites, track)
    for playlist in snapshot["playlists"]:
        node = ElementTree.SubElement(playlists, "playlist", {
            "id": playlist["id"], "name": playlist["name"]
        })
        for field in ("description", "coverDataUri"):
            if playlist.get(field):
                ElementTree.SubElement(node, field).text = playlist[field]
        for track in playlist["tracks"]:
            append_track(node, track)
    return _xml_bytes(root)


def _catalog_xml(snapshot: dict) -> bytes:
    root = ElementTree.Element("catalog")
    for song in snapshot["songs"]:
        song_node = ElementTree.SubElement(root, "song", {
            "id": song["id"], "title": song["title"], "note": song["note"],
            "preferredVersionId": song["preferredVersionId"]})
        for version in song["versions"]:
            version_node = ElementTree.SubElement(song_node, "version", {
                "id": version["id"], "label": version["label"],
                "performer": version["performer"]})
            track = version["track"]
            attributes = {"id": track["id"], "title": track["title"],
                          "artist": track["artist"],
                          "durationSeconds": str(track["durationSeconds"]),
                          "source": track["source"]}
            for optional in ("bvid", "page", "mediaUri", "coverUri", "partCount",
                             "viewCount", "sourceTrackId", "segmentStartMillis",
                             "segmentEndMillis", "sourceDurationSeconds"):
                if track.get(optional) is not None:
                    attributes[optional] = str(track[optional])
            ElementTree.SubElement(version_node, "track", attributes)
    return _xml_bytes(root)


def _version_input(node: ElementTree.Element) -> tuple[str, str, dict]:
    if node.tag != "version" or set(node.attrib) - {"label", "performer"}:
        raise ValueError("需要 version 元素及 label、performer 属性")
    children = list(node)
    if len(children) != 1 or children[0].tag != "track" or list(children[0]):
        raise ValueError("每个版本必须恰有一个 track 元素")
    return node.get("label", ""), node.get("performer", ""), _track_input(children[0])


def _organization_rules(node: ElementTree.Element) -> dict:
    if node.tag != "rules" or set(node.attrib) - {"favoriteOnly", "favoriteOlderDays",
            "listening", "listeningDays", "cached", "minDuration", "maxDuration"}:
        raise ValueError("rules 属性无效")
    result: dict = {}
    for key, value in node.attrib.items():
        if key == "favoriteOnly":
            if value not in ("true", "false"):
                raise ValueError("favoriteOnly 必须是 true 或 false")
            result[key] = value == "true"
        elif key in ("favoriteOlderDays", "listeningDays", "minDuration", "maxDuration"):
            if not value.isdecimal():
                raise ValueError(f"{key} 必须是非负整数")
            result[key] = int(value)
        else:
            result[key] = value
    result["tagsAll"] = []
    for child in node:
        if child.tag != "tag" or set(child.attrib) != {"value"} or list(child):
            raise ValueError("rules 仅接受 tag value 子元素")
        result["tagsAll"].append(child.get("value"))
    return normalize_rules(result)


def _organization_input(node: ElementTree.Element) -> tuple[str, dict]:
    if node.tag != "organization":
        raise ValueError("需要 organization 根元素")
    action = node.get("action", "")
    attributes = {
        "load": {"action"}, "preview": {"action", "id"},
        "save": {"action", "id", "name", "folderId"},
        "delete": {"action", "id"},
        "snapshot": {"action", "id", "name", "folderId"},
        "tags": {"action", "mode"},
        "folderSave": {"action", "id", "name"},
        "folderDelete": {"action", "id"},
        "folderAssign": {"action", "playlistId", "folderId"},
    }
    if action not in attributes or set(node.attrib) - attributes[action]:
        raise ValueError("organization action 或属性无效")
    options = {key: value for key, value in node.attrib.items() if key != "action"}
    allowed_children = {"load": {"cachedIds"}, "preview": {"rules", "cachedIds"},
                        "snapshot": {"rules", "cachedIds"},
                        "save": {"rules"}, "tags": {"ids", "tags"}}
    children: dict[str, ElementTree.Element] = {}
    for child in node:
        if child.tag not in allowed_children.get(action, set()) or child.tag in children:
            raise ValueError("organization 子元素无效或重复")
        children[child.tag] = child
    if "rules" in children:
        options["rules"] = _organization_rules(children["rules"])
    if action in ("save", "snapshot", "folderSave") and not options.get("name", "").strip():
        raise ValueError("name 不能为空")
    if action == "save" and "rules" not in children:
        raise ValueError("save 需要 rules")
    if action in ("delete", "folderDelete") and not options.get("id"):
        raise ValueError("删除需要 id")
    if action == "folderAssign" and (not options.get("playlistId") or "folderId" not in options):
        raise ValueError("folderAssign 需要 playlistId 和 folderId")
    for key, item_tag in (("cachedIds", "id"), ("ids", "id"), ("tags", "tag")):
        if key not in children:
            continue
        container = children[key]
        if container.attrib:
            raise ValueError(f"{key} 不接受属性")
        values = []
        for child in container:
            if child.tag != item_tag or set(child.attrib) != {"value"} or list(child) or not child.get("value", "").strip():
                raise ValueError(f"{key} 子元素无效")
            values.append(child.get("value"))
        options[key] = values
    if action == "tags" and ("ids" not in children or "tags" not in children):
        raise ValueError("tags 需要 ids 和 tags")
    return action, options


def _organization_xml(snapshot: dict, history: dict, results: list[dict],
                      cached_ids: set[str] | None, created: str | None = None) -> bytes:
    attributes = {"version": str(snapshot["version"])}
    if created:
        attributes["createdPlaylistId"] = created
    root = ElementTree.Element("organization", attributes)
    tracks = ElementTree.SubElement(root, "tracks")
    played = {entry["track"]["id"]: entry for entry in history["entries"]}
    for track in candidates(snapshot):
        identifier = track["id"]
        entry_attributes = {"id": identifier}
        if cached_ids is not None:
            entry_attributes["cached"] = str(identifier in cached_ids).lower()
        if identifier in snapshot["favoriteAt"]:
            entry_attributes["favoriteAt"] = snapshot["favoriteAt"][identifier]
        if identifier in played:
            entry_attributes["lastPlayedAt"] = played[identifier]["lastPlayedAt"]
            entry_attributes["playCount"] = str(played[identifier]["playCount"])
        entry = ElementTree.SubElement(tracks, "entry", entry_attributes)
        _stored_track_element(entry, StoredTrack.from_value(track))
        tags = ElementTree.SubElement(entry, "tags")
        for value in snapshot["trackTags"].get(identifier, []):
            ElementTree.SubElement(tags, "tag", {"value": value})
        hints = ElementTree.SubElement(entry, "suggestions")
        for value in suggested_tags(track, snapshot):
            ElementTree.SubElement(hints, "tag", {"value": value})
    smart = ElementTree.SubElement(root, "smartPlaylists")
    for item in snapshot["smartPlaylists"]:
        attrs = {"id": item["id"], "name": item["name"]}
        if item["id"] in snapshot["playlistFolders"]:
            attrs["folderId"] = snapshot["playlistFolders"][item["id"]]
        playlist = ElementTree.SubElement(smart, "smartPlaylist", attrs)
        rule = item["rules"]
        rule_attrs = {"favoriteOnly": str(rule["favoriteOnly"]).lower(),
                      "listening": rule["listening"], "cached": rule["cached"]}
        for key in ("favoriteOlderDays", "listeningDays", "minDuration", "maxDuration"):
            if rule[key] is not None:
                rule_attrs[key] = str(rule[key])
        rules_node = ElementTree.SubElement(playlist, "rules", rule_attrs)
        for tag in rule["tagsAll"]:
            ElementTree.SubElement(rules_node, "tag", {"value": tag})
    folders = ElementTree.SubElement(root, "folders")
    for folder in snapshot["folders"]:
        ElementTree.SubElement(folders, "folder", folder)
    assignments = ElementTree.SubElement(root, "playlistFolders")
    for playlist_id, folder_id in snapshot["playlistFolders"].items():
        ElementTree.SubElement(assignments, "assignment", {"playlistId": playlist_id,
                                                           "folderId": folder_id})
    matches = ElementTree.SubElement(root, "results")
    for track in results:
        _stored_track_element(matches, StoredTrack.from_value(track))
    return _xml_bytes(root)


def _history_xml(snapshot: dict) -> bytes:
    root = ElementTree.Element("history", {"version": str(snapshot["version"])})
    for entry in snapshot["entries"]:
        node = ElementTree.SubElement(root, "entry", {
            "playCount": str(entry["playCount"]),
            "lastPlayedAt": entry["lastPlayedAt"],
            "totalQualifiedSeconds": str(entry["totalQualifiedSeconds"]),
        })
        track = entry["track"]
        attributes = {"id": track["id"], "title": track["title"],
                      "artist": track["artist"],
                      "durationSeconds": str(track["durationSeconds"]),
                      "source": track["source"]}
        for optional in ("bvid", "page", "mediaUri", "coverUri", "partCount", "viewCount",
                         "sourceTrackId", "segmentStartMillis", "segmentEndMillis",
                         "sourceDurationSeconds"):
            if track.get(optional) is not None:
                attributes[optional] = str(track[optional])
        ElementTree.SubElement(node, "track", attributes)
    return _xml_bytes(root)


TRACK_INT_ATTRIBUTES = ("durationSeconds", "page", "partCount", "viewCount",
                        "segmentStartMillis", "segmentEndMillis", "sourceDurationSeconds")
TRACK_TEXT_ATTRIBUTES = ("bvid", "mediaUri", "coverUri", "sourceTrackId")


def _optional_int(attributes: dict, key: str) -> None:
    """空字符串的数值属性按“未提供”处理，缺失的键保持不变。"""
    if key not in attributes:
        return
    raw = (attributes[key] or "").strip()
    attributes[key] = int(raw) if raw else None


def _normalize_track_attributes(attributes: dict) -> dict:
    """收藏与编辑共用同一套归一：空字符串等价于未提供。"""
    for key in TRACK_INT_ATTRIBUTES:
        _optional_int(attributes, key)
    if attributes.get("page") == 0:
        attributes["page"] = None
    for key in TRACK_TEXT_ATTRIBUTES:
        if key in attributes and not attributes[key]:
            attributes[key] = None
    return attributes


def _track_input(node: ElementTree.Element) -> dict:
    attributes = _normalize_track_attributes(dict(node.attrib))
    if attributes.get("durationSeconds") is None:
        attributes["durationSeconds"] = -1
    return attributes


def default_library_path() -> Path:
    configured = os.environ.get("LUMEN_LIBRARY_PATH")
    if configured:
        return Path(configured).expanduser()
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local")))
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local" / "share")))
    return base / "Lumen Music" / "library.json"


def default_history_path(library_path: Path | None = None) -> Path:
    configured = os.environ.get("LUMEN_HISTORY_PATH")
    return Path(configured).expanduser() if configured else (library_path or default_library_path()).with_name("history.json")


def _handler_for(
    provider: TrackProvider, audio_proxy: BilibiliAudioProxy | None,
    library_store: LibraryStore | None, library_fault: str | None,
    history_runtime: HistoryRuntime | None, history_fault: str | None,
    library_path: Path, history_path: Path,
    history_clock: Callable[[], float] | None,
) -> type[BaseHTTPRequestHandler]:
    data_lock = threading.RLock()
    ai_settings = AiSettingsStore(Path(os.environ.get("LUMEN_AI_SETTINGS_PATH")
                                or library_path.with_name("ai-settings.json")))

    class LumenRequestHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if urlsplit(self.path).path in ("/api/library", "/api/library/versions", "/api/library/organization", "/api/library/home", "/api/history", "/api/diary", "/api/ai-settings", "/api/radio/feedback"):
                with data_lock:
                    self._handle_request(head_only=False)
            else:
                self._handle_request(head_only=False)

        def do_HEAD(self) -> None:
            if urlsplit(self.path).path in ("/api/library", "/api/library/versions", "/api/library/organization", "/api/library/home", "/api/history", "/api/diary", "/api/ai-settings", "/api/radio/feedback"):
                with data_lock:
                    self._handle_request(head_only=True)
            else:
                self._handle_request(head_only=True)

        def do_POST(self) -> None:
            if not self._allow_local_write(xml_body=True):
                return
            path = urlsplit(self.path).path
            if path == "/api/radio":
                self._handle_radio()
                return
            with data_lock:
                if path == "/api/listening":
                    self._handle_listening()
                elif path in ("/api/diary/notes", "/api/diary/lyrics"):
                    self._handle_diary("POST", path)
                elif path == "/api/ai-settings":
                    self._handle_ai_settings("POST")
                elif path.startswith("/api/backup/"):
                    self._handle_backup(path)
                elif path == "/api/radio/feedback":
                    self._handle_radio_feedback("POST")
                else:
                    self._handle_library("POST")

        def do_PATCH(self) -> None:
            if not self._allow_local_write(xml_body=True):
                return
            with data_lock:
                self._handle_library("PATCH")

        def do_DELETE(self) -> None:
            if not self._allow_local_write(xml_body=False):
                return
            with data_lock:
                if urlsplit(self.path).path.startswith("/api/radio/feedback/"):
                    self._handle_radio_feedback("DELETE")
                elif urlsplit(self.path).path.startswith("/api/diary/lyrics/"):
                    self._handle_diary("DELETE", urlsplit(self.path).path)
                else:
                    self._handle_library("DELETE")

        def _handle_request(self, head_only: bool) -> None:
            request = urlsplit(self.path)

            if request.path in ("/api/library", "/api/library/versions", "/api/library/organization", "/api/library/home"):
                self._handle_library("GET", head_only)
                return

            if request.path == "/api/history":
                self._handle_history(head_only)
                return

            if request.path == "/api/diary":
                self._handle_diary("GET", request.path, head_only)
                return

            if request.path == "/api/ai-settings":
                self._handle_ai_settings("GET", head_only)
                return

            if request.path == "/api/radio/feedback":
                self._handle_radio_feedback("GET", head_only)
                return

            if request.path == "/api/health":
                body = _xml_bytes(
                    ElementTree.Element("health", {"status": "ok", "provider": provider.name})
                )
                self._send_xml(HTTPStatus.OK, body, head_only)
                return

            if request.path == "/api/lyrics":
                params = parse_qs(request.query, keep_blank_values=True)
                title = params.get("title", [""])[0]
                artist = params.get("artist", [""])[0]
                try:
                    duration = int(params.get("duration", ["0"])[0])
                    result = lookup_lyrics(title, artist, duration)
                except ValueError:
                    self._send_xml(HTTPStatus.BAD_REQUEST,
                                   _error_xml("LYRICS_INVALID_QUERY", "歌词时长无效"), head_only)
                    return
                except ProviderError as error:
                    self._send_xml(HTTPStatus(error.http_status),
                                   _error_xml(error.code, error.message), head_only)
                    return
                root = ElementTree.Element("lyrics", {
                    "autoId": str(result.auto_id) if result.auto_id is not None else "",
                })
                for candidate in result.candidates:
                    node = ElementTree.SubElement(root, "candidate", {
                        "id": str(candidate.id), "title": candidate.title,
                        "artist": candidate.artist,
                        "duration": str(candidate.duration_seconds),
                        "score": str(candidate.score), "reason": candidate.reason,
                    })
                    ElementTree.SubElement(node, "lrc").text = candidate.synced_lyrics
                self._send_xml(HTTPStatus.OK, _xml_bytes(root), head_only)
                return

            if request.path == "/api/tracks":
                parameters = parse_qs(request.query, keep_blank_values=True)
                query = parameters.get("q", [""])[0]
                kind = parameters.get("kind", ["all"])[0]
                if kind not in ("all", "single", "collection"):
                    self._send_xml(HTTPStatus.BAD_REQUEST,
                                   _error_xml("INVALID_KIND", "kind 必须是 all、single 或 collection"), head_only)
                    return
                rerank_values = parameters.get("rerank", ["true"])
                if len(rerank_values) != 1 or rerank_values[0] not in ("true", "false"):
                    self._send_xml(HTTPStatus.BAD_REQUEST,
                                   _error_xml("INVALID_RERANK", "rerank 必须是 true 或 false"), head_only)
                    return
                rerank = rerank_values[0] == "true"
                try:
                    bvid = parse_bv(query)
                    if bvid is not None and isinstance(provider, BilibiliProvider):
                        tracks = provider.resolve_bv(query)
                    else:
                        tracks = rank_tracks(query, provider.search(query), rerank=rerank, kind=kind)
                    unresolved = 0
                    if kind != "all" and isinstance(provider, BilibiliProvider) and bvid is None:
                        enriched = []
                        first_error = None
                        unresolved = sum(track.part_count is None for track in tracks[12:])
                        for track in tracks[:12]:
                            if track.part_count is not None or not track.bvid:
                                enriched.append(track)
                                continue
                            try:
                                enriched.append(replace(track,
                                                        part_count=provider.page_count(track.bvid)))
                            except ProviderError as error:
                                unresolved += 1
                                if first_error is None:
                                    first_error = error
                        enriched.extend(track for track in tracks[12:]
                                        if track.part_count is not None)
                        if not enriched and first_error is not None:
                            raise first_error
                        tracks = tuple(enriched)
                except ProviderError as error:
                    self._send_xml(
                        HTTPStatus(error.http_status), _error_xml(error.code, error.message), head_only
                    )
                    return
                if kind == "single":
                    tracks = tuple(track for track in tracks if track.part_count == 1)
                elif kind == "collection":
                    tracks = tuple(track for track in tracks if track.part_count is not None and track.part_count > 1)
                self._send_xml(HTTPStatus.OK, _tracks_xml(
                    tracks, unresolvedCount=str(unresolved)), head_only)
                return

            if request.path == "/api/recommendations":
                params = parse_qs(request.query, keep_blank_values=True)
                try:
                    hints = params.get("hint", [""])
                    if len(hints) != 1:
                        raise ValueError("本次推荐引导只能提供一次")
                    hint = normalize_hint(hints[0])
                except ValueError as error:
                    self._send_xml(HTTPStatus.BAD_REQUEST,
                                   _error_xml("INVALID_HINT", str(error)), head_only)
                    return
                # Keep a consistent local snapshot; network recommendation work must
                # never hold the lock needed to load or save a personal library.
                with data_lock:
                    taste_fault = library_fault or history_fault
                    library = library_store.snapshot() if library_store is not None else None
                    history = history_runtime.store.snapshot() if history_runtime is not None else None
                    try:
                        config = ai_settings.config()
                    except AiSettingsError:
                        config = None
                if taste_fault is not None:
                    self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                                   _error_xml("TASTE_DAMAGED", taste_fault), head_only)
                    return
                if library is None or history is None:
                    self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                                   _error_xml("TASTE_UNAVAILABLE", "个人数据尚未初始化"), head_only)
                    return
                intent_source = "fallback"
                ai_available = config is not None and config.is_valid()

                if not ai_available and not hint:
                    try:
                        popular = getattr(provider, "music_popular", None)
                        if popular is None:
                            raise ProviderError("NOT_IMPLEMENTED", "当前数据源不支持音乐热门榜", 501)
                        tracks = tuple(popular())[:20]
                    except ProviderError as error:
                        self._send_xml(HTTPStatus(error.http_status),
                                       _error_xml(error.code, error.message), head_only)
                        return
                    self._send_xml(HTTPStatus.OK, _tracks_xml(
                        tracks, intents="", tasteSource="popular", failedIntents=""), head_only)
                    return

                shared_profile = build_taste_profile(library, {})

                def generate(profile):
                    nonlocal intent_source
                    if not ai_available:
                        return ()
                    result = generate_search_intents(
                        shared_profile.queries, config=config,
                        taste_summary=shared_profile.summary, hint=hint)
                    if result.source == "ai":
                        intent_source = "ai"
                        return result.queries
                    return ()

                def rerank(profile, candidates):
                    # Only public result metadata leaves the machine, never the
                    # listening profile, local paths, or playable media URLs.
                    return rerank_candidate_ids(
                        [{"id": track.id, "title": track.title, "artist": track.artist,
                          "durationSeconds": track.duration_seconds} for track in candidates],
                        config=config, taste_summary=shared_profile.summary, hint=hint)

                try:
                    result = recommend(provider, library, history,
                                       intent_generator=generate if ai_available else None,
                                       candidate_ranker=rerank if ai_available else None,
                                       hint=hint)
                except ProviderError as error:
                    self._send_xml(HTTPStatus(error.http_status),
                                   _error_xml(error.code, error.message), head_only)
                    return
                tracks = result.tracks
                if not tracks and provider.name == "demo":
                    tracks = tuple(provider.search(""))
                self._send_xml(HTTPStatus.OK, _tracks_xml(
                    tracks, intents=" | ".join(result.intents), tasteSource=intent_source,
                    failedIntents=" | ".join(result.failed_intents)), head_only)
                return

            path_parts = request.path.split("/")
            if (len(path_parts) == 5 and path_parts[1:3] == ["api", "collections"]
                    and path_parts[3] and path_parts[4] == "tracks"):
                if not isinstance(provider, BilibiliProvider):
                    self._send_xml(HTTPStatus.NOT_IMPLEMENTED,
                                   _error_xml("NOT_IMPLEMENTED", "当前数据源不支持合集展开"), head_only)
                    return
                try:
                    tracks = provider.resolve_collection(unquote(path_parts[3]))
                except ProviderError as error:
                    self._send_xml(HTTPStatus(error.http_status),
                                   _error_xml(error.code, error.message), head_only)
                    return
                self._send_xml(HTTPStatus.OK, _tracks_xml(tracks), head_only)
                return
            if (
                len(path_parts) == 6
                and path_parts[1:3] == ["api", "tracks"]
                and path_parts[3]
                and path_parts[4] == "stream"
                and path_parts[5] == ""
            ):
                self._send_stream(unquote(path_parts[3]), head_only)
                return
            if (
                len(path_parts) == 5
                and path_parts[1:3] == ["api", "tracks"]
                and path_parts[3]
                and path_parts[4] == "stream"
            ):
                self._send_stream(unquote(path_parts[3]), head_only)
                return

            self._send_xml(
                HTTPStatus.NOT_FOUND,
                _error_xml("NOT_FOUND", "The requested route does not exist"),
                head_only,
            )

        def _handle_radio(self) -> None:
            if library_fault or history_fault or library_store is None or history_runtime is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("RADIO_UNAVAILABLE", library_fault or history_fault
                                          or "个人数据尚未初始化"))
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 1024 * 1024:
                    raise ValueError("电台请求内容大小无效")
                node = ElementTree.fromstring(self.rfile.read(length))
                if node.tag != "radio" or set(node.attrib) - {"mode", "hint", "neutral"}:
                    raise ValueError("需要 radio 元素及 mode、hint、neutral 属性")
                mode = node.get("mode", "")
                if mode not in ("familiar", "discover"):
                    raise ValueError("mode 必须是 familiar 或 discover")
                neutral_text = node.get("neutral", "false")
                if neutral_text not in ("true", "false"):
                    raise ValueError("neutral 必须是 true 或 false")
                neutral = neutral_text == "true"
                hint = normalize_hint(node.get("hint", ""))
                seeds: list[StoredTrack] = []
                exclude: set[str] = set()
                for child in node:
                    if child.tag == "seed" and not child.attrib:
                        for track_node in child:
                            if track_node.tag != "track" or list(track_node):
                                raise ValueError("seed 只能包含 track")
                            seeds.append(StoredTrack.from_value(_track_input(track_node)))
                    elif child.tag == "exclude" and set(child.attrib) == {"id"} and not list(child):
                        identifier = child.get("id", "").strip()
                        if not identifier:
                            raise ValueError("exclude id 不能为空")
                        exclude.add(identifier)
                    else:
                        raise ValueError("radio 子元素只能是 seed 或 exclude")
                if len(seeds) > 500 or len(exclude) > 2000:
                    raise ValueError("种子或排除项过多")
                with data_lock:
                    library = library_store.snapshot()
                    history = history_runtime.store.snapshot()
                    try:
                        config = ai_settings.config()
                    except AiSettingsError:
                        config = None
                terms = list(local_hint_intents(hint)) + [seed.title for seed in seeds[:3]]
                summary = {}
                if not neutral:
                    profile = build_taste_profile(library, {})
                    terms.extend(profile.queries)
                    summary = profile.summary
                path_like = lambda value: bool(re.search(r"[/\\]|[A-Za-z]:", value))
                terms = [term for term in terms if not path_like(term)]
                summary = {key: tuple(value for value in values if not path_like(value))
                           for key, values in summary.items()}
                intents = None
                # A request hint may contain a local path. Keep it on the loopback
                # service rather than forwarding it as model context.
                ai_hint = "" if path_like(hint) else hint
                if config is not None and config.is_valid() and ai_hint == hint:
                    try:
                        result = generate_search_intents(terms, config=config,
                                                         taste_summary=summary, hint=ai_hint)
                        if result.source == "ai" and result.queries:
                            intents = result.queries
                    except Exception:
                        pass

                def rank_candidates(candidates: Sequence[StoredTrack]) -> Sequence[str]:
                    safe = [track for track in candidates
                            if track.source not in ("local", "file")
                            and not track.media_uri
                            and not re.search(r"[/\\]|[A-Za-z]:", track.title + track.artist)
                            and not re.search(r"[/\\]|^[A-Za-z]:", track.id)]
                    if not safe:
                        return ()
                    return rerank_candidate_ids(
                        [{"id": track.id, "title": track.title, "artist": track.artist,
                          "durationSeconds": track.duration_seconds} for track in safe],
                        config=config, taste_summary=summary, hint=ai_hint)

                result = make_radio(provider, library, history, mode=mode, hint=hint,
                                    seeds=seeds, exclude=exclude, neutral=neutral,
                                    now=datetime.now(timezone.utc), intents=intents,
                                    candidate_ranker=rank_candidates if config is not None
                                    and config.is_valid() and ai_hint == hint else None)
                root = ElementTree.Element("radio", {
                    "status": result.status, "message": result.message,
                    "intents": " | ".join(result.intents),
                    "failedIntents": " | ".join(result.failed_intents)})
                for track, reason in result.items:
                    item_node = ElementTree.SubElement(root, "item", {"reason": reason})
                    _stored_track_element(item_node, track)
                self._send_xml(HTTPStatus.OK, _xml_bytes(root))
            except (ValueError, LibraryError, ElementTree.ParseError) as error:
                self._send_xml(HTTPStatus.BAD_REQUEST, _error_xml("RADIO_INVALID", str(error)))
            except ProviderError as error:
                self._send_xml(HTTPStatus(error.http_status), _error_xml(error.code, error.message))
            except Exception as error:
                self._send_xml(HTTPStatus.BAD_GATEWAY,
                               _error_xml("RADIO_SOURCE_ERROR", str(error)))

        def _handle_radio_feedback(self, method: str, head_only: bool = False) -> None:
            if library_fault or library_store is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("LIBRARY_DAMAGED", library_fault or "个人曲库未初始化"),
                               head_only)
                return
            try:
                if method == "GET":
                    snapshot = library_store.snapshot()
                elif method == "POST":
                    node = self._library_body()
                    if node.tag != "feedback" or set(node.attrib) != {"kind"}:
                        raise ValueError("需要 feedback 元素及 kind 属性")
                    children = list(node)
                    if len(children) != 1 or children[0].tag != "track" or list(children[0]):
                        raise ValueError("feedback 必须包含一个 track")
                    supplied = StoredTrack.from_value(_track_input(children[0]))
                    snapshot = library_store.set_radio_feedback(supplied, node.get("kind", ""))
                elif method == "DELETE":
                    path = urlsplit(self.path).path
                    identifier = unquote(path.removeprefix("/api/radio/feedback/"))
                    if not identifier:
                        raise ValueError("反馈资源 ID 无效")
                    snapshot = library_store.delete_radio_feedback(identifier)
                else:
                    raise ValueError("不支持的反馈操作")
                self._send_xml(HTTPStatus.OK, _radio_feedback_xml(snapshot), head_only)
            except (ValueError, LibraryError, ElementTree.ParseError) as error:
                self._send_xml(HTTPStatus.BAD_REQUEST,
                               _error_xml("RADIO_FEEDBACK_INVALID", str(error)), head_only)

        def _handle_history(self, head_only: bool = False) -> None:
            if history_fault is not None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("HISTORY_DAMAGED", history_fault), head_only)
            elif history_runtime is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("HISTORY_UNAVAILABLE", "听歌记录未初始化"), head_only)
            else:
                self._send_xml(HTTPStatus.OK,
                               _history_xml(history_runtime.store.snapshot()), head_only)

        def _handle_listening(self) -> None:
            if history_fault is not None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("HISTORY_DAMAGED", history_fault))
                return
            if history_runtime is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("HISTORY_UNAVAILABLE", "听歌记录未初始化"))
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 64 * 1024:
                    raise ValueError("听歌采样请求大小无效")
                root = ElementTree.fromstring(self.rfile.read(length))
                if root.tag != "sample" or len(root) != 1 or root[0].tag != "track":
                    raise ValueError("需要 sample 元素和一个 track 子元素")

                def boolean(name: str) -> bool:
                    value = root.get(name)
                    if value not in ("true", "false"):
                        raise ValueError(f"{name} 必须是 true 或 false")
                    return value == "true"

                result = history_runtime.sample(
                    root.get("sessionId", ""), _track_input(root[0]),
                    float(root.get("positionSeconds", "nan")),
                    boolean("playing"), seeked=boolean("seeked"),
                    finished=boolean("finished"),
                    playback_rate=float(root.get("playbackRate", "1")),
                    elapsed_seconds=float(root.get("elapsedSeconds")) if "elapsedSeconds" in root.attrib else None,
                    playlist_id=root.get("playlistId"),
                    playlist_name=root.get("playlistName"))
            except (HistoryError, ValueError, ElementTree.ParseError) as error:
                is_storage = isinstance(error, (HistoryFormatError, HistoryStorageError))
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR if is_storage else HTTPStatus.BAD_REQUEST,
                               _error_xml("HISTORY_IO_ERROR" if is_storage else "HISTORY_INVALID", str(error)))
                return
            attributes = {key: str(value).lower() if isinstance(value, bool) else str(value)
                          for key, value in result.items() if value is not None}
            self._send_xml(HTTPStatus.OK, _xml_bytes(ElementTree.Element("listening", attributes)))

        def _handle_diary(self, method: str, path: str, head_only: bool = False) -> None:
            if library_fault or history_fault or library_store is None or history_runtime is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                    _error_xml("DIARY_DAMAGED", library_fault or history_fault
                               or "日记数据未初始化"), head_only)
                return
            try:
                params = parse_qs(urlsplit(self.path).query, keep_blank_values=True)
                zone = params.get("zone", ["Asia/Shanghai"])[0]
                if method != "GET":
                    diary_view(library_store.snapshot(), history_runtime.store.snapshot(), zone)
                if method == "POST":
                    root = self._library_body()
                    if path == "/api/diary/notes" and root.tag == "note":
                        track_node = root.find("track")
                        if track_node is None or root.get("trackId") != track_node.get("id"):
                            raise ValueError("备注曲目无效")
                        text_node = root.find("text")
                        if text_node is None:
                            raise ValueError("备注缺少 text")
                        library_store.save_diary_note(_track_input(track_node), text_node.text or "")
                    elif path == "/api/diary/lyrics" and root.tag == "lyric":
                        track_node = root.find("track")
                        text_node = root.find("text")
                        if track_node is None or text_node is None or root.get("trackId") != track_node.get("id"):
                            raise ValueError("歌词收藏曲目或文本无效")
                        library_store.add_lyric_favorite(_track_input(track_node),
                            int(root.get("positionMillis", "-1")), text_node.text or "")
                    else:
                        raise ValueError("日记操作无效")
                elif method == "DELETE":
                    identifier = unquote(path.removeprefix("/api/diary/lyrics/"))
                    library_store.delete_lyric_favorite(identifier)
                view = diary_view(library_store.snapshot(), history_runtime.store.snapshot(), zone)
            except (ValueError, LibraryError, HistoryError, ElementTree.ParseError) as error:
                self._send_xml(HTTPStatus.BAD_REQUEST, _error_xml("DIARY_INVALID", str(error)), head_only)
                return
            except OSError as error:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("DIARY_IO_ERROR", str(error)), head_only)
                return
            self._send_xml(HTTPStatus.OK, diary_xml(view), head_only)

        def _handle_backup(self, route: str) -> None:
            nonlocal library_store, library_fault, history_runtime, history_fault
            action = route.removeprefix("/api/backup/")
            if action not in ("inspect", "export", "restore"):
                self._send_xml(HTTPStatus.NOT_FOUND,
                               _error_xml("NOT_FOUND", "未知备份操作"))
                return
            try:
                root = self._library_body()
                if root.tag != "backup" or not root.get("path", "").strip():
                    raise BackupError("需要 backup 元素和目标路径")
                path = Path(root.get("path", "")).expanduser()
                ai_path = Path(os.environ.get("LUMEN_AI_SETTINGS_PATH")
                               or library_path.with_name("ai-settings.json"))
                backgrounds = [item for suffix in ("png", "jpg", "jpeg", "webp")
                               if (item := library_path.with_name(f"background.{suffix}")).is_file()]
                background = max(backgrounds, key=lambda item: item.stat().st_mtime) if backgrounds else None
                if action == "inspect":
                    preview = inspect_backup(path)
                elif action == "export":
                    if library_fault or history_fault:
                        raise BackupError("个人数据已损坏，无法导出：" + (library_fault or history_fault or ""))
                    if library_store is None:
                        raise BackupError("个人曲库未初始化")
                    protected = [library_path, history_path, ai_path]
                    if background is not None:
                        protected.append(background)
                    if path.suffix.lower() != ".zip" or path.resolve() in {
                            item.resolve() for item in protected}:
                        raise BackupError("备份必须保存为独立的 .zip 文件")
                    if library_path.is_file():
                        preview = export_backup(path, library_path,
                            history_path=history_path if history_path.is_file() else None,
                            ai_config_path=ai_path if ai_path.is_file() else None,
                            background_path=background)
                    else:
                        with tempfile.TemporaryDirectory() as temporary:
                            empty_library = Path(temporary) / "library.json"
                            empty_library.write_text(json.dumps(library_store.snapshot(),
                                ensure_ascii=False), encoding="utf-8")
                            preview = export_backup(path, empty_library,
                                history_path=history_path if history_path.is_file() else None,
                                ai_config_path=ai_path if ai_path.is_file() else None,
                                background_path=background)
                else:
                    preview = inspect_backup(path)
                    if history_fault and "history.json" not in preview.members:
                        raise BackupError("现有听歌记录已损坏，备份未包含听歌记录，无法完整恢复")
                    image = next((name for name in preview.members
                                  if name.startswith("background.")), None)
                    target_image = (library_path.with_name(image) if image else None)
                    if "ai-settings.json" in preview.members:
                        ai_settings.preserve_credential_target()
                    result = restore_backup(path, library_path,
                        history_path=history_path, ai_config_path=ai_path,
                        background_path=target_image)
                    library_store = LibraryStore(library_path)
                    restored_history = HistoryStore(history_path)
                    history_runtime = (HistoryRuntime(restored_history, history_clock, library=library_store)
                                       if history_clock is not None else HistoryRuntime(restored_history, library=library_store))
                    library_fault = None
                    history_fault = None
                    preview = result.preview
            except (BackupError, ValueError, ElementTree.ParseError) as error:
                self._send_xml(HTTPStatus.BAD_REQUEST,
                               _error_xml("BACKUP_INVALID", str(error)))
                return
            except (OSError, LibraryError, HistoryError) as error:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("BACKUP_IO_ERROR", str(error)))
                return
            self._send_xml(HTTPStatus.OK, _backup_preview_xml(preview))

        def _handle_ai_settings(self, method: str, head_only: bool = False) -> None:
            try:
                if method == "POST":
                    root = self._library_body()
                    if root.tag != "ai-settings" or root.get("enabled") not in ("true", "false"):
                        raise AiSettingsError("AI 配置请求无效")
                    ai_settings.save(enabled=root.get("enabled") == "true",
                                     model=root.get("model", ""),
                                     base_url=root.get("baseUrl", ""),
                                     api_key=root.get("apiKey"))
                values = ai_settings.read()
                key_configured = ai_settings.has_key()
            except (AiSettingsError, ValueError, ElementTree.ParseError) as error:
                self._send_xml(HTTPStatus.BAD_REQUEST,
                               _error_xml("AI_SETTINGS_INVALID", str(error)), head_only)
                return
            except OSError as error:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("AI_SETTINGS_IO_ERROR", str(error)), head_only)
                return
            body = _xml_bytes(ElementTree.Element("ai-settings", {
                "enabled": str(values["enabled"]).lower(),
                "provider": values["provider"],
                "model": values["model"],
                "baseUrl": values["baseUrl"],
                "keyConfigured": str(key_configured).lower(),
            }))
            self._send_xml(HTTPStatus.OK, body, head_only)

        def _allow_local_write(self, *, xml_body: bool) -> bool:
            """Validate the loopback authority before reading/parsing a write body."""
            hosts = self.headers.get_all("Host", [])
            origins = self.headers.get_all("Origin", [])
            allowed_hosts = {f"{host}:{self.server.server_port}"
                             for host in ("127.0.0.1", "localhost", "[::1]")}
            if self.server.server_port == 80:
                allowed_hosts.update(("127.0.0.1", "localhost", "[::1]"))
            valid_host = len(hosts) == 1 and hosts[0].lower() in allowed_hosts
            if (not valid_host or len(origins) > 1
                    or (origins and origins[0] != "http://" + hosts[0])):
                self._send_xml(HTTPStatus.FORBIDDEN,
                               _error_xml("LOCAL_REQUEST_REQUIRED", "写入请求必须来自本机服务来源"))
                return False
            types = self.headers.get_all("Content-Type", [])
            if xml_body and (len(types) != 1 or types[0].split(";", 1)[0].strip().lower()
                             not in ("application/xml", "text/xml")):
                self._send_xml(HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
                               _error_xml("XML_REQUIRED", "写入请求需要明确的 XML Content-Type"))
                return False
            return True

        def _library_body(self, max_bytes: int = 64 * 1024) -> ElementTree.Element:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > max_bytes:
                raise ValueError("曲库请求内容大小无效")
            return ElementTree.fromstring(self.rfile.read(length))

        def _handle_library(self, method: str, head_only: bool = False) -> None:
            if library_fault is not None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("LIBRARY_DAMAGED", library_fault), head_only)
                return
            if library_store is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("LIBRARY_UNAVAILABLE", "个人曲库未初始化"), head_only)
                return
            parts = [unquote(part) for part in urlsplit(self.path).path.split("/") if part]
            if parts[:2] != ["api", "library"]:
                self._send_xml(HTTPStatus.NOT_FOUND, _error_xml("NOT_FOUND", "未知曲库路径"), head_only)
                return
            if parts == ["api", "library", "organization"]:
                self._handle_organization(method, head_only)
                return
            try:
                catalog_response = len(parts) > 2 and parts[2] == "versions"
                undo_token = None
                batch_result = None
                if parts == ["api", "library", "home"] and method in ("GET", "POST"):
                    snapshot = (library_store.snapshot() if method == "GET" else
                                library_store.save_home_preferences(home_input(self._library_body())))
                    self._send_xml(HTTPStatus.OK, home_xml(snapshot["homePreferences"]), head_only)
                    return
                elif method == "GET" and parts == ["api", "library", "versions"]:
                    snapshot = library_store.catalog()
                elif method == "POST" and parts == ["api", "library", "versions"]:
                    node = self._library_body()
                    if node.tag != "song" or set(node.attrib) - {"title", "note"}:
                        raise ValueError("需要 song 元素及 title、note 属性")
                    snapshot = library_store.create_song(
                        node.get("title", ""), node.get("note", ""),
                        [_version_input(child) for child in node])
                elif method == "PATCH" and len(parts) == 4 and parts[2] == "versions":
                    node = self._library_body()
                    if node.tag != "song" or list(node):
                        raise ValueError("需要不含子元素的 song 元素")
                    snapshot = library_store.edit_song(parts[3], dict(node.attrib))
                elif method == "POST" and len(parts) == 5 and parts[2] == "versions" and parts[4] == "versions":
                    label, performer, track = _version_input(self._library_body())
                    snapshot = library_store.add_version(parts[3], label, performer, track)
                elif method == "POST" and len(parts) == 5 and parts[2] == "versions" and parts[4] == "merge":
                    node = self._library_body()
                    if node.tag != "merge" or list(node) or set(node.attrib) != {"songId"}:
                        raise ValueError("需要含 songId 的 merge 元素")
                    snapshot = library_store.merge_songs(parts[3], node.get("songId", ""))
                elif method == "PATCH" and len(parts) == 6 and parts[2] == "versions" and parts[4] == "versions":
                    node = self._library_body()
                    if node.tag != "version" or list(node):
                        raise ValueError("需要不含子元素的 version 元素")
                    snapshot = library_store.edit_version(parts[3], parts[5], dict(node.attrib))
                elif method == "DELETE" and len(parts) == 6 and parts[2] == "versions" and parts[4] == "versions":
                    snapshot = library_store.detach_version(parts[3], parts[5])
                elif method == "POST" and len(parts) == 7 and parts[2] == "versions" and parts[4] == "versions" and parts[6] == "replace":
                    node = self._library_body()
                    if node.tag != "track" or list(node):
                        raise ValueError("需要 track 元素")
                    snapshot = library_store.replace_version(parts[3], parts[5], _track_input(node))
                elif method == "POST" and parts == ["api", "library", "versions", "undo"]:
                    snapshot = library_store.undo_catalog()
                elif method == "GET" and parts == ["api", "library"]:
                    snapshot = library_store.snapshot()
                elif method == "POST" and parts == ["api", "library", "segments", "batch"]:
                    node = self._library_body(512 * 1024)
                    if (node.tag != "segments" or set(node.attrib) - {"destination", "name", "batchId"}
                            or node.get("destination") not in ("favorites", "playlist")
                            or any(child.tag != "track" or list(child) for child in node)):
                        raise ValueError("需要含 destination、batchId 和 track 子元素的 segments 请求")
                    destination = node.get("destination")
                    if destination == "favorites" and node.get("name"):
                        raise ValueError("收藏目标不能包含歌单名称")
                    snapshot, saved, skipped, playlist_id = library_store.save_segments(
                        [_track_input(child) for child in node],
                        node.get("name", "") if destination == "playlist" else None,
                        node.get("batchId", ""))
                    batch_result = (saved, skipped, playlist_id)
                elif method == "POST" and parts == ["api", "library", "favorites"]:
                    node = self._library_body()
                    if node.tag != "track":
                        raise ValueError("需要 track 元素")
                    snapshot = library_store.favorite(_track_input(node))
                elif method == "DELETE" and len(parts) == 4 and parts[2] == "favorites":
                    snapshot = library_store.unfavorite(parts[3])
                elif method == "PATCH" and len(parts) == 4 and parts[2] == "tracks":
                    node = self._library_body()
                    if node.tag != "track":
                        raise ValueError("需要 track 元素")
                    snapshot = library_store.edit_segment(
                        parts[3], _normalize_track_attributes(dict(node.attrib)))
                elif method == "DELETE" and len(parts) == 4 and parts[2] == "tracks":
                    snapshot = library_store.delete_segment(parts[3])
                elif method == "POST" and parts == ["api", "library", "favorites", "move"]:
                    node = self._library_body()
                    if node.tag != "move":
                        raise ValueError("需要 move 元素")
                    snapshot = library_store.move_favorite(int(node.get("from", "-1")),
                                                           int(node.get("to", "-1")))
                elif method == "POST" and parts == ["api", "library", "playlists"]:
                    node = self._library_body()
                    if node.tag != "playlist":
                        raise ValueError("需要 playlist 元素")
                    snapshot = library_store.create_playlist(node.get("name", ""))
                elif method == "POST" and parts == ["api", "library", "playlists", "move"]:
                    node = self._library_body()
                    if node.tag != "move":
                        raise ValueError("需要 move 元素")
                    snapshot = library_store.move_playlist(int(node.get("from", "-1")),
                                                           int(node.get("to", "-1")))
                elif method == "POST" and parts == ["api", "library", "playlists", "import"]:
                    node = self._library_body()
                    if node.tag != "import":
                        raise ValueError("需要 import 元素")
                    if not isinstance(provider, BilibiliProvider):
                        self._send_xml(HTTPStatus.NOT_IMPLEMENTED,
                                       _error_xml("NOT_IMPLEMENTED", "当前数据源不支持 B 站收藏夹导入"))
                        return
                    imported = fetch_public_favorite(provider, node.get("url", ""))
                    snapshot = library_store.import_playlist(imported.name, imported.tracks)
                    self._send_xml(HTTPStatus.OK, _xml_bytes(ElementTree.Element("import", {
                        "playlistId": snapshot["playlists"][-1]["id"],
                        "name": imported.name,
                        "importedCount": str(len(imported.tracks)),
                        "skippedCount": str(imported.skipped_count),
                        "duplicateCount": str(imported.duplicate_count),
                        "truncated": str(imported.truncated).lower(),
                    })))
                    return
                elif method == "PATCH" and len(parts) == 4 and parts[2] == "playlists":
                    node = self._library_body(MAX_METADATA_BODY_BYTES)
                    if node.tag != "playlist" or set(node.attrib) - {"name"}:
                        raise ValueError("需要 playlist 元素及可选 name 属性")
                    fields = {}
                    for child in node:
                        if child.tag not in ("description", "coverDataUri") or child.tag in fields \
                                or child.attrib or len(child):
                            raise ValueError("歌单更新仅允许 description、coverDataUri 文本元素")
                        fields[child.tag] = child.text or ""
                    # The larger limit only applies to embedded-cover updates.
                    if int(self.headers.get("Content-Length", "0")) > 64 * 1024 \
                            and "coverDataUri" not in fields:
                        raise ValueError("曲库请求内容大小无效")
                    snapshot = library_store.update_playlist_metadata(parts[3], name=node.get("name"),
                        description=fields.get("description"), cover_data_uri=fields.get("coverDataUri"))
                elif method == "DELETE" and len(parts) == 4 and parts[2] == "playlists":
                    snapshot = library_store.delete_playlist(parts[3])
                elif method == "POST" and len(parts) == 5 and parts[2] == "playlists" and parts[4] == "tracks":
                    node = self._library_body()
                    if node.tag != "track":
                        raise ValueError("需要 track 元素")
                    snapshot = library_store.add_to_playlist(parts[3], _track_input(node))
                elif method == "DELETE" and len(parts) == 6 and parts[2] == "playlists" and parts[4] == "tracks":
                    snapshot = library_store.remove_from_playlist(parts[3], parts[5])
                elif method == "POST" and len(parts) == 6 and parts[2] == "playlists" and parts[4:] == ["tracks", "remove"]:
                    node = self._library_body()
                    if node.tag != "remove" or node.attrib or not list(node) or any(
                            child.tag != "track" or set(child.attrib) != {"id"} or list(child)
                            for child in node):
                        raise ValueError("需要包含曲目 ID 的 remove 元素")
                    snapshot, undo_token = library_store.remove_tracks_from_playlist(
                        parts[3], [child.get("id", "") for child in node])
                elif method == "POST" and len(parts) == 6 and parts[2] == "playlists" and parts[4:] == ["tracks", "undo"]:
                    node = self._library_body()
                    if node.tag != "undo" or set(node.attrib) != {"token"} or list(node):
                        raise ValueError("需要含 token 的 undo 元素")
                    snapshot = library_store.undo_playlist_removal(parts[3], node.get("token", ""))
                elif method == "POST" and len(parts) == 5 and parts[2] == "playlists" and parts[4] == "move":
                    node = self._library_body()
                    if node.tag != "move":
                        raise ValueError("需要 move 元素")
                    snapshot = library_store.move_in_playlist(parts[3], int(node.get("from", "-1")),
                                                              int(node.get("to", "-1")))
                else:
                    self._send_xml(HTTPStatus.NOT_FOUND, _error_xml("NOT_FOUND", "未知曲库路径"), head_only)
                    return
            except (LibraryError, ValueError, ElementTree.ParseError) as error:
                code = ("LIBRARY_DAMAGED" if isinstance(error, LibraryFormatError) else
                        "LIBRARY_IO_ERROR" if isinstance(error, LibraryStorageError) else "LIBRARY_INVALID")
                status = HTTPStatus.INTERNAL_SERVER_ERROR if isinstance(error, (LibraryFormatError, LibraryStorageError)) else HTTPStatus.BAD_REQUEST
                self._send_xml(status, _error_xml(code, str(error)), head_only)
                return
            except ProviderError as error:
                self._send_xml(HTTPStatus(error.http_status),
                               _error_xml(error.code, error.message), head_only)
                return
            except OSError as error:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("LIBRARY_IO_ERROR", str(error)), head_only)
                return
            self._send_xml(HTTPStatus.OK, _catalog_xml(snapshot) if catalog_response
                           else _library_xml(snapshot, undo_token, batch_result=batch_result), head_only)

        def _handle_organization(self, method: str, head_only: bool = False) -> None:
            if history_fault is not None or history_runtime is None:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("HISTORY_DAMAGED", history_fault or "听歌记录未初始化"), head_only)
                return
            try:
                if method == "GET":
                    action, options = "load", {}
                elif method == "POST":
                    action, options = _organization_input(self._library_body())
                else:
                    self._send_xml(HTTPStatus.NOT_FOUND,
                                   _error_xml("NOT_FOUND", "未知曲库路径"), head_only)
                    return
                history = history_runtime.store.snapshot()
                snapshot, results, created = library_store.organization(action, options, history)
                self._send_xml(HTTPStatus.OK, _organization_xml(
                    snapshot, history, results,
                    set(options["cachedIds"]) if "cachedIds" in options else None,
                    created), head_only)
            except (LibraryError, ValueError, ElementTree.ParseError) as error:
                self._send_xml(HTTPStatus.BAD_REQUEST,
                               _error_xml("LIBRARY_INVALID", str(error)), head_only)
            except OSError as error:
                self._send_xml(HTTPStatus.INTERNAL_SERVER_ERROR,
                               _error_xml("LIBRARY_IO_ERROR", str(error)), head_only)

        def _send_stream(self, track_id: str, head_only: bool) -> None:
            requested_range = self.headers.get("Range")
            if audio_proxy is None:
                self._send_xml(
                    HTTPStatus.NOT_IMPLEMENTED,
                    _error_xml("NOT_IMPLEMENTED", "Track streaming is unavailable for this provider"),
                    head_only,
                )
                return
            try:
                source_track_id = (library_store.source_track_id(track_id)
                                   if library_store is not None else None)
                audio = audio_proxy.open(source_track_id or track_id, requested_range, head_only)
            except ProviderError as error:
                self._send_xml(
                    HTTPStatus(error.http_status),
                    _error_xml(error.code, error.message),
                    head_only,
                    error_code=error.code,
                    error_message=error.message,
                )
                return
            try:
                self.send_response(HTTPStatus.OK if head_only else HTTPStatus(audio.status))
                self.send_header("Content-Type", audio.content_type)
                self.send_header("Content-Length", str(audio.total_length if head_only else audio.content_length))
                self.send_header("Accept-Ranges", "bytes")
                if not head_only and audio.content_range is not None:
                    self.send_header("Content-Range", audio.content_range)
                self.end_headers()
                if not head_only:
                    while chunk := audio.response.read(64 * 1024):
                        self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError):
                # A seek or a track switch routinely closes the old client connection.
                pass
            finally:
                audio.close()

        def _send_xml(
            self,
            status: HTTPStatus,
            body: bytes,
            head_only: bool = False,
            error_code: str | None = None,
            error_message: str | None = None,
        ) -> None:
            self.send_response(status)
            self.send_header("Connection", "close")
            self.send_header("Content-Type", "application/xml; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            if error_code is not None:
                self.send_header("X-Lumen-Error-Code", error_code)
            if error_message is not None:
                self.send_header("X-Lumen-Error-Message", quote(error_message, safe=""))
                self.send_header("X-Lumen-Error-Message-Encoding", "percent-encoded-utf8")
            self.end_headers()
            if not head_only:
                self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:
            return

    return LumenRequestHandler


def create_server(
    port: int = 8765,
    provider: TrackProvider | None = None,
    audio_proxy: BilibiliAudioProxy | None = None,
    library_store: LibraryStore | None = None,
    history_store: HistoryStore | None = None,
    history_clock: Callable[[], float] | None = None,
) -> ThreadingHTTPServer:
    """Create a loopback-only HTTP server. Port 0 selects a temporary free port."""
    active_provider = provider or BilibiliProvider()
    active_audio_proxy = audio_proxy
    if active_audio_proxy is None and isinstance(active_provider, BilibiliProvider):
        active_audio_proxy = BilibiliAudioProxy(active_provider)
    library_fault = None
    if library_store is None:
        try:
            library_store = LibraryStore(default_library_path())
        except LibraryFormatError as error:
            library_fault = str(error)
    history_fault = None
    if history_store is None:
        try:
            library_path = library_store.path if library_store is not None else default_library_path()
            history_store = HistoryStore(default_history_path(library_path))
        except HistoryFormatError as error:
            history_fault = str(error)
    library_path = library_store.path if library_store is not None else default_library_path()
    history_path = history_store.path if history_store is not None else default_history_path(library_path)
    history_runtime = (HistoryRuntime(history_store, history_clock, library=library_store)
                       if history_clock is not None else HistoryRuntime(history_store, library=library_store)) if history_store is not None else None
    return ThreadingHTTPServer(
        ("127.0.0.1", port), _handler_for(active_provider, active_audio_proxy,
                                           library_store, library_fault,
                                           history_runtime, history_fault,
                                           library_path, history_path, history_clock)
    )
