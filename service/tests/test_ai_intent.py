import json
import unittest
from unittest.mock import patch

from lumen_service.ai_intent import (
    AiIntentConfig, MAX_CANDIDATES, MAX_HINT_CHARS, MAX_RESPONSE_BYTES,
    generate_search_intents, rerank_candidate_ids,
)


CONFIG = AiIntentConfig("https://example.com/v1/chat/completions", "test-model", "secret")


def reply(queries):
    return result_reply({"queries": queries})


def result_reply(value):
    return json.dumps({"choices": [{"message": {"content": json.dumps(
        value, ensure_ascii=False)}}]}, ensure_ascii=False).encode()


def candidate(identifier, title="公开歌曲", artist="公开作者", duration=240, **extras):
    return {"id": identifier, "title": title, "artist": artist,
            "durationSeconds": duration, **extras}


class AiIntentTest(unittest.TestCase):
    def test_no_configuration_uses_local_terms_without_transport(self):
        result = generate_search_intents(
            ["周杰伦", "周杰伦", "晴天"],
            config=AiIntentConfig("http://example.com", "model", "key"),
            transport=lambda *_: self.fail("network should not run"),
        )
        self.assertEqual(("周杰伦 音乐", "晴天 音乐"), result.queries)
        self.assertEqual("fallback", result.source)

    def test_empty_interests_return_empty_state(self):
        result = generate_search_intents([], config=CONFIG,
                                         transport=lambda *_: self.fail("network should not run"))
        self.assertEqual((), result.queries)
        self.assertEqual("fallback", result.source)

    def test_success_uses_bounded_ai_queries_and_minimal_prompt(self):
        def transport(request, timeout):
            self.assertEqual(5.0, timeout)
            self.assertEqual("Bearer secret", request.get_header("Authorization"))
            body = json.loads(request.data)
            self.assertEqual("test-model", body["model"])
            self.assertIn("周杰伦", body["messages"][1]["content"])
            self.assertNotIn("C:\\Music\\private.mp3", body["messages"][1]["content"])
            return reply(["周杰伦 晴天 原曲", "周杰伦 晴天 原曲", "青花瓷", "稻香", "夜曲", "七里香"])

        result = generate_search_intents(["C:\\Music\\private.mp3", "周杰伦"],
                                         config=CONFIG, transport=transport)
        self.assertEqual(("周杰伦 晴天 原曲", "青花瓷", "稻香", "夜曲"), result.queries)
        self.assertEqual("ai", result.source)

    def test_bad_response_and_network_error_fall_back(self):
        for transport in (
            lambda *_: b"not json",
            lambda *_: reply(["https://example.com/file", "\\path"]),
            lambda *_: b"x" * (MAX_RESPONSE_BYTES + 1),
            lambda *_: (_ for _ in ()).throw(OSError("offline")),
        ):
            with self.subTest(transport=transport):
                result = generate_search_intents(["爵士"], config=CONFIG, transport=transport)
                self.assertEqual(("爵士 音乐",), result.queries)
                self.assertEqual("fallback", result.source)

    def test_environment_requires_all_fields(self):
        self.assertIsNone(AiIntentConfig.from_env({"LUMEN_AI_MODEL": "m"}))
        self.assertEqual(CONFIG, AiIntentConfig.from_env({
            "LUMEN_AI_CHAT_COMPLETIONS_URL": CONFIG.chat_completions_url,
            "LUMEN_AI_MODEL": CONFIG.model,
            "LUMEN_AI_API_KEY": CONFIG.api_key,
        }))

    def test_summary_and_hint_are_bounded_and_only_whitelisted_fields_are_shared(self):
        summary = {
            "artists": ["周杰伦", "周杰伦", "C:\\private\\artist"] +
                       [f"作者{i}" for i in range(12)],
            "titles": [f"歌曲{i}" for i in range(15)] + ["x" * 129],
            "musicTags": ["钢琴", "安静", "https://private.example"] +
                          [f"风格{i}" for i in range(12)],
            "entries": [{"lastPlayedAt": "private timestamp", "playCount": 90}],
            "history": "private history",
            "uri": "C:\\private\\song.mp3",
            "apiKey": "private key",
        }

        def transport(request, timeout):
            self.assertEqual("POST", request.get_method())
            self.assertEqual(CONFIG.chat_completions_url, request.full_url)
            self.assertEqual("Bearer secret", request.get_header("Authorization"))
            self.assertEqual("application/json", request.get_header("Content-type"))
            self.assertEqual("application/json", request.get_header("Accept"))
            body = json.loads(request.data)
            context = json.loads(body["messages"][1]["content"])
            self.assertEqual({"interests", "tasteSummary", "hint"}, set(context))
            self.assertEqual({"artists", "titles", "musicTags"}, set(context["tasteSummary"]))
            self.assertEqual((8, 10, 8), tuple(len(context["tasteSummary"][key])
                                             for key in ("artists", "titles", "musicTags")))
            self.assertEqual(MAX_HINT_CHARS, len(context["hint"]))
            self.assertIn("prioritize", body["messages"][0]["content"])
            self.assertIn("do not save", body["messages"][0]["content"])
            self.assertNotIn("private", body["messages"][1]["content"])
            return reply(["安静 钢琴 纯音乐"])

        result = generate_search_intents(["爵士"], taste_summary=summary,
                                         hint="安静钢琴" * 50, config=CONFIG,
                                         transport=transport)
        self.assertEqual("ai", result.source)
        self.assertEqual(("安静 钢琴 纯音乐",), result.queries)
        self.assertEqual("private history", summary["history"])

    def test_hint_or_summary_can_plan_without_interest_terms(self):
        for context in ({"hint": "想听点安静的钢琴"},
                        {"taste_summary": {"musicTags": ["钢琴"]}}):
            with self.subTest(context=context):
                result = generate_search_intents([], config=CONFIG,
                                                 transport=lambda *_: reply(["钢琴 纯音乐"]),
                                                 **context)
                self.assertEqual("ai", result.source)
                self.assertEqual(("钢琴 纯音乐",), result.queries)

    def test_invalid_summary_values_and_control_hint_do_not_create_context(self):
        result = generate_search_intents(
            [], taste_summary={"artists": "private", "titles": None, "musicTags": [1]},
            hint="钢琴\x00", config=CONFIG,
            transport=lambda *_: self.fail("network should not run"))
        self.assertEqual("fallback", result.source)
        self.assertEqual((), result.queries)


