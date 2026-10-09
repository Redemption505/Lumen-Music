package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

final class LibraryClientBatchTest {
    private static final Track SOURCE = new Track("original", "Song", "Artist", 60,
            "bilibili", null, "BV1xx411c7mD", 1);
    private static Element parse(String text) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
    }

    @Test void favoritesUseOnePostReturnSnapshotCountersAndPreserveExplicitUnknownEnd() throws Exception {
        List<String> requests = new ArrayList<>();
        Track unknownSource = new Track("unknown", "Song", "Artist", 0,
                "bilibili", null, "BV1xx411c7mD", 1);
        Track first = Track.segment(unknownSource, "Clip & \"one\" <live>", "Artist", 1001, 4002);
        Track second = Track.segment(unknownSource, "Second", "Artist", 4002, 6000);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/segments/batch", exchange -> {
            requests.add(exchange.getRequestMethod());
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("<library version=\"1\" savedCount=\"1\" skippedCount=\"1\" playlistId=\"\">"
                    + "<favorites>" + LibraryClient.trackXml(first) + LibraryClient.trackXml(second)
                    + "</favorites><playlists/></library>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            String batchId = UUID.randomUUID().toString();
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var result = client.saveSegments(List.of(first, second), null, batchId);
            assertEquals(2, requests.size(), "Only one HTTP request may be used, without a reload GET");
            assertEquals("POST", requests.get(0));
            Element body = parse(requests.get(1));
            assertEquals("segments", body.getTagName());
            assertEquals("favorites", body.getAttribute("destination"));
            assertEquals(batchId, body.getAttribute("batchId"));
            assertFalse(body.hasAttribute("name"));
            assertEquals(2, body.getElementsByTagName("track").getLength());
            Element clip = (Element) body.getElementsByTagName("track").item(0);
            assertEquals(first.title(), clip.getAttribute("title"));
            assertEquals("0", clip.getAttribute("sourceDurationSeconds"));
            assertEquals("4002", clip.getAttribute("segmentEndMillis"));
            assertEquals(1, result.savedCount());
            assertEquals(1, result.skippedCount());
            assertNull(result.playlistId());
            assertEquals(List.of(first, second), result.snapshot().favorites());
        } finally { server.stop(0); }
    }

    @Test void playlistRetrySendsTheSameStableBatchAndParsesPlaylistFromEachSingleResponse() throws Exception {
        List<Element> bodies = new ArrayList<>();
        Track clip = Track.segment(SOURCE, "Clip", "Artist", 0, 1000);
        String batchId = UUID.randomUUID().toString();
        String playlistId = UUID.fromString(batchId).toString().replace("-", "");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/segments/batch", exchange -> {
            try { bodies.add(parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))); }
            catch (Exception error) { throw new java.io.IOException(error); }
            byte[] response = ("<library version=\"1\" savedCount=\"" + (bodies.size() == 1 ? 1 : 0)
                    + "\" skippedCount=\"" + (bodies.size() == 1 ? 0 : 1) + "\" playlistId=\"" + playlistId
                    + "\"><favorites/><playlists><playlist id=\"" + playlistId + "\" name=\"New &amp; list\">"
                    + LibraryClient.trackXml(clip) + "</playlist></playlists></library>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals(1, client.saveSegments(List.of(clip), " New & list ", batchId).savedCount());
            var retry = client.saveSegments(List.of(clip), " New & list ", batchId);
            assertEquals(2, bodies.size());
            for (Element body : bodies) {
                assertEquals(batchId, body.getAttribute("batchId"));
                assertEquals("playlist", body.getAttribute("destination"));
                assertEquals("New & list", body.getAttribute("name"));
            }
            assertEquals(0, retry.savedCount());
            assertEquals(1, retry.skippedCount());
            assertEquals(playlistId, retry.playlistId());
            assertEquals(List.of(clip), retry.snapshot().playlists().get(0).tracks());
        } finally { server.stop(0); }
    }

    @Test void localInvalidBatchNeverConnectsAndServerFailureDoesNotTriggerExtraRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/segments/batch", exchange -> {
            calls.incrementAndGet();
            byte[] response = "<error code=\"LIBRARY_IO_ERROR\" message=\"disk failure\"/>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            Track clip = Track.segment(SOURCE, "Clip", "Artist", 0, 1000);
            String batchId = UUID.randomUUID().toString();
            assertThrows(IllegalArgumentException.class, () -> client.saveSegments(List.of(), null, batchId));
            assertThrows(IllegalArgumentException.class, () -> client.saveSegments(List.of(SOURCE), null, batchId));
            assertThrows(IllegalArgumentException.class, () -> client.saveSegments(List.of(clip), " ", batchId));
            assertThrows(IllegalArgumentException.class, () -> client.saveSegments(List.of(clip), null, "invalid"));
            assertThrows(IllegalArgumentException.class, () -> client.saveSegments(java.util.Collections.nCopies(201, clip), null, batchId));
            assertEquals(0, calls.get());
            var error = assertThrows(IllegalStateException.class, () -> client.saveSegments(List.of(clip), null, batchId));
            assertTrue(error.getMessage().contains("500"));
            assertTrue(error.getMessage().contains("disk failure"));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
