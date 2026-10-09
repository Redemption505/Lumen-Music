"""Disposable local WAV/library for continuous playback native acceptance."""

from pathlib import Path
import math
import struct
import sys
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.library import LibraryStore


def prepare(root: Path) -> None:
    if "continuous-playback-smoke" not in str(root) or (root / "library.json").exists():
        raise ValueError("A fresh isolated continuous-playback-smoke directory is required")
    root.mkdir(parents=True, exist_ok=True)
    audio = root / "continuous-fixture.wav"
    with wave.open(str(audio), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(22050)
        output.writeframes(b"".join(
            struct.pack("<h", int(350 * math.sin(2 * math.pi * 220 * frame / 22050)))
            for frame in range(32 * 22050)))
    (root / "preferences.properties").write_text("theme=dark\n", encoding="utf-8")
    library = LibraryStore(root / "library.json")
    library.create_playlist("Continuous acceptance")
    playlist = library.snapshot()["playlists"][0]["id"]
    for suffix in ("first", "second"):
        library.add_to_playlist(playlist, {
            "id": "continuous-local-" + suffix, "title": "Continuous local " + suffix,
            "artist": "Synthetic fixture", "source": "local", "durationSeconds": 32,
            "mediaUri": audio.as_uri(),
        })


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
