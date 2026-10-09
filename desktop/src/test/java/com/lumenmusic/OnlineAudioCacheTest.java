package com.lumenmusic;

import com.lumenmusic.audio.OnlineAudioCache;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OnlineAudioCacheTest {
    @TempDir Path directory;

    @Test void cachedPreparationCopiesPrivatelyAndCancellationOrMissingSourceLeavesNoPartial() throws Exception {
        Path saved = directory.resolve("saved.m4a");
        byte[] audio = new byte[] {1, 2, 3};
        Files.write(saved, audio);
        var client = new MusicClient(URI.create("http://127.0.0.1:1"));
        try (var cache = new OnlineAudioCache(client)) {
            var cancelled = new MusicClient.DownloadCancellation();
            cancelled.cancel();
            assertThrows(java.io.InterruptedIOException.class, () -> cache.prepareCached(saved.toUri(), cancelled));
            Path copy = cache.prepareCached(saved.toUri(), new MusicClient.DownloadCancellation());
            assertNotEquals(saved, copy);
            assertArrayEquals(audio, Files.readAllBytes(copy));
            Path sessionDirectory = copy.getParent();
            Files.delete(copy);
            assertThrows(java.io.IOException.class, () -> cache.prepareCached(directory.resolve("missing.m4a").toUri(),
                    new MusicClient.DownloadCancellation()));
            try (var files = Files.list(sessionDirectory)) { assertEquals(0, files.count()); }
            assertArrayEquals(audio, Files.readAllBytes(saved));
        }
    }

    @Test void segmentStreamsOriginalIdAndSharesSessionFile() throws Exception {
        byte[] audio = {1, 2, 3, 4};
        var gets = new AtomicInteger();
        var paths = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                gets.incrementAndGet();
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) { output.write(audio); }
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            Track original = new Track("original", "song", "artist", 60, "bilibili",
                    null, "BV1xx411c7mD", 1);
            Track first = Track.segment(original, "first", "artist", 0, 10_000);
            Track second = Track.segment(original, "second", "artist", 10_000, 20_000);
            try (var cache = new OnlineAudioCache(client)) {
                URI uri = cache.resolve(first, new MusicClient.DownloadCancellation());
                assertEquals(uri, cache.resolve(second, new MusicClient.DownloadCancellation()));
                assertArrayEquals(audio, Files.readAllBytes(Path.of(uri)));
            }
            assertEquals(1, gets.get());
            assertTrue(paths.stream().allMatch("/api/tracks/original/stream"::equals));
        } finally { server.stop(0); }
    }

    @Test void explainsUnavailableAudioBeforeTryingToPlayXml() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            exchange.getResponseHeaders().set("X-Lumen-Error-Code", "AUDIO_FORMAT_UNSUPPORTED");
            exchange.sendResponseHeaders(422, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var error = assertThrows(MusicClient.StreamException.class, () -> client.checkStream(track));
            assertEquals("AUDIO_FORMAT_UNSUPPORTED", error.code());
            assertEquals("该视频没有兼容的音频轨", error.getMessage());
            assertTrue(PlaybackFailure.from(error).permanent());
            assertFalse(PlaybackFailure.from(error).retryable());
        } finally { server.stop(0); }
    }

    @Test void downloadsOnceAndRemovesSessionFile() throws Exception {
        byte[] audio = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        var gets = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                gets.incrementAndGet();
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) { output.write(audio); }
            }
            exchange.close();
        });
        server.start();
        Path file;
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var cache = new OnlineAudioCache(client);
            var cancellation = new MusicClient.DownloadCancellation();
            try {
                file = Path.of(cache.resolve(track, cancellation));
                assertArrayEquals(audio, Files.readAllBytes(file));
                assertEquals(file.toUri(), cache.resolve(track, cancellation));
                assertEquals(1, gets.get());
            } finally { cache.close(); }
            assertFalse(Files.exists(file));
        } finally { server.stop(0); }
    }

    @Test void enablingDiskCachePromotesReadySessionWithoutAnotherNetworkRequest() throws Exception {
        byte[] audio = new byte[] {1, 2, 3, 4};
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) { output.write(audio); }
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var persistent = new com.lumenmusic.audio.PersistentAudioCache(directory);
            try (var session = new OnlineAudioCache(client, persistent)) {
                URI first = session.resolve(track, new MusicClient.DownloadCancellation());
                assertEquals(0, persistent.entryCount());
                int completedRequests = requests.get();
                server.stop(0);
                persistent.configure(true, 100);
                assertEquals(first, session.resolve(track, new MusicClient.DownloadCancellation()));
                assertEquals(completedRequests, requests.get());
                assertArrayEquals(audio, Files.readAllBytes(Path.of(persistent.lookup(track).orElseThrow())));
            }
        } finally { server.stop(0); }
    }

    @Test void cancelledDownloadDoesNotEnterCacheAndCanBeRetried() throws Exception {
        byte[] audio = new byte[8192];
        var gets = new AtomicInteger();
        var firstChunkSent = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var serverWorkers = Executors.newCachedThreadPool();
        var downloads = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/api/tracks/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                int request = gets.incrementAndGet();
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) {
                    if (request == 1) {
                        output.write(audio, 0, 1024);
                        output.flush();
                        firstChunkSent.countDown();
                        try { releaseFirst.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    }
                    output.write(audio, request == 1 ? 1024 : 0,
                            request == 1 ? audio.length - 1024 : audio.length);
                } catch (java.io.IOException ignored) { // Client closed the cancelled response.
                }
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var cache = new OnlineAudioCache(client);
            try {
                var cancellation = new MusicClient.DownloadCancellation();
                var first = downloads.submit(() -> cache.resolve(track, cancellation));
                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS));
                cancellation.cancel();
                releaseFirst.countDown();
                assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));

                Path file = Path.of(cache.resolve(track, new MusicClient.DownloadCancellation()));
                assertArrayEquals(audio, Files.readAllBytes(file));
                assertEquals(2, gets.get(), "Cancelled download must not be reused");
                try (var files = Files.list(file.getParent())) {
                    assertEquals(1, files.count(), "Cancelled partial file must be removed");
                }
            } finally { cache.close(); }
        } finally {
            releaseFirst.countDown();
            server.stop(0);
            downloads.shutdownNow();
            serverWorkers.shutdownNow();
        }
    }

    @Test void persistentHitSurvivesRestartWithoutAnotherDownload() throws Exception {
        byte[] audio = new byte[] {1, 2, 3, 4};
        var gets = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                gets.incrementAndGet();
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) { output.write(audio); }
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var persistent = new com.lumenmusic.audio.PersistentAudioCache(directory);
            persistent.configure(true, 100);
            Path first;
            try (var session = new OnlineAudioCache(client, persistent)) {
                first = Path.of(session.resolve(track, new MusicClient.DownloadCancellation()));
                assertArrayEquals(audio, Files.readAllBytes(first));
            }
            assertFalse(Files.exists(first));
            assertEquals(1, gets.get());
            var restarted = new com.lumenmusic.audio.PersistentAudioCache(directory);
            try (var session = new OnlineAudioCache(client, restarted)) {
                Path second = Path.of(session.resolve(track, new MusicClient.DownloadCancellation()));
                assertArrayEquals(audio, Files.readAllBytes(second));
                assertNotEquals(first, second);
                restarted.clear();
                assertArrayEquals(audio, Files.readAllBytes(second),
                        "A playing session file must survive persistent cache clearing");
            }
            assertEquals(1, gets.get());
        } finally { server.stop(0); }
    }

    @Test void audioLargerThanPersistentLimitStillPlaysFromSession() throws Exception {
        byte[] audio = new byte[] {1, 2, 3, 4};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) { output.write(audio); }
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var persistent = new com.lumenmusic.audio.PersistentAudioCache(directory);
            persistent.configure(true, 2);
            try (var session = new OnlineAudioCache(client, persistent)) {
                assertArrayEquals(audio, Files.readAllBytes(Path.of(
                        session.resolve(track, new MusicClient.DownloadCancellation()))));
            }
            assertEquals(0, persistent.entryCount());
        } finally { server.stop(0); }
    }

    @Test void disablingPersistentCacheDuringDownloadDoesNotBlockSessionPlayback() throws Exception {
        byte[] audio = new byte[8192];
        var downloadStarted = new CountDownLatch(1);
        var releaseDownload = new CountDownLatch(1);
        var serverWorkers = Executors.newCachedThreadPool();
        var downloads = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/api/tracks/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(audio.length));
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                exchange.sendResponseHeaders(200, audio.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(audio, 0, 1024);
                    output.flush();
                    downloadStarted.countDown();
                    try { releaseDownload.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    output.write(audio, 1024, audio.length - 1024);
                } catch (java.io.IOException ignored) { }
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var track = new Track("bilibili:BV1nhMuzaEJr:p1", "test", "test", 8,
                    "bilibili", null, "BV1nhMuzaEJr", 1);
            var persistent = new com.lumenmusic.audio.PersistentAudioCache(directory);
            persistent.configure(true, 100_000);
            try (var session = new OnlineAudioCache(client, persistent)) {
                var pending = downloads.submit(() -> session.resolve(track, new MusicClient.DownloadCancellation()));
                assertTrue(downloadStarted.await(5, TimeUnit.SECONDS));
                persistent.configure(false, 100_000);
                releaseDownload.countDown();
                assertArrayEquals(audio, Files.readAllBytes(Path.of(pending.get(5, TimeUnit.SECONDS))));
                assertEquals(0, persistent.entryCount());
                assertTrue(persistent.storeIfEnabled(track, new MusicClient.DownloadCancellation(),
                        (ignored, target, token) -> fail("Disabled cache must not start a write")).isEmpty());
            }
        } finally {
            releaseDownload.countDown();
            server.stop(0);
            downloads.shutdownNow();
            serverWorkers.shutdownNow();
        }
    }
}
