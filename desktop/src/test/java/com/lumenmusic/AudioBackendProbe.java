package com.lumenmusic;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the backend regression assertions independently while frontend integration is in progress. */
public final class AudioBackendProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        PersistentAudioCacheTest cache = new PersistentAudioCacheTest();
        cache.directory = Files.createTempDirectory(directory, "disabled-");
        cache.disabledByDefaultAndSettingsSurviveRestart();
        cache.directory = Files.createTempDirectory(directory, "eviction-");
        cache.evictsLeastRecentlyUsedAndClearKeepsSettings();
        cache.directory = Files.createTempDirectory(directory, "failed-");
        cache.failedAndOversizedDownloadsPreserveOtherEntries();
        cache.directory = Files.createTempDirectory(directory, "corrupt-");
        cache.corruptedEntryIsRejectedAfterRestart();
        cache.directory = Files.createTempDirectory(directory, "identity-");
        cache.unsafeTrackIdsCannotEscapeCacheDirectory();
        cache.directory = Files.createTempDirectory(directory, "settings-");
        cache.malformedSettingsFailClosedWithoutDeletingCache();
        cache.directory = Files.createTempDirectory(directory, "concurrent-");
        cache.slowDownloadDoesNotBlockLookupSettingsOrClearAndCannotRefillAfterClear();
        cache.directory = Files.createTempDirectory(directory, "cancelled-");
        cache.cancelledDownloadNeverPublishesPartialOrCompleteEntry();
        GlobalHotkeysTest shortcuts = new GlobalHotkeysTest();
        shortcuts.directory = Files.createTempDirectory(directory, "hotkeys-");
        shortcuts.settingsPersistRemovedBindingsAndConvertPunctuation();
        shortcuts.modifiersAloneAndUnmodifiedLetterCannotBeRegistered();
        shortcuts.nativeRegistrationReportsConflictAndReleasesOnClose();
        LoudnessMeterTest loudness = new LoudnessMeterTest();
        loudness.actualPcmAmplitudeDifferenceChangesMeasuredLoudnessAndGain();
        loudness.gainIsBoundedAndSilentAudioDoesNotCreateInfiniteBoost();
        System.out.println("PASS 13 audio backend regression assertions: cache, native shortcuts, measured loudness");
    }
}
