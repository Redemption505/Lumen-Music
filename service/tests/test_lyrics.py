import unittest
from io import BytesIO
from urllib.parse import parse_qs, urlsplit
from urllib.error import HTTPError, URLError
from unittest.mock import MagicMock, patch

from lumen_service import lyrics
from lumen_service.lyrics import _fetch, _netease_rows, lookup_lyrics
from lumen_service.providers import ProviderError


def row(identity, title, artist, duration, synced="[00:01.00]hello"):
    return {
        "id": identity,
        "trackName": title,
        "artistName": artist,
        "duration": duration,
        "syncedLyrics": synced,
    }


class LyricLookupTests(unittest.TestCase):
    def setUp(self):
        lyrics._lrclib_next_allowed = 0.0
        lyrics._lrclib_cooldown_until = 0.0

    def test_auto_match_requires_title_artist_duration_and_unique_candidate(self):
        result = lookup_lyrics("晴天", "周杰伦", 269, fetch_json=lambda url: [
            row(1, "晴天", "周杰伦", 269),
            row(2, "晴天", "翻唱者", 269),
            row(3, "晴天", "周杰伦", 280),
        ])
        self.assertEqual(result.auto_id, 1)
        self.assertEqual(result.candidates[0].score, 100)
        reasons = {candidate.id: candidate.reason for candidate in result.candidates}
        self.assertEqual(reasons[2], "歌手不同，仅供手动确认")
        self.assertEqual(reasons[3], "时长不同，仅供手动确认")

    def test_exact_duration_record_wins_over_nearby_version(self):
        result = lookup_lyrics("晴天", "周杰伦", 269, fetch_json=lambda url: [
            row(1, "晴天", "周杰伦", 269),
            row(2, "晴天", "周杰伦", 270, "[00:01.00]不同版本"),
        ])
        self.assertEqual(result.auto_id, 1)
        self.assertEqual(len(result.candidates), 2)

    def test_same_duration_lyric_conflict_stays_manual(self):
        result = lookup_lyrics("晴天", "周杰伦", 269, fetch_json=lambda url: [
            row(1, "晴天", "周杰伦", 269, "[00:01.00]版本甲"),
            row(2, "晴天", "周杰伦", 269, "[00:01.00]版本乙"),
        ])
        self.assertIsNone(result.auto_id)

    def test_duplicate_record_ids_with_identical_lyrics_are_one_match(self):
        result = lookup_lyrics("晴天", "周杰伦", 269, fetch_json=lambda url: [
            row(1, "晴天", "周杰伦", 269), row(2, "晴天", "周杰伦", 270),
        ])
        self.assertEqual(result.auto_id, 1)

    def test_uploader_and_live_cover_are_manual_only(self):
        result = lookup_lyrics("晴天", "音乐搬运", 269, fetch_json=lambda url: [
            row(1, "晴天", "周杰伦", 269),
            row(2, "晴天 Live", "音乐搬运", 269),
        ])
        self.assertIsNone(result.auto_id)
        self.assertEqual({candidate.id for candidate in result.candidates}, {1, 2})

    def test_complete_artist_and_song_words_in_video_title_are_confirmed_by_source(self):
        result = lookup_lyrics("周杰伦 晴天 官方 MV", "音乐UP主", 269,
                               fetch_json=lambda url: [row(1, "晴天", "周杰伦", 269)])
        self.assertEqual(result.auto_id, 1)
        self.assertEqual(result.candidates[0].score, 100)

    def test_no_timed_lyrics_or_duration_is_not_auto_applied(self):
        result = lookup_lyrics("Song", "Artist", 0, fetch_json=lambda url: [
            row(1, "Song", "Artist", 120, ""),
            row(3, "Song", "Artist", 120, "plain text without timestamps"),
            row(2, "Song", "Artist", 120),
        ])
        self.assertIsNone(result.auto_id)
        self.assertEqual([candidate.id for candidate in result.candidates], [2])

    def test_query_is_url_encoded_and_bad_response_is_reported(self):
        seen = []
        lookup_lyrics("晴天 & 雨", fetch_json=lambda url: seen.append(url) or [])
        self.assertEqual(parse_qs(urlsplit(seen[0]).query)["track_name"], ["晴天 & 雨"])
        with self.assertRaises(ProviderError) as error:
            lookup_lyrics("Song", fetch_json=lambda url: {"bad": True})
        self.assertEqual(error.exception.code, "LYRICS_INVALID_RESPONSE")

    def test_jay_chou_search_scopes_popular_title_to_requested_artist(self):
        seen = []

        def fetch(url):
            seen.append(parse_qs(urlsplit(url).query))
            if "artist_name" in seen[-1]:
                return [row(21, "晴天", "周杰伦", 269)]
            # A title-only search can fill the upstream 20-result window
            # with other recordings of a common title.
            return [row(i, "晴天", f"其他歌手{i}", 269) for i in range(1, 21)]

        result = lookup_lyrics("晴天", "周杰伦", 269, fetch_json=fetch)

        self.assertEqual(seen[0], {"track_name": ["晴天"], "artist_name": ["周杰伦"]})
        self.assertEqual(len(seen), 1)
        self.assertEqual(result.auto_id, 21)

    def test_artist_scoped_empty_search_falls_back_to_title_search(self):
        seen = []

        def fetch(url):
            query = parse_qs(urlsplit(url).query)
            seen.append(query)
            return [] if "artist_name" in query else [row(1, "稻香", "周杰伦", 223)]

        result = lookup_lyrics("稻香", "周杰伦", 223, fetch_json=fetch)

        self.assertEqual(seen, [
            {"track_name": ["稻香"], "artist_name": ["周杰伦"]},
            {"track_name": ["稻香"]},
        ])
        self.assertEqual(result.auto_id, 1)

    def test_artist_search_does_not_weaken_exact_metadata_guards(self):
        result = lookup_lyrics("晴天", "周杰伦", 269, fetch_json=lambda url: [
            row(1, "晴天", "翻唱歌手", 269),
            row(2, "晴天 Live", "周杰伦", 269),
            row(3, "晴天", "周杰伦", 300),
        ])

        self.assertIsNone(result.auto_id)
        self.assertTrue(all(candidate.score < 100 for candidate in result.candidates))

    def test_bilibili_artist_first_title_uses_title_identity_not_uploader(self):
        seen = []

        def fetch(url):
            query = parse_qs(urlsplit(url).query)
            seen.append(query)
            if query == {"track_name": ["晴天"], "artist_name": ["周杰伦"]}:
                return [row(31, "晴天", "周杰伦", 269)]
            return []

        result = lookup_lyrics("周杰伦 - 晴天【官方MV】", "某音乐UP主", 269,
                               fetch_json=fetch)

        self.assertEqual(result.auto_id, 31)
        self.assertEqual(result.candidates[0].title, "晴天")
        self.assertEqual(result.candidates[0].artist, "周杰伦")
        self.assertEqual(seen[0], {"track_name": ["晴天"], "artist_name": ["周杰伦"]})

    def test_bilibili_title_first_artist_second_is_also_recognized(self):
        def fetch(url):
            query = parse_qs(urlsplit(url).query)
            if query == {"track_name": ["晴天"], "artist_name": ["周杰伦"]}:
                return [row(32, "晴天", "周杰伦", 269)]
            return []

        result = lookup_lyrics("晴天 - 周杰伦【官方MV】", "音乐分享账号", 269,
                               fetch_json=fetch)

        self.assertEqual(result.auto_id, 32)

    def test_quoted_bilibili_title_uses_adjacent_artist_and_exact_duration(self):
        seen = []

        def fetch(url):
            query = parse_qs(urlsplit(url).query)
            seen.append(query)
            return [row(34, "晴天", "周杰伦", 270)]

        result = lookup_lyrics("【Hi-Res无损音质】｜《晴天》- 周杰伦 -‘故事的小黄花’",
                               "B站视频", 270, fetch_json=fetch)
        self.assertEqual(result.auto_id, 34)
        self.assertEqual(seen[0], {"track_name": ["晴天"], "artist_name": ["周杰伦"]})

    def test_quoted_cover_or_ambiguous_quote_stays_manual(self):
        for title in ("《晴天》- 周杰伦 Live", "《晴天》- 周杰伦 翻唱",
                      "《晴天》- 周杰伦《稻香》"):
            with self.subTest(title=title):
                result = lookup_lyrics(title, "B站视频", 270,
                                       fetch_json=lambda url: [row(34, "晴天", "周杰伦", 270)])
                self.assertIsNone(result.auto_id)

    def test_bilibili_live_cover_and_mashup_titles_are_never_auto_applied(self):
        for title in (
            "周杰伦 - 晴天 Live【官方现场版】",
            "周杰伦 - 晴天 翻唱",
            "周杰伦 - 晴天 混剪",
            "周杰伦 - 晴天 经典歌曲合辑",
        ):
            with self.subTest(title=title):
                result = lookup_lyrics(title, "周杰伦", 269, fetch_json=lambda url: [
                    row(33, title, "周杰伦", 269),
                ])
                self.assertIsNone(result.auto_id)

    def test_ambiguous_or_multi_part_video_titles_do_not_extract_identity(self):
        seen = []
        result = lookup_lyrics(
            "周杰伦 - 晴天 - 蔡依林",
            "音乐分享账号",
            269,
            fetch_json=lambda url: seen.append(parse_qs(urlsplit(url).query)) or [],
        )

        self.assertIsNone(result.auto_id)
        self.assertEqual(seen, [
            {"track_name": ["周杰伦 - 晴天 - 蔡依林"], "artist_name": ["音乐分享账号"]},
            {"track_name": ["周杰伦 - 晴天 - 蔡依林"]},
            {"track_name": ["周傑倫 - 晴天 - 蔡依林"], "artist_name": ["音樂分享账号"]},
            {"track_name": ["周傑倫 - 晴天 - 蔡依林"]},
        ])

    def test_five_second_boundary_uses_real_duration_before_rounding(self):
        for duration, expected in ((274, 1), (274.01, None), (264, 1), (263.99, None)):
            with self.subTest(duration=duration):
                result = lookup_lyrics("晴天", "周杰伦", 269,
                                       fetch_json=lambda url: [row(1, "晴天", "周杰伦", duration)])
                self.assertEqual(result.auto_id, expected)

    def test_quoted_artist_before_song_and_compact_hyphen_ignore_uploader(self):
        for title in ("李荣浩《恋人》百万豪装录音棚大声听", "恋人-李荣浩",
                      "李荣浩⑧专5单《恋人》MV 现已正式上线"):
            with self.subTest(title=title):
                result = lookup_lyrics(title, "李荣浩" if "⑧" in title else "JLRS-LeoFM", 274,
                                       fetch_json=lambda url: [row(1, "恋人", "李荣浩", 276)])
                self.assertEqual(result.auto_id, 1)

    def test_unknown_quoted_artist_is_confirmed_from_source_and_not_uploader(self):
        result = lookup_lyrics("录音棚里 李荣浩⑧专《恋人》MV", "分享账号", 276,
                               fetch_json=lambda url: [row(1, "恋人", "李荣浩", 276),
                                                       row(2, "恋人", "分享账号", 276)])
        self.assertEqual(result.auto_id, 1)
        no_artist = lookup_lyrics("每日推荐《恋人》", "李荣浩", 276,
                                  fetch_json=lambda url: [row(1, "恋人", "李荣浩", 276)])
        self.assertIsNone(no_artist.auto_id)

    def test_complete_title_boundary_prevents_short_song_substring_match(self):
        result = lookup_lyrics("周杰伦 大晴天 官方 MV", "音乐UP主", 269,
                               fetch_json=lambda url: [row(1, "晴天", "周杰伦", 269)])
        self.assertIsNone(result.auto_id)

    def test_multiple_songs_or_performers_stay_manual_even_when_one_matches(self):
        for title in ("周杰伦 - 晴天 - 蔡依林", "周杰伦《晴天》《稻香》"):
            result = lookup_lyrics(title, "音乐UP主", 269,
                                   fetch_json=lambda url: [row(1, "晴天", "周杰伦", 269)])
            self.assertIsNone(result.auto_id)

    def test_editorial_title_duration_guard_keeps_fractional_seconds(self):
        result = lookup_lyrics("周杰伦 晴天 官方 MV", "音乐UP主", 269,
                               fetch_json=lambda url: [row(1, "晴天", "周杰伦", 274.01)])
        self.assertIsNone(result.auto_id)

    def test_traditional_fallback_runs_after_simplified_rows_without_timed_lyrics(self):
        seen = []
        def fetch(url):
            query = parse_qs(urlsplit(url).query)
            seen.append(query)
            if query["track_name"] == ["路過人間"]:
                return [row(2, "路過人間", "郁可唯", 247)]
            return [row(1, "路过人间 Live", "郁可唯", 289, "")]
        result = lookup_lyrics("路过人间", "郁可唯", 248, fetch_json=fetch)
        self.assertEqual(result.auto_id, 2)
        self.assertTrue(any(query["track_name"] == ["路過人間"] for query in seen))

    def test_simplified_equivalent_candidate_is_first_and_auto_preferred(self):
        result = lookup_lyrics("路过人间", "郁可唯", 247, fetch_json=lambda url: [
            row(1, "路過人間", "郁可唯", 247, "[00:01.00]愛過的人"),
            row(2, "路过人间", "郁可唯", 246, "[00:01.00]爱过的人"),
        ])
        self.assertEqual([candidate.id for candidate in result.candidates], [2, 1])
        self.assertEqual(result.auto_id, 2)
        self.assertEqual(result.candidates[1].synced_lyrics, "[00:01.00]愛過的人")

    def test_script_preference_does_not_hide_a_real_version_conflict(self):
        result = lookup_lyrics("路过人间", "郁可唯", 247, fetch_json=lambda url: [
            row(1, "路過人間", "郁可唯", 247, "[00:01.00]版本甲"),
            row(2, "路过人间", "郁可唯", 247, "[00:02.00]版本乙"),
        ])
        self.assertIsNone(result.auto_id)

    def test_script_preference_uses_body_not_traditional_source_metadata(self):
        simplified_body = "[ti:路過人間]\n[ar:張傑]\n[by:樂樂]\n[00:01.00]爱过的人"
        traditional_body = "[ti:路过人间]\n[00:01.00]愛過的人"
        result = lookup_lyrics("路过人间", "郁可唯", 247, fetch_json=lambda url: [
            row(1, "路过人间", "郁可唯", 247, traditional_body),
            row(2, "路過人間", "郁可唯", 247, simplified_body),
        ])
        self.assertEqual([candidate.id for candidate in result.candidates], [2, 1])
        self.assertEqual(result.candidates[0].synced_lyrics, simplified_body)

    def test_supplemental_lyrics_fill_missing_chinese_song_and_fit_java_int(self):
        def supplement(url):
            if urlsplit(url).path.endswith("search/get"):
                return {"code": 200, "result": {"songs": [
                    {"id": 2600493765, "name": "恋人", "duration": 275912,
                     "artists": [{"name": "李荣浩"}]}]}}
            return {"code": 200, "lrc": {"lyric": "[00:01.00]歌词片段"}}
        result = lookup_lyrics("李荣浩《恋人》MV", "某UP主", 274,
                               fetch_json=lambda url: [], fetch_supplemental_json=supplement)
        self.assertEqual(result.auto_id, 1600493765)
        self.assertTrue(0 < result.auto_id <= 2147483647)
        self.assertIn("网易云", result.candidates[0].reason)

    def test_supplemental_ids_resolve_collision_and_preserve_lrclib_id(self):
        def supplement(url):
            if urlsplit(url).path.endswith("search/get"):
                return {"code": 200, "result": {"songs": [
                    {"id": identity, "name": "恋人", "duration": 276000,
                     "artists": [{"name": "李荣浩"}]} for identity in (2600493765, 3600493765)]}}
            return {"code": 200, "lrc": {"lyric": "[00:01.00]hello"}}
        result = lookup_lyrics("恋人", "李荣浩", 276,
                               fetch_json=lambda url: [row(1600493765, "恋人", "李荣浩", 276)],
                               fetch_supplemental_json=supplement)
        self.assertEqual({candidate.id for candidate in result.candidates},
                         {1600493765, 1600493766, 1600493767})

    def test_lrclib_failure_does_not_block_supplemental_source(self):
        def primary(url):
            raise ProviderError("LYRICS_UNAVAILABLE", "歌词服务暂不可用")
        def supplement(url):
            if urlsplit(url).path.endswith("search/get"):
                return {"code": 200, "result": {"songs": [
                    {"id": 2600493765, "name": "恋人", "duration": 276000,
                     "artists": [{"name": "李荣浩"}]}]}}
            return {"code": 200, "lrc": {"lyric": "[00:01.00]歌词片段"}}
        result = lookup_lyrics("恋人", "李荣浩", 276, fetch_json=primary,
                               fetch_supplemental_json=supplement)
        self.assertIsNotNone(result.auto_id)
        with self.assertRaises(ProviderError):
            lookup_lyrics("恋人", "李荣浩", 276, fetch_json=primary,
                          fetch_supplemental_json=primary)

    def test_supplemental_failure_preserves_existing_timed_candidate(self):
        def supplement(url):
            raise ProviderError("LYRICS_UNAVAILABLE", "华语歌词服务暂不可用")
        result = lookup_lyrics("路过人间", "郁可唯", 247,
                               fetch_json=lambda url: [row(1, "路过人间", "郁可唯", 247)],
                               fetch_supplemental_json=supplement)
        self.assertEqual(result.auto_id, 1)

    def test_supplemental_keeps_success_when_another_lyric_request_fails(self):
        for fail_first in (False, True):
            for failure in (ProviderError("LYRICS_UNAVAILABLE", "原始服务错误"),
                            TimeoutError("原始超时"), {"code": 503}):
                with self.subTest(fail_first=fail_first, failure=failure):
                    def supplement(url):
                        if urlsplit(url).path.endswith("search/get"):
                            return {"code": 200, "result": {"songs": [
                                {"id": identity, "name": "恋人", "duration": 276000,
                                 "artists": [{"name": "李荣浩"}]} for identity in (1, 2)]}}
                        identifier = int(parse_qs(urlsplit(url).query)["id"][0])
                        if identifier == (1 if fail_first else 2):
                            if isinstance(failure, Exception):
                                raise failure
                            return failure
                        return {"code": 200, "lrc": {"lyric": "[00:01.00]歌词片段"}}
                    result = lookup_lyrics("恋人", "李荣浩", 276, fetch_json=lambda url: [],
                                           fetch_supplemental_json=supplement)
                    self.assertEqual(len(result.candidates), 1)
                    self.assertEqual(result.auto_id, 1_000_000_000 + (2 if fail_first else 1))

    def test_all_supplemental_lyric_requests_fail_preserving_first_error(self):
        original = ProviderError("LYRICS_RATE_LIMITED", "原始限流错误", 503)
        requested = []
        def supplement(url):
            if urlsplit(url).path.endswith("search/get"):
                return {"code": 200, "result": {"songs": [
                    {"id": identity, "name": "恋人", "duration": 276000,
                     "artists": [{"name": "李荣浩"}]} for identity in (1, 2)]}}
            identifier = int(parse_qs(urlsplit(url).query)["id"][0])
            requested.append(identifier)
            if identifier == 1:
                raise original
            return {"code": 503}
        with self.assertRaises(ProviderError) as caught:
            _netease_rows("恋人", "李荣浩", 276, supplement, set())
        self.assertIs(caught.exception, original)
        self.assertEqual(requested, [1, 2])

    def test_majority_lyric_consensus_selects_closest_duration_and_skips_reverse_identity(self):
        same_lrc = "[00:01.00]共同版本"
        records = [
            row(100 + index, "晴天", "周杰伦", duration, same_lrc)
            for index, duration in enumerate((268, 270, 271, 269, 268, 270,
                                               271, 269, 268, 270, 271, 269))
        ]
        records.append(row(200, "晴天", "周杰伦", 270, "[00:01.00]少数版本"))
        seen = []

        def fetch(url):
            seen.append(parse_qs(urlsplit(url).query))
            if len(seen) == 1:
                return records
            # This belongs to the reverse interpretation and must not be queried
            # after the first identity has reached consensus.
            return [row(300, "周杰伦", "晴天", 270, "[00:01.00]另一首歌")]

        result = lookup_lyrics("周杰伦 - 晴天【官方MV】", "音乐UP主", 270,
                               fetch_json=fetch)

        self.assertEqual(result.auto_id, 101)
        self.assertEqual(len(seen), 1)
        self.assertEqual(len(result.candidates), 13)

    def test_balanced_lyric_conflict_does_not_auto_apply(self):
        result = lookup_lyrics("晴天", "周杰伦", 270, fetch_json=lambda url: [
            row(1, "晴天", "周杰伦", 270, "[00:01.00]版本甲"),
            row(2, "晴天", "周杰伦", 270, "[00:01.00]版本甲"),
            row(3, "晴天", "周杰伦", 270, "[00:01.00]版本乙"),
            row(4, "晴天", "周杰伦", 270, "[00:01.00]版本乙"),
        ])

        self.assertIsNone(result.auto_id)

    def test_fetch_retries_transient_502_and_identifies_lrclib_client(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b"[]"
        transient = HTTPError("https://lrclib.net/api/search", 502, "temporary", {}, BytesIO())
        with patch.object(lyrics, "_lrclib_next_allowed", 0), \
                patch("lumen_service.lyrics.time.monotonic", return_value=100.0), \
                patch("lumen_service.lyrics.urlopen", side_effect=[transient, response]) as open_url, \
                patch("lumen_service.lyrics.time.sleep") as sleep:
            self.assertEqual(_fetch("https://lrclib.net/api/search", 1.0), [])

        self.assertEqual(open_url.call_count, 2)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [0.25, 0.5])
        user_agent = open_url.call_args_list[0].args[0].get_header("User-agent")
        self.assertEqual(user_agent,
                         "LumenMusic/0.2.2 (https://github.com/Redemption505/Lumen-Music)")

    def test_lrclib_honors_integer_retry_after_and_shared_cooldown(self):
        response = HTTPError("https://lrclib.net/api/search", 429, "limited",
                             {"Retry-After": "17"}, BytesIO())
        with patch.object(lyrics, "_lrclib_next_allowed", 0) as next_allowed, \
                patch("lumen_service.lyrics.time.monotonic", return_value=100.0), \
                patch("lumen_service.lyrics.urlopen", side_effect=response) as open_url:
            with self.assertRaises(ProviderError) as caught:
                _fetch("https://lrclib.net/api/search", 1.0)
            self.assertEqual(caught.exception.code, "LYRICS_RATE_LIMITED")
            with self.assertRaises(ProviderError) as second:
                _fetch("https://lrclib.net/api/search?q=again", 1.0)
            self.assertEqual(lyrics._lrclib_next_allowed, 117.0)
        self.assertEqual(second.exception.code, "LYRICS_RATE_LIMITED")
        open_url.assert_called_once()

    def test_sequential_lrclib_calls_apply_short_minimum_spacing(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b"[]"
        with patch("lumen_service.lyrics.time.monotonic", return_value=100.0), \
                patch("lumen_service.lyrics.time.sleep") as sleep, \
                patch("lumen_service.lyrics.urlopen", return_value=response) as open_url:
            self.assertEqual(_fetch("https://lrclib.net/api/search", 1.0), [])
            self.assertEqual(_fetch("https://lrclib.net/api/search?q=next", 1.0), [])
        self.assertEqual(open_url.call_count, 2)
        sleep.assert_called_once_with(0.5)

    def test_lrclib_http_date_retry_after_uses_shared_monotonic_deadline(self):
        response = HTTPError("https://lrclib.net/api/search", 503, "busy",
                             {"Retry-After": "Thu, 01 Jan 1970 00:02:00 GMT"}, BytesIO())
        with patch.object(lyrics, "_lrclib_next_allowed", 0) as next_allowed, \
                patch("lumen_service.lyrics.time.time", return_value=100.0), \
                patch("lumen_service.lyrics.time.monotonic", return_value=40.0), \
                patch("lumen_service.lyrics.urlopen", side_effect=response):
            with self.assertRaises(ProviderError):
                _fetch("https://lrclib.net/api/search", 1.0)
            self.assertEqual(lyrics._lrclib_next_allowed, 60.0)

    def test_invalid_and_excessive_retry_after_are_bounded(self):
        for header, expected in (("nonsense", 130.0), ("999999", 1_000_099.0)):
            with self.subTest(header=header):
                response = HTTPError("https://lrclib.net/api/search", 429, "limited",
                                     {"Retry-After": header}, BytesIO())
                with patch.object(lyrics, "_lrclib_next_allowed", 0), \
                        patch.object(lyrics, "_lrclib_cooldown_until", 0), \
                        patch("lumen_service.lyrics.time.monotonic", return_value=100.0), \
                        patch("lumen_service.lyrics.urlopen", side_effect=response):
                    with self.assertRaises(ProviderError):
                        _fetch("https://lrclib.net/api/search", 1.0)
                    self.assertEqual(lyrics._lrclib_next_allowed, expected)

    def test_extremely_long_valid_retry_after_never_expires_early(self):
        response = HTTPError("https://lrclib.net/api/search", 429, "limited",
                             {"Retry-After": "9" * 5000}, BytesIO())
        with patch("lumen_service.lyrics.time.monotonic", return_value=100.0), \
                patch("lumen_service.lyrics.urlopen", side_effect=response) as open_url:
            with self.assertRaises(ProviderError):
                _fetch("https://lrclib.net/api/search", 1.0)
            with self.assertRaises(ProviderError):
                _fetch("https://lrclib.net/api/search?q=again", 1.0)
            self.assertEqual(lyrics._lrclib_cooldown_until, float("inf"))
        open_url.assert_called_once()

    def test_retry_after_reached_after_502_is_honored_for_429_and_503(self):
        for status in (429, 503):
            with self.subTest(status=status):
                clock = [10.0]
                lyrics._lrclib_next_allowed = 0.0
                lyrics._lrclib_cooldown_until = 0.0

                def sleep(seconds):
                    clock[0] += seconds

                errors = [HTTPError("https://lrclib.net/api/search", 502, "gateway", {}, BytesIO()),
                          HTTPError("https://lrclib.net/api/search", status, "limited",
                                    {"Retry-After": "12"}, BytesIO())]
                with patch("lumen_service.lyrics.time.monotonic", side_effect=lambda: clock[0]), \
                        patch("lumen_service.lyrics.time.sleep", side_effect=sleep), \
                        patch("lumen_service.lyrics.urlopen", side_effect=errors) as open_url:
                    with self.assertRaises(ProviderError) as caught:
                        _fetch("https://lrclib.net/api/search", 1.0)
                    self.assertEqual(caught.exception.code, "LYRICS_RATE_LIMITED")
                    self.assertEqual(lyrics._lrclib_cooldown_until, 22.5)
                self.assertEqual(open_url.call_count, 2)

    def test_netease_second_retry_timeout_and_rate_limit_are_wrapped(self):
        first = HTTPError("https://music.163.com/api/search/get", 503, "busy", {}, BytesIO())
        for second, expected_code in ((URLError("timed out"), "LYRICS_UNAVAILABLE"),
                                      (HTTPError("https://music.163.com/api/search/get", 429,
                                                 "limited", {}, BytesIO()), "LYRICS_RATE_LIMITED")):
            with self.subTest(expected_code=expected_code), \
                    patch("lumen_service.lyrics.time.sleep"), \
                    patch("lumen_service.lyrics.urlopen", side_effect=[first, second]):
                with self.assertRaises(ProviderError) as caught:
                    _fetch("https://music.163.com/api/search/get", 1.0)
                self.assertEqual(caught.exception.code, expected_code)

    def test_lrclib_requests_are_serial_and_netease_ignores_lrclib_cooldown(self):
        import threading

        entered = threading.Event()
        release = threading.Event()
        calls = []
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b"[]"

        def open_serial(request, timeout):
            calls.append(request.full_url)
            if len(calls) == 1:
                entered.set()
                self.assertTrue(release.wait(2))
            return response

        with patch.object(lyrics, "_lrclib_next_allowed", 0), \
                patch("lumen_service.lyrics.time.monotonic", return_value=100.0), \
                patch("lumen_service.lyrics.urlopen", side_effect=open_serial):
            first = threading.Thread(target=lambda: _fetch("https://lrclib.net/api/search", 1.0))
            first.start()
            self.assertTrue(entered.wait(2))
            errors = []
            second = threading.Thread(target=lambda: self._capture_fetch_error(errors))
            second.start()
            second.join(0.5)
            self.assertFalse(second.is_alive(), "busy-provider calls should fail promptly")
            self.assertEqual(len(errors), 1)
            self.assertEqual(errors[0].code, "LYRICS_RATE_LIMITED")
            release.set()
            first.join(2)
            self.assertFalse(first.is_alive())
            # LRCLIB's minimum interval/cooldown does not apply to NetEase.
            self.assertEqual(_fetch("https://music.163.com/api/search/get", 1.0), [])
        self.assertEqual(calls, ["https://lrclib.net/api/search",
                                 "https://music.163.com/api/search/get"])

    @staticmethod
    def _capture_fetch_error(errors):
        try:
            _fetch("https://lrclib.net/api/search?second=1", 1.0)
        except ProviderError as error:
            errors.append(error)


if __name__ == "__main__":
    unittest.main()
