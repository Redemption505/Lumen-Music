package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LibraryEditingClientTest {
    @Test void sendsBatchRemovalAndReadsUndoToken() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library", exchange -> {
            paths.add(exchange.getRequestURI().getRawPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String xml = paths.size() == 1
                    ? "<library version=\"1\" undoToken=\"token-1\"><favorites/><playlists/></library>"
                    : "<library version=\"1\"><favorites/><playlists/></library>";
            byte[] response = xml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new LibraryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var result = client.removeTracksFromPlaylist("list 1", List.of("a&b", "c"));
            assertEquals("token-1", result.undoToken());
            assertTrue(result.snapshot().playlists().isEmpty());
            client.undoPlaylistRemoval("list 1", result.undoToken());
            assertEquals(List.of("/api/library/playlists/list+1/tracks/remove",
                    "/api/library/playlists/list+1/tracks/undo"), paths);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var root = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    bodies.get(0).getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            assertEquals("remove", root.getTagName());
            assertEquals("a&b", root.getElementsByTagName("track").item(0).getAttributes()
                    .getNamedItem("id").getNodeValue());
            assertEquals("c", root.getElementsByTagName("track").item(1).getAttributes()
                    .getNamedItem("id").getNodeValue());
            assertEquals("<undo token=\"token-1\"/>", bodies.get(1));
        } finally { server.stop(0); }
    }
}
