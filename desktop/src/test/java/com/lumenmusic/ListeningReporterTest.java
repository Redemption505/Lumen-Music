package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ListeningReporterTest {
    private static Track track() {
        return new Track("local-test", "真实曲目", "歌手", 40, "local", URI.create("file:///C:/music.wav"));
    }

    @Test void sendsPlaylistSnapshotAndNotifiesOnlyOncePerSession() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger events = new AtomicInteger();
        CountDownLatch notified = new CountDownLatch(1);
        server.createContext("/api/listening", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "<listening justRecorded=\"true\" revisitOldFavorite=\"true\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (ListeningReporter reporter = new ListeningReporter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))) {
            reporter.setOnQualified(play -> {
                assertEquals(track().id(), play.track().id());
                assertTrue(play.revisitedOldFavorite());
                events.incrementAndGet();
                notified.countDown();
            });
            assertTrue(reporter.start(track(), 40, 0, new ListeningReporter.Context("p1", "钢琴 & 雨\"声"), 1.25));
            reporter.observe(31, true, false, 1.5);
            reporter.finish(31);
            assertTrue(notified.await(3, TimeUnit.SECONDS));
        } finally { server.stop(0); }
        assertEquals(3, requests.size());
        assertEquals(1, events.get());
        var factory = DocumentBuilderFactory.newInstance();
        for (String body : requests) {
            var sample = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
            assertEquals("p1", sample.getAttribute("playlistId"));
            assertEquals("钢琴 & 雨\"声", sample.getAttribute("playlistName"));
        }
        assertTrue(requests.get(0).contains("playbackRate=\"1.25\""));
        assertTrue(requests.get(1).contains("playbackRate=\"1.5\""));
        assertTrue(requests.get(2).contains("playbackRate=\"1.5\""));
        double previousElapsed = -1;
        for (String body : requests) {
            var sample = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
            double elapsed = Double.parseDouble(sample.getAttribute("elapsedSeconds"));
            assertTrue(Double.isFinite(elapsed) && elapsed >= previousElapsed);
            previousElapsed = elapsed;
        }
    }

    @Test void queuedSampleTimestampIsCapturedBeforeWriterDelay() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/listening", exchange -> {
            received.add(System.nanoTime());
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (requests.size() == 1) {
                firstRequest.countDown();
                try { assertTrue(releaseResponse.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            byte[] response = "<listening/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        long started = System.nanoTime();
        try (ListeningReporter reporter = new ListeningReporter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))) {
            started = System.nanoTime();
            reporter.start(track(), 40, 0, null, 1.5);
            assertTrue(firstRequest.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            reporter.observe(0.15, true, false, 1.5);
            reporter.finish(0.15);
            Thread.sleep(250);
            releaseResponse.countDown();
        } finally { releaseResponse.countDown(); server.stop(0); }
        assertEquals(3, requests.size());
        var sample = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(requests.get(1).getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        double elapsed = Double.parseDouble(sample.getAttribute("elapsedSeconds"));
        assertTrue((received.get(1) - started) / 1e9 - elapsed > 0.15,
                "Writer queue delay must not become the playback observation timestamp");
    }

    @Test void switchingTrackFinishesAtItsLastPositionAndResetsDefaultRate() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/api/listening", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "<listening/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (ListeningReporter reporter = new ListeningReporter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))) {
            reporter.start(track(), 40, 0, null, 0.75);
            reporter.observe(15, true, false);
            reporter.start(new Track("next", "下一首", "", 180, "local", track().mediaUri()), 180, 100);
            reporter.finish(100);
        } finally { server.stop(0); }
        assertEquals(5, requests.size());
        var factory = DocumentBuilderFactory.newInstance();
        var previous = factory.newDocumentBuilder().parse(new ByteArrayInputStream(requests.get(2).getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        var next = factory.newDocumentBuilder().parse(new ByteArrayInputStream(requests.get(3).getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        assertEquals("15.0", previous.getAttribute("positionSeconds"));
        assertEquals("true", previous.getAttribute("finished"));
        assertEquals("0.75", previous.getAttribute("playbackRate"));
        assertEquals("1.0", next.getAttribute("playbackRate"));
        assertNotEquals(previous.getAttribute("sessionId"), next.getAttribute("sessionId"));
    }

    @Test void unknownDurationAndNonLocalEndpointDoNotStartHistorySession() {
        try (ListeningReporter reporter = new ListeningReporter(URI.create("https://example.invalid"))) {
            assertFalse(reporter.start(track(), 40, 0));
        }
        Track unknown = new Track("unknown", "未知时长", "", 0, "local", URI.create("file:///C:/unknown.wav"));
        try (ListeningReporter reporter = new ListeningReporter(URI.create("http://127.0.0.1:1"))) {
            assertFalse(reporter.start(unknown, 0, 0));
        }
    }
}
