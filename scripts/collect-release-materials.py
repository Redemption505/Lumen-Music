"""Collect exact local release materials without downloading or changing a release.

Python 3.11+, standard library only. All output is private staging under build/.
The manifest describes available evidence; it is not a redistribution clearance.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tomllib
import zipfile


NOTICE_NAME = re.compile(r"^(?:licen[cs]e|copying|notice|notices|copyright|authors|thanks)(?:$|[._-])", re.I)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_hash(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def assert_ok(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def safe_member(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    assert_ok(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
              "Unsafe material member")
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-home", required=True, type=Path)
    parser.add_argument("--full-zip", required=True, type=Path)
    parser.add_argument("--verification", type=Path, default=Path("build/phase4-release-d/final-verification.json"))
    parser.add_argument("--output", type=Path, default=Path("build/phase4-public-e/materials"))
    parser.add_argument("--cargo-home", type=Path, default=Path.home() / ".cargo")
    parser.add_argument("--maven-home", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--jdk-home", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    app = args.app_home.resolve()
    out = args.output.resolve()
    assert_ok(out.is_relative_to(root / "build"), "Output must stay inside project build directory")
    assert_ok(not out.is_relative_to(app), "Output must not modify the inspected AppHome")
    assert_ok(not out.exists(), "Use a new output directory; existing materials are never overwritten")
    baseline = json.loads(args.verification.read_text(encoding="utf-8-sig"))
    full = args.full_zip.resolve()
    assert_ok(file_hash(full) == baseline["delivery"]["full"]["sha256"], "Full archive differs from D reference")
    jar = app / "app/lumen-desktop-0.2.2.jar"
    assert_ok(file_hash(jar) == baseline["mainJarSha256"], "AppHome main JAR differs from D reference")
    with zipfile.ZipFile(jar) as archive:
        props = archive.read("lumen-build.properties").decode("utf-8")
    assert_ok(re.search(r"(?m)^build\.id=" + re.escape(baseline["buildId"]) + r"\r?$", props) is not None,
              "BuildId differs from D reference")
    with zipfile.ZipFile(full) as archive:
        sources = [name for name in archive.namelist() if name.endswith("/SOURCE.zip") or name == "SOURCE.zip"]
        assert_ok(len(sources) == 1, "Expected one corresponding SOURCE.zip")
        source_data = archive.read(sources[0])
        assert_ok(digest(source_data) == baseline["correspondingSource"]["sha256"], "SOURCE differs from D reference")
        source = zipfile.ZipFile(io.BytesIO(source_data))
        source_names = source.namelist()
        assert_ok(len(source_names) == len(set(source_names)), "Duplicate SOURCE member")
        for relative in ("stream-proxy/Cargo.lock", "stream-proxy/Cargo.toml", "desktop/pom.xml"):
            assert_ok(digest(source.read(relative)) == file_hash(root / relative), "Current dependency input differs from D SOURCE")
        build_info = source.read("BUILD-INFO.txt").decode("utf-8-sig")
        assert_ok("BuildId=" + baseline["buildId"] in build_info, "SOURCE build identity mismatch")
        assert_ok("MainJarSha256=" + file_hash(jar) in build_info
                  and "StreamProxySha256=" + file_hash(app / "app/stream-proxy/lumen-stream-proxy.exe") in build_info
                  and "LauncherSha256=" + file_hash(app / "LumenMusic.exe") in build_info,
                  "SOURCE binary anchors differ from AppHome")
        icons = []
        with zipfile.ZipFile(jar) as application:
            for name in ("lumen.svg", "lumen-256.png", "lumen.ico"):
                relative = "desktop/src/main/resources/icons/" + name
                data = source.read(relative)
                icons.append({"path": relative, "sha256": digest(data),
                    "matchesCurrentSource": digest(data) == file_hash(root / relative),
                    "matchesShippedJar": digest(data) == digest(application.read("icons/" + name)),
                    "authorshipConfirmed": False})

    # Read the current Windows graph. The root path and Cargo warnings are not saved.
    command = ["cargo", "tree", "--manifest-path", str(root / "stream-proxy/Cargo.toml"),
               "--locked", "--offline", "--target", "x86_64-pc-windows-msvc", "-e", "normal",
               "--prefix", "none", "--format", "{p} {l}"]
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", check=False)
    assert_ok(process.returncode == 0, "Offline Windows normal dependency graph failed; no stderr values exported")
    graph = {}
    for line in process.stdout.splitlines():
        match = re.fullmatch(r"([A-Za-z0-9_-]+) v([^ ]+)(?: \(proc-macro\))? (.*?)(?: \(\*\))?", line.strip())
        if match and match.group(1) != "lumen-stream-proxy":
            graph[(match.group(1), match.group(2))] = match.group(3).strip()
    assert_ok(len(graph) > 6, "Unexpected empty or direct-only Windows graph")
    lock = tomllib.loads((root / "stream-proxy/Cargo.lock").read_text(encoding="utf-8"))
    locked = {(item["name"], item["version"]): item for item in lock["package"] if "source" in item}
    assert_ok(set(graph).issubset(locked), "Graph does not match frozen lock")

    out.mkdir(parents=True)
    materials = []

    def put(relative: str, data: bytes, origin: str, component: str, role: str) -> dict:
        member = safe_member(relative)
        target = out.joinpath(*member.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        item = {"path": relative, "bytes": len(data), "sha256": digest(data), "origin": origin,
                "component": component, "role": role}
        materials.append(item)
        return item

    def existing(relative: str, component: str) -> None:
        put("existing/" + relative, (app / relative).read_bytes(), "AppHome/" + relative,
            component, "already-in-D-image")

    for path in sorted((app / "runtime/legal").rglob("*")):
        if path.is_file():
            existing(path.relative_to(app).as_posix(), "JDK 23.0.2")
    for relative, component in (("app/python/LICENSE.txt", "Python 3.13.15"),
                                ("app/vlc/COPYING.txt", "VLC 3.0.23")):
        existing(relative, component)
    existing("runtime/release", "JDK 23.0.2")
    put("existing/project/GPL-3.0.txt", (root / "release/licenses/GPL-3.0.txt").read_bytes(),
        "release/licenses/GPL-3.0.txt", "Lumen", "already-in-D-release")

    jars = []
    groups = {"javafx": ("org", "openjfx"), "jna": ("net", "java", "dev", "jna"),
              "vlcj": ("uk", "co", "caprica")}
    for path in sorted((app / "app").glob("*.jar")):
        entry = {"path": path.relative_to(app).as_posix(), "sha256": file_hash(path), "notices": [],
                 "nativeMembers": []}
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if NOTICE_NAME.match(PurePosixPath(name).name) and not name.endswith("/"):
                    entry["notices"].append(put("existing/jars/" + path.name + "/" + name,
                        archive.read(name), "AppHome/" + entry["path"] + "!/" + name,
                        path.name, "already-inside-D-JAR")["path"])
                if name.lower().endswith((".dll", ".so", ".dylib", ".jnilib")):
                    entry["nativeMembers"].append({"path": name, "sha256": digest(archive.read(name))})
        match = re.fullmatch(r"(.+)-([0-9]+\.[0-9]+\.[0-9]+)(-win)?\.jar", path.name)
        if match and not path.name.startswith("lumen-"):
            artifact, version, classifier = match.groups()
            group = next(value for key, value in groups.items() if artifact.startswith(key))
            cached = args.maven_home.joinpath(*group, artifact, version, path.name)
            entry["cachedBinaryMatches"] = cached.is_file() and file_hash(cached) == entry["sha256"]
            pom = cached.with_name(artifact + "-" + version + ".pom")
            if pom.is_file() and entry["cachedBinaryMatches"]:
                put("supplemented/maven/" + artifact + "/" + pom.name, pom.read_bytes(),
                    "MavenCache/" + "/".join(group) + "/" + artifact + "/" + version + "/" + pom.name,
                    path.name, "exact-cached-published-metadata")
            source_jar = cached.with_name(artifact + "-" + version + "-sources.jar")
            entry["sourceJarCached"] = source_jar.is_file()
        jars.append(entry)

    archives = []
    for name in sorted(source_names):
        if name.startswith("third-party/") and not name.endswith("/"):
            data = source.read(name)
            archives.append(put("existing/" + name, data, "D-SOURCE.zip!/" + name,
                                PurePosixPath(name).name, "already-in-D-SOURCE"))
    source.close()

    rust = []
    cache_roots = sorted((args.cargo_home / "registry/cache").glob("*"))
    for (name, version), license_expression in sorted(graph.items()):
        item = {"name": name, "version": version, "license": license_expression,
                "lockChecksum": locked[(name, version)]["checksum"], "materials": [],
                "sourceUrl": "https://crates.io/api/v1/crates/" + name + "/" + version + "/download"}
        candidates = []
        for registry in cache_roots:
            cached = registry / (name + "-" + version + ".crate")
            if cached.is_file() and file_hash(cached) == item["lockChecksum"]:
                candidates.append(cached)
        if not candidates:
            item["status"] = "exact-cache-not-found"
            rust.append(item)
            continue
        cached = candidates[0]
        # Read the hash-verified .crate itself. Expanded caches may lack per-file checksums.
        with tarfile.open(cached, "r:gz") as archive:
            prefix = name + "-" + version + "/"
            members = {member.name.removeprefix(prefix): member for member in archive.getmembers()
                       if member.isfile() and member.name.startswith(prefix)}
            metadata = tomllib.loads(archive.extractfile(members["Cargo.toml"]).read().decode("utf-8"))["package"]
            assert_ok(metadata["name"] == name and metadata["version"] == version, "Cached package identity mismatch")
            item["cachedLicense"] = metadata.get("license")
            item["licenseFile"] = metadata.get("license-file")
            paths = {relative for relative in members if NOTICE_NAME.match(PurePosixPath(relative).name)}
            if item["licenseFile"]:
                paths.add(item["licenseFile"])
            for relative in sorted(paths):
                safe_member(relative)
                assert_ok(relative in members, "Declared license file missing from locked archive")
                item["materials"].append(put("supplemented/rust/" + name + "-" + version + "/" + relative,
                    archive.extractfile(members[relative]).read(), "LockedCargoCrate/" + cached.name + "!/" + prefix + relative,
                    name + " " + version, "exact-locked-cache-license-notice")["path"])
        item["status"] = "license-notice-collected" if item["materials"] else "named-license-material-missing"
        # An archive is proof of source availability only, not proof of final linkage.
        item["sourceArchiveCachedAndChecksumMatches"] = True
        rust.append(item)
    put("rust-windows-normal.txt", ("\n".join(name + " v" + version + " " + graph[(name, version)]
        for name, version in sorted(graph)) + "\n").encode("utf-8"),
        "cargo tree --locked --offline --target x86_64-pc-windows-msvc -e normal --prefix none --format {p} {l}",
        "Rust Windows normal graph", "fresh-normal-graph-deduplicated-including-proc-macro")

    # A reusable standard text is supplemental, not a JNA/native attribution claim.
    apache = next((entry for entry in materials if entry["path"].endswith("/atomic-waker-1.1.2/LICENSE-APACHE")), None)
    jna_plan = {"proposedJavaLicenseChoice": "Apache-2.0", "nativeAttributionComplete": False,
                "fullStandardTextAvailable": False,
                "status": "prepared-text-candidate-native-review-open"}
    if apache:
        data = (out / apache["path"]).read_bytes()
        assert_ok(b"Apache License" in data and b"Version 2.0, January 2004" in data,
                  "Expected complete Apache text")
        candidate = put("supplemented/common-license-texts/Apache-2.0.txt", data, apache["origin"],
            "Standard Apache-2.0 text", "full-text-candidate-no-component-attribution")
        jna_plan.update({"fullStandardTextAvailable": True, "textPath": candidate["path"]})
    jna_plan["exactSourceUrls"] = ["https://repo.maven.apache.org/maven2/net/java/dev/jna/" + artifact
        + "/" + version + "/" + artifact + "-" + version + "-sources.jar"
        for artifact, version in (("jna", "5.13.0"), ("jna-platform", "5.13.0"),
                                  ("jna-jpms", "5.14.0"), ("jna-platform-jpms", "5.14.0"))]

    jdk = {"version": "23.0.2", "originConfirmedByMatchingModules": False,
           "fullCorrespondingSourceAvailable": False}
    jdk_home = args.jdk_home
    if jdk_home is None:
        executable = shutil.which("jpackage")
        if executable:
            jdk_home = Path(executable).resolve().parents[1]
    if jdk_home and (jdk_home / "release").is_file():
        release = (jdk_home / "release").read_text(encoding="utf-8")
        release_fields = dict(re.findall(r'^([A-Z_]+)="(.*)"$', release, re.M))
        matches = []
        for relative in (path.relative_to(app).as_posix() for path in sorted((app / "runtime/legal").rglob("*")) if path.is_file()):
            cached = jdk_home / relative.removeprefix("runtime/")
            matches.append(cached.is_file() and file_hash(cached) == file_hash(app / relative))
        # java.dll anchors the binary runtime, in addition to license text and version.
        dlls = ["java.dll", "jvm.dll"]
        binary_matches = []
        for name in dlls:
            suffix = Path("bin/server") / name if name == "jvm.dll" else Path("bin") / name
            original, bundled = jdk_home / suffix, app / "runtime" / suffix
            binary_matches.append(original.is_file() and bundled.is_file() and file_hash(original) == file_hash(bundled))
        confirmed = (release_fields.get("JAVA_VERSION") == "23.0.2" and bool(matches)
                     and all(matches) and all(binary_matches))
        jdk.update({"originConfirmedByMatchingModules": confirmed, "legalFileMatches": sum(matches),
                    "runtimeCoreBinaryMatches": all(binary_matches)})
        if confirmed:
            jdk["cachedJdkIdentity"] = {key: release_fields.get(key) for key in
                ("IMPLEMENTOR", "JAVA_VERSION", "JAVA_RUNTIME_VERSION", "SOURCE", "OS_ARCH", "OS_NAME")}
            put("supplemented/jdk/full-jdk-release.txt", release.encode("utf-8"), "MatchingLocalJDK/release",
                "JDK 23.0.2", "local-provenance-evidence-not-original-download-proof")
            src = jdk_home / "lib/src.zip"
            if src.is_file():
                jdk["cachedJavaSourceZip"] = {"bytes": src.stat().st_size, "sha256": file_hash(src),
                    "included": False, "limitation": "Java sources only; not a complete OpenJDK/native build source delivery"}

    vlc = {"version": "3.0.23", "pluginFileCount": sum(path.is_file() for path in (app / "app/vlc/plugins").rglob("*")),
           "fullCorrespondingSourceAvailable": False, "perPluginLicenseMappingComplete": False}
    vlc_zip = root / "build/tools/vlc-3.0.23-win64.zip"
    if vlc_zip.is_file():
        with zipfile.ZipFile(vlc_zip) as archive:
            prefix = "vlc-3.0.23/"
            vlc_matches = []
            for path in sorted((app / "app/vlc").rglob("*")):
                if path.is_file():
                    member = prefix + path.relative_to(app / "app/vlc").as_posix()
                    vlc_matches.append(member in archive.namelist() and digest(archive.read(member)) == file_hash(path))
            vlc["cachedWindowsZipMatchesAllShippedFiles"] = bool(vlc_matches) and all(vlc_matches)
            vlc["cachedWindowsZipSha256"] = file_hash(vlc_zip)
            if vlc["cachedWindowsZipMatchesAllShippedFiles"]:
                for name in ("AUTHORS.txt", "THANKS.txt"):
                    member = prefix + name
                    if member in archive.namelist():
                        put("supplemented/vlc/" + name, archive.read(member),
                            "MatchingCachedVLCWindowsZip!/" + member, "VLC 3.0.23",
                            "upstream-authors-acknowledgments-not-plugin-license-mapping")

    image_inventory = [{"path": path.relative_to(app).as_posix(), "sha256": file_hash(path),
                        "bytes": path.stat().st_size} for path in sorted(app.rglob("*")) if path.is_file()]
    manifest = {"schema": 1, "status": "local-materials-prepared-with-open-items", "privateEvidenceOnly": True,
        "publicRedistributionCleared": False, "buildId": baseline["buildId"],
        "identity": {"mainJarSha256": file_hash(jar), "fullArchiveSha256": file_hash(full),
                     "sourceZipSha256": digest(source_data), "frozenDependencyInputsMatchCurrent": True},
        "collectorSha256": file_hash(Path(__file__)), "imageFiles": image_inventory,
        "jars": jars, "existingSourceArchives": archives, "jdk": jdk, "vlc": vlc,
        "jnaLicensePlan": jna_plan, "projectIcons": icons,
        "rust": {"target": "x86_64-pc-windows-msvc", "edgeScope": "normal including proc-macro dependencies",
                 "lockExternalCount": len(locked), "windowsNormalUniqueCount": len(rust),
                 "licenseMaterialCollectedCount": sum(item["status"] == "license-notice-collected" for item in rust),
                 "scopeLimit": "Dependency graph is not proof each crate is linked into the executable; build-only closure not audited",
                 "packages": rust}, "materials": sorted(materials, key=lambda item: item["path"]),
        "openItems": ["JavaFX exact-version license/native attribution and source materials",
            "JDK full corresponding sources and original download provenance",
            "VLC plugin/native component license mapping and full corresponding sources/build materials",
            "JNA chosen license full text and embedded native component attribution",
            "Rust final linkage/build-only component material audit and selected license scheme",
            "Project icon authorship and source evidence; public media/screenshots permissions",
            "Bilibili and lyrics source terms/content scope; independent friend trials"],
        "releaseArchiveModified": False, "uploaded": False}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [item["sha256"] + "  " + item["path"] for item in manifest["materials"]]
    lines.append(file_hash(out / "manifest.json") + "  manifest.json")
    (out / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"status": manifest["status"], "buildId": manifest["buildId"],
        "materialFiles": len(materials), "imageFiles": len(image_inventory),
        "windowsNormalCrates": len(rust), "rustCollected": manifest["rust"]["licenseMaterialCollectedCount"],
        "jdkOriginMatched": jdk["originConfirmedByMatchingModules"],
        "output": out.relative_to(root).as_posix()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
