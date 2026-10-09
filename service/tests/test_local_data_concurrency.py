from __future__ import annotations

import http.client
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service.ai_intent import AiIntentConfig
from lumen_service.ai_settings import AiSettingsStore
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.models import Track
from lumen_service.server import create_server


class LocalDataConcurrencyTest(unittest.TestCase):
    def test_slow_recommendations_do_not_block_saved_library_or_history(self) -> None:
        self.assert_slow_recommendations_do_not_block_local_data(ai_stage="none")

    def test_slow_ai_does_not_block_saved_library_or_history(self) -> None:
        self.assert_slow_recommendations_do_not_block_local_data(ai_stage="intent")

    def test_slow_ai_rerank_does_not_block_saved_library_or_history(self) -> None:
        self.assert_slow_recommendations_do_not_block_local_data(ai_stage="rerank")

    def assert_slow_recommendations_do_not_block_local_data(self, *, ai_stage: str) -> None:
        entered_search = threading.Event()
        release_search = threading.Event()
        track = Track("saved-track", "月光航线", "林间回声", 180, "demo")
        provider_operations = []

        def wait_for_release():
            entered_search.set()
            if not release_search.wait(timeout=10):
                raise TimeoutError("test did not release the recommendation request")

        class SlowProvider:
            name = "slow-test"

            def search(self, query: str):
                provider_operations.append("search")
                if ai_stage == "none":
                    wait_for_release()
                return (track,)

            def music_popular(self):
                provider_operations.append("popular")
                wait_for_release()
                return (track,)

        def fake_generate(queries, **kwargs):
            if ai_stage == "intent":
                wait_for_release()
            return SimpleNamespace(source="fallback", queries=queries)

        def fake_rerank(candidates, **kwargs):
            if ai_stage == "rerank":
                wait_for_release()
            return tuple(candidate["id"] for candidate in candidates)

        config = AiIntentConfig("https://example.test/v1/chat/completions", "test", "key")

        with tempfile.TemporaryDirectory() as directory:
            library = LibraryStore(Path(directory) / "library.json")
            library.favorite(track)
            playlist = library.create_playlist("已保存歌单")["playlists"][0]
            library.add_to_playlist(playlist["id"], track)
            history = HistoryStore(Path(directory) / "history.json")
            history.record(track, 30)
            saved_bytes = (library.path.read_bytes(), history.path.read_bytes())
            server = create_server(port=0, provider=SlowProvider(),
                                   library_store=library, history_store=history)
            server_thread = threading.Thread(target=server.serve_forever, daemon=True)
            server_thread.start()

            def request(path: str, timeout: float = 1) -> tuple[int, bytes]:
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port,
                                                        timeout=timeout)
                try:
                    connection.request("GET", path)
                    response = connection.getresponse()
                    return response.status, response.read()
                finally:
                    connection.close()

            try:
                before = {path: request(path) for path in ("/api/library", "/api/history")}
                self.assertEqual([200, 200], [value[0] for value in before.values()])
                self.assertEqual(1, len(ElementTree.fromstring(before["/api/library"][1])
                                       .findall("favorites/track")))
                self.assertEqual(1, len(ElementTree.fromstring(before["/api/library"][1])
                                       .findall("playlists/playlist/track")))
                self.assertEqual(1, len(ElementTree.fromstring(before["/api/history"][1])
                                       .findall("entry")))

                # No AI endpoint or real provider is contacted by this regression.
                with patch.object(AiSettingsStore, "config", return_value=config if ai_stage != "none" else None), \
                        patch("lumen_service.server.generate_search_intents", side_effect=fake_generate), \
                        patch("lumen_service.server.rerank_candidate_ids", side_effect=fake_rerank):
                    with ThreadPoolExecutor(max_workers=3) as requests:
                        recommendation = requests.submit(request, "/api/recommendations", 8)
                        try:
                            self.assertTrue(entered_search.wait(timeout=2),
                                            "recommendations never entered the blocked network stage")
                            if ai_stage == "intent":
                                self.assertEqual([], provider_operations)
                            elif ai_stage == "rerank":
                                self.assertTrue(provider_operations)
                                self.assertEqual({"search"}, set(provider_operations))
                            else:
                                self.assertEqual(["popular"], provider_operations)
                            reads = {path: requests.submit(request, path) for path in before}
                            failures = []
                            for path, pending in reads.items():
                                try:
                                    self.assertEqual(before[path], pending.result(timeout=2))
                                except (TimeoutError, AssertionError) as error:
                                    failures.append(f"{path}: {type(error).__name__}: {error}")
                            self.assertFalse(release_search.is_set())
                            self.assertFalse(recommendation.done(),
                                             "local data must load while search is still pending")
                            self.assertEqual(saved_bytes,
                                             (library.path.read_bytes(), history.path.read_bytes()))
                            self.assertEqual([], failures,
                                             "local data was blocked by recommendation search")
                        finally:
                            release_search.set()
                            self.assertEqual(200, recommendation.result(timeout=3)[0])
            finally:
                release_search.set()
                server.shutdown()
                server.server_close()
                server_thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
