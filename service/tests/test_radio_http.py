from __future__ import annotations

import http.client
import json
import os
import tempfile
import threading
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote
from xml.etree import ElementTree

from lumen_service.backup import export_backup, inspect_backup
from lumen_service.ai_intent import IntentResult
from lumen_service.ai_settings import AiSettingsStore
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore, StoredTrack
from lumen_service.models import Track
from lumen_service.server import create_server


class Provider:
    name = "test"

    def __init__(self) -> None:
        self.fail = False
        self.queries: list[str] = []
        self.tracks = tuple(Track(f"provider:{index}", f"Music {index}", "Uploader", 180,
                                  "test") for index in range(12))

    def search(self, query: str):
        self.queries.append(query)
        if self.fail:
            raise OSError("offline")
        return self.tracks


class RadioHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.library = LibraryStore(self.root / "library.json")
        self.history = HistoryStore(self.root / "history.json")
        self.provider = Provider()
        self.env = patch.dict(os.environ, {"LUMEN_AI_SETTINGS_PATH": "",
                                        "LUMEN_AI_CHAT_COMPLETIONS_URL": "",
                                        "LUMEN_AI_MODEL": "", "LUMEN_AI_API_KEY": ""})
        self.env.start()
        self.addCleanup(self.env.stop)
        self._start()

    def _start(self) -> None:
        self.server = create_server(port=0, provider=self.provider, library_store=self.library,
                                    history_store=self.history)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def tearDown(self) -> None:
        self._stop()

    def request(self, method: str, path: str, body: str = ""):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request(method, path, body.encode("utf-8"),
                               {"Content-Type": "application/xml"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def radio(self, *, neutral: bool = False, mode: str = "familiar", extra: str = ""):
        return self.request("POST", "/api/radio",
                            f'<radio mode="{mode}" neutral="{str(neutral).lower()}" '
                            f'hint="Music">{extra}</radio>')

    def feedback(self, identifier: str, kind: str = "version"):
        track = next(item for item in self.provider.tracks if item.id == identifier)
        return self.request("POST", "/api/radio/feedback",
                            f'<feedback kind="{kind}"><track id="{track.id}" '
                            f'title="{track.title}" artist="{track.artist}" '
                            f'durationSeconds="180" source="test" /></feedback>')

    def test_real_candidates_exclusions_and_cold_start(self) -> None:
        status, root = self.radio(extra='<exclude id="provider:0"/><seed><track '
                                  'id="invented" title="Music" artist="Unknown" '
                                  'durationSeconds="180" source="test"/></seed>')
        self.assertEqual((200, "ready"), (status, root.get("status")))
        self.assertEqual(11, len(root))
        self.assertNotIn("provider:0", [item[0].get("id") for item in root])
        self.assertNotIn("invented", [item[0].get("id") for item in root])
        self.assertTrue(all(item.get("reason") for item in root))
        self.assertTrue(root.get("intents"))

    def test_modes_and_recoverable_tired_feedback_across_song_versions(self) -> None:
        self.provider.tracks = tuple(Track(f"version:{index}", "Music", "Uploader", 180,
                                          "test") for index in range(3))
        first, second, third = self.provider.tracks
        self.library.favorite(first)
        self.library.create_song("Music", "", [
            ("studio", "Singer", StoredTrack.from_value(first).to_dict()),
            ("live", "Singer", StoredTrack.from_value(second).to_dict()),
        ])
        _, familiar = self.radio(mode="familiar")
        _, discover = self.radio(mode="discover")
        self.assertEqual(first.id, familiar[0][0].get("id"))
        self.assertNotEqual(first.id, discover[0][0].get("id"))
        self.feedback(first.id, "tired")
        _, filtered = self.radio()
        self.assertEqual([third.id], [item[0].get("id") for item in filtered])
        self.request("DELETE", "/api/radio/feedback/" + quote(first.id, safe=""))
        _, restored = self.radio()
        self.assertEqual({first.id, second.id, third.id}, {item[0].get("id") for item in restored})

    def test_neutral_does_not_send_long_term_taste_to_ai(self) -> None:
        self.library.favorite(Track("private-local", "Secret Music", "Private Artist", 180,
                                    "local"))
        self.history.record(Track("heard", "History Secret", "Private Artist", 180, "test"),
                            32)
        AiSettingsStore(self.library.path.with_name("ai-settings.json")).save(
            enabled=True, model="model", base_url="https://example.test/v1/chat/completions",
            api_key="key")
        with patch("lumen_service.server.generate_search_intents",
                   return_value=IntentResult(("Music",), "ai")) as ai:
            status, root = self.radio(neutral=True)
        self.assertEqual(200, status)
        self.assertEqual({}, ai.call_args.kwargs["taste_summary"])
        self.assertNotIn("Secret", str(ai.call_args))
        self.assertNotIn("History", str(ai.call_args))
        self.assertNotIn("private-local", [item[0].get("id") for item in root])
        self.assertNotIn("Secret Music", self.provider.queries)

    def test_feedback_sort_restore_restart_and_zip(self) -> None:
        _, baseline = self.radio()
        first = baseline[0][0].get("id")
        status, root = self.feedback(first, "version")
        self.assertEqual((200, "version"), (status, root[0].get("kind")))
        _, lowered = self.radio()
        self.assertNotEqual(first, lowered[0][0].get("id"))
        status, root = self.feedback("provider:1", "tired")
        self.assertEqual(200, status)
        self.assertTrue(root[-1].get("until").endswith("Z"))
        _, filtered = self.radio()
        self.assertNotIn("provider:1", [item[0].get("id") for item in filtered])
        archive = self.root / "backup.zip"
        export_backup(archive, self.library.path)
        self.assertEqual(inspect_backup(archive).favorite_count, 0)
        with zipfile.ZipFile(archive) as source:
            self.assertEqual(2, len(json.loads(source.read("library.json"))["radioFeedback"]))
        self._stop()
        self.library = LibraryStore(self.library.path)
        self._start()
        _, persisted = self.request("GET", "/api/radio/feedback")
        self.assertEqual(2, len(persisted))
        status, restored = self.request("DELETE", "/api/radio/feedback/" + quote(first, safe=""))
        self.assertEqual((200, 1), (status, len(restored)))

    def test_unknown_feedback_and_provider_failure(self) -> None:
        status, root = self.request("POST", "/api/radio/feedback",
                                    '<feedback kind="skip"><track id="unknown" title="Fake" '
                                    'artist="Fake" source="test"/>'
                                    '</feedback>')
        self.assertEqual((400, "RADIO_FEEDBACK_INVALID"), (status, root.get("code")))
        status, root = self.request("POST", "/api/radio/feedback",
                                    '<feedback kind="skip"><track id="file/C:/music/a.mp3" '
                                    'title="Local" artist="Local" durationSeconds="180" '
                                    'source="local" mediaUri="file:///C:/music/a.mp3"/></feedback>')
        self.assertEqual((200, "skip"), (status, root[0].get("kind")))
        status, root = self.request("DELETE", "/api/radio/feedback/" +
                                    quote("file/C:/music/a.mp3", safe=""))
        self.assertEqual((200, 0), (status, len(root)))
        self.provider.fail = True
        status, root = self.radio()
        self.assertEqual((502, "RADIO_SOURCE_ERROR"), (status, root.get("code")))
        self.library.favorite(Track("local-resource", "Music local", "Uploader", 180,
                                    "local"))
        status, root = self.radio()
        self.assertEqual((200, "insufficient"), (status, root.get("status")))
        self.assertEqual("local-resource", root[0][0].get("id"))

    def test_neutral_ignores_persisted_feedback(self) -> None:
        self.radio()
        self.feedback("provider:0", "tired")
        _, ordinary = self.radio()
        self.assertNotIn("provider:0", [item[0].get("id") for item in ordinary])
        _, neutral = self.radio(neutral=True)
        self.assertIn("provider:0", [item[0].get("id") for item in neutral])
        _, feedback = self.request("GET", "/api/radio/feedback")
        self.assertEqual(1, len(feedback))

    def test_skip_does_not_replace_explicit_feedback_or_write(self) -> None:
        self.radio()
        self.feedback("provider:0", "tired")
        before = self.library.path.read_bytes()
        _, snapshot = self.feedback("provider:0", "skip")
        self.assertEqual("tired", snapshot[0].get("kind"))
        self.assertEqual(before, self.library.path.read_bytes())
        self.feedback("provider:0", "version")
        before = self.library.path.read_bytes()
        _, snapshot = self.feedback("provider:0", "skip")
        self.assertEqual("version", snapshot[0].get("kind"))
        self.assertEqual(before, self.library.path.read_bytes())

    def test_ai_reranks_only_real_safe_candidates_and_falls_back(self) -> None:
        AiSettingsStore(self.library.path.with_name("ai-settings.json")).save(
            enabled=True, model="model", base_url="https://example.test/v1/chat/completions",
            api_key="key")
        self.provider.tracks += (
            Track("file/C:/private.mp3", "Private C:/track", "Uploader", 180, "local"),)
        self.library.favorite(Track("local-private", "C:/secret.mp3", "Local", 180, "local"))
        self.history.record(Track("heard", "History Only", "Secret Author", 180, "test"),
                            32)
        with patch("lumen_service.server.generate_search_intents",
                   return_value=IntentResult(("Music",), "ai")) as intent, \
                patch("lumen_service.server.rerank_candidate_ids",
                      return_value=("invented", "provider:5")) as rerank:
            status, root = self.radio(neutral=True)
        self.assertEqual((200, "provider:5"), (status, root[0][0].get("id")))
        self.assertNotIn("invented", [item[0].get("id") for item in root])
        self.assertEqual({}, intent.call_args.kwargs["taste_summary"])
        shared = rerank.call_args.args[0]
        self.assertNotIn("private", str(shared).lower())
        self.assertNotIn("History Only", str(shared))
        with patch("lumen_service.server.generate_search_intents", side_effect=OSError("offline")), \
                patch("lumen_service.server.rerank_candidate_ids", side_effect=OSError("offline")):
            status, fallback = self.radio(neutral=True)
        self.assertEqual((200, "ready"), (status, fallback.get("status")))

    def test_old_history_is_not_called_recent(self) -> None:
        old = datetime.now(timezone.utc) - timedelta(days=30)
        self.history.record(self.provider.tracks[0], 32, played_at=old)
        self.history.record(self.provider.tracks[1], 32)
        _, root = self.radio()
        reasons = {item[0].get("id"): item.get("reason") for item in root}
        self.assertNotIn("近期", reasons["provider:0"])
        self.assertIn("近期", reasons["provider:1"])

    def test_author_diversity_across_seed_boundary(self) -> None:
        self.provider.tracks = (
            Track("same", "Music same", "Uploader", 180, "test"),
            Track("other", "Music other", "Other", 180, "test"),
        )
        status, root = self.radio(extra='<seed><track id="seed" title="Music seed" '
                                  'artist="Uploader" durationSeconds="180" source="test"/>'
                                  '</seed>')
        self.assertEqual((200, "other"), (status, root[0][0].get("id")))

    def test_known_performer_balances_versions_after_seed(self) -> None:
        self.provider.tracks = (
            Track("same-performer", "Music 1", "Upload A", 180, "test"),
            Track("other-performer", "Music 2", "Upload A", 180, "test"),
        )
        self.library.create_song("Seed", "", [("original", "Singer A", {
            "id": "seed", "title": "Music seed", "artist": "Upload B",
            "durationSeconds": 180, "source": "test"})])
        self.library.create_song("Same", "", [("version", "Singer A", {
            "id": "same-performer", "title": "Music 1", "artist": "Upload A",
            "durationSeconds": 180, "source": "test"})])
        self.library.create_song("Other", "", [("version", "Singer B", {
            "id": "other-performer", "title": "Music 2", "artist": "Upload A",
            "durationSeconds": 180, "source": "test"})])
        status, root = self.radio(extra='<seed><track id="seed" title="Music seed" '
                                  'artist="Upload B" durationSeconds="180" source="test"/>'
                                  '</seed>')
        self.assertEqual((200, "other-performer"), (status, root[0][0].get("id")))

    def test_empty_cold_start_uses_provider_popular(self) -> None:
        self.provider.search = lambda query: ()
        self.provider.music_popular = lambda: self.provider.tracks
        status, root = self.request("POST", "/api/radio",
                                    '<radio mode="discover" hint="" neutral="true"/>')
        self.assertEqual((200, "ready"), (status, root.get("status")))
        self.assertEqual(12, len(root))

    def test_concurrent_feedback_returns_complete_snapshots(self) -> None:
        self.radio()
        responses = []
        workers = [threading.Thread(target=lambda identifier=track.id:
                    responses.append(self.feedback(identifier, "skip")))
                   for track in self.provider.tracks[:6]]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
        self.assertEqual([200] * 6, [status for status, _ in responses])
        self.assertEqual([1, 2, 3, 4, 5, 6], sorted(len(root) for _, root in responses))
        _, snapshot = self.request("GET", "/api/radio/feedback")
        self.assertEqual(6, len(snapshot))
