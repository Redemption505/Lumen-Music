from __future__ import annotations

import http.client
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote
from xml.etree import ElementTree

from lumen_service.ai_intent import IntentResult
from lumen_service.ai_settings import AiSettingsStore
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.models import Track
from lumen_service.providers import BilibiliProvider, ProviderError
from lumen_service.server import create_server


class DiscoveryHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.library = LibraryStore(root / "library.json")
        self.history = HistoryStore(root / "history.json")
        self.queries: list[str] = []
        self.popular_calls = 0
        self.env = patch.dict(os.environ, {"LUMEN_AI_CHAT_COMPLETIONS_URL": "",
                                          "LUMEN_AI_MODEL": "", "LUMEN_AI_API_KEY": "",
                                          "LUMEN_AI_SETTINGS_PATH": ""})
        self.env.start()

        class Provider(BilibiliProvider):
            def __init__(inner) -> None:
                pass

            def search(inner, query: str):
                self.queries.append(query)
                return (
                    Track("bilibili:BV1xx411c7mD:p1", "晴天 翻唱", "路人", 240,
                          "bilibili", "BV1xx411c7mD", 1, None, 1),
                    Track("bilibili:BV1xx411c7mE:p1", "晴天 官方原曲", "周杰伦", 240,
                          "bilibili", "BV1xx411c7mE", 1, None, 1),
                    Track("bilibili:BV1xx411c7mF:p1", "周杰伦歌曲合集", "周杰伦", 1800,
                          "bilibili", "BV1xx411c7mF", 1, None, 3),
                )

            def music_popular(inner):
                self.popular_calls += 1
                return (
                    Track("popular-first", "热门第一名 翻唱", "热门作者", 220, "bilibili"),
                    Track("popular-second", "热门第二名 原曲", "热门作者", 240, "bilibili"),
                )

            def resolve_bv(inner, value: str):
                self.queries.append("BV:" + value)
                return (
                    Track("bilibili:BV1xx411c7mF:p1", "七里香", "周杰伦", 250,
                          "bilibili", "BV1xx411c7mF", 1, None, 2),
                    Track("bilibili:BV1xx411c7mF:p2", "晴天", "周杰伦", 240,
                          "bilibili", "BV1xx411c7mF", 2, None, 2),
                )

        self.provider = Provider()
        self.server = create_server(port=0, provider=self.provider, library_store=self.library,
                                    history_store=self.history)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()
        self.env.stop()

    def enable_ai(self) -> None:
        AiSettingsStore(self.library.path.with_name("ai-settings.json")).save(
            enabled=True, model="saved-model",
            base_url="https://example.test/v1/chat/completions", api_key="saved-key")

    def get(self, path: str) -> tuple[int, ElementTree.Element]:
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            conn.request("GET", path)
            response = conn.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            conn.close()

    def test_search_reranks_and_filters_by_part_count(self) -> None:
        status, root = self.get("/api/tracks?q=" + quote("周杰伦 晴天"))
        self.assertEqual(200, status)
        self.assertEqual("晴天 官方原曲", root[0].get("title"))
        self.assertIn("3", [node.get("partCount") for node in root])
        status, single = self.get("/api/tracks?q=x&kind=single")
        self.assertEqual(200, status)
        self.assertEqual(2, len(single))
        status, collection = self.get("/api/tracks?q=x&kind=collection")
        self.assertEqual(200, status)
        self.assertEqual(["周杰伦歌曲合集"], [node.get("title") for node in collection])
        status, error = self.get("/api/tracks?q=x&kind=other")
        self.assertEqual((400, "INVALID_KIND"), (status, error.get("code")))

    def test_bv_link_resolves_pages_in_order(self) -> None:
        link = "https://www.bilibili.com/video/BV1xx411c7mF?p=2"
        status, root = self.get("/api/tracks?q=" + quote(link, safe=""))
        self.assertEqual(200, status)
        self.assertEqual(["1", "2"], [node.get("page") for node in root])
        self.assertEqual(["七里香", "晴天"], [node.get("title") for node in root])
        self.assertEqual(["2", "2"], [node.get("partCount") for node in root])
        self.assertEqual(["BV:" + link], self.queries)

    def test_search_rerank_can_be_disabled_and_invalid_values_are_rejected(self) -> None:
        query = quote("周杰伦 晴天")
        status, root = self.get("/api/tracks?q=" + query + "&rerank=false")
        self.assertEqual(200, status)
        self.assertEqual("晴天 翻唱", root[0].get("title"))
        status, root = self.get("/api/tracks?q=" + query + "&rerank=true")
        self.assertEqual(200, status)
        self.assertEqual("晴天 官方原曲", root[0].get("title"))
        queries_before = list(self.queries)
        for value in ("", "1", "True", "true&rerank=false"):
            status, root = self.get("/api/tracks?q=x&rerank=" + value)
            self.assertEqual((400, "INVALID_RERANK"), (status, root.get("code")))
        self.assertEqual(queries_before, self.queries)

    def test_collection_kind_releases_duration_penalty_before_filtering(self) -> None:
        self.provider.search = lambda query: (
            Track("long", "晴天", "周杰伦", 1800, "bilibili", "long", 1, None, 2),
            Track("short", "晴天", "周杰伦", 240, "bilibili", "short", 1, None, 2),
        )
        status, root = self.get("/api/tracks?q=" + quote("晴天"))
        self.assertEqual((200, "short"), (status, root[0].get("id")))
        status, root = self.get("/api/tracks?q=" + quote("晴天") + "&kind=collection")
        self.assertEqual(200, status)
        self.assertEqual(["long", "short"], [node.get("id") for node in root])

    def test_bv_direct_order_is_preserved_with_both_rerank_settings(self) -> None:
        for rerank in ("true", "false"):
            status, root = self.get("/api/tracks?q=BV1xx411c7mF&rerank=" + rerank)
            self.assertEqual(200, status)
            self.assertEqual(["1", "2"], [node.get("page") for node in root])

    def test_collection_route_returns_all_parts_for_selected_result(self) -> None:
        status, root = self.get("/api/collections/BV1xx411c7mF/tracks")
        self.assertEqual(200, status)
        self.assertEqual(["1", "2"], [node.get("page") for node in root])
        self.assertEqual(["七里香", "晴天"], [node.get("title") for node in root])

    def test_filter_verifies_unknown_counts_without_guessing(self) -> None:
        self.provider.search = lambda query: (
            Track("a", "单曲", "甲", 90, "bilibili", "BV1xx411c7mD", 1),
            Track("b", "合集", "乙", 900, "bilibili", "BV1xx411c7mF", 1),
        )
        self.provider.page_count = lambda bvid: 1 if bvid.endswith("D") else 3
        status, root = self.get("/api/tracks?q=x&kind=single")
        self.assertEqual(200, status)
        self.assertEqual(["单曲"], [node.get("title") for node in root])
        self.assertEqual("0", root.get("unresolvedCount"))
        status, root = self.get("/api/tracks?q=x&kind=collection")
        self.assertEqual(200, status)
        self.assertEqual(["合集"], [node.get("title") for node in root])

    def test_ai_uses_saved_taste_without_transmitting_listening_history(self) -> None:
        self.enable_ai()
        self.library.favorite(Track("favorite", "晴天", "周杰伦", 240, "demo"))
        self.history.record({"id": "heard", "title": "历史独有歌曲", "artist": "历史独有作者",
                                       "durationSeconds": 240, "source": "bilibili",
                                       "bvid": "BV1xx411c7mD", "page": 1}, 32)
        with patch("lumen_service.server.generate_search_intents",
                   return_value=IntentResult(("周杰伦 晴天",), "ai")) as ai, \
                patch("lumen_service.server.rerank_candidate_ids", return_value=()):
            status, root = self.get("/api/recommendations")
        self.assertEqual(200, status)
        self.assertEqual("ai", root.get("tasteSource"))
        self.assertEqual("周杰伦 晴天", root.get("intents"))
        self.assertEqual("晴天 官方原曲", root[0].get("title"))
        self.assertEqual(["周杰伦 晴天"], self.queries)
        ai.assert_called_once()
        self.assertEqual(("周杰伦 晴天", "周杰伦"), ai.call_args.args[0])
        self.assertNotIn("历史独有", str(ai.call_args.args))

    def test_recommendations_use_saved_ai_settings(self) -> None:
        self.enable_ai()
        self.history.record({"id": "heard", "title": "晴天", "artist": "周杰伦",
                             "durationSeconds": 240, "source": "bilibili",
                             "bvid": "BV1xx411c7mD", "page": 1}, 32)
        with patch("lumen_service.server.generate_search_intents",
                   return_value=IntentResult(("周杰伦 晴天",), "ai")) as ai, \
                patch("lumen_service.server.rerank_candidate_ids", return_value=()):
            status, root = self.get("/api/recommendations")
        self.assertEqual((200, "ai"), (status, root.get("tasteSource")))
        self.assertEqual("saved-model", ai.call_args.kwargs["config"].model)
        self.assertEqual("saved-key", ai.call_args.kwargs["config"].api_key)

    def test_no_key_returns_real_music_popular_in_provider_order(self) -> None:
        self.library.favorite(Track("favorite", "晴天", "周杰伦", 240, "demo"))
        for enabled_without_key in (False, True):
            with self.subTest(enabled_without_key=enabled_without_key):
                if enabled_without_key:
                    AiSettingsStore(self.library.path.with_name("ai-settings.json")).save(
                        enabled=True, model="saved-model",
                        base_url="https://example.test/v1/chat/completions")
                with patch("lumen_service.server.generate_search_intents") as ai, \
                        patch("lumen_service.server.rerank_candidate_ids") as rerank:
                    status, root = self.get("/api/recommendations")
                self.assertEqual((200, "popular"), (status, root.get("tasteSource")))
                self.assertEqual(["popular-first", "popular-second"],
                                 [node.get("id") for node in root])
                self.assertEqual("", root.get("intents"))
                self.assertEqual([], self.queries)
                ai.assert_not_called()
                rerank.assert_not_called()
        self.assertEqual(2, self.popular_calls)

    def test_popular_provider_failure_is_visible(self) -> None:
        self.provider.music_popular = lambda: (_ for _ in ()).throw(
            ProviderError("UPSTREAM_TIMEOUT", "music ranking timed out", 504))
        status, root = self.get("/api/recommendations")
        self.assertEqual((504, "UPSTREAM_TIMEOUT"), (status, root.get("code")))
        self.assertEqual([], self.queries)

    def test_failed_ai_hint_uses_local_search_and_never_writes_taste(self) -> None:
        self.enable_ai()
        saved = Track("saved", "晴天", "周杰伦", 240, "demo")
        self.library.favorite(saved)
        self.library.create_playlist("原有歌单")
        self.history.record(saved, 30)
        paths = [self.library.path, self.history.path,
                 self.library.path.with_name("ai-settings.json"),
                 self.library.path.with_name("ai-secret.json")]
        before = [path.read_bytes() for path in paths]
        with patch("lumen_service.server.generate_search_intents",
                   return_value=IntentResult((), "fallback")) as ai, \
                patch("lumen_service.server.rerank_candidate_ids", return_value=()):
            status, root = self.get("/api/recommendations?hint=" + quote("安静一点的钢琴曲"))
        self.assertEqual((200, "fallback"), (status, root.get("tasteSource")))
        self.assertEqual("钢琴 安静 纯音乐", root.get("intents"))
        self.assertEqual(["钢琴 安静 纯音乐"], self.queries)
        self.assertEqual(0, self.popular_calls)
        ai.assert_called_once()
        self.assertEqual("安静一点的钢琴曲", ai.call_args.kwargs["hint"])
        self.assertEqual(before, [path.read_bytes() for path in paths])
        with patch("lumen_service.server.generate_search_intents",
                   return_value=IntentResult((), "fallback")), \
                patch("lumen_service.server.rerank_candidate_ids", return_value=()):
            status, root = self.get("/api/recommendations")
        self.assertEqual(200, status)
        self.assertNotIn("钢琴", root.get("intents"))
        self.assertEqual(before, [path.read_bytes() for path in paths])

    def test_no_key_temporary_hint_searches_current_wish(self) -> None:
        with patch("lumen_service.server.generate_search_intents") as ai, \
                patch("lumen_service.server.rerank_candidate_ids") as rerank:
            status, root = self.get("/api/recommendations?hint=" + quote("助眠钢琴"))
        self.assertEqual((200, "钢琴 睡眠 纯音乐"), (status, root.get("intents")))
        self.assertEqual(["钢琴 睡眠 纯音乐"], self.queries)
        self.assertEqual(0, self.popular_calls)
        ai.assert_not_called()
        rerank.assert_not_called()

    def test_invalid_hint_is_rejected_before_provider_or_ai(self) -> None:
        with patch("lumen_service.server.generate_search_intents") as ai:
            for query in ("hint=x&hint=y", "hint=" + "x" * 161, "hint=x%0A", "hint=x%00"):
                with self.subTest(query=query):
                    status, root = self.get("/api/recommendations?" + query)
                    self.assertEqual((400, "INVALID_HINT"), (status, root.get("code")))
        ai.assert_not_called()
        self.assertEqual([], self.queries)
        self.assertEqual(0, self.popular_calls)

    @staticmethod
    def ai_response(content: dict) -> bytes:
        return json.dumps({"choices": [{"message": {"content": json.dumps(content)}}]}).encode()

    def test_ai_round_trip_shares_saved_summary_and_hint_but_not_history(self) -> None:
        self.enable_ai()
        self.library.favorite(Track("favorite", "收藏钢琴作品", "收藏作者", 240, "demo"))
        playlist = self.library.create_playlist("安静钢琴")["playlists"][0]
        self.library.add_to_playlist(playlist["id"],
                                     Track("playlist", "歌单独有作品", "歌单作者", 230, "demo"))
        self.history.record(Track("history-private-id", "历史独有作品", "历史独有作者",
                                  180, "demo"), 31)
        paths = [self.library.path, self.history.path,
                 self.library.path.with_name("ai-settings.json"),
                 self.library.path.with_name("ai-secret.json")]
        before = [path.read_bytes() for path in paths]
        outbound = []
        hinted = "想听点安静的钢琴"

        def transport(request, timeout):
            body = json.loads(request.data)
            self.assertEqual("https://example.test/v1/chat/completions", request.full_url)
            self.assertEqual("Bearer saved-key", request.get_header("Authorization"))
            self.assertEqual("saved-model", body["model"])
            data = json.loads(body["messages"][1]["content"])
            outbound.append(data)
            if "candidates" not in data:
                return self.ai_response({"queries": ["安静 钢琴"]})
            candidates = data["candidates"]
            return self.ai_response({"ids": ["invented-track", candidates[-1]["id"],
                                              candidates[-1]["id"]]})

        with patch("lumen_service.ai_intent._transport", side_effect=transport) as ai:
            status, root = self.get("/api/recommendations?hint=" + quote(hinted))
        self.assertEqual((200, "ai"), (status, root.get("tasteSource")))
        self.assertEqual("安静 钢琴", root.get("intents"))
        self.assertEqual(["安静 钢琴"], self.queries)
        self.assertEqual(2, ai.call_count)
        self.assertEqual({"interests", "tasteSummary", "hint"}, set(outbound[0]))
        self.assertEqual({"tasteSummary", "hint", "candidates"}, set(outbound[1]))
        for payload in outbound:
            self.assertEqual(hinted, payload["hint"])
            summary = payload["tasteSummary"]
            self.assertEqual({"artists", "titles", "musicTags"}, set(summary))
            self.assertIn("收藏作者", summary["artists"])
            self.assertIn("歌单作者", summary["artists"])
            self.assertIn("收藏钢琴作品", summary["titles"])
            self.assertIn("歌单独有作品", summary["titles"])
            self.assertIn("钢琴", summary["musicTags"])
            serialized = json.dumps(payload, ensure_ascii=False)
            for private in ("历史独有", "history-private-id", "playCount", "listenedSeconds",
                            "lastPlayedAt", "firstPlayedAt", "sessions"):
                self.assertNotIn(private, serialized)
        self.assertEqual(outbound[0]["tasteSummary"], outbound[1]["tasteSummary"])
        candidates = outbound[1]["candidates"]
        self.assertEqual(3, len(candidates))
        for candidate in candidates:
            self.assertEqual({"id", "title", "artist", "durationSeconds"}, set(candidate))
        expected = [candidates[-1]["id"]] + [row["id"] for row in candidates[:-1]]
        self.assertEqual(expected, [row.get("id") for row in root])
        self.assertNotEqual(candidates[0]["id"], root[0].get("id"))
        self.assertEqual(before, [path.read_bytes() for path in paths])

    def test_ai_intent_network_failure_keeps_hint_and_can_still_rerank(self) -> None:
        self.enable_ai()
        outbound = []

        def transport(request, timeout):
            data = json.loads(json.loads(request.data)["messages"][1]["content"])
            outbound.append(data)
            if "candidates" not in data:
                raise OSError("intent endpoint temporarily unavailable")
            return self.ai_response({"ids": [data["candidates"][-1]["id"]]})

        with patch("lumen_service.ai_intent._transport", side_effect=transport):
            status, root = self.get("/api/recommendations?hint=" + quote("安静钢琴"))
        self.assertEqual((200, "fallback"), (status, root.get("tasteSource")))
        self.assertEqual("钢琴 安静 纯音乐", root.get("intents"))
        self.assertEqual(["钢琴 安静 纯音乐"], self.queries)
        self.assertEqual(2, len(outbound))
        self.assertEqual(outbound[1]["candidates"][-1]["id"], root[0].get("id"))

    def test_ai_rerank_failure_preserves_real_local_order(self) -> None:
        self.enable_ai()
        self.library.favorite(Track("favorite", "晴天", "周杰伦", 240, "demo"))
        for failure in (OSError("rerank endpoint temporarily unavailable"), b"not json"):
            with self.subTest(failure=type(failure).__name__):
                outbound = []

                def transport(request, timeout):
                    data = json.loads(json.loads(request.data)["messages"][1]["content"])
                    outbound.append(data)
                    if "candidates" not in data:
                        return self.ai_response({"queries": ["周杰伦 晴天"]})
                    if isinstance(failure, Exception):
                        raise failure
                    return failure

                with patch("lumen_service.ai_intent._transport", side_effect=transport):
                    status, root = self.get("/api/recommendations")
                self.assertEqual((200, "ai"), (status, root.get("tasteSource")))
                self.assertEqual(2, len(outbound))
                self.assertEqual([row["id"] for row in outbound[1]["candidates"]],
                                 [row.get("id") for row in root])
                self.assertEqual("晴天 官方原曲", root[0].get("title"))


if __name__ == "__main__":
    unittest.main()
