from __future__ import annotations

import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from lumen_service.library import LibraryError, LibraryFormatError, LibraryStore
from lumen_service.models import Track


class LibraryStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "library.json"
        self.store = LibraryStore(self.path)
        self.song = Track("bili-BV1-1", "周杰伦 晴天", "周杰伦", 269,
                          "bilibili", "BV1", 1,
                          "https://i0.hdslb.com/bfs/archive/example.jpg?x=1&y=2")

    def test_favorites_are_unique_ordered_and_persist_across_restart(self) -> None:
        first = self.store.favorite(self.song)
        self.assertEqual("周杰伦 晴天", first["favorites"][0]["title"])
        self.assertEqual("BV1", first["favorites"][0]["bvid"])
        self.assertEqual(self.song.cover_uri, first["favorites"][0]["coverUri"])
        self.store.favorite(self.song)
        self.store.favorite({"id": "local-1", "title": "本地歌曲", "artist": "我",
                             "durationSeconds": 7, "source": "local",
                             "mediaUri": "file:///C:/music/song.mp3"})
        self.assertEqual(2, len(self.store.snapshot()["favorites"]))
        self.store.move_favorite(1, 0)
        reopened = LibraryStore(self.path)
        self.assertEqual(["local-1", "bili-BV1-1"],
                         [item["id"] for item in reopened.snapshot()["favorites"]])
        self.assertEqual("file:///C:/music/song.mp3",
                         reopened.snapshot()["favorites"][0]["mediaUri"])
        self.assertEqual(self.song.cover_uri, reopened.snapshot()["favorites"][1]["coverUri"])
        reopened.unfavorite("local-1")
        self.assertEqual(["bili-BV1-1"],
                         [item["id"] for item in LibraryStore(self.path).snapshot()["favorites"]])

    def test_playlist_crud_order_and_duplicate_handling(self) -> None:
        result = self.store.create_playlist("  周杰伦精选  ")
        playlist_id = result["playlists"][0]["id"]
        self.assertEqual("周杰伦精选", result["playlists"][0]["name"])
        self.store.add_to_playlist(playlist_id, self.song)
        self.store.add_to_playlist(playlist_id, self.song)
        self.store.add_to_playlist(playlist_id, {"id": "second", "title": "七里香",
                                                 "artist": "周杰伦", "durationSeconds": 300,
                                                 "source": "bilibili"})
        self.assertEqual(2, len(self.store.snapshot()["playlists"][0]["tracks"]))
        self.store.move_in_playlist(playlist_id, 1, 0)
        self.store.rename_playlist(playlist_id, "最爱")
        reopened = LibraryStore(self.path)
        self.assertEqual("最爱", reopened.snapshot()["playlists"][0]["name"])
        self.assertEqual(["second", "bili-BV1-1"],
                         [item["id"] for item in reopened.snapshot()["playlists"][0]["tracks"]])
        self.assertEqual(self.song.cover_uri,
                         reopened.snapshot()["playlists"][0]["tracks"][1]["coverUri"])
        reopened.remove_from_playlist(playlist_id, "second")
        reopened.delete_playlist(playlist_id)
        self.assertEqual([], LibraryStore(self.path).snapshot()["playlists"])

    def test_local_embedded_cover_survives_favorites_playlist_restart_and_backup(self) -> None:
        local = {"id": "local-covered", "title": "本地封面", "artist": "本地",
                 "durationSeconds": 8, "source": "local",
                 "mediaUri": "file:///C:/music/song.mp3",
                 "coverUri": "file:///C:/lumen%20data/covers/embedded.jpg"}
        self.store.favorite(local)
        playlist_id = self.store.create_playlist("本地歌曲")["playlists"][0]["id"]
        self.store.add_to_playlist(playlist_id, local)
        saved = LibraryStore(self.path).snapshot()
        self.assertEqual(local["coverUri"], saved["favorites"][0]["coverUri"])
        self.assertEqual(local["coverUri"], saved["playlists"][0]["tracks"][0]["coverUri"])
        backup = LibraryStore(self.store.backup_path).snapshot()
        self.assertEqual(local["coverUri"], backup["favorites"][0]["coverUri"])

    def test_file_covers_require_local_file_audio_and_explicit_image_uri(self) -> None:
        self.store.favorite(self.song)
        original = self.path.read_bytes()
        backup = self.store.backup_path.read_bytes()
        local = {"id": "local-rejected", "title": "本地封面", "artist": "本地",
                 "durationSeconds": 8, "source": "local",
                 "mediaUri": "file:///C:/music/song.mp3",
                 "coverUri": "file:///C:/lumen/covers/embedded.jpg"}
        invalid = [dict(local, source="bilibili"), dict(local, source="demo"),
                   dict(local, mediaUri=None), dict(local, mediaUri="https://example.com/song.mp3"),
                   dict(local, mediaUri="C:/music/song.mp3"),
                   dict(local, mediaUri="file:relative/song.mp3"),
                   dict(local, coverUri="C:/lumen/covers/embedded.jpg"),
                   dict(local, coverUri="//example.com/cover.jpg"),
                   dict(local, coverUri="file:relative/embedded.jpg"),
                   dict(local, coverUri="file:///C:/lumen/covers/audio.mp3"),
                   dict(local, coverUri="file:///C:/lumen/covers/embedded.jpg?x=1"),
                   dict(local, coverUri="javascript:alert(1)")]
        for track in invalid:
            with self.subTest(track=track):
                with self.assertRaises(LibraryError):
                    self.store.favorite(track)
                self.assertEqual(original, self.path.read_bytes())
                self.assertEqual(backup, self.store.backup_path.read_bytes())

    def test_late_cover_metadata_hydrates_existing_favorite_and_playlist(self) -> None:
        without_cover = Track("bili-BV-cover", "歌曲", "作者", 200, "bilibili",
                              "BV1xx411c7mD", 1)
        with_cover = Track("bili-BV-cover", "歌曲", "作者", 200, "bilibili",
                           "BV1xx411c7mD", 1,
                           "https://i0.hdslb.com/bfs/archive/cover.jpg")
        self.store.favorite(without_cover)
        playlist_id = self.store.create_playlist("收藏歌单")["playlists"][0]["id"]
        self.store.add_to_playlist(playlist_id, without_cover)

        self.store.favorite(with_cover)
        self.store.add_to_playlist(playlist_id, with_cover)
        reopened = LibraryStore(self.path).snapshot()
        self.assertEqual(with_cover.cover_uri, reopened["favorites"][0]["coverUri"])
        self.assertEqual(with_cover.cover_uri,
                         reopened["playlists"][0]["tracks"][0]["coverUri"])

    def test_import_playlist_is_atomic_and_playlist_order_survives_restart(self) -> None:
        first = self.store.import_playlist("导入一", [self.song, self.song])
        self.assertEqual(1, len(first["playlists"][0]["tracks"]))
        before = self.path.read_bytes()
        with self.assertRaises(LibraryError):
            self.store.import_playlist("坏导入", [self.song, {"id": "invalid"}])
        self.assertEqual(before, self.path.read_bytes())
        self.store.import_playlist("导入二", [self.song])
        moved = self.store.move_playlist(1, 0)
        self.assertEqual(["导入二", "导入一"],
                         [item["name"] for item in moved["playlists"]])
        self.assertEqual(["导入二", "导入一"],
                         [item["name"] for item in LibraryStore(self.path).snapshot()["playlists"]])

    def test_backup_keeps_previous_valid_state(self) -> None:
        self.store.favorite(self.song)
        self.store.create_playlist("精选")
        backup = LibraryStore(self.store.backup_path).snapshot()
        current = LibraryStore(self.path).snapshot()
        self.assertEqual(["bili-BV1-1"], [item["id"] for item in backup["favorites"]])
        self.assertEqual([], backup["playlists"])
        self.assertEqual(1, len(current["playlists"]))
        self.assertEqual([], list(self.path.parent.glob("*.tmp")))

    def test_corrupt_or_unknown_version_is_preserved(self) -> None:
        for contents in ("{not json", json.dumps({"version": 99,
                                                   "favorites": [], "playlists": []}),
                         json.dumps({"version": 1, "favorites": [{"id": "x"}],
                                     "playlists": []})):
            with self.subTest(contents=contents):
                self.path.write_text(contents, encoding="utf-8")
                with self.assertRaises(LibraryFormatError):
                    LibraryStore(self.path)
                self.assertEqual(contents, self.path.read_text(encoding="utf-8"))

    def test_failed_replace_keeps_current_state_and_cleans_temp(self) -> None:
        self.store.favorite(self.song)
        original = self.path.read_bytes()
        from lumen_service import library
        actual_replace = library.os.replace

        def fail_main_replace(source: str, destination: Path) -> None:
            if Path(destination) == self.path:
                raise OSError("test write failure")
            actual_replace(source, destination)

        with patch.object(library.os, "replace", side_effect=fail_main_replace):
            with self.assertRaises(LibraryError):
                self.store.create_playlist("失败")
        self.assertEqual(original, self.path.read_bytes())
        self.assertEqual([], self.store.snapshot()["playlists"])
        self.assertEqual([], list(self.path.parent.glob("*.tmp")))

    def test_external_corruption_is_not_overwritten_or_backed_up(self) -> None:
        self.store.favorite(self.song)
        valid_backup = self.store.backup_path.read_bytes()
        self.path.write_text("{externally damaged", encoding="utf-8")
        with self.assertRaisesRegex(LibraryError, "外部修改"):
            self.store.create_playlist("不要覆盖")
        self.assertEqual("{externally damaged", self.path.read_text(encoding="utf-8"))
        self.assertEqual(valid_backup, self.store.backup_path.read_bytes())
        self.assertEqual([], self.store.snapshot()["playlists"])

    def test_permission_denied_creating_temp_file_fails_immediately(self) -> None:
        from lumen_service import library
        with patch.object(library.os, "open", side_effect=PermissionError("access denied")) as opening:
            with self.assertRaisesRegex(LibraryError, "access denied"):
                self.store.favorite(self.song)
        self.assertEqual(1, opening.call_count)
        self.assertFalse(self.path.exists())
        self.assertFalse(self.store.backup_path.exists())
        self.assertEqual([], list(self.path.parent.glob("*.tmp")))

    def test_invalid_operations_do_not_change_disk(self) -> None:
        self.store.favorite(self.song)
        original = self.path.read_bytes()
        with self.assertRaises(LibraryError):
            self.store.move_favorite(0, 1)
        with self.assertRaises(LibraryError):
            self.store.add_to_playlist("missing", self.song)
        with self.assertRaises(LibraryError):
            self.store.create_playlist(" ")
        self.assertEqual(original, self.path.read_bytes())

    def test_parallel_favorites_do_not_lose_updates(self) -> None:
        threads = []
        for number in range(20):
            track = {"id": f"track-{number}", "title": f"曲目 {number}",
                     "artist": "周杰伦", "durationSeconds": 1, "source": "bilibili"}
            thread = threading.Thread(target=self.store.favorite, args=(track,))
            threads.append(thread)
            thread.start()
        for thread in threads:
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive())
        self.assertEqual(20, len(LibraryStore(self.path).snapshot()["favorites"]))
