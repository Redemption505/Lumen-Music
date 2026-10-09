package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeClientTest {
    private static final String HOME = "<home version=\"1\"><sections>"
            + "<section id=\"pinned\"/><section id=\"continue\"/><section id=\"reunion\"/><section id=\"discover\"/>"
            + "</sections><pinned><pin kind=\"playlist\" id=\"list &amp; &quot;1&quot;\"/>"
            + "<pin kind=\"smart\" id=\"smart:1\"/></pinned></home>";

    @Test void loadAndSavePreserveOrderingKindAndEscapedId() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = server(HOME, 200, requests);
        try {
            HomeClient client = client(server);
            HomeClient.Preferences preferences = client.load();
            assertEquals(List.of("pinned", "continue", "reunion", "discover"), preferences.sections());
            assertEquals(new HomeClient.Pin("playlist", "list & \"1\""), preferences.pinned().get(0));
            assertEquals("smart", preferences.pinned().get(1).kind());
            assertEquals(preferences, client.save(preferences));
            assertEquals("GET ", requests.get(0));
            assertTrue(requests.get(1).startsWith("POST <home version=\"1\">"));
            assertTrue(requests.get(1).contains("id=\"list &amp; &quot;1&quot;\""));
        } finally { server.stop(0); }
    }

    @Test void preferencesValidateAndCopyInputs() {
        var sections = new ArrayList<>(HomeClient.DEFAULT_SECTIONS);
        var pins = new ArrayList<>(List.of(new HomeClient.Pin("smart", "s")));
        var preferences = new HomeClient.Preferences(sections, pins);
        sections.clear(); pins.clear();
        assertEquals(4, preferences.sections().size());
        assertEquals(1, preferences.pinned().size());
        assertThrows(UnsupportedOperationException.class, () -> preferences.pinned().clear());
        assertThrows(IllegalArgumentException.class, () -> new HomeClient.Pin("folder", "f"));
        assertThrows(IllegalArgumentException.class, () -> new HomeClient.Pin("playlist", ""));
        assertThrows(IllegalArgumentException.class, () -> new HomeClient.Preferences(List.of("continue"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HomeClient.Preferences(
                List.of("continue", "continue", "reunion", "discover"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HomeClient.Preferences(HomeClient.DEFAULT_SECTIONS,
                List.of(new HomeClient.Pin("smart", "s"), new HomeClient.Pin("smart", "s"))));
        var tooMany = new ArrayList<HomeClient.Pin>();
        for (int i = 0; i < 7; i++) tooMany.add(new HomeClient.Pin("playlist", "p" + i));
        assertThrows(IllegalArgumentException.class, () -> new HomeClient.Preferences(HomeClient.DEFAULT_SECTIONS, tooMany));
        assertTrue(HomeClient.defaults().pinned().isEmpty());
    }

    @Test void rejectsInvalidResponseVersionMissingSectionsAndHttpError() throws Exception {
        for (String body : List.of(HOME.replace("version=\"1\"", "version=\"2\""),
                "<home version=\"1\"><sections/><pinned/></home>", "<library/>")) {
            HttpServer server = server(body, 200, new ArrayList<>());
            try { assertThrows(Exception.class, () -> client(server).load()); }
            finally { server.stop(0); }
        }
        HttpServer server = server("<error message=\"歌单不存在\"/>", 400, new ArrayList<>());
        try {
            var error = assertThrows(IllegalStateException.class, () -> client(server).load());
            assertTrue(error.getMessage().contains("歌单不存在"));
        } finally { server.stop(0); }
    }

    private static HttpServer server(String response, int status, List<String> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/home", exchange -> {
            requests.add(exchange.getRequestMethod() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
    private static HomeClient client(HttpServer server) {
        return new HomeClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
}
