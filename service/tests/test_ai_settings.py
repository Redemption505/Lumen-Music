from __future__ import annotations

import http.client
import json
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service.ai_settings import AiSettingsError, AiSettingsStore
from lumen_service.backup import export_backup, restore_backup
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


class AiSettingsTest(unittest.TestCase):
    def test_existing_environment_configuration_is_visible_until_saved(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict("os.environ", {
                "LUMEN_AI_CHAT_COMPLETIONS_URL": "https://example.test/v1/chat/completions",
                "LUMEN_AI_MODEL": "environment-model", "LUMEN_AI_API_KEY": "key"}):
            store = AiSettingsStore(Path(folder) / "ai-settings.json")
            self.assertTrue(store.read()["enabled"])
            self.assertEqual("environment-model", store.read()["model"])
            store.save(enabled=False, model="", base_url="")
            self.assertFalse(store.read()["enabled"])
            self.assertIsNone(store.config())

    def test_saved_config_uses_separate_secret_and_survives_restart(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict("os.environ", {"LUMEN_AI_API_KEY": ""}):
            store = AiSettingsStore(Path(folder) / "ai-settings.json")
            self.assertIsNone(store.config())
            store.save(enabled=True, model="test-model",
                       base_url="https://example.test/v1/chat/completions", api_key="private-key")
            restored = AiSettingsStore(store.path)
            self.assertEqual("private-key", restored.config().api_key)
            self.assertNotIn("private-key", restored.path.read_text(encoding="utf-8"))
            restored.save(enabled=False, model="test-model",
                          base_url="https://example.test/v1/chat/completions")
            self.assertIsNone(restored.config())

    def test_bad_file_is_kept_and_reported(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "ai-settings.json"
            path.write_text("broken", encoding="utf-8")
            with self.assertRaises(AiSettingsError):
                AiSettingsStore(path).read()
            self.assertEqual("broken", path.read_text(encoding="utf-8"))

    def test_backup_restores_preferences_without_credential(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict("os.environ", {"LUMEN_AI_API_KEY": ""}):
            root = Path(folder)
            library = LibraryStore(root / "library.json")
            library.path.write_text(json.dumps(library.snapshot()), encoding="utf-8")
            settings = AiSettingsStore(root / "ai-settings.json")
            settings.save(enabled=True, model="backup-model",
                          base_url="https://example.test/v1/chat/completions", api_key="private-key")
            archive = root / "backup.zip"
            export_backup(archive, library.path, ai_config_path=settings.path)
            with zipfile.ZipFile(archive) as source:
                self.assertIn("ai-settings.json", source.namelist())
                self.assertNotIn("ai-secret.json", source.namelist())
                self.assertNotIn(b"private-key", source.read("ai-settings.json"))
            target = root / "restored"
            restore_backup(archive, target / "library.json",
                           ai_config_path=target / "ai-settings.json")
            restored = AiSettingsStore(target / "ai-settings.json")
            self.assertEqual("backup-model", restored.read()["model"])
            self.assertIsNone(restored.config())

    def test_http_settings_never_return_key(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict("os.environ", {"LUMEN_AI_API_KEY": ""}):
            root = Path(folder)
            server = create_server(port=0, provider=DemoProvider(),
                                   library_store=LibraryStore(root / "library.json"),
                                   history_store=HistoryStore(root / "history.json"))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                payload = ElementTree.tostring(ElementTree.Element("ai-settings", {
                    "enabled": "true", "model": "model-a",
                    "baseUrl": "https://example.test/v1/chat/completions",
                    "apiKey": "test-secret"}))
                conn.request("POST", "/api/ai-settings", payload,
                             {"Content-Type": "application/xml"})
                response = conn.getresponse()
                body = response.read()
                self.assertEqual(200, response.status)
                self.assertNotIn(b"test-secret", body)
                self.assertEqual("true", ElementTree.fromstring(body).get("keyConfigured"))
                conn.request("GET", "/api/ai-settings")
                response = conn.getresponse()
                self.assertEqual(200, response.status)
                self.assertEqual("model-a", ElementTree.fromstring(response.read()).get("model"))
                conn.close()
                self.assertNotIn("test-secret", (root / "ai-settings.json").read_text(encoding="utf-8"))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
