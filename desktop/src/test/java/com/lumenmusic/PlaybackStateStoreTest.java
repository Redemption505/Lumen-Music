package com.lumenmusic;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackStateStoreTest {
    @TempDir Path temporary;

    @Test void restoresQueueOrderSelectedPartLocalUrisAndPosition() throws Exception {
        Path file = temporary.resolve("state/playback.xml");
        URI localUri = URI.create("file:///D:/Music/song%20one.mp3");
        Track local = new Track(localUri.toString(), "本地曲目", "本地文件", 0, "local", localUri,
                null, 0, URI.create("file:///D:/Music/cover.jpg"), 0);
        var state = new PlaybackStateStore.State(List.of(part(2), local, part(1)), 0,
                PlayerQueue.RepeatMode.SHUFFLE, 25.125);

        new PlaybackStateStore(file).save(state);

        assertEquals(state, new PlaybackStateStore(file).read().orElseThrow());
    }

    @Test void supportsEmptyQueueAndUnknownLocalDuration() throws Exception {
        Path file = temporary.resolve("playback.xml");
        var store = new PlaybackStateStore(file);
        assertTrue(store.read().isEmpty());
        var empty = new PlaybackStateStore.State(List.of(), -1, PlayerQueue.RepeatMode.ALL, 0);
        store.save(empty);
        assertEquals(empty, new PlaybackStateStore(file).read().orElseThrow());
        Track local = new Track("local", "song", "artist", 0, "local", URI.create("file:///song.mp3"));
        var playing = new PlaybackStateStore.State(List.of(local), 0, PlayerQueue.RepeatMode.ONE, 125.5);
        store.save(playing);
        assertEquals(playing, new PlaybackStateStore(file).read().orElseThrow());
    }

    @Test void segmentQueueRoundTripsWithoutChangingPlaybackPosition() throws Exception {
        Path file = temporary.resolve("playback.xml");
        Track segment = Track.segment(part(1), "片段", "歌手", 3_500, 6_000);
        var state = new PlaybackStateStore.State(List.of(segment), 0, PlayerQueue.RepeatMode.ONE, 1.25);
        new PlaybackStateStore(file).save(state);
        assertEquals(state, new PlaybackStateStore(file).read().orElseThrow());
    }

    @Test void validatesQueueIndexIdentityPositionAndSnapshotsList() {
        List<Track> tracks = new ArrayList<>(List.of(part(1)));
        var state = new PlaybackStateStore.State(tracks, 0, PlayerQueue.RepeatMode.ALL, 0);
        tracks.clear();
        assertEquals(1, state.tracks().size());
        assertThrows(UnsupportedOperationException.class, () -> state.tracks().clear());
        assertEquals(-1, new PlaybackStateStore.State(List.of(part(1)), -1,
                PlayerQueue.RepeatMode.ALL, 0).index());
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackStateStore.State(List.of(part(1)), 1, PlayerQueue.RepeatMode.ALL, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackStateStore.State(List.of(), 0, PlayerQueue.RepeatMode.ALL, 0));
        for (double position : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY })
            assertThrows(IllegalArgumentException.class,
                    () -> new PlaybackStateStore.State(List.of(part(1)), 0, PlayerQueue.RepeatMode.ALL, position));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackStateStore.State(
                List.of(new Track("", "song", "artist", 1, "local")), 0, PlayerQueue.RepeatMode.ALL, 0));
    }

    @Test void reportsCorruptionAndLeavesFileUntouched() throws Exception {
        Path file = temporary.resolve("playback.xml");
        byte[] damaged = "not XML".getBytes(StandardCharsets.UTF_8);
        Files.write(file, damaged);

        IOException error = assertThrows(IOException.class, () -> new PlaybackStateStore(file).read());

        assertTrue(error.getMessage().contains("原文件已保留"));
        assertArrayEquals(damaged, Files.readAllBytes(file));
    }

    @Test void detectsCurrentTrackMismatchAndNonfiniteSavedPosition() throws Exception {
        Path file = temporary.resolve("playback.xml");
        var store = new PlaybackStateStore(file);
        var state = new PlaybackStateStore.State(List.of(part(1), part(2)), 1, PlayerQueue.RepeatMode.OFF, 2);
        store.save(state);
        Properties values = properties(file);
        values.setProperty("index", "0");
        writeProperties(file, values);
        byte[] mismatched = Files.readAllBytes(file);
        assertThrows(IOException.class, store::read);
        assertArrayEquals(mismatched, Files.readAllBytes(file));

        store.save(state);
        values = properties(file);
        values.setProperty("positionSeconds", "NaN");
        writeProperties(file, values);
        assertThrows(IOException.class, store::read);
    }

    @Test void preservesSourcesAndReadsLegacyStateWithoutThem() throws Exception {
        Path file = temporary.resolve("playback.xml");
        var store = new PlaybackStateStore(file);
        var mixed = new PlaybackStateStore.State(List.of(part(1), part(1), part(2)), 1,
                PlayerQueue.RepeatMode.SHUFFLE, 3.5,
                List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.MANUAL,
                        PlayerQueue.Source.RADIO));
        store.save(mixed);
        assertEquals(mixed, store.read().orElseThrow());

        Properties legacy = properties(file);
        for (int i = 0; i < mixed.tracks().size(); i++) legacy.remove("source." + i);
        writeProperties(file, legacy);
        var restored = store.read().orElseThrow();
        assertEquals(List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.CURRENT_LIST,
                PlayerQueue.Source.CURRENT_LIST), restored.sources());
        assertEquals(mixed.tracks(), restored.tracks());
    }

    @Test void rejectsMismatchedOrUnknownSources() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PlaybackStateStore.State(
                List.of(part(1)), 0, PlayerQueue.RepeatMode.ALL, 0, List.of()));
        Path file = temporary.resolve("playback.xml");
        var store = new PlaybackStateStore(file);
        store.save(new PlaybackStateStore.State(List.of(part(1)), 0,
                PlayerQueue.RepeatMode.ALL, 0));
        Properties values = properties(file);
        values.setProperty("source.0", "UNKNOWN");
        writeProperties(file, values);
        assertThrows(IOException.class, store::read);
    }

    @Test void failedReplacementLeavesTargetAndCleansTemporaryFile() throws Exception {
        Path target = temporary.resolve("playback.xml");
        Files.createDirectory(target);
        Path original = target.resolve("original");
        Files.writeString(original, "keep this data");
        var state = new PlaybackStateStore.State(List.of(part(1)), 0, PlayerQueue.RepeatMode.ALL, 5);

        assertThrows(IOException.class, () -> new PlaybackStateStore(target).save(state));

        assertEquals("keep this data", Files.readString(original));
        try (var paths = Files.list(temporary)) { assertEquals(List.of(target), paths.toList()); }
    }

    private static Track part(int page) {
        return new Track("bilibili:BV1xx411c7mD:p" + page, "第 " + page + " P", "UP", 100,
                "bilibili", null, "BV1xx411c7mD", page, URI.create("https://example.test/cover.jpg"), 2);
    }

    private static Properties properties(Path file) throws Exception {
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.loadFromXML(input); }
        return values;
    }

    private static void writeProperties(Path file, Properties values) throws Exception {
        try (var output = Files.newOutputStream(file)) { values.storeToXML(output, "test", "UTF-8"); }
    }
}
