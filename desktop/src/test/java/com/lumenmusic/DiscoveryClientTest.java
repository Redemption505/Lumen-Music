package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryClientTest {
    @Test void searchKindAndRecommendationsParsePartIdentity() throws Exception {
        List<String> paths = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            reply(exchange, "<tracks><track id=\"bilibili:BV1xx411c7mD:p2\" title=\"晴天\" "
                    + "artist=\"周杰伦\" durationSeconds=\"240\" source=\"bilibili\" "
                    + "bvid=\"BV1xx411c7mD\" page=\"2\" partCount=\"3\"/></tracks>");
        });
        server.createContext("/api/recommendations", exchange -> reply(exchange,
                "<tracks intents=\"周杰伦 晴天\" tasteSource=\"ai\"><track id=\"demo\" "
                        + "title=\"推荐\" artist=\"周杰伦\" durationSeconds=\"60\" source=\"demo\"/>"
                        + "</tracks>"));
        server.createContext("/api/collections/BV1xx411c7mD/tracks", exchange -> reply(exchange,
                "<tracks><track id=\"bilibili:BV1xx411c7mD:p1\" title=\"第一页\" "
                        + "artist=\"周杰伦\" durationSeconds=\"180\" source=\"bilibili\" "
                        + "bvid=\"BV1xx411c7mD\" page=\"1\" partCount=\"2\"/>"
                        + "<track id=\"bilibili:BV1xx411c7mD:p2\" title=\"第二页\" "
                        + "artist=\"周杰伦\" durationSeconds=\"200\" source=\"bilibili\" "
                        + "bvid=\"BV1xx411c7mD\" page=\"2\" partCount=\"2\"/></tracks>"));
        server.start();
        try {
            MusicClient client = new MusicClient(base(server));
            var tracks = client.search("晴天", "collection");
            assertEquals(3, tracks.get(0).partCount());
            assertEquals(2, tracks.get(0).page());
            assertTrue(paths.get(0).contains("kind=collection"));
            assertTrue(paths.get(0).contains("rerank=true"));
            assertEquals("ai", client.recommendations().tasteSource());
            assertEquals("周杰伦 晴天", client.recommendations().intents());
            var pages = client.resolveCollection("BV1xx411c7mD");
            assertEquals(List.of(1, 2), pages.stream().map(Track::page).toList());
            assertEquals("第二页", pages.get(1).title());
        } finally { server.stop(0); }
    }

    @Test void searchRequestsExplicitRerankingAndDefaultsToEnabled() throws Exception {
        List<String> queries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            reply(exchange, "<tracks/>");
        });
        server.start();
        try {
            MusicClient client = new MusicClient(base(server));
            client.search("song", "all", true);
            client.search("song", "single", false);
            client.search("song");
            client.search("song", "collection");
            client.searchDetailed("song", "all");
            client.searchDetailed("song", "collection", false);
            assertEquals(List.of(
                    "q=song&kind=all&rerank=true",
                    "q=song&kind=single&rerank=false",
                    "q=song&kind=all&rerank=true",
                    "q=song&kind=collection&rerank=true",
                    "q=song&kind=all&rerank=true",
                    "q=song&kind=collection&rerank=false"), queries);
        } finally { server.stop(0); }
    }

    @Test void recommendationHintIsEncodedWithoutChangingDefaultRequest() throws Exception {
        List<String> queries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/recommendations", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            reply(exchange, "<tracks tasteSource=\"popular\"/>");
        });
        server.start();
        try {
            MusicClient client = new MusicClient(base(server));
            client.recommendations("想听点安静的钢琴 & 雨声");
            client.recommendations();
            assertEquals("hint=" + java.net.URLEncoder.encode("想听点安静的钢琴 & 雨声",
                    StandardCharsets.UTF_8), queries.get(0));
            assertEquals("hint=", queries.get(1));
        } finally { server.stop(0); }
    }

    @Test void listeningReporterSendsOrderedSessionSamples() throws Exception {
        List<String> samples = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/listening", exchange -> {
            synchronized (samples) { samples.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)); }
            reply(exchange, "<listening recorded=\"false\"/>");
            latch.countDown();
        });
        server.start();
        try {
            var track = new Track("demo-1", "歌曲 & 试播", "歌手", 100, "demo");
            try (var reporter = new ListeningReporter(base(server))) {
                reporter.start(track, 100, 0);
                reporter.observe(10, true, false);
                reporter.finish(10);
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(3, samples.size());
            String id = null;
            for (String sample : samples) {
                var root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                        new java.io.ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8)))
                        .getDocumentElement();
                assertEquals("歌曲 & 试播", root.getElementsByTagName("track").item(0)
                        .getAttributes().getNamedItem("title").getNodeValue());
                if (id == null) id = root.getAttribute("sessionId");
                else assertEquals(id, root.getAttribute("sessionId"));
            }
            assertEquals("true", DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(samples.get(2).getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement().getAttribute("finished"));
        } finally { server.stop(0); }
    }

    @Test void listeningReporterPreservesSegmentFieldsForLocalService() throws Exception {
        List<String> samples = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/listening", exchange -> {
            synchronized (samples) {
                samples.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            reply(exchange, "<listening recorded=\"false\"/>");
        });
        server.start();
        try {
            Track source = new Track("original", "原曲", "歌手", 60, "bilibili",
                    null, "BV1xx411c7mD", 1);
            Track segment = Track.segment(source, "片段", "歌手", 1_500, 4_000);
            try (var reporter = new ListeningReporter(base(server))) {
                reporter.start(segment, 60, 0);
                reporter.finish(2.5);
            }
            assertEquals(2, samples.size());
            for (String sample : samples) {
                var root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                        new java.io.ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8)))
                        .getDocumentElement();
                var track = (org.w3c.dom.Element) root.getElementsByTagName("track").item(0);
                assertEquals(segment.id(), track.getAttribute("id"));
                assertEquals("3", track.getAttribute("durationSeconds"));
                assertEquals(source.id(), track.getAttribute("sourceTrackId"));
                assertEquals("1500", track.getAttribute("segmentStartMillis"));
                assertEquals("4000", track.getAttribute("segmentEndMillis"));
                assertEquals("60", track.getAttribute("sourceDurationSeconds"));
            }
        } finally { server.stop(0); }
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String xml)
            throws java.io.IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
