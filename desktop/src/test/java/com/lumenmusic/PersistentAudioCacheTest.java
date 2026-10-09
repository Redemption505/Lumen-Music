package com.lumenmusic;

import com.lumenmusic.audio.PersistentAudioCache;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistentAudioCacheTest {
    @TempDir Path directory;

    @Test void sourceAndSegmentsShareOneCompleteAudioEntry() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        Track original = new Track("original", "song", "artist", 60, "bilibili",
                null, "BV1xx411c7mD", 1);
        Track first = Track.segment(original, "first", "artist", 0, 10_000);
        Track second = Track.segment(original, "second", "artist", 10_000, 20_000);
        var downloaded = new java.util.concurrent.atomic.AtomicInteger();
        URI saved = cache.store(first, new MusicClient.DownloadCancellation(), (track, target, token) -> {
            assertEquals(original.id(), track.id());
            downloaded.incrementAndGet();
            Files.writeString(target, "audio");
        });
        assertEquals(saved, cache.lookup(original).orElseThrow());
        assertEquals(saved, cache.lookup(second).orElseThrow());
        assertEquals(saved, cache.store(second, new MusicClient.DownloadCancellation(),
                (track, target, token) -> fail("Shared source must not download again")));
        assertEquals(1, cache.entryCount());
        assertEquals(1, downloaded.get());
    }

    private static Track track(String id) { return new Track(id, id, "artist", 100, "test"); }

    @Test void smartPlaylistInspectionSharesSourcesAndDoesNotChangeLruOrSettings() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        Track original = track("original");
        Track segment = Track.segment(original, "short", "artist", 1000, 4000);
        Path audio = Path.of(cache.store(original, new MusicClient.DownloadCancellation(), bytes("original")));
        FileTime before = Files.getLastModifiedTime(audio);
        cache.configure(false, 100);
        assertEquals(java.util.Set.of(original.id(), segment.id()),
                cache.cachedTrackIds(java.util.List.of(original, segment, track("missing"))));
        assertEquals(before, Files.getLastModifiedTime(audio));
        assertFalse(cache.enabled());
        Files.writeString(audio, "tampered");
        assertTrue(cache.cachedTrackIds(java.util.List.of(original, segment)).isEmpty());
        assertTrue(Files.exists(audio));
        cache.clear();
        assertTrue(cache.cachedTrackIds(java.util.List.of(original)).isEmpty());
    }
    private static PersistentAudioCache.Downloader bytes(String value) {
        return (track, target, cancellation) -> Files.writeString(target, value, StandardCharsets.UTF_8);
    }

    @Test void disabledByDefaultAndSettingsSurviveRestart() throws Exception {
        var cache = new PersistentAudioCache(directory);
        assertFalse(cache.enabled());
        assertTrue(cache.lookup(track("one")).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> cache.store(track("one"), new MusicClient.DownloadCancellation(), bytes("audio")));
        cache.configure(true, 1024);
        cache.store(track("one"), new MusicClient.DownloadCancellation(), bytes("audio"));

        var restarted = new PersistentAudioCache(directory);
        assertTrue(restarted.enabled());
        assertEquals(1024, restarted.maxBytes());
        assertEquals("audio", Files.readString(Path.of(restarted.lookup(track("one")).orElseThrow())));
        restarted.configure(false, 1024);
        assertTrue(restarted.lookup(track("one")).isEmpty());
        assertEquals(5, restarted.usedBytes()); // Disabling does not silently delete saved data.
    }

    @Test void evictsLeastRecentlyUsedAndClearKeepsSettings() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 8);
        Path first = Path.of(cache.store(track("first"), new MusicClient.DownloadCancellation(), bytes("aaaa")));
        Files.setLastModifiedTime(first, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        cache.store(track("second"), new MusicClient.DownloadCancellation(), bytes("bbbb"));
        cache.lookup(track("first")); // First becomes most recent.
        cache.store(track("third"), new MusicClient.DownloadCancellation(), bytes("cccc"));
        assertTrue(cache.lookup(track("first")).isPresent());
        assertTrue(cache.lookup(track("second")).isEmpty());
        assertEquals(8, cache.usedBytes());
        cache.clear();
        assertEquals(0, cache.usedBytes());
        assertEquals(0, new PersistentAudioCache(directory).entryCount());
        assertTrue(new PersistentAudioCache(directory).enabled());
    }

    @Test void failedAndOversizedDownloadsPreserveOtherEntries() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 10);
        cache.store(track("good"), new MusicClient.DownloadCancellation(), bytes("valid"));
        assertThrows(IOException.class, () -> cache.store(track("broken"),
                new MusicClient.DownloadCancellation(), (track, target, cancellation) -> {
                    Files.writeString(target, "partial");
                    throw new IOException("network stopped");
                }));
        assertThrows(IOException.class, () -> cache.store(track("huge"),
                new MusicClient.DownloadCancellation(), bytes("too-large-file")));
        assertEquals(1, cache.entryCount());
        assertEquals("valid", Files.readString(Path.of(cache.lookup(track("good")).orElseThrow())));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.toString().endsWith(".part")).count());
        }
    }

    @Test void corruptedEntryIsRejectedAfterRestart() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        Path file = Path.of(cache.store(track("broken"), new MusicClient.DownloadCancellation(), bytes("original")));
        Files.writeString(file, "tampered"); // Same byte length; checksum still catches this.
        var restarted = new PersistentAudioCache(directory);
        assertTrue(restarted.lookup(track("broken")).isEmpty());
        assertEquals(0, restarted.usedBytes());
    }

    @Test void unsafeTrackIdsCannotEscapeCacheDirectory() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        Path file = Path.of(cache.store(track("../../outside"), new MusicClient.DownloadCancellation(), bytes("audio")));
        assertEquals(directory.toAbsolutePath().normalize(), file.getParent());
        assertFalse(file.getFileName().toString().contains(".."));
    }

    @Test void malformedSettingsFailClosedWithoutDeletingCache() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        cache.store(track("saved"), new MusicClient.DownloadCancellation(), bytes("audio"));
        Files.writeString(directory.resolve("settings.properties"), "enabled=\\uINVALID");
        var restarted = new PersistentAudioCache(directory);
        assertFalse(restarted.enabled());
        assertEquals(5, restarted.usedBytes());
        restarted.configure(true, 100);
        assertTrue(restarted.lookup(track("saved")).isPresent());
    }

    @Test void slowDownloadDoesNotBlockLookupSettingsOrClearAndCannotRefillAfterClear() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        cache.store(track("saved"), new MusicClient.DownloadCancellation(), bytes("audio"));
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var write = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.storeIfEnabled(track("slow"), new MusicClient.DownloadCancellation(),
                        (track, target, cancellation) -> {
                            downloading.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Download timed out");
                            Files.writeString(target, "new audio");
                        });
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        assertTrue(downloading.await(2, TimeUnit.SECONDS));
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    assertTrue(cache.lookup(track("saved")).isPresent());
                    cache.configure(true, 200);
                    cache.clear();
                } catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
            }).get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertTrue(write.get(2, TimeUnit.SECONDS).isEmpty());
        assertEquals(0, cache.entryCount());
        assertEquals(0, new PersistentAudioCache(directory).entryCount());
    }

    @Test void cancelledDownloadNeverPublishesPartialOrCompleteEntry() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        var cancellation = new MusicClient.DownloadCancellation();
        assertThrows(java.io.InterruptedIOException.class, () -> cache.store(track("cancelled"), cancellation,
                (track, target, token) -> { Files.writeString(target, "audio"); token.cancel(); }));
        assertEquals(0, cache.entryCount());
        assertTrue(cache.lookup(track("cancelled")).isEmpty());
    }

    @Test void concurrentSameTrackWritesPublishOnceAndDiscardTheOtherCompleteFile() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        CountDownLatch downloading = new CountDownLatch(2), release = new CountDownLatch(1);
        java.util.concurrent.Callable<java.net.URI> save = () -> cache.store(track("same"),
                new MusicClient.DownloadCancellation(), (track, target, cancellation) -> {
                    downloading.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test download timed out");
                    Files.writeString(target, "complete audio");
                });
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(save); var second = workers.submit(save);
            assertTrue(downloading.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, cache.entryCount());
            assertEquals("complete audio", Files.readString(Path.of(cache.lookup(track("same")).orElseThrow())));
            try (var files = Files.list(directory)) {
                assertEquals(0, files.filter(path -> path.toString().endsWith(".part")).count());
            }
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void clearingAloneInvalidatesInflightWriteWithoutChangingSettings() throws Exception {
        var cache = new PersistentAudioCache(directory);
        cache.configure(true, 100);
        CountDownLatch downloading = new CountDownLatch(1), release = new CountDownLatch(1);
        var write = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.storeIfEnabled(track("cleared"), new MusicClient.DownloadCancellation(),
                        (track, target, cancellation) -> {
                            downloading.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test download timed out");
                            Files.writeString(target, "audio");
                        });
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        assertTrue(downloading.await(2, TimeUnit.SECONDS));
        try { cache.clear(); } finally { release.countDown(); }
        assertTrue(write.get(2, TimeUnit.SECONDS).isEmpty());
        assertTrue(cache.enabled());
        assertEquals(0, cache.entryCount());
    }
}
