"""Compare candidate audio class bytes and production playback methods using only stdlib/JDK."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile


def digest(value):
    return hashlib.sha256(value).hexdigest()


def identity(jar, javap, project, output):
    jar = jar.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        classes = {name: digest(archive.read(name)) for name in sorted(archive.namelist()) if name.endswith(".class")}
    result = subprocess.run([str(javap), "-classpath", str(jar), "-c", "-p", "com.lumenmusic.LumenFxApp"],
                            capture_output=True, check=True)
    text = result.stdout.decode("utf-8", errors="replace")
    (output / "LumenFxApp-javap.txt").write_text(text, encoding="utf-8")
    methods = {}
    signature = None
    body = []

    def save():
        if signature is not None:
            # Symbolic javap comments are retained. Only pool/bootstrap/lambda indices,
            # which can shift when an unrelated method changes, are canonicalized.
            canonical = re.sub(r"#\d+", "#INDEX", "\n".join(body))
            canonical = re.sub(r"(lambda\$[\w]+\$)\d+", r"\1INDEX", canonical)
            key = re.sub(r"(lambda\$[\w]+\$)\d+", r"\1INDEX", signature)
            methods.setdefault(key, []).append(digest(canonical.encode("utf-8")))

    for line in text.splitlines():
        if re.match(r"^  .+\([^)]*\);$", line):
            save()
            signature, body = line.strip(), []
        elif signature is not None:
            body.append(line.rstrip())
    save()
    sources = [project / "desktop/src/main/java/com/lumenmusic" / name for name in
               ("PlayerQueue.java", "Track.java", "SegmentTimeline.java", "PlaybackEnvelope.java", "PlaybackStateStore.java")]
    sources.extend(sorted((project / "desktop/src/main/java/com/lumenmusic/audio").glob("*.java")))
    record = {"jar": str(jar), "jarSha256": digest(jar.read_bytes()), "classes": classes,
              "LumenFxAppMethods": methods,
              "observedSourceHashes": {str(p.relative_to(project)): digest(p.read_bytes()) for p in sources if p.is_file()},
              "sourceHashScope": "Observed current audio sources; main application baseline source identity is separately recorded by release source freeze. Class/method identities come from actual candidate JAR bytes."}
    (output / "identity.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    return record


def compare(previous, current):
    audio_prefixes = ("com/lumenmusic/audio/", "com/lumenmusic/PlayerQueue", "com/lumenmusic/Track$",
                      "com/lumenmusic/Track.class", "com/lumenmusic/SegmentTimeline", "com/lumenmusic/PlaybackEnvelope",
                      "com/lumenmusic/PlaybackStateStore", "com/lumenmusic/MusicClient", "com/lumenmusic/ListeningReporter")
    old, new = previous["classes"], current["classes"]
    changed = [name for name in sorted(old.keys() | new.keys()) if old.get(name) != new.get(name)]
    playback_methods = ("select", "loadCurrent", "seekToFraction", "playbackPosition", "playbackDuration", "switchAudio",
                        "playOnlineUri", "fallbackSeekToLocal", "loadAudioRange", "onPlaybackState", "onAudioError", "stop")
    old_methods, new_methods = previous["LumenFxAppMethods"], current["LumenFxAppMethods"]
    selected = sorted(key for key in old_methods.keys() | new_methods.keys() if any(
        re.search(r"(?:\s|lambda\$)" + re.escape(name) + r"(?:\(|\$)", key) for name in playback_methods))
    changed_methods = [key for key in selected if old_methods.get(key) != new_methods.get(key)]
    old_sources, new_sources = previous["observedSourceHashes"], current["observedSourceHashes"]
    changed_sources = [key for key in sorted(old_sources.keys() | new_sources.keys()) if old_sources.get(key) != new_sources.get(key)]
    audio_changes = [name for name in changed if name.startswith(audio_prefixes)]
    return {"baselineJar": previous["jar"], "baselineJarSha256": previous["jarSha256"],
            "candidateJar": current["jar"], "candidateJarSha256": current["jarSha256"],
            "allChangedClasses": changed, "audioClassByteChanges": audio_changes,
            "checkedPlaybackMethodSignatures": selected, "changedPlaybackMethods": changed_methods,
            "changedObservedAudioSources": changed_sources,
            "audioIdentitySupportsLongPlaybackEvidenceReuse": not (audio_changes or changed_methods or changed_sources),
            "scope": "Static change-impact evidence only; baseline long playback is not a fresh 60-minute run on this candidate. Dynamic short scenarios must be recorded separately."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=pathlib.Path, required=True)
    parser.add_argument("--javap", type=pathlib.Path, required=True)
    parser.add_argument("--project", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--baseline", type=pathlib.Path)
    args = parser.parse_args()
    record = identity(args.jar, args.javap, args.project.resolve(), args.output)
    if args.baseline:
        report = compare(json.loads(args.baseline.read_text(encoding="utf-8-sig")), record)
        (args.output / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("Recorded actual candidate class/method identities: " + str(args.output / "identity.json"))


if __name__ == "__main__":
    main()
