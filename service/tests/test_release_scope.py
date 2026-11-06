from __future__ import annotations

import http.client
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote
from xml.etree import ElementTree

import lumen_service.server as server_module
from lumen_service.history import HistoryStore
from lumen_service.library import LibraryStore
from lumen_service.models import Track
from lumen_service.release_scope import LocalOnlyProvider
from lumen_service.server import create_server


class ProviderSpy:
    name = "unexpected"

    def __init__(self) -> None:
        self.calls: list[str] = []

    def search(self, query: str):
        self.calls.append("search")
        return (Track("unexpected", "unexpected", "unexpected", 1, "bilibili"),)

    def music_popular(self):
        self.calls.append("popular")
        return ()


class LocalFirstReleaseProfileTest(unittest.TestCase):
    def setUp(self) -> None:
        workspace_build = Path(__file__).resolve().parents[2] / "build"
        workspace_build.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=workspace_build)
        root = Path(self.temp.name)
        self.library_path = root / "library.json"
        self.library_store = LibraryStore(self.library_path)
        self.provider = ProviderSpy()
        self.server = create_server(
            port=0,
            provider=self.provider,
            library_store=self.library_store,
            history_store=HistoryStore(root / "history.json"),
            release_profile="local-first",
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: str | None = None) -> tuple[int, ElementTree.Element]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, body=body.encode("utf-8") if body is not None else None,
                               headers={"Content-Type": "application/xml; charset=utf-8"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def assert_unavailable(self, method: str, path: str, body: str | None = None) -> None:
        status, root = self.request(method, path, body)
        self.assertEqual(503, status, path)
        self.assertEqual("LOCAL_FIRST_UNAVAILABLE", root.get("code"), path)

    def test_health_identifies_local_profile_and_never_constructs_bilibili(self) -> None:
        scratch = Path(self.temp.name) / "constructor-probe"
        with patch.object(server_module.BilibiliProvider, "__init__",
                          side_effect=AssertionError("must not construct")):
            probe = create_server(
                port=0, release_profile="local-first",
                library_store=LibraryStore(scratch / "library.json"),
                history_store=HistoryStore(scratch / "history.json"),
            )
        probe.server_close()
        status, root = self.request("GET", "/api/health")
        self.assertEqual(200, status)
        self.assertEqual("local", root.get("provider"))
        self.assertEqual("local-first", root.get("releaseProfile"))
        self.assertIsInstance(LocalOnlyProvider(), LocalOnlyProvider)

    def test_all_online_search_discovery_and_stream_routes_fail_closed(self) -> None:
        paths = (
            f"/api/tracks?q={quote('歌名')}",
            "/api/tracks?q=BV1xx411c7mD",
            "/api/collections/BV1xx411c7mD/tracks",
            "/api/recommendations",
            "/api/tracks/bilibili%3ABV1xx411c7mD%3Ap1/stream",
        )
        with patch.object(server_module.BilibiliAudioProxy, "open",
                          side_effect=AssertionError("audio proxy must not run")) as audio_open:
            for path in paths:
                with self.subTest(path=path):
                    self.assert_unavailable("GET", path)
            connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
            try:
                connection.request("HEAD", paths[-1])
                response = connection.getresponse()
                self.assertEqual(503, response.status)
                self.assertEqual(b"", response.read())
            finally:
                connection.close()
            audio_open.assert_not_called()
        self.assert_unavailable("POST", "/api/radio", "<radio mode=\"discover\"/>")
        self.assertEqual([], self.provider.calls)

    def test_library_edits_and_backup_keep_existing_online_metadata(self) -> None:
        self.library_store.favorite({
            "id": "bilibili:BV1xx411c7mD:p1", "title": "已有在线收藏", "artist": "歌手",
            "durationSeconds": 120, "source": "bilibili", "bvid": "BV1xx411c7mD", "page": 1,
        })
        local = ('<track id="local-1" title="本地歌曲" artist="本地歌手" '
                 'durationSeconds="120" source="local" mediaUri="file:///music/song.mp3"/>')
        self.assertEqual(200, self.request("POST", "/api/library/favorites", local)[0])
        online = ('<track id="bilibili:BV1xx411c7mD:p1" title="在线歌曲" artist="歌手" '
                  'durationSeconds="120" source="bilibili" bvid="BV1xx411c7mD" page="1"/>')
        self.assertEqual(200, self.request("POST", "/api/library/favorites", online)[0])

        status, root = self.request("GET", "/api/library")
        self.assertEqual(200, status)
        self.assertIn("bilibili:BV1xx411c7mD:p1",
                      [track.get("id") for track in root.findall("favorites/track")])
        self.assertEqual(200, self.request("DELETE", "/api/library/favorites/local-1")[0])

        status, root = self.request("POST", "/api/library/playlists", '<playlist name="临时整理"/>')
        self.assertEqual(200, status)
        playlist_id = root.find("playlists/playlist").get("id")
        self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/tracks", local)[0])
        self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/tracks", online)[0])
        extra_local = ('<track id="local-2" title="另一首本地歌曲" artist="本地歌手" '
                       'durationSeconds="90" source="local" mediaUri="file:///music/another.mp3"/>')
        self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/tracks",
                                            extra_local)[0])
        status, before_move = self.request("GET", "/api/library")
        self.assertEqual(200, status)
        playlist_tracks = before_move.find("playlists/playlist")
        self.assertIsNotNone(playlist_tracks)
        self.assertEqual(["local-1", "bilibili:BV1xx411c7mD:p1", "local-2"],
                         [track.get("id") for track in playlist_tracks.findall("track")])
        status, root = self.request("POST", f"/api/library/playlists/{playlist_id}/move",
                                    '<move from="2" to="0"/>')
        self.assertEqual(200, status)
        playlist_tracks = root.find("playlists/playlist")
        self.assertIsNotNone(playlist_tracks)
        self.assertEqual(["local-2", "local-1", "bilibili:BV1xx411c7mD:p1"],
                         [track.get("id") for track in playlist_tracks.findall("track")])
        self.assertEqual(200, self.request("DELETE",
            f"/api/library/playlists/{playlist_id}/tracks/local-1")[0])
        self.assertEqual(200, self.request("POST",
            f"/api/library/playlists/{playlist_id}/tracks/remove",
            '<remove><track id="local-2"/><track id="bilibili:BV1xx411c7mD:p1"/></remove>')[0])
        self.assertEqual(200, self.request("DELETE", f"/api/library/playlists/{playlist_id}")[0])

        backup_path = Path(self.temp.name) / "library-backup.zip"
        backup_xml = f'<backup path="{backup_path.as_posix()}"/>'
        self.assertEqual(200, self.request("POST", "/api/backup/export", backup_xml)[0])
        self.assertEqual(200, self.request("DELETE", "/api/library/favorites/bilibili%3ABV1xx411c7mD%3Ap1")[0])
        self.assertEqual(200, self.request("POST", "/api/backup/restore", backup_xml)[0])
        status, root = self.request("GET", "/api/library")
        self.assertEqual(200, status)
        self.assertIn("bilibili:BV1xx411c7mD:p1",
                      [track.get("id") for track in root.findall("favorites/track")])

        self.assert_unavailable("POST", "/api/library/playlists/import",
                                '<import url="https://space.bilibili.com/1/favlist"/>')
        self.assertEqual([], self.provider.calls)

    def test_lyrics_keeps_lrclib_path_but_receives_noop_supplemental_fetcher(self) -> None:
        seen: dict[str, object] = {}

        def fake_lookup(title, artist, duration, **kwargs):
            seen.update(kwargs)
            return type("Result", (), {"auto_id": None, "candidates": ()})()

        with patch.object(server_module, "lookup_lyrics", side_effect=fake_lookup):
            status, root = self.request("GET", f"/api/lyrics?title={quote('春日')}&artist={quote('歌手')}")
        self.assertEqual(200, status)
        self.assertEqual("lyrics", root.tag)
        self.assertIsNone(seen.get("fetch_json"))
        self.assertEqual({"code": 200, "result": {"songs": []}},
                         seen["fetch_supplemental_json"]("https://blocked.invalid"))

    def test_lyrics_http_uses_lrclib_without_opening_netease(self) -> None:
        class EmptyResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, _limit: int) -> bytes:
                return b"[]"

        urls: list[str] = []

        def open_lrclib(request, timeout):
            urls.append(request.full_url)
            return EmptyResponse()

        with patch("lumen_service.lyrics.urlopen", side_effect=open_lrclib), \
             patch("lumen_service.lyrics.time.sleep"):
            status, root = self.request(
                "GET", f"/api/lyrics?title={quote('Spring')}&artist={quote('Singer')}")
        self.assertEqual(200, status)
        self.assertEqual("lyrics", root.tag)
        self.assertEqual([], root.findall("candidate"))
        self.assertGreaterEqual(len(urls), 1)
        self.assertTrue(all(url.startswith("https://lrclib.net/") for url in urls), urls)
        self.assertFalse(any("163.com" in url for url in urls), urls)

    def test_cli_local_first_selects_local_provider_without_constructing_online_providers(self) -> None:
        from lumen_service import __main__ as entrypoint

        class StoppedServer:
            server_port = 0

            def serve_forever(self):
                raise KeyboardInterrupt

            def server_close(self):
                pass

        server = StoppedServer()
        with patch.object(sys, "argv", ["lumen_service", "--release-profile", "local-first"]), \
             patch.object(entrypoint, "BilibiliProvider",
                          side_effect=AssertionError("must not construct Bilibili")), \
             patch.object(entrypoint, "DemoProvider",
                          side_effect=AssertionError("must not construct demo")), \
             patch.object(entrypoint, "create_server", return_value=server) as create:
            entrypoint.main()
        kwargs = create.call_args.kwargs
        self.assertIsInstance(kwargs["provider"], LocalOnlyProvider)
        self.assertEqual("local-first", kwargs["release_profile"])

    def test_profile_cannot_be_selected_from_service_api(self) -> None:
        status, _ = self.request("GET", "/api/settings/release-profile")
        self.assertEqual(404, status)
        status, _ = self.request("POST", "/api/settings/release-profile", "<settings profile=\"full\"/>")
        self.assertEqual(404, status)


if __name__ == "__main__":
    unittest.main()
