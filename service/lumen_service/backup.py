"""Version 1 portable data backup with validated, rollback-safe restore."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import uuid
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

from .history import HistoryError, HistoryStore
from .library import LibraryError, LibraryStore


class BackupError(ValueError):
    """A backup cannot be exported or safely imported."""


FORMAT = "lumen-music-backup"
VERSION = 1
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_BACKGROUND_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
_JSON_MEMBERS = {"library.json", "history.json", "ai-settings.json"}
_BACKGROUND_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}
_HASH = re.compile(r"[0-9a-f]{64}\Z")
_AI_FIELDS = {"enabled", "provider", "model", "baseUrl"}


@dataclass(frozen=True)
class BackupPreview:
    created_at: str
    members: tuple[str, ...]
    favorite_count: int
    playlist_count: int
    history_count: int | None
    background_bytes: int | None


@dataclass(frozen=True)
class RestoreResult:
    preview: BackupPreview
    restored_paths: tuple[Path, ...]


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def _read_limited(path: Path, limit: int) -> bytes:
    try:
        if path.stat().st_size > limit:
            raise BackupError(f"文件超过允许大小：{path}")
        with path.open("rb") as stream:
            content = stream.read(limit + 1)
    except OSError as exc:
        raise BackupError(f"无法读取备份源：{path}；{exc}") from exc
    if len(content) > limit:
        raise BackupError(f"文件超过允许大小：{path}")
    return content


def _parse_json(content: bytes, name: str) -> Any:
    try:
        return json.loads(content.decode("utf-8"))
    except (UnicodeError, ValueError) as exc:
        raise BackupError(f"{name} 不是有效 UTF-8 JSON：{exc}") from exc


def _library(content: bytes) -> dict[str, Any]:
    try:
        return LibraryStore._decode(content.decode("utf-8"))
    except (UnicodeError, ValueError, LibraryError) as exc:
        raise BackupError(f"library.json 数据无效：{exc}") from exc


def _history(content: bytes, validator: Callable[[bytes], Any] | None) -> dict[str, Any]:
    if validator is not None:
        try:
            result = validator(content)
        except (UnicodeError, ValueError, HistoryError) as exc:
            raise BackupError(f"history.json 数据无效：{exc}") from exc
    else:
        try:
            result = HistoryStore._decode(content.decode("utf-8"))
        except (UnicodeError, ValueError, HistoryError) as exc:
            raise BackupError(f"history.json 数据无效：{exc}") from exc
    if not isinstance(result, dict):
        raise BackupError("history.json 验证结果必须是对象")
    return result


def _ai_settings(content: bytes) -> dict[str, Any]:
    value = _parse_json(content, "ai-settings.json")
    if not isinstance(value, dict):
        raise BackupError("AI 配置必须是对象")
    # Explicit allowlist: credentials and unknown future fields never enter ZIP.
    result: dict[str, Any] = {"version": VERSION}
    if "version" in value and (type(value["version"]) is not int or value["version"] != VERSION):
        raise BackupError("AI 配置版本不受支持")
    for key in _AI_FIELDS:
        if key not in value:
            continue
        item = value[key]
        if key == "enabled":
            if type(item) is not bool:
                raise BackupError("AI enabled 必须是布尔值")
        elif not isinstance(item, str) or not item.strip() or len(item) > 512:
            raise BackupError(f"AI {key} 必须是非空短字符串")
        else:
            item = item.strip()
            if key == "baseUrl":
                parsed = urlsplit(item)
                if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username or parsed.password:
                    raise BackupError("AI baseUrl 必须是无账号信息的 HTTP(S) 地址")
        result[key] = item
    return result


def _background_name(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix not in _BACKGROUND_SUFFIXES:
        raise BackupError("背景图只支持 PNG、JPEG 或 WebP")
    return "background" + suffix


def _validate_background(name: str, content: bytes) -> None:
    suffix = Path(name).suffix
    valid = (content.startswith(b"\x89PNG\r\n\x1a\n") if suffix == ".png" else
             content.startswith(b"\xff\xd8") if suffix in {".jpg", ".jpeg"} else
             content.startswith(b"RIFF") and content[8:12] == b"WEBP")
    if not valid:
        raise BackupError(f"背景图格式与文件内容不符：{name}")


def _member_limit(name: str) -> int:
    if name == "manifest.json" or name in _JSON_MEMBERS:
        return MAX_JSON_BYTES
    if name.startswith("background") and Path(name).suffix in _BACKGROUND_SUFFIXES:
        return MAX_BACKGROUND_BYTES
    raise BackupError(f"未知备份条目：{name}")


def export_backup(destination: str | Path, library_path: str | Path, *,
                  history_path: str | Path | None = None,
                  ai_config_path: str | Path | None = None,
                  background_path: str | Path | None = None,
                  history_validator: Callable[[bytes], Any] | None = None) -> BackupPreview:
    """Export validated data to a ZIP; destination changes only after success."""
    destination = Path(destination)
    contents: dict[str, bytes] = {}
    library = _read_limited(Path(library_path), MAX_JSON_BYTES)
    _library(library)
    contents["library.json"] = library
    if history_path is not None:
        history = _read_limited(Path(history_path), MAX_JSON_BYTES)
        _history(history, history_validator)
        contents["history.json"] = history
    if ai_config_path is not None:
        settings = _ai_settings(_read_limited(Path(ai_config_path), MAX_JSON_BYTES))
        contents["ai-settings.json"] = _json_bytes(settings)
    if background_path is not None:
        path = Path(background_path)
        name = _background_name(path)
        background = _read_limited(path, MAX_BACKGROUND_BYTES)
        _validate_background(name, background)
        contents[name] = background
    manifest = {
        "format": FORMAT,
        "version": VERSION,
        "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "files": {name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                  for name, data in contents.items()},
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=6, allowZip64=False) as archive:
            archive.writestr("manifest.json", _json_bytes(manifest))
            for name, data in contents.items():
                archive.writestr(name, data)
        if temporary.stat().st_size > MAX_ARCHIVE_BYTES:
            raise BackupError("ZIP 超过允许大小")
        os.replace(temporary, destination)
    except (OSError, zipfile.BadZipFile, OverflowError) as exc:
        raise BackupError(f"导出备份失败：{exc}") from exc
    finally:
        temporary.unlink(missing_ok=True)
    return inspect_backup(destination, history_validator=history_validator)


def _read_backup(source: str | Path, *,
                 history_validator: Callable[[bytes], Any] | None = None) -> tuple[BackupPreview, dict[str, bytes]]:
    """Read and validate every member in memory before any restore writes."""
    source = Path(source)
    try:
        if source.stat().st_size > MAX_ARCHIVE_BYTES:
            raise BackupError("ZIP 超过允许大小")
        with zipfile.ZipFile(source) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)) or not 2 <= len(names) <= 5:
                raise BackupError("ZIP 条目重复或数量错误")
            if any(info.is_dir() or info.flag_bits & 1 or (info.external_attr >> 16) & 0o170000 == 0o120000
                   for info in infos):
                raise BackupError("ZIP 不允许目录、加密或符号链接")
            for info in infos:
                limit = _member_limit(info.filename)
                if info.file_size > limit:
                    raise BackupError(f"ZIP 条目过大：{info.filename}")
            if "manifest.json" not in names or "library.json" not in names:
                raise BackupError("ZIP 缺少 manifest.json 或 library.json")
            backgrounds = [name for name in names if name.startswith("background")]
            if len(backgrounds) > 1:
                raise BackupError("ZIP 只能包含一张背景图")
            with archive.open("manifest.json") as stream:
                manifest = _parse_json(stream.read(MAX_JSON_BYTES + 1), "manifest.json")
            if not isinstance(manifest, dict) or manifest.get("format") != FORMAT or type(manifest.get("version")) is not int or manifest["version"] != VERSION:
                raise BackupError("备份格式或版本不受支持")
            if not isinstance(manifest.get("createdAt"), str):
                raise BackupError("manifest.json 缺少创建时间")
            try:
                created_at = datetime.fromisoformat(manifest["createdAt"])
            except ValueError as exc:
                raise BackupError("manifest.json 创建时间无效") from exc
            if created_at.tzinfo is None:
                raise BackupError("manifest.json 创建时间必须包含时区")
            files = manifest.get("files")
            if not isinstance(files, dict) or set(files) != set(names) - {"manifest.json"}:
                raise BackupError("manifest.json 条目与 ZIP 不一致")
            data: dict[str, bytes] = {}
            for name, metadata in files.items():
                if not isinstance(metadata, dict) or type(metadata.get("size")) is not int or not isinstance(metadata.get("sha256"), str) or not _HASH.fullmatch(metadata["sha256"]):
                    raise BackupError(f"manifest.json 中 {name} 元数据无效")
                with archive.open(name) as stream:
                    content = stream.read(_member_limit(name) + 1)
                if len(content) > _member_limit(name) or len(content) != metadata["size"] or hashlib.sha256(content).hexdigest() != metadata["sha256"]:
                    raise BackupError(f"备份条目校验失败：{name}")
                data[name] = content
    except (OSError, zipfile.BadZipFile, EOFError) as exc:
        raise BackupError(f"无法读取备份 ZIP：{exc}") from exc
    library = _library(data["library.json"])
    history_count = None
    if "history.json" in data:
        history = _history(data["history.json"], history_validator)
        if isinstance(history.get("entries"), list):
            history_count = len(history["entries"])
    if "ai-settings.json" in data:
        settings = _ai_settings(data["ai-settings.json"])
        if set(_parse_json(data["ai-settings.json"], "ai-settings.json")) != set(settings):
            raise BackupError("AI 配置包含非白名单字段")
    if backgrounds:
        _validate_background(backgrounds[0], data[backgrounds[0]])
    preview = BackupPreview(manifest["createdAt"], tuple(sorted(data)),
                            len(library["favorites"]), len(library["playlists"]),
                            history_count,
                            len(data[backgrounds[0]]) if backgrounds else None)
    return preview, data


def inspect_backup(source: str | Path, *,
                   history_validator: Callable[[bytes], Any] | None = None) -> BackupPreview:
    """Validate the entire archive without modifying user data or extracting files."""
    return _read_backup(source, history_validator=history_validator)[0]


def restore_backup(source: str | Path, library_path: str | Path, *,
                   history_path: str | Path | None = None,
                   ai_config_path: str | Path | None = None,
                   background_path: str | Path | None = None,
                   history_validator: Callable[[bytes], Any] | None = None) -> RestoreResult:
    """Restore a complete selected snapshot with preflight, staging and rollback.

    Optional members need explicit destinations. The caller must pause writers and
    reload in-memory stores after success; this function owns only filesystem data.
    """
    preview, data = _read_backup(source, history_validator=history_validator)
    paths: dict[str, Path] = {"library.json": Path(library_path)}
    optional = {"history.json": history_path, "ai-settings.json": ai_config_path}
    for name, destination in optional.items():
        if name in data:
            if destination is None:
                raise BackupError(f"备份包含 {name}，但未指定恢复位置")
            paths[name] = Path(destination)
    backgrounds = [name for name in data if name.startswith("background")]
    if backgrounds:
        name = backgrounds[0]
        if background_path is None:
            raise BackupError("备份包含背景图，但未指定恢复位置")
        path = Path(background_path)
        if path.suffix.lower() != Path(name).suffix:
            raise BackupError("背景图目标扩展名必须与备份格式一致")
        paths[name] = path
    resolved = [path.resolve() for path in paths.values()]
    if len(set(resolved)) != len(paths):
        raise BackupError("恢复目标路径不能重复")
    if Path(source).resolve() in resolved:
        raise BackupError("备份 ZIP 不能同时作为恢复目标")

    # Stage every new value and original next to its destination so os.replace
    # never crosses volumes. Keep original copies until the entire commit passes.
    staged: dict[Path, Path] = {}
    originals: dict[Path, Path | None] = {}
    replaced: list[Path] = []
    retained_originals: set[Path] = set()
    try:
        for name, path in paths.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            marker = uuid.uuid4().hex
            next_path = path.with_name(f".{path.name}.{marker}.restore")
            staged[path] = next_path
            with next_path.open("xb") as output:
                output.write(data[name])
                output.flush()
                os.fsync(output.fileno())
            if path.exists():
                previous = path.with_name(f".{path.name}.{marker}.previous")
                originals[path] = previous
                shutil.copyfile(path, previous)
            else:
                originals[path] = None
        for path, next_path in staged.items():
            os.replace(next_path, path)
            replaced.append(path)
    except OSError as exc:
        rollback_errors: list[str] = []
        for path in reversed(replaced):
            try:
                previous = originals[path]
                if previous is None:
                    path.unlink(missing_ok=True)
                else:
                    os.replace(previous, path)
                    originals[path] = None
            except OSError as rollback_exc:
                rollback_errors.append(f"{path}：{rollback_exc}")
                if originals[path] is not None:
                    retained_originals.add(originals[path])
        if rollback_errors:
            raise BackupError(f"恢复失败且回滚未完成；原件副本已保留：{'；'.join(rollback_errors)}") from exc
        raise BackupError(f"恢复失败，原数据已回滚：{exc}") from exc
    finally:
        for path in staged.values():
            path.unlink(missing_ok=True)
        # A failed rollback leaves its original copy in place for manual repair.
        for path in originals.values():
            if path is not None and path not in retained_originals:
                path.unlink(missing_ok=True)
    return RestoreResult(preview, tuple(paths.values()))
