import copy
import unittest

from lumen_service.discovery import (
    MAX_HINT_CHARS, build_taste_profile, local_hint_intents, normalize_hint,
    order_candidate_ids, rank_tracks, recommend, relevance_score,
)
from lumen_service.models import Track
from lumen_service.providers import ProviderError


def track(identifier, title, artist="周杰伦"):
    return Track(identifier, title, artist, 240, "bilibili", identifier, 1)


class Provider:
    def __init__(self, results, failing=()):
        self.results = results
        self.failing = set(failing)
        self.queries = []

    @property
    def name(self):
        return "test"

    def search(self, query):
        self.queries.append(query)
        if query in self.failing:
            raise ProviderError("UPSTREAM_UNAVAILABLE", "failed")
        return self.results.get(query, ())


class DiscoveryTests(unittest.TestCase):
    def test_original_song_beats_cover_and_stable_equal_candidates(self):
        cover = track("BV1", "晴天 翻唱", "其他歌手")
        original = track("BV2", "晴天", "周杰伦")
        live = track("BV3", "周杰伦 晴天 Live")
        ranked = rank_tracks("周杰伦 晴天", (cover, live, original))
        self.assertEqual(ranked[0], original)
        self.assertEqual(set(ranked), {cover, live, original})
        self.assertEqual(rank_tracks("未知查询", (cover, live)), (cover, live))
        self.assertEqual(rank_tracks("", (cover, live)), (cover, live))

    def test_explicit_live_query_does_not_demote_live(self):
        live = track("BV3", "晴天 Live")
        original = track("BV2", "晴天")
        self.assertEqual(rank_tracks("晴天 live", (original, live))[0], live)

    def test_coverage_beats_repeated_partial_hits_and_is_normalized(self):
        partial = track("partial", "晴天 晴天 晴天", "无关作者")
        complete = track("complete", "晴天", "周杰伦")
        self.assertEqual(rank_tracks("周杰伦 晴天", (partial, complete))[0], complete)
        self.assertEqual(relevance_score("周杰伦 晴天", complete),
                         relevance_score("周杰伦 周杰伦 晴天 晴天", complete))

    def test_phrase_match_and_long_titles_keep_their_signal(self):
        phrase = track("phrase", "周杰伦 晴天 高清音频", "音乐频道")
        separated = track("separated", "周杰伦精选 晴天 高清音频", "音乐频道")
        long_title = track("long", "周杰伦 晴天 高清音频 " + "说明" * 100, "音乐频道")
        self.assertGreater(relevance_score("周杰伦 晴天", phrase),
                           relevance_score("周杰伦 晴天", separated))
        self.assertEqual(relevance_score("周杰伦 晴天", phrase),
                         relevance_score("周杰伦 晴天", long_title))

    def test_duration_prior_is_released_for_explicit_collections(self):
        long = Track("long", "晴天 合集", "周杰伦", 1800, "bilibili", "long", 1)
        single = Track("single", "晴天 合集", "周杰伦", 240, "bilibili", "single", 1)
        self.assertEqual(rank_tracks("晴天", (long, single)), (single, long))
        self.assertEqual(rank_tracks("晴天 合集", (long, single)), (long, single))
        self.assertEqual(rank_tracks("晴天", (long, single), kind="collection"), (long, single))
        unknown = Track("unknown", "晴天 合集", "周杰伦", 0, "bilibili", "unknown", 1)
        self.assertGreater(relevance_score("晴天", single), relevance_score("晴天", unknown))
        self.assertGreater(relevance_score("晴天", unknown), relevance_score("晴天", long))

    def test_disable_preserves_provider_order_and_prior_stays_weak(self):
        wrong = track("wrong", "无关曲目", "无关作者")
        exact = track("exact", "晴天", "周杰伦")
        self.assertEqual(rank_tracks("晴天", (wrong, exact), rerank=False), (wrong, exact))
        self.assertEqual(rank_tracks("晴天", (wrong, exact)), (exact, wrong))
        twin = track("twin", "晴天", "周杰伦")
        self.assertEqual(rank_tracks("晴天", (twin, exact)), (twin, exact))

    def test_history_play_counts_drive_taste_and_real_search(self):
        library = {"favorites": [{"id": "old", "title": "晴天", "artist": "周杰伦"}],
                   "playlists": []}
        history = {"entries": [
            {"track": {"id": "history", "title": "海阔天空", "artist": "Beyond"},
             "playCount": 4},
            {"track": {"id": "other", "title": "晴天", "artist": "周杰伦"},
             "playCount": 1},
        ]}
        profile = build_taste_profile(library, history)
        self.assertEqual(profile.artists[0], "Beyond")
        self.assertEqual(profile.queries[0], "Beyond 海阔天空")
        real = track("BV1", "海阔天空", "Beyond")
        provider = Provider({"Beyond 海阔天空": (real,)})
        result = recommend(provider, library, history)
        self.assertEqual(result.tracks, (real,))
        self.assertEqual(provider.queries, list(result.intents))
        self.assertFalse(result.cold_start)

    def test_optional_intent_still_searches_provider_and_deduplicates(self):
        one = track("BV1", "晴天", "周杰伦")
        duplicate = Track("different-id", "晴天", "周杰伦", 240,
                          "bilibili", "BV1", 1)
        two = track("BV2", "稻香", "周杰伦")
        provider = Provider({"A": (one, two), "B": (duplicate,)})
        result = recommend(provider, {}, {}, intent_generator=lambda _: ["A", "B"])
        self.assertEqual(provider.queries, ["A", "B"])
        self.assertEqual(len(result.tracks), 2)
        self.assertEqual(set(result.tracks), {one, two})

    def test_cold_start_fallback_and_upstream_errors(self):
        provider = Provider({"热门音乐": (track("BV1", "歌曲"),)})
        result = recommend(provider, {}, {}, intent_generator=lambda _: 1 / 0)
        self.assertTrue(result.cold_start)
        self.assertEqual(result.tracks[0].id, "BV1")
        self.assertEqual(provider.queries, ["热门音乐", "华语音乐"])
        self.assertEqual(recommend(Provider({}), {}, {}).tracks, ())
        with self.assertRaises(ProviderError):
            recommend(Provider({}, failing={"热门音乐", "华语音乐"}), {}, {})

    def test_partial_failure_keeps_successful_real_candidate(self):
        item = track("BV1", "晴天")
        provider = Provider({"华语音乐": (item,)}, failing={"热门音乐"})
        result = recommend(provider, {}, {})
        self.assertEqual(result.tracks, (item,))
        self.assertEqual(result.failed_intents, ("热门音乐",))

    def test_hint_is_bounded_and_extracts_current_music_wish(self):
        self.assertEqual("安静一点的钢琴曲", normalize_hint("  安静一点的钢琴曲  "))
        self.assertEqual(("钢琴 安静 纯音乐",), local_hint_intents("安静一点的钢琴曲"))
        self.assertEqual(("钢琴 学习 纯音乐",), local_hint_intents("piano 学习"))
        self.assertEqual(("周杰伦 稻香",), local_hint_intents("推荐周杰伦 稻香"))
        self.assertEqual((), local_hint_intents("  "))
        for value in ("x" * (MAX_HINT_CHARS + 1), "钢琴\n", "钢琴\x00", 42):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_hint(value)

    def test_temporary_hint_changes_only_current_search_and_keeps_snapshots(self):
        library = {"favorites": [{"id": "old", "title": "晴天", "artist": "周杰伦"}],
                   "playlists": [{"name": "喜欢", "tracks": []}]}
        history = {"entries": [{"track": {"id": "heard", "title": "稻香",
                                            "artist": "周杰伦"}, "playCount": 4}]}
        original = copy.deepcopy((library, history))
        piano = track("piano", "安静钢琴纯音乐", "演奏者")
        provider = Provider({"钢琴 安静 纯音乐": (piano,)})
        result = recommend(provider, library, history, hint="安静一点的钢琴曲")
        self.assertEqual(("钢琴 安静 纯音乐",), result.intents)
        self.assertEqual((piano,), result.tracks)
        self.assertEqual(original, (library, history))
        provider.queries.clear()
        recommend(provider, library, history)
        self.assertNotIn("钢琴 安静 纯音乐", provider.queries)
        self.assertEqual("周杰伦 稻香", provider.queries[0])

    def test_history_changes_local_weights_but_not_favorite_playlist_summary(self):
        library = {"favorites": [{"title": "晴天", "artist": "周杰伦"}],
                   "playlists": [{"name": "学习钢琴", "tracks": [
                       {"title": "钢琴曲", "artist": "演奏者"}]}]}
        history = {"entries": [{"track": {"id": "private", "title": "私人收听歌曲",
                                            "artist": "历史独有作者"}, "playCount": 5}]}
        profile = build_taste_profile(library, history)
        self.assertEqual("历史独有作者", profile.artists[0])
        self.assertEqual(15, profile.artist_weights["历史独有作者"])
        self.assertEqual(frozenset({"private"}), profile.heard_ids)
        self.assertEqual(build_taste_profile(library, {}).summary, profile.summary)
        self.assertNotIn("历史独有作者", profile.summary["artists"])
        self.assertNotIn("私人收听歌曲", profile.summary["titles"])

    def test_artist_without_title_still_produces_a_local_query(self):
        profile = build_taste_profile({"favorites": [{"artist": "周杰伦"}]}, {})
        self.assertEqual(("周杰伦",), profile.queries)
        self.assertFalse(profile.cold_start)

    def test_diary_private_notes_lyrics_and_event_context_do_not_enter_taste_profile(self):
        library = {"favorites": [{"title": "晴天", "artist": "周杰伦"}]}
        history = {"entries": []}
        baseline = build_taste_profile(library, history)
        library["diaryNotes"] = [{"text": "私人回忆", "track": {
            "id": "private-note", "title": "私人备注里的歌曲", "artist": "私密作者"}}]
        library["lyricFavorites"] = [{"text": "私密歌词", "track": {
            "id": "private-lyric", "title": "歌词收藏里的歌曲", "artist": "私密作者"}}]
        history["events"] = [{"playlistName": "私人歌单来源", "playedAt": "2026-10-06T00:00:00Z",
                              "track": {"id": "private-event", "title": "历史事件", "artist": "私密作者"}}]
        self.assertEqual(baseline, build_taste_profile(library, history))

    def test_candidate_ids_cannot_inject_or_drop_real_tracks(self):
        first = track("first", "第一首")
        second = track("second", "第二首")
        third = track("third", "第三首")
        self.assertEqual((second, first, third), order_candidate_ids(
            (first, second, third), ("made-up", "second", "second", None, "https://bad.test")))
        self.assertEqual((first, second, third), order_candidate_ids(
            (first, second, third), ("made-up",)))

    def test_candidate_ranker_receives_only_provider_tracks_and_can_only_reorder(self):
        first = track("first", "第一首", "甲")
        second = track("second", "第二首", "乙")
        third = track("third", "第三首", "丙")
        provider = Provider({"本次": (first, second, third)})

        def ranker(profile, candidates):
            self.assertEqual({first, second, third}, set(candidates))
            return ("made-up", "third", "third")

        result = recommend(provider, {}, {}, hint="本次", candidate_ranker=ranker)
        self.assertEqual((third, first, second), result.tracks)
        fallback = recommend(provider, {}, {}, hint="本次", candidate_ranker=lambda *_: 1 / 0)
        self.assertEqual((first, second, third), fallback.tracks)


if __name__ == "__main__":
    unittest.main()
