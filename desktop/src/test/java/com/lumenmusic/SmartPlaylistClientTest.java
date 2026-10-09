package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistClientTest {
    private static final String TRACK = "<track id=\"segment:1\" title=\"Live &amp; Light\" "
            + "artist=\"Singer\" durationSeconds=\"3\" source=\"bilibili\" "
            + "sourceTrackId=\"origin\" segmentStartMillis=\"1500\" segmentEndMillis=\"4000\" "
            + "sourceDurationSeconds=\"90\" mediaUri=\"https://example.com/audio?a=1&amp;b=2\"/>";
    private static final String STATE = "<organization version=\"1\"><tracks>"
            + "<entry id=\"segment:1\" playCount=\"2\" favoriteAt=\"2026-01-01T00:00:00Z\">"
            + TRACK + "<tags><tag value=\"现场\"/></tags>"
            + "<suggestions><tag value=\"纯音乐\"/></suggestions></entry>"
            + "</tracks><smartPlaylists><smartPlaylist id=\"smart:1\" name=\"Live &amp; Light\" "
            + "folderId=\"folder:1\"><rules favoriteOnly=\"true\" listening=\"notRecent\" "
            + "listeningDays=\"14\" cached=\"yes\"><tag value=\"现场\"/></rules>"
            + "</smartPlaylist></smartPlaylists><folders><folder id=\"folder:1\" name=\"Shows\"/>"
            + "</folders><playlistFolders><assignment playlistId=\"smart:1\" folderId=\"folder:1\"/>"
            + "</playlistFolders><results>" + TRACK + "</results></organization>";

    @Test void readsMetadataAndPreservesUnknownCacheState() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(STATE, 200, requests);
        try {
            SmartPlaylistClient.State state = client(server).load();
            assertEquals("GET", requests.get(0).method());
            assertEquals(1, state.entries().size());
            var entry = state.entries().get(0);
            assertNull(entry.cached());
            assertNull(entry.lastPlayedAt());
            assertEquals(2, entry.playCount());
            assertEquals(List.of("现场"), entry.tags());
            assertEquals(List.of("纯音乐"), entry.suggestions());
            assertEquals(1500, entry.track().segmentStartMillis());
            assertEquals("origin", entry.track().sourceTrackId());
            assertEquals(URI.create("https://example.com/audio?a=1&b=2"), entry.track().mediaUri());
            assertEquals("Live & Light", state.smartPlaylists().get(0).name());
            assertEquals("notRecent", state.smartPlaylists().get(0).rules().listening());
            assertEquals(List.of("现场"), state.smartPlaylists().get(0).rules().tagsAll());
            assertEquals("segment:1", state.results().get(0).id());
            assertEquals("folder:1", state.assignments().get(0).folderId());
        } finally { server.stop(0); }
    }

    @Test void sendsEscapedRulesTagsFoldersAndExplicitCacheContext() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(STATE, 200, requests);
        try {
            SmartPlaylistClient client = client(server);
            var rules = new SmartPlaylistClient.Rules(true, 30, "notRecent", 14, "yes", 60, 600,
                    List.of("现场", "A & B"));
            client.save("smart:1", "A \"name\" & more", "folder:1", rules);
            client.preview("smart:1", rules, Set.of("segment:1"));
            client.tags("replace", List.of("segment:1"), List.of("现场", "a<b"));
            client.folderAssign("smart:1", "folder:1");
            client.snapshot(null, "New\nlist", null, rules, Set.of());
            assertEquals(5, requests.size());
            assertTrue(requests.stream().allMatch(request -> request.path().equals("/api/library/organization")));
            Element save = parse(requests.get(0).body());
            assertEquals("save", save.getAttribute("action"));
            assertEquals("A \"name\" & more", save.getAttribute("name"));
            Element ruleNode = (Element) save.getElementsByTagName("rules").item(0);
            assertEquals("30", ruleNode.getAttribute("favoriteOlderDays"));
            assertEquals("600", ruleNode.getAttribute("maxDuration"));
            assertEquals("A & B", ((Element) ruleNode.getElementsByTagName("tag").item(1)).getAttribute("value"));
            Element preview = parse(requests.get(1).body());
            assertEquals("segment:1", ((Element) preview.getElementsByTagName("id").item(0)).getAttribute("value"));
            Element tags = parse(requests.get(2).body());
            assertEquals("replace", tags.getAttribute("mode"));
            assertEquals("a<b", ((Element) tags.getElementsByTagName("tag").item(1)).getAttribute("value"));
            Element assignment = parse(requests.get(3).body());
            assertEquals("folder:1", assignment.getAttribute("folderId"));
            Element snapshot = parse(requests.get(4).body());
            assertEquals("New\nlist", snapshot.getAttribute("name"));
            assertNotNull(snapshot.getElementsByTagName("cachedIds").item(0));
        } finally { server.stop(0); }
    }

    @Test void handlesEmptyResultsAndHttpErrors() throws Exception {
        String empty = "<organization version=\"1\"><tracks/><smartPlaylists/><folders/>"
                + "<playlistFolders/><results/></organization>";
        HttpServer server = server(empty, 200, new ArrayList<>());
        try {
            var state = client(server).preview(null, SmartPlaylistClient.Rules.empty(), null);
            assertTrue(state.results().isEmpty());
            assertTrue(state.entries().isEmpty());
        } finally { server.stop(0); }
        HttpServer errorServer = server("<error message=\"bad rules &amp; tags\"/>", 400, new ArrayList<>());
        try {
            assertTrue(assertThrows(IllegalStateException.class, () -> client(errorServer).load())
                    .getMessage().contains("bad rules & tags"));
        } finally { errorServer.stop(0); }
    }

    private static HttpServer server(String responseXml, int status, List<Request> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/library/organization", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = responseXml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }
    private static SmartPlaylistClient client(HttpServer server) {
        return new SmartPlaylistClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
    private static Element parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
    }
    private record Request(String method, String path, String body) { }
}
