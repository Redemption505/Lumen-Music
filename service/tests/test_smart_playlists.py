"""Deterministic smart-playlist rule boundaries and candidate behavior."""

from __future__ import annotations

import copy
import unittest
from datetime import datetime, timezone

from lumen_service.smart_playlists import candidates, evaluate, normalize_rules


NOW = datetime(2026, 10, 5, 12, tzinfo=timezone.utc)


def track(identifier: str, duration: int = 120) -> dict:
    return {"id": identifier, "title": identifier, "artist": "Artist",
            "durationSeconds": duration, "source": "demo"}


def state(*, favorites: list[dict] | None = None, playlists: list[dict] | None = None,
          songs: list[dict] | None = None, dates: dict | None = None,
          tags: dict | None = None) -> dict:
    return {"favorites": favorites or [], "playlists": playlists or [],
            "songs": songs or [], "favoriteAt": dates or {}, "trackTags": tags or {}}


def history(*entries: tuple[str, str]) -> dict:
    return {"entries": [{"track": track(identifier), "lastPlayedAt": when}
                        for identifier, when in entries]}


def ids(items: list[dict]) -> list[str]:
    return [item["id"] for item in items]


class SmartPlaylistRulesTest(unittest.TestCase):
    def test_favorite_age_includes_exact_boundary_and_excludes_unknown(self) -> None:
        library = state(favorites=[track("boundary"), track("older"), track("newer"),
                                   track("unknown")], dates={
            "boundary": "2026-09-05T12:00:00Z",
            "older": "2026-09-05T11:59:59Z",
            "newer": "2026-09-05T12:00:01Z",
        })
        result = evaluate(library, history(), {"favoriteOnly": True,
                          "favoriteOlderDays": 30}, set(), now=NOW)
        self.assertEqual(["boundary", "older"], ids(result))

    def test_recent_and_not_recent_split_at_exact_boundary(self) -> None:
        library = state(favorites=[track("boundary"), track("older"), track("newer"),
                                   track("unrecorded")])
        heard = history(("boundary", "2026-10-03T12:00:00Z"),
                        ("older", "2026-10-03T11:59:59Z"),
                        ("newer", "2026-10-03T12:00:01Z"))
        self.assertEqual(["boundary", "newer"], ids(evaluate(
            library, heard, {"listening": "recent", "listeningDays": 2}, set(), now=NOW)))
        self.assertEqual(["older"], ids(evaluate(
            library, heard, {"listening": "notRecent", "listeningDays": 2}, set(), now=NOW)))
        self.assertEqual(["unrecorded"], ids(evaluate(
            library, heard, {"listening": "unrecorded"}, set(), now=NOW)))

    def test_duration_bounds_are_inclusive_but_zero_is_unknown(self) -> None:
        library = state(favorites=[track("zero", 0), track("below", 59), track("min", 60),
                                   track("middle", 90), track("max", 120), track("above", 121)])
        self.assertEqual(["min", "middle", "max"], ids(evaluate(
            library, history(), {"minDuration": 60, "maxDuration": 120}, set(), now=NOW)))
        self.assertEqual(["min"], ids(evaluate(
            library, history(), {"minDuration": 60, "maxDuration": 60}, set(), now=NOW)))

    def test_cached_and_all_tags_combine_with_other_rules(self) -> None:
        library = state(favorites=[track("match"), track("uncached"), track("one_tag"),
                                   track("unheard"), track("not_favorite")], tags={
            "match": ["现场", "纯音乐"], "uncached": ["现场", "纯音乐"],
            "one_tag": ["现场"], "unheard": ["现场", "纯音乐"],
            "not_favorite": ["现场", "纯音乐"],
        })
        library["favorites"] = library["favorites"][:-1]
        library["playlists"] = [{"id": "p", "name": "P", "tracks": [track("not_favorite")]}]
        heard = history(("match", "2026-10-05T11:00:00Z"),
                        ("uncached", "2026-10-05T11:00:00Z"),
                        ("one_tag", "2026-10-05T11:00:00Z"))
        rules = {"favoriteOnly": True, "listening": "recent", "listeningDays": 1,
                 "cached": "yes", "tagsAll": ["现场", "纯音乐"]}
        self.assertEqual(["match"], ids(evaluate(
            library, heard, rules, {"match", "one_tag", "unheard", "not_favorite"}, now=NOW)))

    def test_candidates_deduplicate_in_source_order_without_mutation(self) -> None:
        favorite = track("favorite")
        playlist_only = track("playlist")
        version_only = track("version")
        library = state(
            favorites=[favorite],
            playlists=[{"id": "p", "name": "P", "tracks": [playlist_only, track("favorite")]}],
            songs=[{"id": "s", "title": "S", "versions": [
                {"id": "v1", "label": "First", "track": version_only},
                {"id": "v2", "label": "Second", "track": track("playlist")}]}],
        )
        before = copy.deepcopy(library)
        self.assertEqual(["favorite", "playlist", "version"], ids(candidates(library)))
        self.assertEqual(["favorite", "playlist", "version"], ids(evaluate(
            library, history(), {}, set(), now=NOW)))
        self.assertEqual(before, library)

    def test_invalid_number_bool_and_conflicting_bounds(self) -> None:
        invalid = (
            {"favoriteOnly": "true"}, {"favoriteOnly": 1},
            {"favoriteOlderDays": -1, "favoriteOnly": True},
            {"favoriteOlderDays": 365001, "favoriteOnly": True},
            {"listening": "recent", "listeningDays": 365001},
            {"minDuration": True}, {"maxDuration": 2**31},
            {"minDuration": 121, "maxDuration": 120},
        )
        for rules in invalid:
            with self.subTest(rules=rules), self.assertRaises(ValueError):
                normalize_rules(rules)

    def test_incompatible_rule_fields_are_rejected(self) -> None:
        for rules in ({"favoriteOlderDays": 1}, {"listening": "recent"},
                      {"listening": "any", "listeningDays": 2},
                      {"listening": "unrecorded", "listeningDays": 2},
                      {"cached": "maybe"}, {"unknown": True}):
            with self.subTest(rules=rules), self.assertRaises(ValueError):
                normalize_rules(rules)


if __name__ == "__main__":
    unittest.main()
