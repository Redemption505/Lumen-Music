package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class LibraryMetadataTest {
    @Test void readsOldDefaultsAndEmbeddedMetadataAndKeepsOldConstructor() throws Exception {
        String xml = "<library version=\"1\"><favorites/><playlists>"
                + "<playlist id=\"old\" name=\"旧\"/>"
                + "<playlist id=\"new\" name=\"新\"><description>夜晚 &amp; 独处\n下一行</description>"
                + "<coverDataUri>data:image/png;base64,embedded</coverDataUri>"
                + "<track id=\"one\" title=\"歌\" artist=\"作者\" durationSeconds=\"60\" source=\"demo\"/>"
                + "</playlist></playlists></library>";
        HttpServer server = server(xml, new ArrayList<>());
        try {
            var snapshot = new LibraryClient(base(server)).load();
            assertEquals("", snapshot.playlists().get(0).description());
            assertEquals("", snapshot.playlists().get(0).coverDataUri());
            assertEquals("夜晚 & 独处\n下一行", snapshot.playlists().get(1).description());
            assertEquals("data:image/png;base64,embedded", snapshot.playlists().get(1).coverDataUri());
            assertEquals(1, snapshot.playlists().get(1).tracks().size());
            assertEquals("", new LibraryClient.Playlist("old", "旧", List.of()).description());
        } finally { server.stop(0); }
    }

    @Test void partialUpdatesClearAndEscapeMetadataWithoutChangingRenameContract() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server("<library version=\"1\"><favorites/><playlists/></library>", requests);
        try {
            var client = new LibraryClient(base(server));
            client.updatePlaylistMetadata("id / 1", "夜晚 <独处> & 心情\n第二行", null);
            client.updatePlaylistMetadata("id / 1", null, "data:image/png;base64,abcd");
            client.updatePlaylistMetadata("id / 1", null, "");
            client.renamePlaylist("id / 1", "新 & 名字");
            assertEquals(4, requests.size());
            for (var request : requests) {
                assertEquals("PATCH", request.method());
                assertEquals("/api/library/playlists/id+%2F+1", request.path());
            }
            Element first = parse(requests.get(0).body());
            assertEquals("夜晚 <独处> & 心情\n第二行", first.getElementsByTagName("description").item(0).getTextContent());
            assertEquals(0, first.getElementsByTagName("coverDataUri").getLength());
            Element second = parse(requests.get(1).body());
            assertEquals(0, second.getElementsByTagName("description").getLength());
            assertEquals("data:image/png;base64,abcd", second.getElementsByTagName("coverDataUri").item(0).getTextContent());
            Element clear = parse(requests.get(2).body());
            assertEquals("", clear.getElementsByTagName("coverDataUri").item(0).getTextContent());
            assertEquals("新 & 名字", parse(requests.get(3).body()).getAttribute("name"));
            assertThrows(IllegalArgumentException.class, () -> client.updatePlaylistMetadata("id", null, null));
        } finally { server.stop(0); }
    }

    private record Request(String method, String path, byte[] body) { }

    private static HttpServer server(String xml, List<Request> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestBody().readAllBytes()));
            byte[] response = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Element parse(byte[] xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml)).getDocumentElement();
    }
}
