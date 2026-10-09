"""Disposable §3.3 fixture. No personal media or external providers are used."""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import json
import math
import struct
import sys
import wave
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.library import LibraryStore
from lumen_service.history import HistoryStore


def png(path: Path, width: int, height: int, color: tuple[int, int, int]) -> None:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            # Distinct borders make cropped corners and wrong aspect ratios visible.
            rows.extend((255, 255, 255) if x < 8 or y < 8 or x >= width - 8 or y >= height - 8 else color)
    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def prepare(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    audio = root / "synthetic-memory.wav"
    with wave.open(str(audio), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        second = b"".join(struct.pack("<h", int(160 * math.sin(2 * math.pi * 220 * i / 8000))) for i in range(8000))
        for _ in range(600):
            output.writeframesraw(second)
    colors = [(24, 160, 150), (220, 70, 80), (65, 105, 220), (220, 160, 30)]
    for index, color in enumerate(colors):
        png(root / f"synthetic-cover-{index}.png", 64, 64, color)
    png(root / "synthetic-wide-cover.png", 640, 320, colors[0])
    tracks = [{"id": f"memory-{i}", "title": f"记忆旋律 {i} · 中文与 English",
               "artist": "隔离验收 Fixture", "source": "local", "durationSeconds": 600,
               "mediaUri": audio.as_uri()} for i in range(4)]
    library = LibraryStore(root / "library.json")
    for track in tracks:
        library.favorite(track)
    snapshot = library.snapshot()
    now = datetime.now(timezone.utc)
    today = now.isoformat().replace("+00:00", "Z")
    previous = (now - timedelta(days=2)).isoformat().replace("+00:00", "Z")
    snapshot["diaryNotes"] = [
        {"track": tracks[0], "text": "旧数据保留的私人记忆：日期未知，不猜测时间。"},
        {"track": tracks[1], "text": "今天的长备注 · 中文与 English\n" + "雨声中记下旋律与回忆，保持文字可读。" * 35,
         "createdAt": today, "updatedAt": today},
        {"track": tracks[2], "text": "前两天的记忆", "createdAt": previous, "updatedAt": previous},
    ]
    snapshot["lyricFavorites"] = [
        {"id": "memory-lyric-today", "track": tracks[0], "positionMillis": 4200,
         "createdAt": today, "text": "记住这句旋律 · 中文与 English\n" + "这一句值得反复听。" * 20},
        {"id": "memory-lyric-old", "track": tracks[2], "positionMillis": 8500,
         "createdAt": previous, "text": "前两天收藏的歌词"},
    ]
    (root / "library.json").write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
    history = HistoryStore(root / "history.json")
    history.record(tracks[0], 32, played_at=now - timedelta(days=2))
    history.record(tracks[1], 32, played_at=now)
    (root / "preferences.properties").write_text(
        "theme=dark\npet=HIDDEN\nsidebar.collapsed=false\nsidebar.width=176\n", encoding="utf-8")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
