package com.lumenmusic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class DiaryClientTest {
    private static final String TRACK = "<track id=\"video:1\" title=\"一首 &amp; 歌\" "
            + "artist=\"歌手\" durationSeconds=\"90\" source=\"bilibili\"/>";
    private static final String DIARY = "<diary zone=\"Asia/Shanghai\" generatedAt=\"2026-10-06T10:00:00Z\">"
            + "<summary totalPlays=\"40\" thisMonthPlays=\"3\" sourceCount=\"1\" "
            + "trackCount=\"1\" songCount=\"0\" unknownFirstCount=\"2\"/>"
            + "<section id=\"weekly-first\" title=\"上周第一次听到\" count=\"1\">"
            + "<entry playedAt=\"2026-10-01T10:00:00Z\" firstPlayedAt=\"2026-10-01T10:00:00Z\">"
            + TRACK + "</entry></section>"
            + "<section id=\"reheard\" title=\"重听\" count=\"0\"/>"
            + "<section id=\"monthly-playlists\" title=\"本月常听歌单\" count=\"1\">"
            + "<entry playlistId=\"list / 1\" playlistName=\"夜晚\" playCount=\"3\">"
            + TRACK + "</entry></section>"
            + "<section id=\"recent\" title=\"最近听过\" count=\"1\">"
            + "<entry legacy=\"true\" playedAt=\"2026-09-01T10:00:00Z\">" + TRACK
            + "</entry></section>"
            + "<notes><note trackId=\"video:1\"><text>私人 &amp; 备注</text>" + TRACK + "</note></notes>"
            + "<lyricFavorites><lyric id=\"lyric / 1\" trackId=\"video:1\" "
            + "positionMillis=\"65000\" createdAt=\"2026-10-06T09:00:00Z\">"
            + "<text>一句歌词</text>" + TRACK + "</lyric></lyricFavorites></diary>";

    @Test void readsSectionsRealCountsNotesAndLyrics() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(requests, DIARY);
        try {
            DiaryClient.Diary diary = client(server).load();
            assertEquals(40, diary.summary().totalPlays());
            assertEquals(2, diary.summary().unknownFirstCount());
            assertEquals("一首 & 歌", diary.section("weekly-first").entries().get(0).track().title());
            assertEquals("list / 1", diary.section("monthly-playlists").entries().get(0).playlistId());
            assertEquals("私人 & 备注", diary.notes().get(0).text());
            assertEquals(65000, diary.lyricFavorites().get(0).positionMillis());
            assertEquals("一句歌词", diary.lyricFavorites().get(0).text());
            assertTrue(diary.section("recent").entries().get(0).legacy());
            assertEquals("GET", requests.get(0).method());
            assertEquals("/api/diary", requests.get(0).path());
            assertEquals(ZoneId.systemDefault().getId(), zone(requests.get(0)));
            assertEquals("", requests.get(0).body());
        } finally { server.stop(0); }
    }

    @Test void editsSendTrackSnapshotAndEscapedTextAndEncodeDeleteId() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(requests, DIARY);
        try {
            DiaryClient client = client(server);
            Track track = new Track("video:1", "一首 & 歌", "歌手", 90, "bilibili");
            client.saveNote(track, "私密 <内容>\n下一行");
            client.saveNote(track, "");
            client.saveLyric(track, 65000, "一句 & 歌词");
            client.deleteLyric("lyric / 1");
            assertEquals(List.of("POST /api/diary/notes", "POST /api/diary/notes",
                    "POST /api/diary/lyrics", "DELETE /api/diary/lyrics/lyric%20%2F%201"),
                    requests.stream().map(r -> r.method() + " " + r.path()).toList());
            assertTrue(requests.stream().allMatch(r -> ZoneId.systemDefault().getId().equals(zone(r))));
            Element note = parse(requests.get(0).body());
            assertEquals("video:1", note.getAttribute("trackId"));
            assertEquals("私密 <内容>\n下一行", ((Element) note.getElementsByTagName("text").item(0)).getTextContent());
            assertEquals("一首 & 歌", ((Element) note.getElementsByTagName("track").item(0))
                    .getAttribute("title"));
            assertEquals("", ((Element) parse(requests.get(1).body())
                    .getElementsByTagName("text").item(0)).getTextContent());
            Element lyric = parse(requests.get(2).body());
            assertEquals("65000", lyric.getAttribute("positionMillis"));
            assertEquals("一句 & 歌词", ((Element) lyric.getElementsByTagName("text").item(0))
                    .getTextContent());
        } finally { server.stop(0); }
    }

    @Test void preservesEmptySectionsAndRejectsInvalidXml() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer empty = server(requests, "<diary><summary/><section id=\"recent\" count=\"0\"/>"
                + "<notes/><lyricFavorites/></diary>");
        try {
            DiaryClient.Diary diary = client(empty).load();
            assertTrue(diary.section("recent").entries().isEmpty());
            assertTrue(diary.notes().isEmpty());
        } finally { empty.stop(0); }
        HttpServer invalid = server(new ArrayList<>(), "<!DOCTYPE diary [<!ENTITY x SYSTEM "
                + "\"file:///C:/private.txt\">]><diary>&x;</diary>");
        try { assertThrows(Exception.class, () -> client(invalid).load()); }
        finally { invalid.stop(0); }
    }

    @Test void exportsOnlySnapshotFacts() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(requests, DIARY);
        try {
            String markdown = DiaryPane.exportText(client(server).load(), true);
            assertTrue(markdown.contains("本月有效播放：3 次；详细记录累计音源：1；曲目：1；歌曲：0"));
            assertTrue(markdown.contains("夜晚 · 3 次"));
            assertTrue(markdown.contains("私人 & 备注"));
            assertTrue(markdown.contains("一首 & 歌 @ 1:05：一句歌词"));
            assertTrue(markdown.contains("旧记录·最后收听"));
            assertFalse(markdown.contains("0 首新歌"));
        } finally { server.stop(0); }
    }

    private static HttpServer server(List<Request> requests, String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/diary", exchange -> reply(exchange, requests, response));
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, List<Request> requests, String response)
            throws java.io.IOException {
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static DiaryClient client(HttpServer server) {
        return new DiaryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static Element parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private static String zone(Request request) {
        assertTrue(request.query().startsWith("zone="));
        return URLDecoder.decode(request.query().substring(5), StandardCharsets.UTF_8);
    }

    private record Request(String method, String path, String query, String body) { }
}
