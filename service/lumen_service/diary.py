"""Local diary views derived only from qualified listening events."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from xml.etree import ElementTree

from .library import StoredTrack


def _date(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def diary_view(library: dict[str, Any], history: dict[str, Any],
               zone_name: str, *, now: datetime | None = None) -> dict[str, Any]:
    if not isinstance(zone_name, str) or not zone_name or len(zone_name) > 64:
        raise ValueError("时区无效")
    try:
        zone = ZoneInfo(zone_name)
    except ZoneInfoNotFoundError as exc:
        if zone_name == "Asia/Shanghai":
            zone = timezone(timedelta(hours=8), "Asia/Shanghai")
        elif zone_name in ("UTC", "Etc/UTC"):
            zone = timezone.utc
        else:
            raise ValueError("时区无效") from exc
    except (ValueError, KeyError) as exc:
        raise ValueError("时区无效") from exc
    now_utc = now or datetime.now(timezone.utc)
    if now_utc.tzinfo is None:
        raise ValueError("当前时间必须包含时区")
    local_now = now_utc.astimezone(zone)
    this_monday = (local_now - timedelta(days=local_now.weekday())).date()
    last_monday = this_monday - timedelta(days=7)
    month = (local_now.year, local_now.month)
    events = sorted(history["events"], key=lambda event: _date(event["playedAt"]), reverse=True)
    entry_by_id = {item["track"]["id"]: item for item in history["entries"]}
    event_track_ids = {event["track"]["id"] for event in events}
    recent = list(events)
    recent.extend({"track": entry["track"], "playedAt": entry["lastPlayedAt"],
                   "legacy": True}
                  for entry in history["entries"]
                  if entry["track"]["id"] not in event_track_ids)
    recent.sort(key=lambda item: _date(item["playedAt"]), reverse=True)

    weekly: list[dict[str, Any]] = []
    for entry in history["entries"]:
        first = entry.get("firstPlayedAt")
        if first and last_monday <= _date(first).astimezone(zone).date() < this_monday:
            weekly.append({"track": entry["track"], "firstPlayedAt": first,
                           "playedAt": entry["lastPlayedAt"],
                           "songId": next((event["songId"] for event in events
                                           if event["track"]["id"] == entry["track"]["id"]
                                           and event["playedAt"] == first), None)})
    weekly.sort(key=lambda item: item["firstPlayedAt"], reverse=True)

    monthly_events = [event for event in events
                      if (_date(event["playedAt"]).astimezone(zone).year,
                          _date(event["playedAt"]).astimezone(zone).month) == month]
    playlists: dict[str, dict[str, Any]] = {}
    for event in monthly_events:
        playlist_id = event.get("playlistId")
        if playlist_id:
            row = playlists.setdefault(playlist_id, {"playlistId": playlist_id,
                             "playlistName": event["playlistName"], "playCount": 0,
                             "track": event["track"], "playedAt": event["playedAt"]})
            row["playCount"] += 1

    favorites = library["favoriteAt"]
    previous: dict[str, datetime] = {}
    reheard: list[dict[str, Any]] = []
    for event in reversed(events):
        track_id = event["track"]["id"]
        at = _date(event["playedAt"])
        before = previous.get(track_id) or (_date(event["previousPlayedAt"])
                                           if event.get("previousPlayedAt") else None)
        favorite_at = favorites.get(track_id)
        if before and favorite_at and _date(favorite_at) <= before \
                and at - before >= timedelta(days=30) and at >= now_utc - timedelta(days=30):
            reheard.append({**event, "favoriteAt": favorite_at,
                            "previousPlayedAt": before.isoformat().replace("+00:00", "Z"),
                            "firstPlayedAt": entry_by_id.get(track_id, {}).get("firstPlayedAt")})
        previous[track_id] = at
    reheard.reverse()

    unique_source = {event["track"].get("sourceTrackId") or event["track"]["id"]
                     for event in events}
    unique_track = {event["track"]["id"] for event in events}
    unique_song = {event["songId"] for event in events if event.get("songId")}
    sections = {"weekly-first": weekly, "monthly-playlists": sorted(playlists.values(),
              key=lambda item: (-item["playCount"], item["playlistName"])),
              "reheard": reheard, "recent": recent}
    limits = {"weekly-first": 20, "monthly-playlists": 10, "reheard": 20, "recent": 30}
    return {"zone": zone_name, "generatedAt": now_utc.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
            "summary": {"totalPlays": sum(item["playCount"] for item in history["entries"]),
                        "thisMonthPlays": len(monthly_events), "sourceCount": len(unique_source),
                        "trackCount": len(unique_track), "songCount": len(unique_song),
                        "unknownFirstCount": sum("firstPlayedAt" not in item for item in history["entries"])},
            "sectionCounts": {key: len(value) for key, value in sections.items()},
            "sections": {key: value[:limits[key]] for key, value in sections.items()},
            "notes": library["diaryNotes"], "lyrics": library["lyricFavorites"]}


def diary_xml(view: dict[str, Any]) -> bytes:
    root = ElementTree.Element("diary", {"zone": view["zone"], "generatedAt": view["generatedAt"]})
    ElementTree.SubElement(root, "summary", {key: str(value) for key, value in view["summary"].items()})
    titles = {"weekly-first": "上周第一次听到", "monthly-playlists": "本月常听歌单",
              "reheard": "近期重听老收藏", "recent": "最近听过"}
    for section_id, title in titles.items():
        entries = view["sections"][section_id]
        section = ElementTree.SubElement(root, "section", {"id": section_id,
                                  "title": title, "count": str(view["sectionCounts"][section_id])})
        for item in entries:
            attributes = {key: (str(value).lower() if isinstance(value, bool) else str(value))
                          for key, value in item.items()
                          if key != "track" and value is not None}
            node = ElementTree.SubElement(section, "entry", attributes)
            _track(node, item["track"])
    notes = ElementTree.SubElement(root, "notes")
    for item in view["notes"]:
        attributes = {"trackId": item["track"]["id"]}
        attributes.update({field: item[field] for field in ("createdAt", "updatedAt") if field in item})
        node = ElementTree.SubElement(notes, "note", attributes)
        ElementTree.SubElement(node, "text").text = item["text"]
        _track(node, item["track"])
    lyrics = ElementTree.SubElement(root, "lyricFavorites")
    for item in view["lyrics"]:
        node = ElementTree.SubElement(lyrics, "lyric", {"id": item["id"],
            "trackId": item["track"]["id"], "positionMillis": str(item["positionMillis"]),
            "createdAt": item["createdAt"]})
        ElementTree.SubElement(node, "text").text = item["text"]
        _track(node, item["track"])
    return ElementTree.tostring(root, encoding="utf-8", xml_declaration=True)


def _track(parent: ElementTree.Element, track: dict[str, Any]) -> None:
    item = StoredTrack.from_value(track).to_dict()
    ElementTree.SubElement(parent, "track", {key: str(value) for key, value in item.items()
                                            if value is not None})