class AiCandidateOrderTest(unittest.TestCase):
    def setUp(self):
        self.candidates = [candidate("bilibili:BV1111111111:p1"),
                           candidate("bilibili:BV2222222222:p2"),
                           candidate("bilibili:BV3333333333:p1")]
        self.ids = tuple(item["id"] for item in self.candidates)

    def test_real_ids_only_duplicates_removed_and_omissions_appended(self):
        result = rerank_candidate_ids(
            self.candidates, config=CONFIG,
            transport=lambda *_: result_reply({"ids": [self.ids[1], "invented song",
                                                        self.ids[1], None, {"id": self.ids[2]}]}))
        self.assertEqual((self.ids[1], self.ids[0], self.ids[2]), result)
        self.assertEqual(set(self.ids), set(result))

    def test_request_sends_public_metadata_summary_and_hint_only(self):
        candidates = [candidate(self.ids[0], title="公开曲名", artist="公开作者",
                                source="private source", uri="file:///private/song.mp3",
                                coverUri="https://private.example", playCount=23,
                                lastPlayedAt="private timestamp", localPath="private path")]

        def transport(request, timeout):
            self.assertEqual(5.0, timeout)
            self.assertEqual("Bearer secret", request.get_header("Authorization"))
            body = json.loads(request.data)
            context = json.loads(body["messages"][1]["content"])
            self.assertEqual({"candidates", "tasteSummary", "hint"}, set(context))
            self.assertEqual([{key: candidates[0][key] for key in
                               ("id", "title", "artist", "durationSeconds")}], context["candidates"])
            self.assertEqual({"artists": ["收藏作者"], "musicTags": ["钢琴"]}, context["tasteSummary"])
            self.assertEqual("想听点安静的钢琴", context["hint"])
            self.assertNotIn("private", body["messages"][1]["content"])
            self.assertIn("Prioritize", body["messages"][0]["content"])
            self.assertIn("must not be saved", body["messages"][0]["content"])
            self.assertIn("already ordered by local taste", body["messages"][0]["content"])
            self.assertIn("preserve the supplied order", body["messages"][0]["content"])
            return result_reply({"ids": [self.ids[0]]})

        result = rerank_candidate_ids(candidates, config=CONFIG, transport=transport,
                                     taste_summary={"artists": ["收藏作者"], "musicTags": ["钢琴"],
                                                    "history": "private"}, hint="想听点安静的钢琴")
        self.assertEqual((self.ids[0],), result)
        self.assertIn("source", candidates[0])

    def test_bad_responses_and_network_errors_preserve_order(self):
        for transport in (
            lambda *_: b"not json",
            lambda *_: result_reply({"ids": "not an array"}),
            lambda *_: result_reply({"ids": ["invented"]}),
            lambda *_: result_reply({"ids": []}),
            lambda *_: result_reply({"queries": ["钢琴"]}),
            lambda *_: result_reply([]),
            lambda *_: b"x" * (MAX_RESPONSE_BYTES + 1),
            lambda *_: json.dumps({"choices": []}).encode(),
            lambda *_: (_ for _ in ()).throw(OSError("offline")),
        ):
            with self.subTest(transport=transport):
                self.assertEqual(self.ids, rerank_candidate_ids(self.candidates, config=CONFIG,
                                                               transport=transport))

    def test_empty_and_unconfigured_inputs_do_not_request(self):
        no_transport = lambda *_: self.fail("network should not run")
        self.assertEqual((), rerank_candidate_ids([], config=CONFIG, transport=no_transport))
        invalid = AiIntentConfig("http://example.com", "model", "key")
        self.assertEqual(self.ids, rerank_candidate_ids(self.candidates, config=invalid,
                                                      transport=no_transport))
        with patch.dict("os.environ", {}, clear=True):
            self.assertEqual(self.ids, rerank_candidate_ids(self.candidates, transport=no_transport))

    def test_candidate_count_and_fields_are_bounded_without_losing_omitted_ids(self):
        candidates = [candidate(f"bilibili:BV{i:010d}:p1", title="x" * 300, artist="y" * 200,
                                duration=float("nan")) for i in range(MAX_CANDIDATES + 2)]
        ids = tuple(item["id"] for item in candidates)

        def transport(request, _):
            context = json.loads(json.loads(request.data)["messages"][1]["content"])
            self.assertEqual(MAX_CANDIDATES, len(context["candidates"]))
            self.assertEqual(160, len(context["candidates"][0]["title"]))
            self.assertEqual(80, len(context["candidates"][0]["artist"]))
            self.assertNotIn("durationSeconds", context["candidates"][0])
            self.assertEqual(MAX_HINT_CHARS, len(context["hint"]))
            return result_reply({"ids": [ids[-1], ids[1]]})

        result = rerank_candidate_ids(candidates, config=CONFIG, transport=transport,
                                     hint="h" * 300)
        self.assertEqual((ids[1], ids[0], *ids[2:]), result)

    def test_duplicate_invalid_candidates_and_bad_durations_are_sanitized(self):
        candidates = [candidate(self.ids[0], duration=10 ** 1000), candidate(self.ids[0]),
                      candidate(self.ids[1], duration=True), candidate(self.ids[2], duration=-1),
                      candidate("file:///private.mp3"), candidate("x" * 161),
                      candidate("bad\x00id"), {}, None]

        def transport(request, _):
            rows = json.loads(json.loads(request.data)["messages"][1]["content"])["candidates"]
            self.assertEqual(self.ids, tuple(row["id"] for row in rows))
            self.assertTrue(all("durationSeconds" not in row for row in rows))
            return result_reply({"ids": list(reversed(self.ids))})

        self.assertEqual(tuple(reversed(self.ids)), rerank_candidate_ids(candidates, config=CONFIG,
                                                                        transport=transport))


if __name__ == "__main__":
    unittest.main()
