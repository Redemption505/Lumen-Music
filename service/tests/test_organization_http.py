"""Organization persistence and HTTP rules across the real server route."""

from __future__ import annotations

import http.client
import json
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from xml.etree import ElementTree

from lumen_service.backup import export_backup, restore_backup
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.providers import DemoProvider
from lumen_service.server import create_server


def track(identifier: str, *, duration: int = 120, title: str = "Song") -> str:
    return (f'<track id="{identifier}" title="{title}" artist="Artist" '
            f'durationSeconds="{duration}" source="demo"/>')


class OrganizationHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "library.json"
        self.history_path = Path(self.temp.name) / "history.json"
        self.library = LibraryStore(self.path)
        self.history = HistoryStore(self.history_path)
        self._start()

    def _start(self) -> None:
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=self.library, history_store=self.history)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def tearDown(self) -> None:
        self._stop()
        self.temp.cleanup()

    def request(self, method: str, path: str = "/api/library/organization",
                body: str | None = None) -> tuple[int, ElementTree.Element]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, body=body.encode("utf-8") if body else None,
                               headers={"Content-Type": "application/xml; charset=utf-8"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def org(self, body: str) -> tuple[int, ElementTree.Element]:
        return self.request("POST", body=body)

    def test_rules_unknown_dates_duration_history_cache_and_no_play_effect(self) -> None:
        self.library.favorite({"id": "old", "title": "Old Live", "artist": "A",
                               "durationSeconds": 120, "source": "demo"})
        self.library.favorite({"id": "zero", "title": "Zero", "artist": "A",
                               "durationSeconds": 0, "source": "demo"})
        self.library.create_playlist("P")
        playlist = self.library.snapshot()["playlists"][0]["id"]
        self.library.add_to_playlist(playlist, {"id": "other", "title": "Other", "artist": "A",
                                                "durationSeconds": 120, "source": "demo"})
        saved = json.loads(self.path.read_text(encoding="utf-8"))
        saved["favoriteAt"].pop("old")
        self.path.write_text(json.dumps(saved), encoding="utf-8")
        self._stop()
        self.library = LibraryStore(self.path)
        self.history = HistoryStore(self.history_path)
        self._start()
        status, root = self.request("GET")
        self.assertEqual(200, status)
        self.assertIsNone(root.find("tracks/entry[@id='old']").get("favoriteAt"))
        self.assertEqual("现场", root.find("tracks/entry[@id='old']/suggestions/tag").get("value"))
        self.assertIsNone(root.find("tracks/entry[@id='old']").get("cached"))
        status, root = self.org('<organization action="preview"><rules favoriteOnly="true" '
                                'favoriteOlderDays="0"/></organization>')
        self.assertEqual(200, status)
        self.assertEqual(["zero"], [x.get("id") for x in root.findall("results/track")])
        self.assertEqual(400, self.org('<organization action="preview"><rules cached="no"/>'
                                       '</organization>')[0])
        status, root = self.org('<organization action="preview"><rules cached="no" '
                                'minDuration="0"/><cachedIds/></organization>')
        self.assertEqual(200, status)
        self.assertEqual(["old", "other"], [x.get("id") for x in root.findall("results/track")])
        self.assertEqual("false", root.find("tracks/entry[@id='old']").get("cached"))
        status, root = self.org('<organization action="load"><cachedIds><id value="old"/>'
                                '</cachedIds></organization>')
        self.assertEqual(200, status)
        self.assertEqual("true", root.find("tracks/entry[@id='old']").get("cached"))
        self.assertEqual("false", root.find("tracks/entry[@id='other']").get("cached"))
        status, root = self.org('<organization action="preview"><rules listening="unrecorded"/>'
                                '</organization>')
        self.assertEqual(3, len(root.findall("results/track")))
        self.assertEqual([], self.history.snapshot()["entries"])

    def test_crud_tags_snapshot_folders_restart_zip_and_validation_atomicity(self) -> None:
        self.request("POST", "/api/library/favorites", track("a"))
        status, root = self.org('<organization action="folderSave" name="Collection"/>')
        self.assertEqual(200, status)
        folder = root.find("folders/folder").get("id")
        status, root = self.org(f'<organization action="save" name="Tagged" folderId="{folder}">'
                                '<rules><tag value="现场"/></rules></organization>')
        self.assertEqual(200, status)
        smart = root.find("smartPlaylists/smartPlaylist").get("id")
        self.assertEqual(folder, root.find("smartPlaylists/smartPlaylist").get("folderId"))
        before = self.path.read_bytes()
        self.assertEqual(400, self.org('<organization action="tags" mode="add"><ids>'
                                       '<id value="a"/><id value="missing"/></ids><tags>'
                                       '<tag value="现场"/></tags></organization>')[0])
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(400, self.org('<organization action="preview"><rules '
                                       'listening="recent" listeningDays="999999999"/>'
                                       '</organization>')[0])
        status, root = self.org('<organization action="tags" mode="add"><ids><id value="a"/>'
                                '</ids><tags><tag value="现场"/></tags></organization>')
        self.assertEqual(200, status)
        self.assertEqual("现场", root.find("tracks/entry/tags/tag").get("value"))
        status, root = self.org(f'<organization action="preview" id="{smart}"/>')
        self.assertEqual(["a"], [x.get("id") for x in root.findall("results/track")])
        status, root = self.org(f'<organization action="snapshot" id="{smart}" '
                                f'name="Frozen" folderId="{folder}"/>')
        playlist = root.get("createdPlaylistId")
        self.assertTrue(playlist)
        self.assertEqual(folder, root.find(f"playlistFolders/assignment[@playlistId='{playlist}']").get("folderId"))
        self._stop()
        self.library = LibraryStore(self.path)
        self.history = HistoryStore(self.history_path)
        self._start()
        self.assertEqual("现场", self.request("GET")[1].find("tracks/entry/tags/tag").get("value"))
        backup = Path(self.temp.name) / "backup.zip"
        export_backup(backup, self.path)
        restore_path = Path(self.temp.name) / "restored" / "library.json"
        restore_backup(backup, restore_path)
        self.assertEqual(1, len(LibraryStore(restore_path).snapshot()["smartPlaylists"]))
        status, root = self.org(f'<organization action="folderDelete" id="{folder}"/>')
        self.assertEqual([], root.findall("playlistFolders/assignment"))
        self.assertEqual(200, self.org(f'<organization action="delete" id="{smart}"/>')[0])

    def test_version_replace_metadata_undo_and_favorite_lifecycle(self) -> None:
        self.request("POST", "/api/library/favorites", track("a"))
        first_date = self.request("GET")[1].find("tracks/entry").get("favoriteAt")
        self.request("POST", "/api/library/favorites", track("a"))
        self.assertEqual(first_date, self.request("GET")[1].find("tracks/entry").get("favoriteAt"))
        self.org('<organization action="tags" mode="add"><ids><id value="a"/></ids>'
                 '<tags><tag value="现场"/></tags></organization>')
        status, catalog = self.request("POST", "/api/library/versions",
                                       '<song title="S"><version label="L">'
                                       f'{track("a")}</version></song>')
        self.assertEqual(200, status)
        song = catalog.find("song")
        version = song.find("version")
        route = f'/api/library/versions/{song.get("id")}/versions/{version.get("id")}/replace'
        self.assertEqual(200, self.request("POST", route, track("b"))[0])
        root = self.request("GET")[1]
        self.assertEqual(first_date, root.find("tracks/entry[@id='b']").get("favoriteAt"))
        self.assertEqual("现场", root.find("tracks/entry[@id='b']/tags/tag").get("value"))
        self.assertEqual(200, self.request("POST", "/api/library/versions/undo", "<undo/>")[0])
        root = self.request("GET")[1]
        self.assertEqual(first_date, root.find("tracks/entry[@id='a']").get("favoriteAt"))
        self.request("DELETE", "/api/library/favorites/a")
        self.assertIsNone(self.request("GET")[1].find("tracks/entry[@id='a']").get("favoriteAt"))
        self.request("POST", "/api/library/favorites", track("a"))
        self.assertIsNotNone(self.request("GET")[1].find("tracks/entry[@id='a']").get("favoriteAt"))

    def test_listening_tags_edit_and_folder_assignment(self) -> None:
        for identifier in ("recent", "old", "never"):
            self.request("POST", "/api/library/favorites", track(identifier))
        base = {"title": "Song", "artist": "Artist", "durationSeconds": 120,
                "source": "demo"}
        self.history.record({**base, "id": "recent"}, 30,
                            played_at=datetime.now(timezone.utc) - timedelta(hours=1))
        self.history.record({**base, "id": "old"}, 30,
                            played_at=datetime.now(timezone.utc) - timedelta(days=10))
        for mode, tags in (("add", "<tag value='A'/><tag value='B'/>"),
                           ("remove", "<tag value='A'/>"),
                           ("replace", "<tag value='C'/>")):
            status, root = self.org(f'<organization action="tags" mode="{mode}"><ids>'
                                    f'<id value="recent"/></ids><tags>{tags}</tags></organization>')
            self.assertEqual(200, status)
        self.assertEqual(["C"], [x.get("value") for x in root.findall(
            "tracks/entry[@id='recent']/tags/tag")])
        for listening, expected in (("recent", ["recent"]), ("notRecent", ["old"]),
                                    ("unrecorded", ["never"])):
            days = ' listeningDays="2"' if listening != "unrecorded" else ""
            status, root = self.org(f'<organization action="preview"><rules listening="{listening}"'
                                    f'{days}/></organization>')
            self.assertEqual(200, status)
            self.assertEqual(expected, [x.get("id") for x in root.findall("results/track")])
        status, root = self.org('<organization action="save" name="Recent"><rules '
                                'listening="recent" listeningDays="2"/></organization>')
        smart = root.find("smartPlaylists/smartPlaylist").get("id")
        status, root = self.org(f'<organization action="save" id="{smart}" name="Old">'
                                '<rules listening="notRecent" listeningDays="2"/>'
                                '</organization>')
        self.assertEqual("Old", root.find("smartPlaylists/smartPlaylist").get("name"))
        self.assertEqual(["old"], [x.get("id") for x in self.org(
            f'<organization action="preview" id="{smart}"/>')[1].findall("results/track")])
        folder = self.org('<organization action="folderSave" name="F"/>')[1].find("folders/folder").get("id")
        status, root = self.org(f'<organization action="folderAssign" playlistId="{smart}" '
                                f'folderId="{folder}"/>')
        self.assertEqual(folder, root.find("playlistFolders/assignment").get("folderId"))
        status, root = self.org(f'<organization action="folderAssign" playlistId="{smart}" folderId=""/>')
        self.assertEqual([], root.findall("playlistFolders/assignment"))

    def test_corrupt_history_is_explicit_error(self) -> None:
        self._stop()
        self.history_path.write_text("not json", encoding="utf-8")
        self.server = create_server(port=0, provider=DemoProvider(), library_store=self.library)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        status, root = self.request("GET")
        self.assertEqual(500, status)
        self.assertEqual("HISTORY_DAMAGED", root.get("code"))
        self.assertEqual("not json", self.history_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
