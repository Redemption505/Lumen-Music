package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LibraryTransferClientTest {
    @Test void importAndBackupClientsUseTheExpectedLocalContracts() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/playlists/import", exchange -> {
            requests.add(exchange.getRequestURI() + " " + new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "<import playlistId=\"one\" name=\"精选\" importedCount=\"2\" "
                    + "skippedCount=\"1\" duplicateCount=\"1\"/>");
        });
        server.createContext("/api/library", exchange -> respond(exchange,
                "<library version=\"1\"><favorites/><playlists><playlist id=\"one\" "
                        + "name=\"精选\"/></playlists></library>"));
        server.createContext("/api/backup", exchange -> {
            requests.add(exchange.getRequestURI() + " " + new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "<backup-preview favorites=\"2\" playlists=\"1\" "
                    + "history=\"3\" backgroundBytes=\"0\" members=\"library.json | history.json\"/>");
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var imported = new LibraryClient(base).importPublicFavorite(
                    "https://www.bilibili.com/medialist/detail/ml123?x=1&y=2");
            assertEquals(2, imported.importedCount());
            assertEquals(1, imported.skippedCount());
            assertEquals("精选", imported.snapshot().playlists().get(0).name());
            var backup = new BackupClient(base);
            assertEquals(3, backup.inspect(Path.of("example.zip")).history());
            assertEquals(2, backup.restore(Path.of("example.zip")).favorites());
            assertTrue(requests.get(0).contains("&amp;"));
            assertTrue(requests.stream().anyMatch(item -> item.startsWith("/api/backup/inspect")));
            assertTrue(requests.stream().anyMatch(item -> item.startsWith("/api/backup/restore")));
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String xml)
            throws java.io.IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
