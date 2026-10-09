package com.lumenmusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalMusicStoreTest {
    @TempDir Path root;
    @Test void retainsReferencesArtworkAndLyricsAcrossRestartAndRemovalDoesNotDeleteAudio() throws Exception {
        Path audio = Files.writeString(root.resolve("我的音乐.mp3"), "original-audio");
        Path cover = Files.writeString(root.resolve("cover.png"), "cover");
        Track track = new Track(audio.toUri().toString(), "内嵌标题", "歌手", 123, "local",
                audio.toUri(), null, 0, cover.toUri());
        Path file = root.resolve("local-music.xml");
        LocalMusicStore store = new LocalMusicStore(file);
        String lyric = "[00:01.00]第一句\n[00:02.00]第二句";
        store.save(new LocalMusicStore.State(List.of(track), Map.of(track.id(), lyric)));
        var restored = new LocalMusicStore(file).read();
        assertEquals(List.of(track), restored.tracks());
        assertEquals(lyric, restored.lyrics().get(track.id()));
        store.save(new LocalMusicStore.State(List.of(), Map.of()));
        assertTrue(new LocalMusicStore(file).read().tracks().isEmpty());
        assertEquals("original-audio", Files.readString(audio));
        assertTrue(Files.exists(cover));
    }
    @Test void damagedCatalogueIsRetainedForRecovery() throws Exception {
        Path file = Files.writeString(root.resolve("local-music.xml"), "broken");
        assertThrows(java.io.IOException.class, () -> new LocalMusicStore(file).read());
        assertEquals("broken", Files.readString(file));
    }

    @Test void concurrentReadersFromNewInstancesDoNotPreventAtomicSaveOrAudioRetention() throws Exception {
        List<Track> tracks = new ArrayList<>();
        Map<String, String> lyrics = new LinkedHashMap<>();
        byte[] original = "untouched-original-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 120; i++) {
            Path audio = Files.write(root.resolve("fixture-" + i + ".wav"), original);
            Track track = new Track(audio.toUri().toString(), "Concurrent fixture " + i, "Fixture artist", 60, "local", audio.toUri());
            tracks.add(track);
            lyrics.put(track.id(), "[00:00.000]" + "original embedded lyric ".repeat(20));
        }
        LocalMusicStore.State full = new LocalMusicStore.State(tracks, lyrics);
        LocalMusicStore.State removed = new LocalMusicStore.State(tracks.subList(1, tracks.size()), lyrics);
        Path file = root.resolve("local-music.xml");
        LocalMusicStore writer = new LocalMusicStore(file);
        writer.save(full);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicInteger saveAttempts = new AtomicInteger(), readAttempts = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<?>> readers = new ArrayList<>();
        Future<?> writes = workers.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 150; i++) {
                    try { writer.save(i % 2 == 0 ? removed : full); }
                    catch (Throwable error) { failures.add(error); }
                    finally { saveAttempts.incrementAndGet(); }
                }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); failures.add(error); }
            finally { writing.set(false); }
        });
        for (int thread = 0; thread < 3; thread++) readers.add(workers.submit(() -> {
            try {
                start.await();
                do {
                    try {
                        LocalMusicStore.State state = new LocalMusicStore(file).read();
                        assertTrue(state.tracks().equals(full.tracks()) || state.tracks().equals(removed.tracks()), "reader saw incomplete catalogue");
                        for (Track track : state.tracks()) assertEquals(lyrics.get(track.id()), state.lyrics().get(track.id()));
                    } catch (Throwable error) { failures.add(error); }
                    finally { readAttempts.incrementAndGet(); }
                } while (writing.get());
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); failures.add(error); }
        }));
        try {
            start.countDown();
            // Every successful save now waits for up to three full XML parses. Keep all
            // 150 writes and their concurrent readers, allowing the real serialized I/O to finish.
            writes.get(120, TimeUnit.SECONDS);
            for (Future<?> reader : readers) reader.get(10, TimeUnit.SECONDS);
        } finally {
            System.err.println("CONCURRENT LOCAL STORE saveAttempts=" + saveAttempts.get()
                    + " readAttempts=" + readAttempts.get() + " failures=" + failures.size());
            writing.set(false); workers.shutdownNow();
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            System.err.println("CONCURRENT LOCAL STORE failures=" + failures.size() + " first=" + first);
            first.printStackTrace(System.err);
            fail("concurrent read/save failed: " + first, first);
        }
        writer.save(removed);
        assertEquals(removed.tracks(), new LocalMusicStore(file).read().tracks());
        for (Track track : tracks) assertArrayEquals(original, Files.readAllBytes(Path.of(track.mediaUri())), "catalogue save/removal altered audio");
    }
}
