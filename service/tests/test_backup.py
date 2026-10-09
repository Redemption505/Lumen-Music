from __future__ import annotations

import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

from lumen_service.backup import BackupError, export_backup, inspect_backup, restore_backup


class BackupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.library = self.root / "library.json"
        self.library.write_text(json.dumps({"version": 1, "favorites": [], "playlists": []}),
                                encoding="utf-8")
        self.history = self.root / "history.json"
        self.history.write_text(json.dumps({"version": 1, "entries": []}), encoding="utf-8")
        self.destination = self.root / "backup.zip"

    def test_export_and_inspect_with_optional_sections(self) -> None:
        ai_config = self.root / "ai.json"
        ai_config.write_text(json.dumps({"version": 1, "enabled": True,
                                          "provider": "example", "model": "model",
                                          "apiKey": "never-export-this"}), encoding="utf-8")
        background = self.root / "wallpaper.png"
        background.write_bytes(b"\x89PNG\r\n\x1a\nexample")
        preview = export_backup(self.destination, self.library, history_path=self.history,
                                ai_config_path=ai_config, background_path=background)
        self.assertEqual(0, preview.favorite_count)
        self.assertEqual(0, preview.history_count)
        self.assertEqual(len(background.read_bytes()), preview.background_bytes)
        with zipfile.ZipFile(self.destination) as archive:
            self.assertNotIn(b"never-export-this", archive.read("ai-settings.json"))
            self.assertEqual(True, json.loads(archive.read("ai-settings.json"))["enabled"])
        self.assertEqual(preview, inspect_backup(self.destination))

    def test_invalid_source_leaves_previous_export_untouched(self) -> None:
        export_backup(self.destination, self.library)
        original = self.destination.read_bytes()
        self.library.write_text('{"version":1,"favorites":[{}],"playlists":[]}', encoding="utf-8")
        with self.assertRaises(BackupError):
            export_backup(self.destination, self.library)
        self.assertEqual(original, self.destination.read_bytes())

    def test_preflight_rejects_tampered_data_without_touching_source(self) -> None:
        export_backup(self.destination, self.library)
        with zipfile.ZipFile(self.destination) as archive:
            manifest = archive.read("manifest.json")
        with zipfile.ZipFile(self.destination, "w") as archive:
            archive.writestr("manifest.json", manifest)
            archive.writestr("library.json", b"tampered")
        original = self.destination.read_bytes()
        with self.assertRaisesRegex(BackupError, "校验失败"):
            inspect_backup(self.destination)
        self.assertEqual(original, self.destination.read_bytes())

    def test_preflight_rejects_path_traversal_and_duplicate_entries(self) -> None:
        for extra in ("../history.json", "library.json"):
            with self.subTest(extra=extra):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(self.destination, "w") as archive:
                        archive.writestr("manifest.json", "{}")
                        archive.writestr("library.json", self.library.read_bytes())
                        archive.writestr(extra, "{}")
                with self.assertRaises(BackupError):
                    inspect_backup(self.destination)

    def test_invalid_history_is_not_exported(self) -> None:
        self.history.write_text('{"version":1,"entries":[{}]}', encoding="utf-8")
        with self.assertRaisesRegex(BackupError, "history.json"):
            export_backup(self.destination, self.library, history_path=self.history)
        self.assertFalse(self.destination.exists())

    def test_ai_url_credentials_rejected(self) -> None:
        ai_config = self.root / "ai.json"
        ai_config.write_text('{"baseUrl":"https://user:password@example.com"}',
                             encoding="utf-8")
        with self.assertRaisesRegex(BackupError, "baseUrl"):
            export_backup(self.destination, self.library, ai_config_path=ai_config)

    def test_restore_all_members_from_valid_snapshot(self) -> None:
        self.library.write_text('{"version":1,"favorites":[],"playlists":[]}', encoding="utf-8")
        ai = self.root / "ai.json"
        ai.write_text('{"version":1,"enabled":true,"provider":"test","model":"m"}', encoding="utf-8")
        background = self.root / "wallpaper.png"
        background.write_bytes(b"\x89PNG\r\n\x1a\nbackup-image")
        export_backup(self.destination, self.library, history_path=self.history,
                      ai_config_path=ai, background_path=background)
        expected = (self.library.read_bytes(), self.history.read_bytes(),
                    background.read_bytes())
        self.library.write_text('{"version":1,"favorites":[],"playlists":[],"changed":true}', encoding="utf-8")
        self.history.write_text('{"version":1,"entries":[],"changed":true}', encoding="utf-8")
        ai.write_text('{"version":1,"enabled":false}', encoding="utf-8")
        background.write_bytes(b"\x89PNG\r\n\x1a\nnew-image")
        result = restore_backup(self.destination, self.library, history_path=self.history,
                                ai_config_path=ai, background_path=background)
        self.assertEqual(4, len(result.restored_paths))
        self.assertEqual(expected[0], self.library.read_bytes())
        self.assertEqual(expected[1], self.history.read_bytes())
        self.assertEqual(expected[2], background.read_bytes())
        self.assertTrue(json.loads(ai.read_text(encoding="utf-8"))["enabled"])
        self.assertEqual([], list(self.root.glob(".*.restore")))
        self.assertEqual([], list(self.root.glob(".*.previous")))

    def test_tampered_archive_cannot_change_targets(self) -> None:
        export_backup(self.destination, self.library, history_path=self.history)
        self.library.write_text('{"version":1,"favorites":[],"playlists":[],"current":true}', encoding="utf-8")
        original = self.library.read_bytes()
        with zipfile.ZipFile(self.destination) as archive:
            manifest = archive.read("manifest.json")
            history = archive.read("history.json")
        with zipfile.ZipFile(self.destination, "w") as archive:
            archive.writestr("manifest.json", manifest)
            archive.writestr("library.json", b"tampered")
            archive.writestr("history.json", history)
        with self.assertRaisesRegex(BackupError, "校验失败"):
            restore_backup(self.destination, self.library, history_path=self.history)
        self.assertEqual(original, self.library.read_bytes())

    def test_restore_failure_rolls_back_every_replaced_target(self) -> None:
        export_backup(self.destination, self.library, history_path=self.history)
        self.library.write_bytes(b"current-library")
        self.history.write_bytes(b"current-history")
        real_replace = __import__("os").replace

        def fail_second(source: str | Path, target: str | Path) -> None:
            if Path(target) == self.history and str(source).endswith(".restore"):
                raise OSError("injected replacement failure")
            real_replace(source, target)

        with patch("lumen_service.backup.os.replace", side_effect=fail_second):
            with self.assertRaisesRegex(BackupError, "已回滚"):
                restore_backup(self.destination, self.library, history_path=self.history)
        self.assertEqual(b"current-library", self.library.read_bytes())
        self.assertEqual(b"current-history", self.history.read_bytes())
        self.assertEqual([], list(self.root.glob(".*.restore")))
        self.assertEqual([], list(self.root.glob(".*.previous")))

    def test_restore_requires_destination_for_every_included_member(self) -> None:
        export_backup(self.destination, self.library, history_path=self.history)
        original = self.library.read_bytes()
        with self.assertRaisesRegex(BackupError, "history.json"):
            restore_backup(self.destination, self.library)
        self.assertEqual(original, self.library.read_bytes())

    def test_rollback_removes_target_created_before_failure(self) -> None:
        export_backup(self.destination, self.library, history_path=self.history)
        fresh_library = self.root / "fresh-library.json"
        real_replace = __import__("os").replace

        def fail_history(source: str | Path, target: str | Path) -> None:
            if Path(target) == self.history and str(source).endswith(".restore"):
                raise OSError("injected replacement failure")
            real_replace(source, target)

        with patch("lumen_service.backup.os.replace", side_effect=fail_history):
            with self.assertRaisesRegex(BackupError, "已回滚"):
                restore_backup(self.destination, fresh_library, history_path=self.history)
        self.assertFalse(fresh_library.exists())

    def test_restore_rejects_zip_as_destination(self) -> None:
        export_backup(self.destination, self.library)
        original = self.destination.read_bytes()
        with self.assertRaisesRegex(BackupError, "备份 ZIP"):
            restore_backup(self.destination, self.destination)
        self.assertEqual(original, self.destination.read_bytes())


if __name__ == "__main__":
    unittest.main()
