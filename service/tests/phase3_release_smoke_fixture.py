"""Build a disposable profile using the frozen v0.1.1 library serializer."""

from pathlib import Path
import hashlib
import json
import math
import struct
import sys
import wave


def prepare(root: Path, frozen_service: Path) -> None:
    if "phase3-release-smoke" not in str(root) or (root / "library.json").exists():
        raise ValueError("A fresh isolated phase3-release-smoke profile is required")
    old_source = frozen_service / "lumen_service" / "library.py"
    if not old_source.is_file():
        raise ValueError("Frozen v0.1.1 service/library.py is required")
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(frozen_service))
    from lumen_service.library import LibraryStore, StoredTrack

    # Reject accidental use of the current implementation as legacy evidence.
    if Path(sys.modules[LibraryStore.__module__].__file__).resolve() != old_source.resolve():
        raise ValueError("The frozen library implementation was not imported")
    root.mkdir(parents=True, exist_ok=True)
    with wave.open(str(root / "collection.wav"), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(11025)
        output.writeframes(b"".join(struct.pack("<h", int(400 * math.sin(
            2 * math.pi * 220 * frame / 11025))) for frame in range(96 * 11025)))

    def track(identifier: str, title: str) -> dict:
        return StoredTrack.from_value({
            "id": identifier, "title": title, "artist": "Release fixture",
            "durationSeconds": 96, "source": "local",
            "mediaUri": (root / "collection.wav").as_uri(),
        }).to_dict()

    source = track("release-old-source", "Legacy collection")
    manual = track("release-old-manual", "Legacy arranged song")
    state = {"version": 1, "favorites": [source, manual], "playlists": [
        {"id": "release-old-playlist", "name": "Legacy playlist", "tracks": [source, manual]},
        {"id": "release-performance", "name": "2000 tracks", "tracks": [
            track(f"perf-{index:04d}", f"Performance song {index:04d}") for index in range(2000)]},
    ]}
    encoded = LibraryStore._encode(LibraryStore._decode(json.dumps(state)))
    (root / "library.json").write_bytes(encoded)
    (root / "legacy-library-original.json").write_bytes(encoded)
    (root / "preferences.properties").write_text(
        "theme=light\naccent=PINK\naudio.normalization=true\n", encoding="ascii")
    (root / "legacy-evidence.json").write_text(json.dumps({
        "origin": "frozen v0.1.1 LibraryStore._decode/_encode and StoredTrack.from_value",
        "source": str(old_source.resolve()),
        "sourceSha256": hashlib.sha256(old_source.read_bytes()).hexdigest(),
        "profileSha256": hashlib.sha256(encoded).hexdigest(),
        "legacySettings": {"theme": "light", "accent": "PINK", "audio.normalization": "true"},
        "limitations": "Synthetic old-format collection and playlist; new segments/versions/diary are created by the release UI.",
    }, indent=2), encoding="utf-8")
    print(f"PASS frozen legacy serializer: {old_source}; 2000-track isolated playlist")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
