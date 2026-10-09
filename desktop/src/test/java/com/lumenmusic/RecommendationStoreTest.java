package com.lumenmusic;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationStoreTest {
    @TempDir Path temporary;

    @Test void restoresResponseWithEveryTrackFieldAfterRestart() throws Exception {
        Path file = temporary.resolve("state/recommendations.xml");
        Track first = part(1);
        Track second = part(2);
        URI localUri = URI.create("file:///D:/Music/album%20one/song%20%26%20live.mp3");
        Track local = new Track(localUri.toString(), "本地 & <live>", "歌手", 0, "local", localUri,
                null, 0, URI.create("file:///D:/Music/cover%20one.jpg"), 0);
        var result = new MusicClient.RecommendationResult(List.of(first, second, local),
                "夜晚\n轻快 & <放松>", "listening-history");

        new RecommendationStore(file).save(result);

        assertEquals(result, new RecommendationStore(file).read().orElseThrow());
    }

    @Test void missingStateDiffersFromSavedEmptyResponse() throws Exception {
        Path file = temporary.resolve("recommendations.xml");
        var store = new RecommendationStore(file);
        assertTrue(store.read().isEmpty());
        var empty = new MusicClient.RecommendationResult(List.of(), "", "empty");
        store.save(empty);
        assertEquals(empty, new RecommendationStore(file).read().orElseThrow());
    }

    @Test void segmentFieldsRoundTripAndLegacyFieldsDefaultToOrdinary() throws Exception {
        Path file = temporary.resolve("recommendations.xml");
        Track segment = Track.segment(part(1), "片段", "歌手", 1_500, 2_501);
        var store = new RecommendationStore(file);
        store.save(new MusicClient.RecommendationResult(List.of(segment), "", "test"));
        assertEquals(segment, store.read().orElseThrow().tracks().get(0));

        store.save(new MusicClient.RecommendationResult(List.of(part(1)), "", "test"));
        Properties legacy = properties(file);
        legacy.remove("track.0.sourceTrackId");
        legacy.remove("track.0.segmentStartMillis");
        legacy.remove("track.0.segmentEndMillis");
        legacy.remove("track.0.sourceDurationSeconds");
        writeProperties(file, legacy);
        assertFalse(store.read().orElseThrow().tracks().get(0).isSegment());
    }

    @Test void reportsCorruptionWithoutChangingOriginalFile() throws Exception {
        Path file = temporary.resolve("recommendations.xml");
        byte[] damaged = "<properties><broken>".getBytes(StandardCharsets.UTF_8);
        Files.write(file, damaged);

        IOException error = assertThrows(IOException.class, () -> new RecommendationStore(file).read());

        assertTrue(error.getMessage().contains("原文件已保留"));
        assertArrayEquals(damaged, Files.readAllBytes(file));
    }

    @Test void detectsMissingTrackFieldAndIdentityMismatch() throws Exception {
        Path file = temporary.resolve("recommendations.xml");
        var store = new RecommendationStore(file);
        var result = new MusicClient.RecommendationResult(List.of(part(1)), "test", "test");
        store.save(result);
        Properties values = properties(file);
        values.remove("track.0.artist");
        writeProperties(file, values);
        byte[] missingArtist = Files.readAllBytes(file);
        assertThrows(IOException.class, store::read);
        assertArrayEquals(missingArtist, Files.readAllBytes(file));

        store.save(result);
        values = properties(file);
        values.setProperty("track.0.page", "2");
        writeProperties(file, values);
        assertThrows(IOException.class, store::read);
    }

    @Test void failedReplacementLeavesExistingTargetAndCleansTemporaryFile() throws Exception {
        Path target = temporary.resolve("recommendations.xml");
        Files.createDirectory(target);
        Path original = target.resolve("original");
        Files.writeString(original, "keep this data");
        var result = new MusicClient.RecommendationResult(List.of(part(1)), "test", "test");

        assertThrows(IOException.class, () -> new RecommendationStore(target).save(result));

        assertEquals("keep this data", Files.readString(original));
        try (var paths = Files.list(temporary)) {
            assertEquals(List.of(target), paths.toList());
        }
    }

    private static Track part(int page) {
        return new Track("bilibili:BV1xx411c7mD:p" + page, "合集 & 第 " + page + " P", "UP 主", 180 + page,
                "bilibili", null, "BV1xx411c7mD", page,
                URI.create("https://i.example.test/cover.jpg?one=1&two=2"), 2);
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
