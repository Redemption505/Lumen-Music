package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import com.lumenmusic.audio.NextTrackPreloader;
import com.lumenmusic.audio.OnlineAudioCache;
import com.lumenmusic.audio.PersistentAudioCache;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NextTrackPreloaderTest {
    @TempDir Path directory;
    private static final Track TRACK = track("original");
    private static Track track(String id) {
        return new Track(id, "Song", "UP", 60, "bilibili", null, "BV1nhMuzaEJr", 1);
    }

    private static final class Source implements AutoCloseable {
        final byte[] audio = new byte[8192];
        final AtomicInteger heads = new AtomicInteger();
        final AtomicInteger gets = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        volatile boolean delayHead;
        volatile boolean delayDownload;
        volatile String failure;
        Source() throws IOException {
            server.setExecutor(workers);
            server.createContext("/api/tracks/", exchange -> {
                boolean head = exchange.getRequestMethod().equals("HEAD");
                int count = head ? heads.incrementAndGet() : gets.incrementAndGet();
                if (head && delayHead && count == 1) {
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                try {
                    if (failure != null) {
                        exchange.getResponseHeaders().set("X-Lumen-Error-Code", failure);
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(audio.length));
                    exchange.sendResponseHeaders(200, head ? -1 : audio.length);
                    if (!head) try (var output = exchange.getResponseBody()) {
                        if (delayDownload && count == 1) {
                            output.write(audio, 0, 1024);
                            output.flush();
                            entered.countDown();
                            try { release.await(5, TimeUnit.SECONDS); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                            output.write(audio, 1024, audio.length - 1024);
                        } else output.write(audio);
                    }
                } catch (IOException ignored) { }
                finally { exchange.close(); }
            });
            server.start();
        }
        MusicClient client() { return new MusicClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort())); }
        public void close() { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }

    private static void awaitReady(NextTrackPreloader preload, Track track, boolean streaming) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!preload.isReady(track, streaming) && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(preload.isReady(track, streaming), "Expected bounded preparation to complete");
    }

    private static URI preparedUri(NextTrackPreloader preload) throws Exception {
        var field = NextTrackPreloader.class.getDeclaredField("current");
        field.setAccessible(true);
        Object preparation = field.get(preload);
        var ready = preparation.getClass().getDeclaredField("ready");
        ready.setAccessible(true);
        return (URI) ready.get(preparation);
    }

    @Test void streamingWithoutDiskCacheOnlyWarmsHeadAndSharesSegmentIdentity() throws Exception {
        try (var source = new Source(); var session = new OnlineAudioCache(source.client());
             var preload = new NextTrackPreloader(source.client(), session, null)) {
            Track first = Track.segment(TRACK, "first", "UP", 0, 10_000);
            Track second = Track.segment(TRACK, "second", "UP", 10_000, 20_000);
            preload.request(first, true);
            awaitReady(preload, first, true);
            for (int index = 0; index < 20; index++) preload.request(second, true);
            assertTrue(preload.isReady(second, true));
            assertEquals("http", preload.take(second, true).getScheme());
            assertEquals(1, source.heads.get());
            assertEquals(0, source.gets.get());
            assertNull(preload.take(first, true));
        }
    }

    @Test void completeSpeculationPersistsOnlyAfterActualUseAndAdoptedFileSurvivesCancel() throws Exception {
        try (var source = new Source()) {
            var persistent = new PersistentAudioCache(directory);
            persistent.configure(true, 100_000);
            try (var session = new OnlineAudioCache(source.client(), persistent);
                 var preload = new NextTrackPreloader(source.client(), session, persistent)) {
                preload.request(TRACK, true);
                awaitReady(preload, TRACK, true);
                assertEquals(0, persistent.entryCount());
                URI uri = preload.take(TRACK, true);
                assertArrayEquals(source.audio, Files.readAllBytes(Path.of(uri)));
                preload.cancel();
                assertTrue(Files.exists(Path.of(uri)));
                assertEquals(0, persistent.entryCount());
                session.retainPrepared(TRACK, uri, new MusicClient.DownloadCancellation());
                assertEquals(1, persistent.entryCount());
                assertEquals(1, source.gets.get());
                Track segment = Track.segment(TRACK, "second", "UP", 10_000, 20_000);
                preload.request(segment, false);
                awaitReady(preload, segment, false);
                assertNotNull(preload.take(segment, false));
                assertEquals(1, source.gets.get());
            }
        }
    }

    @Test void javaFxUnplayedFileIsDeletedOnReplacementAndClose() throws Exception {
        try (var source = new Source(); var session = new OnlineAudioCache(source.client())) {
            var preload = new NextTrackPreloader(source.client(), session, null);
            preload.request(TRACK, false);
            awaitReady(preload, TRACK, false);
            Path first = Path.of(preparedUri(preload));
            preload.request(track("replacement"), false);
            assertFalse(Files.exists(first));
            awaitReady(preload, track("replacement"), false);
            Path second = Path.of(preparedUri(preload));
            try (var files = Files.list(second.getParent())) { assertEquals(1, files.count()); }
            preload.close();
            assertFalse(Files.exists(second));
        }
    }

    @Test void persistentPreparationDoesNotTouchLruAndClearInvalidatesReady() throws Exception {
        var persistent = new PersistentAudioCache(directory);
        persistent.configure(true, 100);
        URI stored = persistent.store(TRACK, new MusicClient.DownloadCancellation(),
                (track, target, token) -> Files.write(target, new byte[] { 1, 2, 3 }));
        var modified = Files.getLastModifiedTime(Path.of(stored));
        var client = new MusicClient(URI.create("http://127.0.0.1:1"));
        try (var session = new OnlineAudioCache(client, persistent);
             var preload = new NextTrackPreloader(client, session, persistent)) {
            preload.request(TRACK, true);
            awaitReady(preload, TRACK, true);
            assertEquals(modified, Files.getLastModifiedTime(Path.of(stored)));
            preload.cancel();
            assertTrue(Files.exists(Path.of(stored)), "Borrowed persistent files are never deleted by cancellation");
            preload.request(TRACK, true);
            awaitReady(preload, TRACK, true);
            persistent.clear();
            assertNull(preload.take(TRACK, true));
        }
    }

    @Test void settingsChangeDiscardsSpeculationAndTinyCapacityPreventsGet() throws Exception {
        try (var source = new Source()) {
            var persistent = new PersistentAudioCache(directory);
            persistent.configure(true, 100_000);
            try (var session = new OnlineAudioCache(source.client(), persistent);
                 var preload = new NextTrackPreloader(source.client(), session, persistent)) {
                preload.request(TRACK, true);
                awaitReady(preload, TRACK, true);
                Path file = Path.of(preparedUri(preload));
                persistent.configure(false, 100_000);
                assertNull(preload.take(TRACK, true));
                assertFalse(Files.exists(file));
                persistent.configure(true, 2);
                preload.request(TRACK, true);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (source.heads.get() < 2 && System.nanoTime() < end) Thread.sleep(10);
                assertEquals(2, source.heads.get());
                assertNull(preload.take(TRACK, true));
                assertEquals(1, source.gets.get(), "Capacity refusal must happen before speculative GET");
                assertEquals(0, persistent.entryCount());
            }
        }
    }

    @Test void obsoleteDelayedHeadCannotReplaceNewTarget() throws Exception {
        try (var source = new Source(); var session = new OnlineAudioCache(source.client());
             var preload = new NextTrackPreloader(source.client(), session, null)) {
            source.delayHead = true;
            preload.request(TRACK, true);
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            Track replacement = track("replacement");
            preload.request(replacement, true);
            awaitReady(preload, replacement, true);
            source.release.countDown();
            assertFalse(preload.isReady(TRACK, true));
            assertTrue(preload.take(replacement, true).getPath().contains("replacement"));
            assertEquals(2, source.heads.get());
        }
    }

    @Test void foregroundTakeCancelsUnfinishedDownloadAndPartialIsNotReused() throws Exception {
        try (var source = new Source(); var session = new OnlineAudioCache(source.client());
             var preload = new NextTrackPreloader(source.client(), session, null)) {
            source.delayDownload = true;
            preload.request(TRACK, false);
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            assertNull(preload.take(TRACK, false));
            source.release.countDown();
            URI uri = session.resolve(TRACK, new MusicClient.DownloadCancellation());
            assertArrayEquals(source.audio, Files.readAllBytes(Path.of(uri)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long count;
            do {
                try (var files = Files.list(Path.of(uri).getParent())) { count = files.count(); }
                if (count > 1) Thread.sleep(10);
            } while (count > 1 && System.nanoTime() < end);
            assertEquals(1, count);
            assertEquals(2, source.gets.get());
        }
    }

    @Test void permanentFailureRemainsAttemptedAndLocalSourcesAreSkipped() throws Exception {
        try (var source = new Source(); var session = new OnlineAudioCache(source.client());
             var preload = new NextTrackPreloader(source.client(), session, null)) {
            source.failure = "VIDEO_NOT_FOUND";
            preload.request(TRACK, true);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (source.heads.get() == 0 && System.nanoTime() < end) Thread.sleep(10);
            for (int index = 0; index < 20; index++) preload.request(TRACK, true);
            assertEquals(1, source.heads.get());
            assertFalse(preload.isReady(TRACK, true));
            preload.request(new Track("local", "local", "UP", 10, "local", directory.toUri()), false);
            assertNull(preload.take(TRACK, true));
            assertEquals(0, source.gets.get());
        }
    }

    @Test void expiredTruncatedOrRemovedPreparedFilesAreNotHandedToPlayer() throws Exception {
        try (var source = new Source(); var session = new OnlineAudioCache(source.client());
             var preload = new NextTrackPreloader(source.client(), session, null)) {
            for (int scenario = 0; scenario < 3; scenario++) {
                preload.request(TRACK, false);
                awaitReady(preload, TRACK, false);
                Path file = Path.of(preparedUri(preload));
                if (scenario == 0) {
                    var field = NextTrackPreloader.class.getDeclaredField("current");
                    field.setAccessible(true);
                    Object preparation = field.get(preload);
                    var readyAt = preparation.getClass().getDeclaredField("readyAt");
                    readyAt.setAccessible(true);
                    readyAt.setLong(preparation, System.nanoTime() - TimeUnit.MINUTES.toNanos(3));
                } else if (scenario == 1) Files.write(file, new byte[] {1});
                else Files.delete(file);
                assertNull(preload.take(TRACK, false));
                assertFalse(Files.exists(file));
            }
        }
    }

    @Test void javaFxPersistentHitUsesOwnedCopyAndClearingCachePreservesPlayingSession() throws Exception {
        var persistent = new PersistentAudioCache(directory);
        persistent.configure(true, 100);
        byte[] audio = new byte[] {1, 2, 3};
        URI stored = persistent.store(TRACK, new MusicClient.DownloadCancellation(),
                (track, target, token) -> Files.write(target, audio));
        var modified = Files.getLastModifiedTime(Path.of(stored));
        var client = new MusicClient(URI.create("http://127.0.0.1:1"));
        try (var session = new OnlineAudioCache(client, persistent);
             var preload = new NextTrackPreloader(client, session, persistent)) {
            preload.request(TRACK, false);
            awaitReady(preload, TRACK, false);
            Path abandoned = Path.of(preparedUri(preload));
            assertNotEquals(Path.of(stored), abandoned);
            assertEquals(modified, Files.getLastModifiedTime(Path.of(stored)));
            preload.cancel();
            assertFalse(Files.exists(abandoned));
            assertTrue(Files.exists(Path.of(stored)));
            assertEquals(1, persistent.entryCount());

            preload.request(TRACK, false);
            awaitReady(preload, TRACK, false);
            URI playing = preload.take(TRACK, false);
            assertNotEquals(stored, playing);
            assertEquals(playing, session.lookup(TRACK));
            Track segment = Track.segment(TRACK, "second", "UP", 10_000, 20_000);
            preload.request(segment, false);
            awaitReady(preload, segment, false);
            assertEquals(playing, preload.take(segment, false), "Reuse the adopted source without a second copy");
            persistent.clear();
            assertEquals(0, persistent.entryCount());
            assertFalse(Files.exists(Path.of(stored)));
            assertArrayEquals(audio, Files.readAllBytes(Path.of(playing)));
        }
    }
}
