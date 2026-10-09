package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LibraryClientSegmentTest {
    @Test void writesSegmentXmlAndUsesTrackUpdateAndDeleteRoutes() throws Exception {
        List<String> methods = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library", exchange -> {
            methods.add(exchange.getRequestMethod());
            paths.add(exchange.getRequestURI().getRawPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "<library version=\"1\"><favorites/><playlists/></library>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            Track original = new Track("bilibili:BV1xx411c7mD:p1", "原曲", "UP", 60,
                    "bilibili", null, "BV1xx411c7mD", 1);
            Track segment = Track.segment(original, "片段 & 一", "歌手", 1_500, 4_000);
            client.favorite(segment);
            client.updateSegment(segment.withSegment("修改", "歌手", 2_000, 5_000));
            client.deleteSegment(segment.id());
            assertEquals(List.of("POST", "PATCH", "DELETE"), methods);
            assertEquals("/api/library/favorites", paths.get(0));
            assertEquals("/api/library/tracks/" + URLEncoder.encode(segment.id(), StandardCharsets.UTF_8), paths.get(1));
            assertEquals(paths.get(1), paths.get(2));
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var first = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    bodies.get(0).getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            assertEquals(segment.id(), first.getAttribute("id"));
            assertEquals(original.id(), first.getAttribute("sourceTrackId"));
            assertEquals("1500", first.getAttribute("segmentStartMillis"));
            assertEquals("4000", first.getAttribute("segmentEndMillis"));
            assertEquals("60", first.getAttribute("sourceDurationSeconds"));
            assertEquals("片段 & 一", first.getAttribute("title"));
            assertTrue(bodies.get(1).contains("segmentStartMillis=\"2000\""));
            assertEquals("", bodies.get(2));
        } finally { server.stop(0); }
    }

    @Test void omitsEmptyOptionalAttributesForOrdinaryTracks() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "<library version=\"1\"><favorites/><playlists/></library>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            client.favorite(new Track("bilibili:BV1xx411c7mD:p1", "原曲", "UP", 60,
                    "bilibili", null, "BV1xx411c7mD", 1));
            assertEquals("<track id=\"bilibili:BV1xx411c7mD:p1\" title=\"原曲\" artist=\"UP\" "
                    + "durationSeconds=\"60\" source=\"bilibili\" bvid=\"BV1xx411c7mD\" page=\"1\"/>",
                    bodies.get(0));
        } finally { server.stop(0); }
    }

    @Test void readsSegmentAndOldOrdinaryTracks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library", exchange -> {
            byte[] response = ("<library version=\"1\"><favorites>"
                    + "<track id=\"segment:one\" title=\"clip\" artist=\"a\" durationSeconds=\"2\" "
                    + "source=\"bilibili\" bvid=\"BV1xx411c7mD\" page=\"1\" "
                    + "sourceTrackId=\"original\" segmentStartMillis=\"1001\" "
                    + "segmentEndMillis=\"2002\" sourceDurationSeconds=\"60\"/>"
                    + "<track id=\"old\" title=\"old\" artist=\"a\" durationSeconds=\"60\" "
                    + "source=\"bilibili\"/></favorites><playlists/></library>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            List<Track> tracks = client.load().favorites();
            assertTrue(tracks.get(0).isSegment());
            assertEquals("original", tracks.get(0).audioSource().id());
            assertFalse(tracks.get(1).isSegment());
            assertNull(tracks.get(1).sourceTrackId());
        } finally { server.stop(0); }
    }
}
