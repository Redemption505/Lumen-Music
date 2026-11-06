from pathlib import Path
import hashlib
import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "verify-prerelease-materials.py"
spec = importlib.util.spec_from_file_location("prerelease_materials", SCRIPT)
materials = importlib.util.module_from_spec(spec)
spec.loader.exec_module(materials)


class NativeMaterialGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / "candidate"
        self.app.mkdir()
        self.catalog = {"schemaVersion": 1, "image": {"buildId": "fixture-1", "releaseProfile": "local-first",
                         "mainJar": "app/main.jar", "files": []}, "materials": [], "components": []}
        jar = self.app / "app/main.jar"
        jar.parent.mkdir()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("lumen-build.properties", "build.id=fixture-1\nrelease.profile=local-first\n")
        self.add_image("app/main.jar")
        self.catalog["components"].append({"id": "project", "imagePaths": ["app/main.jar"]})
        for identity in sorted(materials.NATIVE_COMPONENTS):
            path = self.app / (identity + "/component.bin")
            path.parent.mkdir()
            path.write_bytes((identity + " binary fixture").encode())
            relative = path.relative_to(self.app).as_posix()
            self.add_image(relative)
            ids = []
            for role in ("license", "source"):
                material = self.root / (identity + "-" + role + ".txt")
                material.write_text("synthetic fixture for " + identity + " " + role, encoding="utf-8")
                item = self.file_entry(material, self.root)
                item.update({"id": identity+"-"+role, "component": identity, "role": role})
                ids.append(item["id"])
                self.catalog["materials"].append(item)
            self.catalog["components"].append({"id": identity, "imagePaths": [relative],
                 "materialIds": ids, "reviewState": "ready", "reviewEvidence": "synthetic fixture review", "openItems": []})

    @staticmethod
    def file_entry(path, root):
        return {"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def add_image(self, relative):
        self.catalog["image"]["files"].append(self.file_entry(self.app/relative, self.app))

    def verify(self):
        return materials.verify_catalog(self.catalog, self.app, self.root)

    def test_complete_reviewed_fixture_passes_integrity_without_accepting_binary_release(self):
        result = self.verify()
        self.assertTrue(result["integrityPassed"])
        self.assertTrue(result["nativeMaterialGatePassed"])
        self.assertFalse(result["binaryReleaseAccepted"])

    def test_changed_material_fails_even_if_component_is_marked_ready(self):
        item = self.catalog["materials"][0]
        (self.root/item["path"]).write_bytes(b"tampered")
        result = self.verify()
        self.assertFalse(result["nativeMaterialGatePassed"])
        self.assertIn("MATERIAL_FILE_MISMATCH", [e["code"] for e in result["errors"]])

    def test_new_candidate_file_requires_new_inventory(self):
        (self.app/"unexpected.dll").write_bytes(b"not reviewed")
        result = self.verify()
        self.assertIn("CANDIDATE_INVENTORY_CHANGED", [e["code"] for e in result["errors"]])

    def test_other_build_cannot_reuse_material_catalog_after_hash_replacement(self):
        path = self.app/"app/main.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("lumen-build.properties", "build.id=other-build\nrelease.profile=full\n")
        self.catalog["image"]["files"][0] = self.file_entry(path, self.app)
        result = self.verify()
        self.assertEqual({"build.id", "release.profile"}, {e["field"] for e in result["errors"]})

    def test_reference_archive_does_not_satisfy_corresponding_source_gate(self):
        item = next(i for i in self.catalog["materials"] if i["id"] == "javafx-source")
        item["role"] = "reference"
        result = self.verify()
        self.assertTrue(result["integrityPassed"])
        self.assertFalse(result["nativeMaterialGatePassed"])
        self.assertIn({"component": "javafx", "reason": "Missing reviewed source material"}, result["openItems"])

    def test_component_cannot_borrow_another_components_source(self):
        component = next(i for i in self.catalog["components"] if i["id"] == "javafx")
        component["materialIds"] = ["jdk-source", "javafx-license"]
        with self.assertRaisesRegex(ValueError, "different component"):
            self.verify()

    def test_rejects_traversal_absolute_paths_and_duplicate_materials(self):
        for path in ("../private.txt", "/private.txt", "C:/private.txt", "dir\\private.txt"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                materials.safe_file(self.root, path)
        self.catalog["materials"].append(dict(self.catalog["materials"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate material"):
            self.verify()

    def test_cli_preserves_open_findings_and_strict_gate_fails_without_overwriting(self):
        component = next(i for i in self.catalog["components"] if i["id"] == "vlc")
        component.update(reviewState="open", openItems=["Contrib source mapping not reviewed"])
        catalog_path = self.root/"catalog.json"
        catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        args = ["--catalog", str(catalog_path), "--app-home", str(self.app), "--materials-root", str(self.root)]
        with patch.object(materials, "__file__", str(self.root/"scripts/verify-prerelease-materials.py")):
            self.assertEqual(0, materials.main(args+["--output", str(self.root/"build/report")]))
            self.assertEqual(2, materials.main(args+["--output", str(self.root/"build/strict"), "--require-release-ready"]))
            with self.assertRaises(SystemExit):
                materials.main(args+["--output", str(self.root/"build/report")])
            with self.assertRaises(SystemExit):
                materials.main(args+["--output", str(self.app/"evidence")])
        result = json.loads((self.root/"build/strict/verification.json").read_text(encoding="utf-8"))
        self.assertIn({"component": "vlc", "reason": "Contrib source mapping not reviewed"}, result["openItems"])
        self.assertFalse((self.app/"evidence").exists())


if __name__ == "__main__":
    unittest.main()
