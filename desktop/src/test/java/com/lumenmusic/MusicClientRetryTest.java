package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class MusicClientRetryTest {
    @TempDir Path directory;
    private static final Track TRACK = new Track("bilibili:BV1nhMuzaEJr:p1", "Song", "UP", 10,
            "bilibili", null, "BV1nhMuzaEJr", 1);

    @Test void temporaryHeadFailureRetriesButPermanentFailureDoesNot() throws Exception {
        for (String code : new String[] { "UPSTREAM_TIMEOUT", "VIDEO_NOT_FOUND" }) {
            var calls = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/tracks/", exchange -> {
                int count = calls.incrementAndGet();
                if (count == 1) {
                    exchange.getResponseHeaders().set("X-Lumen-Error-Code", code);
                    exchange.sendResponseHeaders(code.equals("VIDEO_NOT_FOUND") ? 404 : 504, -1);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
                    exchange.getResponseHeaders().set("Content-Length", "100");
                    exchange.sendResponseHeaders(200, -1);
                }
                exchange.close();
            });
            server.start();
            String previous = System.getProperty("lumen.stream.base");
            try {
                System.clearProperty("lumen.stream.base");
                var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
                if (code.equals("VIDEO_NOT_FOUND")) {
                    var failure = assertThrows(MusicClient.StreamException.class, () -> client.checkStream(TRACK));
                    assertEquals(code, failure.code());
                    assertTrue(PlaybackFailure.from(failure).permanent());
                    assertEquals(1, calls.get());
                } else {
                    assertNotNull(client.checkStream(TRACK));
                    assertEquals(2, calls.get());
                }
            } finally {
                server.stop(0);
                if (previous == null) System.clearProperty("lumen.stream.base");
                else System.setProperty("lumen.stream.base", previous);
            }
        }
    }

    @Test void alreadyCancelledRequestDoesNotConnect() {
        var cancellation = new MusicClient.DownloadCancellation();
        cancellation.cancel();
        var client = new MusicClient(URI.create("http://127.0.0.1:1"));
        assertThrows(java.io.InterruptedIOException.class, () -> client.checkStream(TRACK, cancellation));
        assertFalse(PlaybackFailure.forCode("UPSTREAM_TLS_ERROR").retryable());
        assertTrue(PlaybackFailure.forCode("UPSTREAM_TLS_ERROR").displayMessage().contains("证书"));
        assertTrue(PlaybackFailure.forCode("UPSTREAM_CONNECTION_LOST").retryable());
        assertFalse(PlaybackFailure.forCode("UPSTREAM_CONNECTION_LOST").permanent());
    }

    @Test void metadataFailureRetainsPermanentCodeAndExplanation() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/collections/", exchange -> {
            byte[] body = "<error code=\"VIDEO_NOT_FOUND\" message=\"deleted fixture\"/>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var failure = assertThrows(MusicClient.StreamException.class,
                    () -> client.resolveCollection(TRACK.bvid()));
            assertEquals("VIDEO_NOT_FOUND", failure.code());
            assertTrue(failure.getMessage().contains("deleted fixture"));
            assertTrue(PlaybackFailure.from(failure).permanent());
            assertFalse(PlaybackFailure.from(failure).retryable());
        } finally { server.stop(0); }
    }

    @Test void tokenCancellationInterruptsDelayedHeadWithoutWaitingForServer() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newCachedThreadPool();
        var clientWorker = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/api/tracks/", exchange -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var cancellation = new MusicClient.DownloadCancellation();
            Future<URI> pending = clientWorker.submit(() -> client.checkStream(TRACK, cancellation));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            cancellation.cancel();
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.InterruptedIOException.class, failure.getCause());
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
            clientWorker.shutdownNow();
        }
    }

    @Test @Timeout(10) void truncatedDownloadRetriesOnceCleansPartialAndReportsProgress() throws Exception {
        var gets = new AtomicInteger();
        var server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5000);
        var worker = Executors.newSingleThreadExecutor();
        byte[] audio = new byte[] {1, 2, 3, 4};
        Future<?> peer = worker.submit(() -> {
            for (int request = 0; request < 4; request++) try (var socket = server.accept()) {
                socket.setSoTimeout(5000);
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                boolean head = input.readLine().startsWith("HEAD ");
                for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
                var output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\n"
                        + "Content-Length: 4\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (!head) output.write(audio, 0, gets.incrementAndGet() == 1 ? 1 : 4);
                output.flush();
            }
            return null;
        });
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getLocalPort()));
            var events = new ArrayList<MusicClient.PreparationStage>();
            Path file = directory.resolve("audio.m4a");
            client.downloadStream(TRACK, file, new MusicClient.DownloadCancellation(),
                    (stage, attempt) -> events.add(stage));
            assertArrayEquals(audio, Files.readAllBytes(file));
            assertEquals(2, gets.get());
            assertEquals(1, events.stream().filter(stage -> stage == MusicClient.PreparationStage.RETRYING).count());
            assertEquals(2, events.stream().filter(stage -> stage == MusicClient.PreparationStage.DOWNLOADING).count());
            peer.get(2, TimeUnit.SECONDS);
        } finally { server.close(); worker.shutdownNow(); }
    }

    @Test void headAndGetShareOneRetryBudgetAndNeverLeaveFailureFile() throws Exception {
        var heads = new AtomicInteger();
        var gets = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            if (exchange.getRequestMethod().equals("HEAD") && heads.incrementAndGet() > 1) {
                exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
                exchange.getResponseHeaders().set("Content-Length", "4");
                exchange.sendResponseHeaders(200, -1);
            } else {
                if (exchange.getRequestMethod().equals("GET")) gets.incrementAndGet();
                exchange.getResponseHeaders().set("X-Lumen-Error-Code", "UPSTREAM_UNAVAILABLE");
                exchange.sendResponseHeaders(503, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            Path file = directory.resolve("audio.m4a");
            Files.write(file, new byte[] {9});
            assertThrows(MusicClient.StreamException.class,
                    () -> client.downloadStream(TRACK, file, new MusicClient.DownloadCancellation()));
            assertEquals(2, heads.get());
            assertEquals(1, gets.get());
            assertFalse(Files.exists(file));
        } finally { server.stop(0); }
    }
}
