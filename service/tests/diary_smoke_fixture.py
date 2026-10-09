"""Disposable, explicitly synthetic history for native diary acceptance."""

from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import math
import struct
import sys
import wave
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.library import LibraryStore
from lumen_service.history import HistoryStore


def prepare(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    background = root / "bright-background.png"
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    background.write_bytes(b"\x89PNG\r\n\x1a\n"
                           + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(b"\x00\xff\xff\xff\xa0\xef\xe4" * 2))
                           + chunk(b"IEND", b""))
    (root / "preferences.properties").write_text("background=" + background.as_posix() + "\n", encoding="utf-8")
    audio = root / "diary-fixture.wav"
    with wave.open(str(audio), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        data = b"".join(struct.pack("<h", int(350 * math.sin(2 * math.pi * 220 * i / 8000)))
                        for i in range(32 * 8000))
        output.writeframes(data)

    def track(identifier: str, title: str) -> dict:
        return {"id": identifier, "title": title, "artist": "隔离测试", "source": "local",
                "durationSeconds": 32, "mediaUri": audio.as_uri()}

    old = track("diary-old", "曾经收藏的歌曲：从这里重新听见熟悉的旋律与很长的曲目标题")
    weekly = track("diary-weekly", "上周第一次听到的歌曲")
    legacy = track("diary-legacy", "旧记录，首次时间保持未知")
    fresh = track("diary-new", "新收藏的歌曲")
    library = LibraryStore(root / "library.json")
    library.favorite(old)
    library.create_playlist("日记验收歌单")
    playlist_id = library.snapshot()["playlists"][0]["id"]
    for item in (old, fresh):
        library.add_to_playlist(playlist_id, item)
    state = library.snapshot()
    now = datetime.now(timezone.utc)
    state["favoriteAt"][old["id"]] = (now - timedelta(days=90)).isoformat()
    (root / "library.json").write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")

    history = HistoryStore(root / "history.json")
    history.record(old, 29, played_at=now - timedelta(days=65))
    local = now.astimezone(timezone(timedelta(hours=8)))
    this_monday = (local - timedelta(days=local.weekday())).replace(hour=12, minute=0, second=0, microsecond=0)
    history.record(weekly, 29, played_at=this_monday - timedelta(days=4))
    data = history.snapshot()
    data["entries"].append({"track": legacy, "playCount": 3,
                            "lastPlayedAt": (now - timedelta(days=2)).isoformat(),
                            "totalQualifiedSeconds": 90.0})
    (root / "history.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    (root / "fixture.json").write_text(json.dumps({"old": old, "fresh": fresh,
                                                  "playlistId": playlist_id}, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
