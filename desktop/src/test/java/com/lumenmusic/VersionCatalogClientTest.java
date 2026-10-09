package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class VersionCatalogClientTest {
    private static final String CATALOG = "<catalog><song id=\"song-1\" title=\"Light &amp; Sound\" "
            + "note=\"alternate takes\" preferredVersionId=\"version-2\">"
            + "<version id=\"version-1\" label=\"studio\" performer=\"Singer A\">"
            + "<track id=\"ordinary\" title=\"Light &amp; Sound\" artist=\"Singer A\" "
            + "durationSeconds=\"60\" source=\"bilibili\"/></version>"
            + "<version id=\"version-2\" label=\"live\" performer=\"Singer B\">"
            + "<track id=\"segment:live\" title=\"Light &amp; Sound\" artist=\"Singer B\" "
            + "durationSeconds=\"3\" source=\"bilibili\" bvid=\"BV1xx411c7mD\" page=\"1\" "
            + "mediaUri=\"https://example.com/audio?a=1&amp;b=2\" coverUri=\"https://example.com/cover\" "
            + "partCount=\"2\" viewCount=\"123\" sourceTrackId=\"original\" "
            + "segmentStartMillis=\"1500\" segmentEndMillis=\"4000\" sourceDurationSeconds=\"60\"/>"
            + "</version></song></catalog>";

    @Test void readsVersionsWithOptionalAttributesAndResolvesPreferredResource() throws Exception {
        HttpServer server = server(CATALOG, 200, new ArrayList<>());
        try {
            VersionCatalog catalog = client(server).load();
            var song = catalog.findByResourceId("ordinary").orElseThrow();
            assertEquals("song-1", song.id());
            assertEquals("Singer B", catalog.versionByResourceId("segment:live").orElseThrow().performer());
            Track preferred = song.preferredTrack().orElseThrow();
            assertEquals("segment:live", preferred.id());
            assertEquals(preferred, catalog.preferredTrack(song.versions().get(0).track()));
            assertTrue(VersionCatalog.related(song.versions().get(0).track(), preferred));
            assertTrue(preferred.isSegment());
            assertEquals("original", preferred.sourceTrackId());
            assertEquals(1500, preferred.segmentStartMillis());
            assertEquals(4000, preferred.segmentEndMillis());
            assertEquals(60, preferred.sourceDurationSeconds());
            assertEquals(URI.create("https://example.com/audio?a=1&b=2"), preferred.mediaUri());
            assertEquals(123, preferred.viewCount());
            assertNull(song.versions().get(0).track().mediaUri());
            assertFalse(catalog.findByResourceId("missing").isPresent());
            assertEquals(List.of(song), catalog.suggestionsFor(new Track("new", "  ＬＩＧＨＴ   &  Sound ",
                    "Another Singer", 40, "local")));
            assertTrue(catalog.suggestionsFor(new Track("new", "Different", "Singer A", 40, "local")).isEmpty());
        } finally { server.stop(0); }
    }

    @Test void sendsAllMutationRoutesAndEscapedTrackData() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(CATALOG, 200, requests);
        try {
            VersionCatalogClient client = client(server);
            Track original = new Track("source:1", "Original", "Artist", 60, "bilibili");
            Track segment = Track.segment(original, "Light & Sound", "Singer <B>", 1500, 4000);
            client.create("Light & Sound", "A \"note\"\nsecond\tline\r", List.of(
                    new VersionCatalog.Version("Live <edit>", "Singer <B>", segment)));
            client.update("song:1", "New title", "", "version:2");
            client.add("song:1", "Studio", "Singer A", original);
            client.updateVersion("song:1", "version:2", "Live & edit", "Singer B");
            client.merge("song:1", "other:2");
            client.detach("song:1", "version:2");
            client.replace("song:1", "version:2", segment);
            client.undo();

            String songPath = "/api/library/versions/song%3A1";
            String versionPath = songPath + "/versions/version%3A2";
            assertEquals(List.of("POST /api/library/versions", "PATCH " + songPath,
                    "POST " + songPath + "/versions", "PATCH " + versionPath,
                    "POST " + songPath + "/merge", "DELETE " + versionPath,
                    "POST " + versionPath + "/replace", "POST /api/library/versions/undo"),
                    requests.stream().map(r -> r.method() + " " + r.path()).toList());
            Element created = parse(requests.get(0).body());
            assertEquals("Light & Sound", created.getAttribute("title"));
            assertEquals("A \"note\"\nsecond\tline\r", created.getAttribute("note"));
            Element version = (Element) created.getElementsByTagName("version").item(0);
            assertEquals("Live <edit>", version.getAttribute("label"));
            assertEquals("Singer <B>", version.getAttribute("performer"));
            Element track = (Element) version.getElementsByTagName("track").item(0);
            assertEquals(segment.id(), track.getAttribute("id"));
            assertEquals("source:1", track.getAttribute("sourceTrackId"));
            assertEquals("1500", track.getAttribute("segmentStartMillis"));
            assertEquals("4000", track.getAttribute("segmentEndMillis"));
            assertEquals("version:2", parse(requests.get(1).body()).getAttribute("preferredVersionId"));
            assertEquals("Live & edit", parse(requests.get(3).body()).getAttribute("label"));
            assertEquals("Singer B", parse(requests.get(3).body()).getAttribute("performer"));
            assertEquals("other:2", parse(requests.get(4).body()).getAttribute("songId"));
            assertEquals("", requests.get(5).body());
            assertEquals("track", parse(requests.get(6).body()).getTagName());
            assertEquals("", requests.get(7).body());
        } finally { server.stop(0); }
    }

    @Test void rejectsEmptyCreateAndReportsServerAndFormatErrors() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer errorServer = server("<error message=\"resource already linked\"/>", 409, requests);
        try {
            var client = client(errorServer);
            assertThrows(IllegalArgumentException.class, () -> client.create("Song", "", List.of()));
            assertTrue(requests.isEmpty());
            assertTrue(assertThrows(IllegalStateException.class, client::load)
                    .getMessage().contains("resource already linked"));
        } finally { errorServer.stop(0); }
        HttpServer malformedServer = server("<library version=\"1\"/>", 200, requests);
        try {
            assertTrue(assertThrows(IllegalStateException.class, () -> client(malformedServer).load())
                    .getMessage().contains("响应格式错误"));
        } finally { malformedServer.stop(0); }
    }

    @Test void relatedTitlesUseQuotedSongAndKnownVersionLabelsOnly() {
        Track song = new Track("one", "光", "Singer A", 80, "local");
        Track video = new Track("two", "【热门视频】Singer B《光》现场版", "Singer B", 83, "bilibili");
        Track live = new Track("three", "光 (Live)", "Singer B", 83, "bilibili");
        Track studio = new Track("four", "光 [录音室版]", "Singer A", 80, "local");
        Track other = new Track("five", "光年 (Live)", "Singer A", 80, "local");
        Track meaningfulParenthesis = new Track("six", "光 (Part II)", "Singer A", 80, "local");
        assertTrue(VersionCatalog.related(song, video));
        assertTrue(VersionCatalog.related(live, studio));
        assertFalse(VersionCatalog.related(song, other));
        assertFalse(VersionCatalog.related(song, meaningfulParenthesis));
        assertEquals("Singer B", video.artist());
    }

    private static HttpServer server(String responseXml, int status, List<Request> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/versions", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = responseXml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }

    private static VersionCatalogClient client(HttpServer server) {
        return new VersionCatalogClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static Element parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private record Request(String method, String path, String body) { }
}
