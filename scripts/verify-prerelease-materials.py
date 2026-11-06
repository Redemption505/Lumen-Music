"""Verify a candidate's exact files and reviewed native-material catalog offline.

Python 3.11+, standard library only. No downloads, extraction, application launch
or release writes. Hash agreement verifies evidence integrity, not legal advice.
Use --require-release-ready at the native-material gate; open reviews fail it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile


NATIVE_COMPONENTS = {"jdk", "javafx", "python", "vlc", "jna", "vlcj", "rust"}
ROLES = {"license", "notice", "source", "build-metadata", "reference"}


def safe_file(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative:
        raise ValueError("Expected a nonempty relative file path")
    path = PurePosixPath(relative)
    if path.is_absolute() or any(p in ("..", ".") for p in relative.split("/")) or "\\" in relative or ":" in relative:
        raise ValueError("Unsafe catalog path")
    base = root.resolve()
    result = base.joinpath(*path.parts)
    if not result.resolve().is_relative_to(base):
        raise ValueError("Catalog path escapes its root")
    node = result
    while node != base:
        if node.is_symlink():
            raise ValueError("Catalog path uses a symbolic link")
        node = node.parent
    return result


def file_hash(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_catalog(catalog: dict, app_home: Path, materials_root: Path) -> dict:
    if catalog.get("schemaVersion") != 1:
        raise ValueError("Unsupported catalog schema")
    errors, open_items, verified = [], [], []
    image = catalog["image"]
    expected = {}
    for entry in image["files"]:
        relative = entry["path"]
        key = relative.casefold()
        if key in expected:
            raise ValueError("Duplicate candidate file")
        expected[key] = relative
        path = safe_file(app_home, relative)
        if not path.is_file() or file_hash(path) != entry["sha256"] or path.stat().st_size != entry["bytes"]:
            errors.append({"code": "CANDIDATE_FILE_MISMATCH", "path": relative})
    actual = {p.relative_to(app_home).as_posix().casefold() for p in app_home.rglob("*") if p.is_file()}
    if actual != set(expected):
        errors.append({"code": "CANDIDATE_INVENTORY_CHANGED", "extraCount": len(actual-set(expected)),
                       "missingCount": len(set(expected)-actual)})
    jar_path = safe_file(app_home, image["mainJar"])
    with zipfile.ZipFile(jar_path) as jar:
        props = jar.read("lumen-build.properties").decode("utf-8")
    for field, expected_value in (("build.id", image["buildId"]), ("release.profile", image["releaseProfile"])):
        if re.findall(r"(?m)^"+re.escape(field)+r"=([^\r\n]*)\r?$", props) != [expected_value]:
            errors.append({"code": "CANDIDATE_IDENTITY_MISMATCH", "field": field})

    materials = {}
    material_paths = set()
    for entry in catalog["materials"]:
        identity = entry["id"]
        if identity in materials or entry["path"].casefold() in material_paths:
            raise ValueError("Duplicate material identity or file")
        if entry["role"] not in ROLES or entry["component"] not in NATIVE_COMPONENTS:
            raise ValueError("Unknown material role or component")
        path = safe_file(materials_root, entry["path"])
        matches = path.is_file() and path.stat().st_size == entry["bytes"] and file_hash(path) == entry["sha256"]
        materials[identity] = entry
        material_paths.add(entry["path"].casefold())
        verified.append({"id": identity, "component": entry["component"], "role": entry["role"], "matches": matches})
        if not matches:
            errors.append({"code": "MATERIAL_FILE_MISMATCH", "id": identity})

    components, covered = set(), set()
    for component in catalog["components"]:
        identity = component["id"]
        if identity in components or identity not in NATIVE_COMPONENTS | {"project"}:
            raise ValueError("Duplicate or unknown component")
        components.add(identity)
        paths = {p.casefold() for p in component["imagePaths"]}
        if len(paths) != len(component["imagePaths"]) or covered.intersection(paths) or not paths.issubset(expected):
            raise ValueError("Invalid component-to-candidate mapping")
        covered.update(paths)
        if identity == "project":
            continue
        if not paths:
            errors.append({"code": "COMPONENT_WITHOUT_BINARY", "component": identity})
        selected = [materials[item] for item in component["materialIds"]]
        if any(item["component"] != identity for item in selected):
            raise ValueError("Material belongs to a different component")
        roles = {item["role"] for item in selected}
        for role in ("license", "source"):
            if role not in roles:
                open_items.append({"component": identity, "reason": "Missing reviewed "+role+" material"})
        if component["reviewState"] not in ("open", "ready"):
            raise ValueError("Unknown component review state")
        if not component.get("reviewEvidence"):
            open_items.append({"component": identity, "reason": "Missing component correspondence review evidence"})
        reasons = component.get("openItems", [])
        if component["reviewState"] == "open" and not reasons:
            reasons = ["Component correspondence review remains open"]
        open_items.extend({"component": identity, "reason": reason} for reason in reasons)
    if not NATIVE_COMPONENTS.issubset(components) or covered != set(expected):
        errors.append({"code": "INCOMPLETE_COMPONENT_COVERAGE"})
    gate = not errors and not open_items
    return {"schemaVersion": 1, "status": "integrity-failed" if errors else
            "verified-with-open-items" if open_items else "native-material-gate-passed",
            "buildId": image["buildId"], "releaseProfile": image["releaseProfile"],
            "candidateFilesChecked": len(expected), "materialsChecked": len(materials),
            "materialChecks": verified, "openItems": open_items, "errors": errors,
            "integrityPassed": not errors, "nativeMaterialGatePassed": gate,
            "binaryReleaseAccepted": False,
            "limitations": ["Review decisions are explicit catalog inputs; hashes do not establish legal sufficiency.",
                            "This tool does not verify GUI, audio, reboot sessions or an independent computer.",
                            "No installation, release packaging or publication is performed."]}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--app-home", required=True, type=Path)
    parser.add_argument("--materials-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--require-release-ready", action="store_true")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    app = args.app_home.resolve()
    if not output.is_relative_to(root / "build") or output.is_relative_to(app) or output.exists():
        parser.error("Use a new output directory inside project build/, outside the inspected application")
    catalog = json.loads(args.catalog.read_text(encoding="utf-8-sig"))
    result = verify_catalog(catalog, app, args.materials_root.resolve())
    output.mkdir(parents=True)
    (output/"verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("status", "buildId", "candidateFilesChecked", "materialsChecked",
                                                "integrityPassed", "nativeMaterialGatePassed", "errors")}))
    return 1 if result["errors"] else 2 if args.require_release_ready and not result["nativeMaterialGatePassed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
