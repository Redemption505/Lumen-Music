"""Synthetic, disposable library and real WAV for native practice acceptance."""

from pathlib import Path
import math
import struct
import sys
import uuid
import wave
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.library import LibraryStore


def prepare(root: Path) -> None:
    if "practice-smoke" not in str(root) or (root / "library.json").exists():
        raise ValueError("A fresh isolated practice-smoke directory is required")
    root.mkdir(parents=True, exist_ok=True)
    audio = root / "practice-fixture.wav"
    with wave.open(str(audio), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(11025)
        output.writeframes(b"".join(
            struct.pack("<h", int(350 * math.sin(2 * math.pi * 220 * frame / 11025)))
            for frame in range(45 * 11025)))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    background = root / "practice-background.png"
    background.write_bytes(b"\x89PNG\r\n\x1a\n"
                           + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(b"\x00\xd8\xef\xe4\xa0\xef\xe4" * 2))
                           + chunk(b"IEND", b""))
    (root / "preferences.properties").write_text(
        "theme=dark\nbackground=" + background.as_posix() + "\n", encoding="utf-8")

    source = {"id": "practice-source", "title": "Practice source", "artist": "Fixture",
              "source": "local", "durationSeconds": 45, "mediaUri": audio.as_uri()}
    segment = {**source, "id": "segment:" + str(uuid.uuid4()), "title": "Practice offset segment",
               "sourceTrackId": source["id"], "segmentStartMillis": 8000,
               "segmentEndMillis": 28000, "sourceDurationSeconds": 45, "durationSeconds": 20}
    second = {**source, "id": "practice-second", "title": "Practice second track"}
    library = LibraryStore(root / "library.json")
    library.create_playlist("Practice acceptance")
    playlist_id = library.snapshot()["playlists"][0]["id"]
    for track in (source, segment, second):
        library.add_to_playlist(playlist_id, track)
    library.add_lyric_favorite(segment, 6000, "Favorite resumes at six seconds within the segment")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
