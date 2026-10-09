"""Validation and evaluation for local smart playlists."""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping


RULE_FIELDS = {"favoriteOnly", "favoriteOlderDays", "listening", "listeningDays",
               "cached", "minDuration", "maxDuration", "tagsAll"}


def utc_timestamp(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("时间必须是含时区的 ISO 时间")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("时间必须是含时区的 ISO 时间") from exc
    if parsed.tzinfo is None:
        raise ValueError("时间必须包含时区")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_tags(values: Any) -> list[str]:
    if not isinstance(values, list):
        raise ValueError("标签必须是数组")
    result: list[str] = []
    for value in values:
        if not isinstance(value, str) or not value.strip() or len(value.strip()) > 64:
            raise ValueError("标签必须是 1-64 字符的文本")
        tag = value.strip()
        if tag not in result:
            result.append(tag)
    return result


def normalize_rules(value: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) - RULE_FIELDS:
        raise ValueError("智能歌单规则字段无效")
    favorite = value.get("favoriteOnly", False)
    if type(favorite) is not bool:
        raise ValueError("favoriteOnly 必须是布尔值")
    listening = value.get("listening", "any")
    cached = value.get("cached", "any")
    if listening not in ("any", "recent", "notRecent", "unrecorded"):
        raise ValueError("listening 规则无效")
    if cached not in ("any", "yes", "no"):
        raise ValueError("cached 规则无效")
    normalized = {"favoriteOnly": favorite, "listening": listening,
                  "cached": cached, "tagsAll": normalize_tags(value.get("tagsAll", []))}
    for field in ("favoriteOlderDays", "listeningDays", "minDuration", "maxDuration"):
        number = value.get(field)
        limit = 365000 if field.endswith("Days") else 2**31 - 1
        if number is not None and (type(number) is not int or not 0 <= number <= limit):
            raise ValueError(f"{field} 必须是 0-{limit} 的整数")
        normalized[field] = number
    if normalized["favoriteOlderDays"] is not None and not favorite:
        raise ValueError("favoriteOlderDays 需要 favoriteOnly=true")
    if (listening in ("recent", "notRecent")) != (normalized["listeningDays"] is not None):
        raise ValueError("listeningDays 仅用于 recent/notRecent，且必填")
    minimum, maximum = normalized["minDuration"], normalized["maxDuration"]
    if minimum is not None and maximum is not None and minimum > maximum:
        raise ValueError("minDuration 不能大于 maxDuration")
    return normalized


def candidates(state: Mapping[str, Any]) -> list[dict[str, Any]]:
    ordered: dict[str, dict[str, Any]] = {}
    for track in state["favorites"]:
        ordered.setdefault(track["id"], track)
    for playlist in state["playlists"]:
        for track in playlist["tracks"]:
            ordered.setdefault(track["id"], track)
    for song in state["songs"]:
        for version in song["versions"]:
            track = version["track"]
            ordered.setdefault(track["id"], track)
    return list(ordered.values())


def evaluate(state: Mapping[str, Any], history: Mapping[str, Any],
             rules: Mapping[str, Any], cached_ids: set[str],
             *, now: datetime | None = None) -> list[dict[str, Any]]:
    rules = normalize_rules(rules)
    now = now or datetime.now(timezone.utc)
    favorites = {track["id"] for track in state["favorites"]}
    played = {entry["track"]["id"]: entry for entry in history["entries"]}
    result: list[dict[str, Any]] = []
    for track in candidates(state):
        identifier = track["id"]
        if rules["favoriteOnly"] and identifier not in favorites:
            continue
        older_days = rules["favoriteOlderDays"]
        if older_days is not None:
            timestamp = state["favoriteAt"].get(identifier)
            if timestamp is None or datetime.fromisoformat(timestamp.replace("Z", "+00:00")) > now - timedelta(days=older_days):
                continue
        entry = played.get(identifier)
        listening = rules["listening"]
        if listening == "unrecorded":
            if entry is not None:
                continue
        elif listening in ("recent", "notRecent"):
            if entry is None:
                continue
            last = datetime.fromisoformat(entry["lastPlayedAt"].replace("Z", "+00:00"))
            is_recent = last >= now - timedelta(days=rules["listeningDays"])
            if (listening == "recent") != is_recent:
                continue
        if rules["cached"] != "any" and (identifier in cached_ids) != (rules["cached"] == "yes"):
            continue
        duration = track["durationSeconds"]
        if (rules["minDuration"] is not None or rules["maxDuration"] is not None):
            if duration == 0 or (rules["minDuration"] is not None and duration < rules["minDuration"]) \
                    or (rules["maxDuration"] is not None and duration > rules["maxDuration"]):
                continue
        if not set(rules["tagsAll"]).issubset(state["trackTags"].get(identifier, [])):
            continue
        result.append(track)
    return result


def suggested_tags(track: Mapping[str, Any], state: Mapping[str, Any]) -> list[str]:
    """Only local title/version text; these hints never modify manual tags."""
    words = [track["title"]]
    for song in state["songs"]:
        for version in song["versions"]:
            if version["track"]["id"] == track["id"]:
                words.extend((version["label"], song["title"]))
    text = " ".join(words).casefold()
    hints = (("现场", r"\blive\b|现场|演唱会"),
             ("混音", r"\bremix\b|混音"),
             ("不插电", r"\bacoustic\b|不插电"),
             ("翻唱", r"\bcover\b|翻唱"),
             ("纯音乐", r"\binstrumental\b|伴奏|纯音乐"))
    return [label for label, pattern in hints if re.search(pattern, text)]
