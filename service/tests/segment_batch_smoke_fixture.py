"""Disposable real WAVs and formal-library data for batch-segment acceptance."""

from pathlib import Path
import math
import struct
import sys
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.library import LibraryStore


def prepare(root: Path) -> None:
    if "segment-batch-smoke" not in str(root) or (root / "library.json").exists():
        raise ValueError("A fresh isolated segment-batch-smoke profile is required")
    root.mkdir(parents=True, exist_ok=True)
    for name, seconds, frequency in (("batch-source.wav", 32, 220), ("batch-baseline.wav", 96, 330)):
        with wave.open(str(root / name), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(11025)
            output.writeframes(b"".join(struct.pack("<h", int(350 * math.sin(
                2 * math.pi * frequency * frame / 11025))) for frame in range(seconds * 11025)))
    (root / "preferences.properties").write_text(
        "theme=dark\naudio.preload=false\naudio.shortFade=false\n", encoding="utf-8")
    store = LibraryStore(root / "library.json")
    source = {"id": "batch-source", "title": "Long synthetic collection", "artist": "Synthetic fixture",
              "source": "local", "durationSeconds": 32, "mediaUri": (root / "batch-source.wav").as_uri()}
    store.favorite(source)
    store.create_playlist("Batch acceptance baseline")
    playlist = store.snapshot()["playlists"][0]["id"]
    for suffix in ("first", "second"):
        store.add_to_playlist(playlist, {
            "id": "batch-baseline-" + suffix, "title": "Baseline " + suffix,
            "artist": "Synthetic fixture", "source": "local", "durationSeconds": 96,
            "mediaUri": (root / "batch-baseline.wav").as_uri(),
        })


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
