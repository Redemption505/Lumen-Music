"""Isolated installed-JAR layout, synthetic future candidates and privacy canaries."""

from datetime import datetime, timezone
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import json
import math
import os
import shutil
import struct
import sys
import uuid
import wave
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lumen_service.ai_settings import AiSettingsStore
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore


def config(version: str) -> bytes:
    return ("[Application]\napp.mainclass=com.lumenmusic.LumenLauncher\n"
            f"app.classpath=$APPDIR\\lumen-desktop-{version}.jar\n"
            f"[JavaOptions]\njava-options=-Djpackage.app-version={version}\n").encode()


def prepare(root: Path, jar: Path, launcher: Path) -> None:
    if "update-feedback-smoke" not in str(root) or (root / "library.json").exists():
        raise ValueError("A fresh isolated update-feedback-smoke profile is required")
    with ZipFile(jar) as archive:
        metadata = archive.read("lumen-build.properties").decode()
        if "version=0.2.0" not in metadata:
            raise ValueError("Production JAR must actually contain 0.2.0 build metadata")
    install = root / "install"
    app = install / "app"
    app.mkdir(parents=True)
    shutil.copy2(jar, app / "lumen-desktop-0.2.0.jar")
    # The copied launcher identifies the layout only; the smoke never executes it.
    shutil.copy2(launcher, install / "LumenMusic.exe")
    (app / "LumenMusic.cfg").write_bytes(config("0.2.0"))
    (app / ".jpackage.xml").write_text('<jpackage-state><app-version>0.2.0</app-version></jpackage-state>', encoding="utf-8")
    (install / "runtime").mkdir()
    runtime = b"SYNTHETIC_RUNTIME_REQUIREMENT_FOR_PREFLIGHT_ONLY\n"
    (install / "runtime/release").write_bytes(runtime)
    with wave.open(str(root / "feedback-baseline.wav"), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(11025)
        second = b"".join(struct.pack("<h", int(300 * math.sin(2 * math.pi * 220 * frame / 11025)))
                          for frame in range(11025))
        output.writeframes(second * 120)
    track = {"id": "update-private-history-canary", "title": "PRIVATE_TITLE_CANARY", "artist": "PRIVATE_ARTIST_CANARY",
             "source": "local", "durationSeconds": 120, "mediaUri": (root / "feedback-baseline.wav").as_uri()}
    LibraryStore(root / "library.json").favorite(track)
    HistoryStore(root / "history.json").record(track, 40, played_at=datetime.now(timezone.utc),
                                               playlist_id="private-history-playlist", playlist_name="PRIVATE_HISTORY_CANARY")
    AiSettingsStore(root / "ai-settings.json").save(enabled=False, model="",
        base_url=os.environ["LUMEN_SERVICE_URL"] + "/unused-ai", api_key="canaryKey")
    (root / "private-note.txt").write_text("PRIVATE_NOTE_CANARY", encoding="utf-8")
    (root / "preferences.properties").write_text(
        "theme=dark\naudio.preload=false\naudio.shortFade=false\n", encoding="utf-8")
    future = BytesIO()
    with ZipFile(future, "w", ZIP_DEFLATED) as archive:
        archive.writestr("lumen-build.properties", "version=0.2.1\nbuild.id=smoke-future\n")
    payload = {
        ("root", "LumenMusic.exe"): launcher.read_bytes(),
        ("app", "lumen-desktop-0.2.1.jar"): future.getvalue(),
        ("app", "LumenMusic.cfg"): config("0.2.1"),
        ("app", ".jpackage.xml"): b'<jpackage-state><app-version>0.2.1</app-version></jpackage-state>',
        ("app", "stream-proxy/lumen-stream-proxy.exe"): b"SYNTHETIC_PROXY_PREFLIGHT_ONLY",
        ("app", "service/lumen_service/__main__.py"): b"# Synthetic preflight only, not executable update acceptance\n",
        ("app", "service/lumen_service/__init__.py"): b"# Synthetic future metadata\n",
    }
    candidates = root / "candidates"
    candidates.mkdir()
    for kind in ("valid-future", "wrong-from", "bad-hash", "missing-manifest"):
        from_version = "0.1.1" if kind == "wrong-from" else "0.2.0"
        manifest = ET.Element("update", format="1", app="LumenMusic", platform="windows", arch="x64",
                              fromVersion=from_version, toVersion="0.2.1", oldJar=f"lumen-desktop-{from_version}.jar",
                              newJar="lumen-desktop-0.2.1.jar", buildId="smoke-future", packageId=str(uuid.uuid4()))
        files = ET.SubElement(manifest, "files")
        for (scope, name), data in payload.items():
            digest = "0" * 64 if kind == "bad-hash" and name.endswith("0.2.1.jar") else sha256(data).hexdigest()
            ET.SubElement(files, "file", scope=scope, path=name, sha256=digest, size=str(len(data)))
        requirements = ET.SubElement(manifest, "requirements")
        ET.SubElement(requirements, "file", path="runtime/release", sha256=sha256(runtime).hexdigest(), size=str(len(runtime)))
        with ZipFile(candidates / f"{kind}.zip", "w", ZIP_DEFLATED) as archive:
            if kind != "missing-manifest":
                archive.writestr("manifest.xml", ET.tostring(manifest, encoding="utf-8", xml_declaration=True))
            for (scope, name), data in payload.items():
                archive.writestr("payload/" + ("root/" if scope == "root" else "") + name, data)
    (root / "fixture-scope.json").write_text(json.dumps({"installedJarVersion": "0.2.0", "candidateVersion": "0.2.1",
        "syntheticCandidate": True, "nativeLauncherExecuted": False, "upgradeExecuted": False}), encoding="utf-8")


if __name__ == "__main__":
    prepare(*(Path(value).resolve() for value in sys.argv[1:4]))
