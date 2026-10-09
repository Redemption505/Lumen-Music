package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrackPreferencesStoreTest {
    @TempDir Path directory;

    @Test void perPartPreferenceAndUnavailableMarkersAreIndependentAndPersisted() throws Exception {
        Path file = directory.resolve("preferences.xml");
        var store = new TrackPreferencesStore(file);
        store.setExcluded("bilibili:BV1nhMuzaEJr:p2", true);
        store.markUnavailable("bilibili:BV1nhMuzaEJr:p1", "视频已删除");
        var restored = new TrackPreferencesStore(file);
        assertTrue(restored.isExcluded("bilibili:BV1nhMuzaEJr:p2"));
        assertFalse(restored.isExcluded("bilibili:BV1nhMuzaEJr:p1"));
        assertEquals("视频已删除", restored.failureReason("bilibili:BV1nhMuzaEJr:p1"));
        restored.clearUnavailable("bilibili:BV1nhMuzaEJr:p1");
        restored.setExcluded("bilibili:BV1nhMuzaEJr:p2", false);
        var cleared = new TrackPreferencesStore(file);
        assertTrue(cleared.excludedIds().isEmpty());
        assertTrue(cleared.unavailableIds().isEmpty());
    }

    @Test void corruptPreferencesArePreserved() throws Exception {
        Path file = directory.resolve("preferences.xml");
        Files.writeString(file, "damaged");
        assertThrows(java.io.IOException.class, () -> new TrackPreferencesStore(file));
        assertEquals("damaged", Files.readString(file));
    }

    @Test void transportFailuresAreNeverConfirmedUnavailable() {
        assertTrue(PlaybackFailure.from(new java.io.IOException("reset")).retryable());
        assertFalse(PlaybackFailure.forCode("UPSTREAM_TIMEOUT").permanent());
        assertFalse(PlaybackFailure.forCode("VIDEO_ACCESS_DENIED").permanent());
        assertTrue(PlaybackFailure.forCode("VIDEO_NOT_FOUND").permanent());
        assertTrue(PlaybackFailure.forCode("AUDIO_UNAVAILABLE").permanent());
    }
}
