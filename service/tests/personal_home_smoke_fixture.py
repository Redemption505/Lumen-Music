"""Synthetic ZIP fixture; the actual smoke service starts with an empty library."""

from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import math
import struct
import sys
import wave
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.backup import export_backup
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore


def prepare(root: Path) -> None:
    seed = root / "synthetic-seed"
    seed.mkdir(parents=True, exist_ok=True)
    background = root / "synthetic-bright-background.png"
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    background.write_bytes(b"\x89PNG\r\n\x1a\n"
                           + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(b"\x00\xff\xff\xff\xa0\xef\xe4" * 2))
                           + chunk(b"IEND", b""))
    audio = root / "synthetic-home.wav"
    # Quiet, ten-minute synthetic tone; never imports or plays a user's file.
    with wave.open(str(audio), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        one_second = b"".join(struct.pack("<h", int(180 * math.sin(2 * math.pi * 220 * i / 8000)))
                              for i in range(8000))
        for _ in range(600):
            output.writeframesraw(one_second)

    def track(identifier: str, title: str) -> dict:
        return {"id": identifier, "title": title, "artist": "合成验收 Fixture",
                "source": "local", "durationSeconds": 600, "mediaUri": audio.as_uri()}

    first = track("home-first", "继续听 · 中文与 English 的合成旋律")
    second = track("home-second", "下一首 · 隔离验收")
    old = track("home-reunion", "今日重逢 · 曾经听过的合成旋律")
    library = LibraryStore(seed / "library.json")
    library.favorite(old)
    library.favorite(first)
    library.create_playlist("通勤 · 中文与 English 的长歌单名称 " * 5)
    playlist_id = library.snapshot()["playlists"][0]["id"]
    library.add_to_playlist(playlist_id, first)
    library.add_to_playlist(playlist_id, second)
    library.create_playlist("睡前 · 第二个常驻")
    snapshot = library.snapshot()
    playlist = next(item for item in snapshot["playlists"] if item["id"] == playlist_id)
    playlist["tracks"].extend(track(f"home-row-{i}", f"曲库曲目 {i} · 首页只显示小卡")
                              for i in range(2, 200))
    now = datetime.now(timezone.utc)
    snapshot["favoriteAt"][old["id"]] = (now - timedelta(days=100)).isoformat()
    (seed / "library.json").write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
    history = HistoryStore(seed / "history.json")
    history.record(old, 40, played_at=now - timedelta(days=65))
    history.record(old, 40, played_at=now - timedelta(days=2))
    local = now.astimezone(timezone(timedelta(hours=8)))
    monday = (local - timedelta(days=local.weekday())).replace(hour=12, minute=0, second=0, microsecond=0)
    history.record(second, 40, played_at=monday - timedelta(days=4))
    export_backup(root / "synthetic-home.zip", seed / "library.json", history_path=seed / "history.json")
    # Live paths deliberately remain absent: first startup must have no history.
    (root / "preferences.properties").write_text("theme=dark\npet=HIDDEN\nsidebar.collapsed=false\nsidebar.width=176\nbackground="
                                                   + background.as_posix() + "\n",
                                                   encoding="utf-8")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
