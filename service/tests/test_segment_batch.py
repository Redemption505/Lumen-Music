"""Atomic segment batches, retry identity, HTTP responses, and backup round trips."""

from __future__ import annotations

import copy
import http.client
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from lumen_service import library as library_module
from lumen_service.backup import export_backup, restore_backup
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryError, LibraryStorageError, LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server
from lumen_service.smart_playlists import normalize_rules


def segment(start: int, end: int, **updates) -> dict:
    return {"id": f"segment:{uuid.uuid4()}", "title": "Clip", "artist": "Artist",
            "durationSeconds": (end - start + 999) // 1000, "source": "bilibili",
            "bvid": "BV1xx411c7mD", "page": 1, "sourceTrackId": "bilibili:BV1xx411c7mD:p1",
            "sourceDurationSeconds": 300, "segmentStartMillis": start,
            "segmentEndMillis": end, **updates}


def batch_xml(tracks: list[dict], name: str | None = None, batch_id: str | None = None) -> ElementTree.Element:
    attributes = {"destination": "favorites" if name is None else "playlist",
                  "batchId": batch_id or str(uuid.uuid4())}
    if name is not None:
        attributes["name"] = name
    root = ElementTree.Element("segments", attributes)
    for track in tracks:
        ElementTree.SubElement(root, "track", {key: str(value) for key, value in track.items() if value is not None})
    return root


class SegmentBatchStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "library.json"
        self.store = LibraryStore(self.path)

    def save(self, entries, name=None, batch_id=None):
        return self.store.save_segments(entries, name, batch_id or str(uuid.uuid4()))

    def test_favorites_use_range_identity_preserve_existing_metadata_and_save_once(self) -> None:
        existing = segment(0, 1000, title="Existing title")
        self.store.favorite(existing)
        original_at = self.store.snapshot()["favoriteAt"][existing["id"]]
        retry = segment(0, 1000, title="A different draft title")
        addition = segment(1000, 2501)
        with patch.object(self.store, "_save", wraps=self.store._save) as writes:
            snapshot, saved, skipped, playlist_id = self.save([retry, addition])
            self.assertEqual(1, writes.call_count)
        self.assertEqual((1, 1, None), (saved, skipped, playlist_id))
        self.assertEqual(existing["id"], snapshot["favorites"][0]["id"])
        self.assertEqual("Existing title", snapshot["favorites"][0]["title"])
        self.assertEqual(original_at, snapshot["favoriteAt"][existing["id"]])
        self.assertEqual(2, snapshot["favorites"][1]["durationSeconds"])
        before = self.path.read_bytes()
        self.assertEqual((0, 2, None), self.save([segment(0, 1000), segment(1000, 2501)])[1:])
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(snapshot, LibraryStore(self.path).snapshot())

    def test_playlist_batch_retry_survives_reopen_and_preserves_saved_segment_ids(self) -> None:
        entries = [segment(0, 1000), segment(1000, 3000)]
        batch_id = str(uuid.uuid4())
        snapshot, saved, skipped, playlist_id = self.save(entries, "New playlist", batch_id)
        self.assertEqual(uuid.UUID(batch_id).hex, playlist_id)
        self.assertEqual((2, 0), (saved, skipped))
        before = self.path.read_bytes()
        self.store = LibraryStore(self.path)
        with patch.object(self.store, "_save", wraps=self.store._save) as writes:
            retry = self.save([segment(0, 1000), segment(1000, 3000)], "New playlist", batch_id)
            self.assertEqual(0, writes.call_count)
        self.assertEqual((0, 2, playlist_id), retry[1:])
        self.assertEqual(snapshot, retry[0])
        self.assertEqual(before, self.path.read_bytes())

    def test_modified_playlist_retry_is_rejected_without_overwriting(self) -> None:
        entries = [segment(0, 1000), segment(1000, 2000)]
        batch_id = str(uuid.uuid4())
        playlist_id = self.save(entries, "New", batch_id)[3]
        self.store.rename_playlist(playlist_id, "Renamed")
        before = self.path.read_bytes()
        with self.assertRaisesRegex(LibraryError, "已修改"):
            self.save(entries, "New", batch_id)
        self.assertEqual(before, self.path.read_bytes())
        self.store.rename_playlist(playlist_id, "New")
        self.store.edit_segment(entries[0]["id"], {"title": "Changed"})
        before = self.path.read_bytes()
        with self.assertRaisesRegex(LibraryError, "已修改"):
            self.save(entries, "New", batch_id)
        self.assertEqual(before, self.path.read_bytes())

    def test_full_validation_rejects_bad_later_item_overlap_duplicate_or_other_source(self) -> None:
        self.store.favorite(segment(290000, 291000))
        before = self.path.read_bytes()
        valid = segment(0, 1000)
        for entries in ([valid, segment(1000, 2000, title=" ")],
                        [valid, segment(500, 2000)], [valid, segment(0, 1000)],
                        [valid, segment(1000, 2000, sourceTrackId="other")],
                        [valid, segment(1000, 2000, bvid="BVdifferent")],
                        [valid, {"id": "ordinary", "title": "Song", "artist": "Artist",
                                 "source": "bilibili", "durationSeconds": 60}],
                        [valid, segment(1000, 2000, id=valid["id"])],
                        [valid, segment(1000, 300001)],
                        [valid, segment(1000, 2000, segmentEndMillis=None)]):
            with self.subTest(entries=entries), self.assertRaises(LibraryError):
                self.save(entries, "Must not exist")
            self.assertEqual(before, self.path.read_bytes())
            self.assertEqual([], self.store.snapshot()["playlists"])

    def test_same_segment_id_different_resource_conflicts_even_outside_favorites(self) -> None:
        original = segment(0, 1000)
        playlist_id = self.store.create_playlist("Existing")["playlists"][0]["id"]
        self.store.add_to_playlist(playlist_id, original)
        before = self.path.read_bytes()
        for conflicting in (segment(1000, 2000, id=original["id"]),
                            segment(0, 1000, id=original["id"], mediaUri="file:///different.m4a")):
            with self.assertRaisesRegex(LibraryError, "其他资源"):
                self.save([conflicting])
            self.assertEqual(before, self.path.read_bytes())

    def test_count_uuid_unknown_duration_and_explicit_end(self) -> None:
        for entries in ([], [segment(index * 1000, (index + 1) * 1000) for index in range(201)]):
            with self.assertRaises(LibraryError):
                self.save(entries)
        with self.assertRaises(LibraryError):
            self.save([segment(0, 1000)], batch_id="not-uuid")
        unknown = segment(400000, 405100, sourceDurationSeconds=0)
        snapshot, saved, _, _ = self.save([unknown])
        self.assertEqual(1, saved)
        self.assertEqual(0, snapshot["favorites"][0]["sourceDurationSeconds"])
        missing_end = copy.deepcopy(unknown)
        missing_end.pop("segmentEndMillis")
        with self.assertRaises(LibraryError):
            self.save([missing_end])
        entries = [segment(index * 1000, (index + 1) * 1000) for index in range(200)]
        self.assertEqual(200, self.save(entries, "At limit")[1])

    def test_main_write_failure_rolls_back_memory_disk_and_partial_playlist(self) -> None:
        self.store.favorite(segment(0, 1000))
        before = self.path.read_bytes()
        snapshot = self.store.snapshot()
        actual_replace = library_module.os.replace
        def fail_main(source, destination):
            if Path(destination) == self.path:
                raise OSError("injected disk failure")
            actual_replace(source, destination)
        with patch.object(library_module.os, "replace", side_effect=fail_main):
            with self.assertRaises(LibraryStorageError):
                self.save([segment(1000, 2000), segment(2000, 3000)], "No partial playlist")
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(snapshot, self.store.snapshot())
        self.assertEqual([], list(self.path.parent.glob(".*.tmp")))

    def test_unrelated_catalog_smart_metadata_and_zip_restore_are_preserved(self) -> None:
        ordinary = {"id": "catalog-original", "title": "Original", "artist": "Artist",
                    "durationSeconds": 300, "source": "demo"}
        self.store.favorite(ordinary)
        self.store.create_song("Song", "Note", [("Version", "Artist", ordinary)])
        state = self.store.snapshot()
        state["smartPlaylists"] = [{"id": "smart", "name": "Smart", "rules": normalize_rules({})}]
        state["trackTags"] = {ordinary["id"]: ["tag"]}
        state["folders"] = [{"id": "folder", "name": "Folder"}]
        state["playlistFolders"] = {"smart": "folder"}
        self.path.write_text(json.dumps(state), encoding="utf-8")
        self.store = LibraryStore(self.path)
        before = self.store.snapshot()
        after = self.save([segment(0, 1000), segment(1000, 2000)], "Batch")[0]
        for key in before.keys() - {"favorites", "favoriteAt", "playlists", "versionsUndo"}:
            self.assertEqual(before[key], after[key], key)
        self.assertNotIn("versionsUndo", after)
        with self.assertRaisesRegex(LibraryError, "没有可撤销"):
            self.store.undo_catalog()
        self.assertEqual(after, self.store.snapshot())
        archive = self.path.parent / "backup.zip"
        export_backup(archive, self.path)
        restored = self.path.parent / "restored.json"
        restore_backup(archive, restored)
        self.assertEqual(after, LibraryStore(restored).snapshot())


class SegmentBatchHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "library.json"
        self.store = LibraryStore(self.path)
        self.store.favorite(segment(290000, 291000))
        with patch.dict("os.environ", {"LUMEN_AI_SETTINGS_PATH": str(self.path.parent / "ai.json")}):
            self.server = create_server(port=0, provider=DemoProvider(), library_store=self.store,
                                        history_store=HistoryStore(self.path.parent / "history.json"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, body):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request("POST", "/api/library/segments/batch", body=ElementTree.tostring(body),
                               headers={"Content-Type": "application/xml"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def test_one_http_snapshot_counters_and_stable_playlist_retry(self) -> None:
        batch_id = str(uuid.uuid4())
        body = batch_xml([segment(0, 1000), segment(1000, 2501)], "New & playlist", batch_id)
        status, root = self.request(body)
        self.assertEqual(200, status)
        self.assertEqual(("library", "1", "2", "0", uuid.UUID(batch_id).hex),
                         (root.tag, root.get("version"), root.get("savedCount"), root.get("skippedCount"), root.get("playlistId")))
        self.assertEqual(2, len(root.findall("playlists/playlist/track")))
        status, retry = self.request(body)
        self.assertEqual((200, "0", "2"), (status, retry.get("savedCount"), retry.get("skippedCount")))
        self.assertEqual(1, len(LibraryStore(self.path).snapshot()["playlists"]))

    def test_large_valid_batch_and_unknown_duration_explicit_end(self) -> None:
        body = batch_xml([segment(index * 1000, (index + 1) * 1000, title="Long title " * 20)
                          for index in range(200)], "At limit")
        self.assertGreater(len(ElementTree.tostring(body)), 64 * 1024)
        status, root = self.request(body)
        self.assertEqual((200, "200"), (status, root.get("savedCount")))
        status, root = self.request(batch_xml([segment(400000, 405100, sourceDurationSeconds=0)]))
        self.assertEqual((200, "1", ""), (status, root.get("savedCount"), root.get("playlistId")))

    def test_invalid_later_entry_malformed_destination_and_unknown_end_are_atomic(self) -> None:
        before = self.path.read_bytes()
        invalid = batch_xml([segment(0, 1000), segment(1000, 2000, title=" ")], "No playlist")
        unknown = segment(400000, 405100, sourceDurationSeconds=0)
        unknown.pop("segmentEndMillis")
        malformed = batch_xml([segment(0, 1000)])
        malformed.set("destination", "unknown")
        for body in (invalid, batch_xml([unknown]), malformed):
            with self.subTest(body=ElementTree.tostring(body)):
                status, root = self.request(body)
                self.assertEqual((400, "LIBRARY_INVALID"), (status, root.get("code")))
                self.assertEqual(before, self.path.read_bytes())
                self.assertEqual([], self.store.snapshot()["playlists"])

    def test_disk_error_returns_500_without_publishing_half_batch(self) -> None:
        before = self.path.read_bytes()
        actual_replace = library_module.os.replace
        def fail_main(source, destination):
            if Path(destination) == self.path:
                raise OSError("injected disk failure")
            actual_replace(source, destination)
        with patch.object(library_module.os, "replace", side_effect=fail_main):
            status, root = self.request(batch_xml([segment(0, 1000), segment(1000, 2000)], "No playlist"))
        self.assertEqual((500, "LIBRARY_IO_ERROR"), (status, root.get("code")))
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual([], self.store.snapshot()["playlists"])


if __name__ == "__main__":
    unittest.main()
