from __future__ import annotations

import http.client
import threading
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote
from xml.etree import ElementTree

from lumen_service.providers import DemoProvider, ProviderError
from lumen_service.library import LibraryStore
from lumen_service.server import create_server


class LumenServiceHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = create_server(port=0, provider=DemoProvider())
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def request(self, path: str) -> tuple[int, str, bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            return response.status, response.getheader("Content-Type", ""), response.read()
        finally:
            connection.close()

    def test_health(self) -> None:
        status, content_type, body = self.request("/api/health")
        root = ElementTree.fromstring(body)
        self.assertEqual(200, status)
        self.assertEqual("application/xml; charset=utf-8", content_type)
        self.assertEqual("health", root.tag)
        self.assertEqual({"status": "ok", "provider": "demo"}, root.attrib)

    def test_tracks_filters_chinese_metadata(self) -> None:
        status, _, body = self.request(f"/api/tracks?q={quote('星尘')}")
        root = ElementTree.fromstring(body)
        self.assertEqual(200, status)
        tracks = root.findall("track")
        self.assertEqual(1, len(tracks))
        self.assertEqual("demo-2", tracks[0].get("id"))
        self.assertEqual("星尘与海 <序曲>", tracks[0].get("title"))

    def test_xml_attributes_are_escaped(self) -> None:
        status, _, body = self.request("/api/tracks?q=demo")
        self.assertEqual(200, status)
        self.assertEqual([], ElementTree.fromstring(body).findall("track"))

        _, _, all_tracks = self.request("/api/tracks")
        self.assertIn(b"&lt;", all_tracks)
        self.assertIn(b"&amp;", all_tracks)
        parsed = ElementTree.fromstring(all_tracks)
        escaped_track = next(track for track in parsed if track.get("id") == "demo-2")
        self.assertEqual("星尘与海 <序曲>", escaped_track.get("title"))
        self.assertEqual("安和 & 乐团", escaped_track.get("artist"))

    def test_unknown_route_returns_xml_404(self) -> None:
        status, _, body = self.request("/missing")
        error = ElementTree.fromstring(body)
        self.assertEqual(404, status)
        self.assertEqual("error", error.tag)
        self.assertEqual("NOT_FOUND", error.get("code"))

    def test_stream_returns_explicit_xml_501(self) -> None:
        status, _, body = self.request("/api/tracks/demo-1/stream")
        error = ElementTree.fromstring(body)
        self.assertEqual(501, status)
        self.assertEqual("error", error.tag)
        self.assertEqual("NOT_IMPLEMENTED", error.get("code"))


class FailingProvider:
    name = "failing"

    def search(self, query: str):
        raise ProviderError("UPSTREAM_TIMEOUT", "Bilibili search timed out", 504)


class ProviderFailureHttpTest(unittest.TestCase):
    def test_provider_failure_returns_non_200_xml(self) -> None:
        server = create_server(port=0, provider=FailingProvider())
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        try:
            connection.request("GET", "/api/tracks?q=test")
            response = connection.getresponse()
            status = response.status
            body = response.read()
        finally:
            connection.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        error = ElementTree.fromstring(body)
        self.assertEqual(504, status)
        self.assertEqual("UPSTREAM_TIMEOUT", error.get("code"))
        self.assertEqual("B 站请求超时", error.get("message"))


class LibraryHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "library.json"
        self.server = create_server(port=0, provider=DemoProvider(),
                                    library_store=LibraryStore(self.path))
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
            connection.request(method, path, body=body.encode("utf-8") if body else None,
                               headers={"Content-Type": "application/xml; charset=utf-8"})
            response = connection.getresponse()
            return response.status, ElementTree.fromstring(response.read())
        finally:
            connection.close()

    def test_favorites_playlists_and_order_persist_after_restart(self) -> None:
        first = '<track id="demo-1" title="月光航线" artist="林间回声" durationSeconds="8" source="demo" page="0"/>'
        second = '<track id="demo-2" title="星尘与海" artist="安和" durationSeconds="8" source="demo" page="0"/>'
        self.assertEqual(200, self.request("POST", "/api/library/favorites", first)[0])
        self.assertEqual(200, self.request("POST", "/api/library/favorites", first)[0])
        self.assertEqual(200, self.request("POST", "/api/library/favorites", second)[0])
        status, root = self.request("POST", "/api/library/favorites/move", '<move from="1" to="0"/>')
        self.assertEqual(200, status)
        self.assertEqual(["demo-2", "demo-1"], [item.get("id") for item in root.find("favorites")])

        status, root = self.request("POST", "/api/library/playlists", '<playlist name="周杰伦相关"/>')
        self.assertEqual(200, status)
        playlist_id = root.find("playlists/playlist").get("id")
        self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/tracks", first)[0])
        self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/tracks", second)[0])
        self.assertEqual(200, self.request("POST", f"/api/library/playlists/{playlist_id}/move",
                                            '<move from="1" to="0"/>')[0])
        self.assertEqual(200, self.request("PATCH", f"/api/library/playlists/{playlist_id}",
                                            '<playlist name="喜欢的视频"/>')[0])

        saved = LibraryStore(self.path).snapshot()
        self.assertEqual(["demo-2", "demo-1"], [item["id"] for item in saved["favorites"]])
        self.assertEqual("喜欢的视频", saved["playlists"][0]["name"])
        self.assertEqual(["demo-2", "demo-1"],
                         [item["id"] for item in saved["playlists"][0]["tracks"]])
        self.assertTrue(self.path.with_name("library.json.bak").exists())
        self.assertEqual(200, self.request("DELETE", "/api/library/favorites/demo-1")[0])
        self.assertEqual(200, self.request("DELETE", f"/api/library/playlists/{playlist_id}/tracks/demo-1")[0])
        self.assertEqual(200, self.request("DELETE", f"/api/library/playlists/{playlist_id}")[0])

    def test_cover_uri_survives_library_xml_and_disk(self) -> None:
        cover = "https://i0.hdslb.com/bfs/archive/example.jpg?x=1&y=2"
        body = ('<track id="video-1" title="封面测试" artist="歌手" '
                'durationSeconds="8" source="bilibili" '
                'coverUri="https://i0.hdslb.com/bfs/archive/example.jpg?x=1&amp;y=2"/>')
        status, root = self.request("POST", "/api/library/favorites", body)
        self.assertEqual(200, status)
        self.assertEqual(cover, root.find("favorites/track").get("coverUri"))
        self.assertEqual(cover, LibraryStore(self.path).snapshot()["favorites"][0]["coverUri"])

        invalid = body.replace("https://i0.hdslb.com/bfs/archive/example.jpg?x=1&amp;y=2",
                               "https://example.com/audio.mp3")
        self.assertEqual(400, self.request("POST", "/api/library/favorites", invalid)[0])

    def test_local_file_cover_survives_favorite_and_playlist_http_round_trip(self) -> None:
        cover = "file:///C:/lumen%20data/covers/embedded.png"
        body = ('<track id="local-covered" title="本地封面" artist="本地" '
                'durationSeconds="8" source="local" mediaUri="file:///C:/music/song.mp3" '
                'coverUri="file:///C:/lumen%20data/covers/embedded.png"/>')
        status, root = self.request("POST", "/api/library/favorites", body)
        self.assertEqual(200, status)
        self.assertEqual(cover, root.find("favorites/track").get("coverUri"))
        status, root = self.request("POST", "/api/library/playlists", '<playlist name="本地歌曲"/>')
        self.assertEqual(200, status)
        playlist_id = root.find("playlists/playlist").get("id")
        status, root = self.request("POST", f"/api/library/playlists/{playlist_id}/tracks", body)
        self.assertEqual(200, status)
        self.assertEqual(cover, root.find("playlists/playlist/track").get("coverUri"))
        saved = LibraryStore(self.path).snapshot()
        self.assertEqual(cover, saved["favorites"][0]["coverUri"])
        self.assertEqual(cover, saved["playlists"][0]["tracks"][0]["coverUri"])
        before = self.path.read_bytes()
        for source in ("bilibili", "demo"):
            invalid = body.replace('source="local"', f'source="{source}"')
            self.assertEqual(400, self.request("POST", "/api/library/favorites", invalid)[0])
            self.assertEqual(before, self.path.read_bytes())

    def test_damaged_library_returns_error_without_disabling_search(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.path.write_text("{broken", encoding="utf-8")
        with patch.dict("os.environ", {"LUMEN_LIBRARY_PATH": str(self.path)}):
            self.server = create_server(port=0, provider=DemoProvider())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        status, error = self.request("GET", "/api/library")
        self.assertEqual(500, status)
        self.assertEqual("LIBRARY_DAMAGED", error.get("code"))
        self.assertEqual("{broken", self.path.read_text(encoding="utf-8"))
        self.assertEqual(200, self.request("GET", "/api/tracks")[0])


if __name__ == "__main__":
    unittest.main()
