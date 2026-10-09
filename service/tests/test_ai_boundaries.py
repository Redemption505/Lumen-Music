"""B-02 behavior checks use isolated files and in-memory AI transport only."""
from __future__ import annotations

import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from lumen_service.ai_intent import generate_search_intents
from lumen_service.ai_settings import AiSettingsError, AiSettingsStore
from lumen_service.backup import export_backup
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


A = "https://a.example.test/v1/chat/completions"
B = "https://b.example.test/v1/chat/completions"


class CredentialBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = patch.dict(os.environ, {"LUMEN_AI_API_KEY": "",
            "LUMEN_AI_CHAT_COMPLETIONS_URL": "", "LUMEN_AI_MODEL": ""})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.store = AiSettingsStore(Path(self.temp.name) / "ai-settings.json")

    def save(self, url=A, key=None):
        return self.store.save(enabled=True, model="fixture-model", base_url=url, api_key=key)

    def test_file_key_cannot_follow_endpoint_change(self):
        self.save(key="fixture-a-credential")
        self.save(B)
        self.assertIsNone(self.store.config())
        self.assertFalse(self.store.has_key())
        self.assertIsNone(AiSettingsStore(self.store.path).config())
        self.save(A)
        self.assertTrue(self.store.config().api_key == "fixture-a-credential")

    def test_environment_key_is_bound_to_environment_endpoint(self):
        with patch.dict(os.environ, {"LUMEN_AI_API_KEY": "fixture-env-credential",
                "LUMEN_AI_CHAT_COMPLETIONS_URL": A, "LUMEN_AI_MODEL": "env-model"}):
            self.assertTrue(self.store.config().api_key == "fixture-env-credential")
            self.save(A)
            self.assertTrue(self.store.has_key())
            self.save(B)
            self.assertIsNone(self.store.config())
            self.assertFalse(self.store.has_key())
            self.save(B, "fixture-b-credential")
            captured = []
            def transport(request, timeout):
                captured.append((request.full_url, request.get_header("Authorization")))
                return json.dumps({"choices": [{"message": {"content":
                    json.dumps({"queries": ["piano music"]})}}]}).encode()
            result = generate_search_intents(["piano"], config=self.store.config(), transport=transport)
            self.assertEqual("ai", result.source)
            self.assertTrue(captured == [(B, "Bearer fixture-b-credential")])

    def test_environment_key_without_explicit_target_cannot_pair_with_saved_url(self):
        with patch.dict(os.environ, {"LUMEN_AI_API_KEY": "fixture-env-credential"}):
            self.save(A)
            self.assertIsNone(self.store.config())

    def test_legacy_secret_is_retained_and_bound_before_endpoint_changes(self):
        self.save(A)
        self.store.secret_path.write_text(json.dumps({"version": 1,
            "apiKey": "fixture-legacy-credential"}), encoding="utf-8")
        self.save(B)
        self.assertIsNone(self.store.config())
        self.save(A)
        self.assertTrue(self.store.config().api_key == "fixture-legacy-credential")

    def test_legacy_secret_first_use_persists_original_target(self):
        self.save(A)
        self.store.secret_path.write_text(json.dumps({"version": 1,
            "apiKey": "fixture-legacy-credential"}), encoding="utf-8")
        self.assertTrue(self.store.config().api_key == "fixture-legacy-credential")
        self.assertEqual(A, json.loads(self.store.secret_path.read_text())["baseUrl"])
        # Simulate a preferences-only restore after adoption; credentials remain local.
        value = json.loads(self.store.path.read_text())
        value["baseUrl"] = B
        self.store.path.write_text(json.dumps(value), encoding="utf-8")
        self.assertIsNone(AiSettingsStore(self.store.path).config())

    def test_disabled_legacy_preferences_without_target_keep_key_unbound(self):
        self.store.save(enabled=False, model="", base_url="")
        self.store.secret_path.write_text(json.dumps({"version": 1,
            "apiKey": "fixture-legacy-credential"}), encoding="utf-8")
        self.assertFalse(self.store.has_key())
        self.save(B)
        self.assertIsNone(self.store.config())
        self.assertTrue(json.loads(self.store.secret_path.read_text())["apiKey"] == "fixture-legacy-credential")

    def test_journal_publish_failure_leaves_old_pair_without_partial_recovery_file(self):
        self.save(key="fixture-a-credential")
        before = (self.store.path.read_bytes(), self.store.secret_path.read_bytes())
        replace = os.replace
        def fail_journal(source, destination):
            if Path(destination) == self.store.recovery_path:
                raise OSError("injected journal publication failure")
            return replace(source, destination)
        with patch("lumen_service.ai_settings.os.replace", side_effect=fail_journal):
            with self.assertRaises(OSError):
                self.save(B, "fixture-b-credential")
        self.assertFalse(self.store.recovery_path.exists())
        self.assertTrue(before == (self.store.path.read_bytes(), self.store.secret_path.read_bytes()))

    def test_interrupted_commit_recovers_old_pair_on_restart(self):
        self.save(key="fixture-a-credential")
        before = (self.store.path.read_bytes(), self.store.secret_path.read_bytes())
        replace = os.replace
        def interrupt(source, destination):
            if Path(destination) == self.store.secret_path:
                raise SystemExit("simulated process interruption")
            return replace(source, destination)
        with patch("lumen_service.ai_settings.os.replace", side_effect=interrupt):
            with self.assertRaises(SystemExit):
                self.save(B, "fixture-b-credential")
        restored = AiSettingsStore(self.store.path)
        self.assertEqual(A, restored.read()["baseUrl"])
        self.assertTrue(before == (restored.path.read_bytes(), restored.secret_path.read_bytes()))
        self.assertFalse(restored.recovery_path.exists())

    def test_recovery_preserves_unknown_external_edit_until_resolved(self):
        self.save(key="fixture-a-credential")
        before = self.store.path.read_bytes()
        replace = os.replace
        def interrupt(source, destination):
            if Path(destination) == self.store.secret_path:
                raise SystemExit("simulated process interruption")
            return replace(source, destination)
        with patch("lumen_service.ai_settings.os.replace", side_effect=interrupt):
            with self.assertRaises(SystemExit):
                self.save(B, "fixture-b-credential")
        external = before + b" "
        self.store.path.write_bytes(external)
        with self.assertRaises(AiSettingsError):
            AiSettingsStore(self.store.path).read()
        self.assertTrue(self.store.path.read_bytes() == external)
        self.assertTrue(self.store.recovery_path.exists())
        self.store.path.write_bytes(before)
        self.assertEqual(A, self.store.read()["baseUrl"])
        self.assertFalse(self.store.recovery_path.exists())

    def test_second_file_failure_rolls_back_both_files_and_restart(self):
        self.save(key="fixture-a-credential")
        before = (self.store.path.read_bytes(), self.store.secret_path.read_bytes())
        replace = os.replace
        def fail_secret(source, destination):
            if Path(destination) == self.store.secret_path:
                raise OSError("injected second-file failure")
            return replace(source, destination)
        with patch("lumen_service.ai_settings.os.replace", side_effect=fail_secret):
            with self.assertRaises((OSError, AiSettingsError)):
                self.save(B, "fixture-b-credential")
            self.assertFalse(self.store.recovery_path.exists())
        self.assertTrue(before == (self.store.path.read_bytes(), self.store.secret_path.read_bytes()))
        self.assertTrue(AiSettingsStore(self.store.path).config().api_key == "fixture-a-credential")

    def test_rollback_failure_keeps_recoverable_old_pair_and_restart_recovers(self):
        self.save(key="fixture-a-credential")
        before = (self.store.path.read_bytes(), self.store.secret_path.read_bytes())
        replace = os.replace
        def fail_commit_and_rollback(source, destination):
            if Path(destination) == self.store.secret_path:
                raise OSError("injected secret commit failure")
            if Path(destination) == self.store.path and self.store.path.read_bytes() != before[0]:
                raise OSError("injected rollback failure")
            return replace(source, destination)
        with patch("lumen_service.ai_settings.os.replace", side_effect=fail_commit_and_rollback):
            with self.assertRaises((OSError, AiSettingsError)):
                self.save(B, "fixture-b-credential")
            self.assertTrue(self.store.recovery_path.exists())
            # An unresolved mixed pair must never be usable for AI.
            with self.assertRaises(AiSettingsError):
                self.store.config()
        restored = AiSettingsStore(self.store.path)
        self.assertEqual(A, restored.read()["baseUrl"])
        self.assertTrue(before == (restored.path.read_bytes(), restored.secret_path.read_bytes()))
        self.assertTrue(restored.config().api_key == "fixture-a-credential")


class LocalWriteBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"LUMEN_AI_API_KEY": "", "LUMEN_AI_SETTINGS_PATH": "",
            "LUMEN_AI_CHAT_COMPLETIONS_URL": "", "LUMEN_AI_MODEL": ""})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.store = AiSettingsStore(self.root / "ai-settings.json")
        self.store.save(enabled=True, model="fixture-model", base_url=A, api_key="fixture-a-credential")
        self.before = (self.store.path.read_bytes(), self.store.secret_path.read_bytes())
        self.server = create_server(port=0, provider=DemoProvider(),
            library_store=LibraryStore(self.root / "library.json"),
            history_store=HistoryStore(self.root / "history.json"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, headers, route="/api/ai-settings", method="POST", body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            conn.request(method, route, body=body if body is not None else
                f'<ai-settings enabled="true" model="fixture-model" baseUrl="{B}"/>'.encode(),
                headers=headers)
            response = conn.getresponse()
            response.read()
            return response.status
        finally:
            conn.close()

    def test_rejected_headers_leave_old_pair_byte_identical(self):
        host = f"127.0.0.1:{self.server.server_port}"
        cases = [
            {"Content-Type": "application/xml", "Origin": "https://outside.example.test"},
            {"Content-Type": "application/xml", "Origin": "null"},
            {"Content-Type": "application/xml", "Origin": "http://" + host + "/path"},
            {"Content-Type": "application/xml", "Host": "outside.example.test"},
            {"Content-Type": "application/xml", "Host": "127.0.0.1:1"},
            {"Content-Type": "text/plain"}, {},
        ]
        for headers in cases:
            with self.subTest(case=list(headers.keys())):
                self.assertIn(self.request(headers, body=b"<"), (403, 415))
                self.assertTrue(self.before == (self.store.path.read_bytes(), self.store.secret_path.read_bytes()))

    def test_desktop_xml_and_same_origin_are_allowed(self):
        self.assertEqual(200, self.request({"Content-Type": "application/xml; charset=utf-8"}))
        self.assertIsNone(self.store.config())
        self.assertEqual(200, self.request({"Content-Type": "text/xml", "Origin":
            f"http://127.0.0.1:{self.server.server_port}"}))

    def test_other_writes_share_boundary_including_bodyless_delete(self):
        before = {path.name: path.read_bytes() for path in self.root.iterdir() if path.is_file()}
        for method, route in [("POST", "/api/backup/export"), ("POST", "/api/listening"),
                ("POST", "/api/radio"), ("PATCH", "/api/library/tracks/id"),
                ("DELETE", "/api/library/favorites/id"), ("POST", "/api/diary/notes")]:
            with self.subTest(method=method, route=route):
                self.assertEqual(403, self.request({"Origin": "https://outside.example.test",
                    "Content-Type": "application/xml"}, route, method, b"<fixture/>"))
        self.assertTrue(before == {path.name: path.read_bytes() for path in self.root.iterdir() if path.is_file()})

    def test_endpoint_change_cannot_call_ai_from_recommendation_or_radio(self):
        for env in ({}, {"LUMEN_AI_API_KEY": "fixture-env-credential",
                "LUMEN_AI_CHAT_COMPLETIONS_URL": A, "LUMEN_AI_MODEL": "fixture-model"}):
            with self.subTest(environment=bool(env)), patch.dict(os.environ, env):
                self.store.save(enabled=True, model="fixture-model", base_url=A,
                                api_key="fixture-a-credential")
                self.assertEqual(200, self.request({"Content-Type": "application/xml"}))
                with patch("lumen_service.ai_intent._transport") as transport:
                    self.assertEqual(200, self.request({}, "/api/recommendations?hint=piano", "GET", b""))
                    self.assertEqual(200, self.request({"Content-Type": "application/xml"},
                        "/api/radio", "POST", b'<radio mode="discover" hint="piano"/>'))
                    transport.assert_not_called()

    def test_explicit_http_key_pairs_with_new_endpoint_in_memory_transport(self):
        from xml.etree import ElementTree
        payload = ElementTree.tostring(ElementTree.Element("ai-settings", {
            "enabled": "true", "model": "fixture-model", "baseUrl": B,
            "apiKey": "fixture-b-credential"}))
        self.assertEqual(200, self.request({"Content-Type": "application/xml"}, body=payload))
        captured = []
        def transport(request, timeout):
            captured.append((request.full_url, request.get_header("Authorization")))
            return json.dumps({"choices": [{"message": {"content":
                json.dumps({"queries": ["piano music"]})}}]}).encode()
        with patch("lumen_service.ai_intent._transport", side_effect=transport):
            self.assertEqual(200, self.request({}, "/api/recommendations?hint=piano", "GET", b""))
        self.assertTrue(captured and all(pair == (B, "Bearer fixture-b-credential") for pair in captured))

    def restore_new_preferences(self):
        from xml.etree import ElementTree
        source = self.root / "backup-source"
        source.mkdir()
        library = LibraryStore(source / "library.json")
        library.path.write_text(json.dumps(library.snapshot()), encoding="utf-8")
        settings = AiSettingsStore(source / "ai-settings.json")
        settings.save(enabled=True, model="fixture-model", base_url=B)
        archive = self.root / "preferences.zip"
        export_backup(archive, library.path, ai_config_path=settings.path)
        body = ElementTree.tostring(ElementTree.Element("backup", {"path": str(archive)}))
        return self.request({"Content-Type": "application/xml"}, "/api/backup/restore", body=body)

    def test_backup_restore_binds_unused_legacy_key_to_old_target(self):
        self.store.secret_path.write_text(json.dumps({"version": 1,
            "apiKey": "fixture-legacy-credential"}), encoding="utf-8")
        with patch.dict(os.environ, {"LUMEN_AI_API_KEY": "fixture-env-credential",
                "LUMEN_AI_CHAT_COMPLETIONS_URL": A, "LUMEN_AI_MODEL": "fixture-model"}):
            self.assertEqual(200, self.restore_new_preferences())
            self.assertIsNone(self.store.config())
            secret = json.loads(self.store.secret_path.read_text())
            self.assertEqual(A, secret["baseUrl"])
            self.assertTrue(secret["apiKey"] == "fixture-legacy-credential")
            with patch("lumen_service.ai_intent._transport") as transport:
                self.assertEqual(200, self.request({}, "/api/recommendations?hint=piano", "GET", b""))
                transport.assert_not_called()

    def test_backup_repairs_damaged_preferences_without_adopting_unbound_key(self):
        self.store.path.write_text("damaged preferences", encoding="utf-8")
        self.store.secret_path.write_text(json.dumps({"version": 1,
            "apiKey": "fixture-legacy-credential"}), encoding="utf-8")
        self.assertEqual(200, self.restore_new_preferences())
        self.assertEqual(B, self.store.read()["baseUrl"])
        self.assertIsNone(self.store.config())
        secret = json.loads(self.store.secret_path.read_text())
        self.assertEqual("", secret["baseUrl"])
        self.assertTrue(secret["apiKey"] == "fixture-legacy-credential")


if __name__ == "__main__":
    unittest.main()
