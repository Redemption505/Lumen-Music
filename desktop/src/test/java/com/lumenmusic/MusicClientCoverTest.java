package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicClientCoverTest {
    @Test
    void parsesCoverUriSeparatelyFromMediaUri() throws Exception {
        String xml = "<tracks>"
                + "<track id=\"bilibili:BV1:p1\" title=\"Song\" artist=\"Artist\" "
                + "durationSeconds=\"8\" source=\"bilibili\" bvid=\"BV1\" page=\"1\" "
                + "coverUri=\"https://i0.hdslb.com/bfs/archive/cover.jpg?x=1&amp;y=2\" viewCount=\"12345\"/>"
                + "<track id=\"demo-1\" title=\"Demo\" artist=\"Artist\" "
                + "durationSeconds=\"8\" source=\"demo\"/>"
                + "</tracks>";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks", exchange -> {
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var tracks = client.search("song");
            assertEquals(URI.create("https://i0.hdslb.com/bfs/archive/cover.jpg?x=1&y=2"),
                    tracks.get(0).coverUri());
            assertNull(tracks.get(0).mediaUri());
            assertEquals(12345, tracks.get(0).viewCount());
            assertEquals(0, tracks.get(1).viewCount());
            assertNull(tracks.get(1).coverUri());
            assertNull(new Track("local", "Local", "Artist", 1, "local").coverUri());
        } finally {
            server.stop(0);
        }
    }
}
