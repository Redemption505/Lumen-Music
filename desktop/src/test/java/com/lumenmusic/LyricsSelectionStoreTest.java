package com.lumenmusic;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class LyricsSelectionStoreTest {
    @TempDir Path temporary;

    @Test void savesAndLoadsCompleteSelectionAfterStoreRestart() throws Exception {
        Track track = part(1);
        var selection = new LyricsSelectionStore.Selection("晴天 (live)", "周杰伦 feat. 乐队",
                "[ti:晴天]\r\n[ar:周杰伦]\r\n[00:01.25]第一句\n[00:02.50]第二句\n", -275);
        new LyricsSelectionStore(temporary).save(track, selection);

        var restored = new LyricsSelectionStore(temporary).load(track);
        assertEquals(selection, restored.orElseThrow());
    }

    @Test void savesStartAndOffsetIndependentlyAcrossRestart() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        var original = new LyricsSelectionStore.Selection("美人鱼", "林俊杰", "[00:01.00]词", 0);
        var withStart = original.withStartMillis(18 * 60_000L);
        store.save(track, withStart);
        assertEquals(withStart, new LyricsSelectionStore(temporary).load(track).orElseThrow());

        var withOffset = withStart.withOffsetMillis(-275);
        store.save(track, withOffset);
        assertEquals(withOffset, new LyricsSelectionStore(temporary).load(track).orElseThrow());
        assertEquals(18 * 60_000L, withOffset.startMillis());
        assertEquals(0, original.startMillis());
        assertThrows(IllegalArgumentException.class, () -> original.withStartMillis(-1));
    }

    @Test void keepsBilibiliPartsIsolated() throws Exception {
        Track first = part(1);
        Track second = part(2);
        var store = new LyricsSelectionStore(temporary);
        var firstChoice = new LyricsSelectionStore.Selection("第一 P", "歌手", "[00:01.00]一", 100)
                .withStartMillis(18 * 60_000L);
        store.save(first, firstChoice);

        assertTrue(store.load(second).isEmpty());
        var secondChoice = new LyricsSelectionStore.Selection("第二 P", "歌手", "[00:01.00]二", 200)
                .withStartMillis(24 * 60_000L);
        store.save(second, secondChoice);
        assertEquals(firstChoice, store.load(first).orElseThrow());
        assertEquals(secondChoice, store.load(second).orElseThrow());
    }

    @Test void segmentsOfSamePartHaveIndependentLyrics() throws Exception {
        Track source = part(1);
        Track first = Track.segment(source, "第一段", "歌手", 0, 10_000);
        Track second = Track.segment(source, "第二段", "歌手", 10_000, 20_000);
        var store = new LyricsSelectionStore(temporary);
        var selected = new LyricsSelectionStore.Selection("第一段", "歌手", "[00:01.00]词", 0);
        store.save(first, selected);
        assertEquals(selected, store.load(first).orElseThrow());
        assertTrue(store.load(second).isEmpty());
        assertTrue(store.load(source).isEmpty());
    }

    @Test void reportsCorruptionAndLeavesOriginalFileUntouched() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        store.save(track, new LyricsSelectionStore.Selection("标题", "歌手", "[00:01.00]词", 0));
        Path file;
        try (var paths = Files.list(temporary)) { file = paths.findFirst().orElseThrow(); }
        byte[] damaged = "version=broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, damaged);

        var error = assertThrows(java.io.IOException.class, () -> new LyricsSelectionStore(temporary).load(track));
        assertTrue(error.getMessage().contains("原文件已保留"));
        assertArrayEquals(damaged, Files.readAllBytes(file));
    }

    @Test void sourceIsPartOfIdentityForTracksWithSameId() throws Exception {
        var store = new LyricsSelectionStore(temporary);
        Track local = new Track("same-id", "本地", "歌手", 0, "local", URI.create("file:///music.mp3"));
        Track online = new Track("same-id", "在线", "歌手", 0, "bilibili");
        store.save(local, new LyricsSelectionStore.Selection("本地选项", "歌手", "local lrc", 0));
        assertTrue(store.load(online).isEmpty());
    }

    @Test void automaticSelectionIsAvailableAfterRestartWithoutReplacingManualChoice() throws Exception {
        Track track = part(1);
        var automatic = new LyricsSelectionStore.Selection("自动歌曲", "歌手", "[00:01.00]自动词", -50)
                .withStartMillis(18 * 60_000L);
        var store = new LyricsSelectionStore(temporary);
        store.saveAutomatic(track, automatic);
        var cached = new LyricsSelectionStore(temporary).load(track).orElseThrow();
        assertEquals(LyricsSelectionStore.Origin.AUTOMATIC, cached.origin());
        assertEquals(automatic.lrc(), cached.lrc());
        assertEquals(automatic.startMillis(), cached.startMillis());

        var manual = new LyricsSelectionStore.Selection("手动歌曲", "歌手", "[00:01.00]手动词", 100);
        store.save(track, manual);
        store.saveAutomatic(track, new LyricsSelectionStore.Selection("更新自动", "歌手", "new lrc", 0));
        assertEquals(manual, new LyricsSelectionStore(temporary).read(track).orElseThrow());
    }

    @Test void changingOffsetPreservesAutomaticOriginAfterRestart() throws Exception {
        Track track = part(2);
        var store = new LyricsSelectionStore(temporary);
        store.saveAutomatic(track, new LyricsSelectionStore.Selection("自动", "歌手", "[00:01.00]词", 0)
                .withStartMillis(18 * 60_000L));
        var adjusted = store.load(track).orElseThrow().withOffsetMillis(275);
        store.save(track, adjusted);

        assertEquals(adjusted, new LyricsSelectionStore(temporary).load(track).orElseThrow());
        assertEquals(LyricsSelectionStore.Origin.AUTOMATIC, adjusted.origin());
        assertEquals(18 * 60_000L, adjusted.startMillis());
        try (var paths = Files.list(temporary)) {
            assertTrue(paths.allMatch(path -> path.getFileName().toString().endsWith(".automatic.properties")));
        }
    }

    @Test void readsLegacyManualFileWithoutOriginMetadata() throws Exception {
        Track track = part(1);
        var selection = new LyricsSelectionStore.Selection("旧选择", "歌手", "[00:01.00]词", 125);
        var store = new LyricsSelectionStore(temporary);
        store.save(track, selection);
        Path file;
        try (var paths = Files.list(temporary)) { file = paths.findFirst().orElseThrow(); }
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        values.remove("origin");
        values.remove("startMillis");
        try (var output = Files.newOutputStream(file)) { values.store(output, "legacy manual"); }

        assertEquals(selection, new LyricsSelectionStore(temporary).load(track).orElseThrow());
        assertEquals(0, new LyricsSelectionStore(temporary).load(track).orElseThrow().startMillis());
    }

    @Test void rejectsNegativeStoredStartWithoutChangingCorruptFile() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        store.save(track, new LyricsSelectionStore.Selection("歌曲", "歌手", "[00:01.00]词", 0));
        Path file;
        try (var paths = Files.list(temporary)) { file = paths.findFirst().orElseThrow(); }
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        values.setProperty("startMillis", "-1");
        try (var output = Files.newOutputStream(file)) { values.store(output, "corrupt start"); }
        byte[] damaged = Files.readAllBytes(file);

        var error = assertThrows(java.io.IOException.class, () -> new LyricsSelectionStore(temporary).load(track));
        assertTrue(error.getMessage().contains("原文件已保留"));
        assertArrayEquals(damaged, Files.readAllBytes(file));
    }

    @Test void corruptManualChoiceIsReportedDespiteAutomaticCache() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        var selection = new LyricsSelectionStore.Selection("歌曲", "歌手", "lrc", 0);
        store.save(track, selection);
        store.saveAutomatic(track, selection);
        Path manual;
        try (var paths = Files.list(temporary)) {
            manual = paths.filter(path -> !path.getFileName().toString().contains(".automatic.")).findFirst().orElseThrow();
        }
        byte[] damaged = "version=broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(manual, damaged);

        assertThrows(java.io.IOException.class, () -> new LyricsSelectionStore(temporary).load(track));
        assertArrayEquals(damaged, Files.readAllBytes(manual));

        store.saveAutomatic(track, new LyricsSelectionStore.Selection("新的自动歌曲", "歌手", "new lrc", 25));
        var error = assertThrows(LyricsSelectionStore.CorruptRecordException.class,
                () -> new LyricsSelectionStore(temporary).load(track));
        assertEquals(LyricsSelectionStore.Origin.MANUAL, error.origin());
        assertArrayEquals(damaged, Files.readAllBytes(manual));
    }

    @Test void corruptAutomaticFallbackSaveRetainsOriginalBytesAcrossRestart() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        store.saveAutomatic(track, new LyricsSelectionStore.Selection("旧自动", "歌手", "old lrc", 0));
        Path target = automaticFile();
        byte[] damaged = "version=broken\nlrc=private-corruption-canary\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, damaged);

        var error = assertThrows(LyricsSelectionStore.CorruptRecordException.class, () -> store.load(track));
        assertEquals(LyricsSelectionStore.Origin.AUTOMATIC, error.origin());
        assertTrue(error.getMessage().contains("原文件已保留"));
        assertFalse(error.getMessage().contains(temporary.toString()));
        assertFalse(error.getMessage().contains("private-corruption-canary"));
        assertNull(error.getCause());
        assertArrayEquals(damaged, Files.readAllBytes(target));

        // This is the Store part of load -> online fallback -> automatic application -> queued save.
        var fallback = new LyricsSelectionStore.Selection("新自动", "新歌手", "[00:01.25]新词\n", -275,
                LyricsSelectionStore.Origin.AUTOMATIC, 18 * 60_000L);
        store.save(track, fallback);
        var restarted = new LyricsSelectionStore(temporary);
        assertEquals(fallback, restarted.load(track).orElseThrow());
        assertArrayEquals(damaged, Files.readAllBytes(recoveryFile(target)));

        // Later offset saves retain the same recovery copy without creating more files.
        var adjusted = fallback.withOffsetMillis(125).withStartMillis(24 * 60_000L);
        restarted.save(track, adjusted);
        assertEquals(adjusted, new LyricsSelectionStore(temporary).load(track).orElseThrow());
        assertArrayEquals(damaged, Files.readAllBytes(recoveryFile(target)));
        try (var paths = Files.list(temporary)) { assertEquals(2, paths.count()); }
    }

    @Test void failedRecoveryCopyBlocksFallbackSaveWithoutTouchingOriginal() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        var selection = new LyricsSelectionStore.Selection("歌曲", "歌手", "lrc", 0);
        store.saveAutomatic(track, selection);
        Path target = automaticFile();
        byte[] damaged = "version=broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, damaged);
        Path recovery = recoveryFile(target);
        Files.createDirectory(recovery);
        Files.writeString(recovery.resolve("keep"), "existing recovery data");

        assertThrows(LyricsSelectionStore.CorruptRecordException.class, () -> store.load(track));
        var error = assertThrows(java.io.IOException.class, () -> store.saveAutomatic(track, selection));
        assertEquals("损坏歌词记录无法安全保留，未覆盖原文件", error.getMessage());
        assertArrayEquals(damaged, Files.readAllBytes(target));
        assertEquals("existing recovery data", Files.readString(recovery.resolve("keep")));
        assertThrows(LyricsSelectionStore.CorruptRecordException.class,
                () -> new LyricsSelectionStore(temporary).load(track));
        assertNoTemporaryFiles();
    }

    @Test void failedFallbackReplacementKeepsCorruptOriginalAndReusableRecoveryCopy() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        var selection = new LyricsSelectionStore.Selection("歌曲", "歌手", "lrc", 0);
        store.saveAutomatic(track, selection);
        Path target = automaticFile();
        byte[] damaged = "version=broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, damaged);
        var failing = new LyricsSelectionStore(temporary, (source, destination) -> {
            throw new java.io.IOException("injected replacement failure");
        });

        assertThrows(java.io.IOException.class, () -> failing.saveAutomatic(track, selection));
        assertArrayEquals(damaged, Files.readAllBytes(target));
        assertArrayEquals(damaged, Files.readAllBytes(recoveryFile(target)));
        assertThrows(LyricsSelectionStore.CorruptRecordException.class,
                () -> new LyricsSelectionStore(temporary).load(track));
        assertNoTemporaryFiles();

        store.saveAutomatic(track, selection);
        assertEquals(LyricsSelectionStore.Origin.AUTOMATIC,
                new LyricsSelectionStore(temporary).load(track).orElseThrow().origin());
        assertArrayEquals(damaged, Files.readAllBytes(recoveryFile(target)));
        try (var paths = Files.list(temporary)) { assertEquals(2, paths.count()); }
    }

    @Test void differentRecoveryCopyBlocksOverwriteAndPreservesBothDamagedVersions() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        var selection = new LyricsSelectionStore.Selection("歌曲", "歌手", "lrc", 0);
        store.saveAutomatic(track, selection);
        Path target = automaticFile();
        byte[] firstDamage = "version=first-broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, firstDamage);
        store.saveAutomatic(track, selection);
        byte[] laterDamage = "version=later-broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, laterDamage);

        assertThrows(java.io.IOException.class,
                () -> new LyricsSelectionStore(temporary).saveAutomatic(track, selection));
        assertArrayEquals(firstDamage, Files.readAllBytes(recoveryFile(target)));
        assertArrayEquals(laterDamage, Files.readAllBytes(target));
        assertNoTemporaryFiles();
        try (var paths = Files.list(temporary)) { assertEquals(2, paths.count()); }
    }

    @Test void manualRecoverySaveRestoresManualPriorityAndPreservesDamagedBytes() throws Exception {
        Track track = part(1);
        var store = new LyricsSelectionStore(temporary);
        var manual = new LyricsSelectionStore.Selection("手动歌曲", "歌手", "manual lrc", -125)
                .withStartMillis(5_000);
        store.save(track, manual);
        Path target;
        try (var paths = Files.list(temporary)) { target = paths.findFirst().orElseThrow(); }
        byte[] damaged = "title=bad-\\uZZZZ\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, damaged);
        store.saveAutomatic(track, new LyricsSelectionStore.Selection("自动歌曲", "歌手", "automatic lrc", 0));
        assertThrows(LyricsSelectionStore.CorruptRecordException.class, () -> store.load(track));

        store.save(track, manual);
        assertEquals(manual, new LyricsSelectionStore(temporary).load(track).orElseThrow());
        assertArrayEquals(damaged, Files.readAllBytes(recoveryFile(target)));
    }

    @Test void failedReplacementKeepsOriginalAndCleansTemporaryFile() throws Exception {
        Track track = part(1);
        var selection = new LyricsSelectionStore.Selection("歌曲", "歌手", "lrc", 0);
        var store = new LyricsSelectionStore(temporary);
        store.save(track, selection);
        Path target;
        try (var paths = Files.list(temporary)) { target = paths.findFirst().orElseThrow(); }
        Files.delete(target);
        Files.createDirectory(target);
        Path original = target.resolve("original");
        Files.writeString(original, "keep this data");

        assertThrows(java.io.IOException.class, () -> store.save(track, selection));

        assertEquals("keep this data", Files.readString(original));
        try (var paths = Files.list(temporary)) { assertEquals(List.of(target), paths.toList()); }
    }

    private Path automaticFile() throws Exception {
        try (var paths = Files.list(temporary)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".automatic.properties"))
                    .findFirst().orElseThrow();
        }
    }

    private static Path recoveryFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".corrupt");
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var paths = Files.list(temporary)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static Track part(int page) {
        return new Track("same-track-id", "视频标题", "UP", 300, "bilibili", null,
                "BV1xx411c7mD", page, null, 2);
    }
}
