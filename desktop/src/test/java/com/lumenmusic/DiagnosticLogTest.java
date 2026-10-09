package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticLogTest {
    @TempDir Path root;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);

    @Test void exportContainsOnlyWhitelistMetadataAndSafeCodes() throws Exception {
        Files.writeString(root.resolve("ai-settings.json"), "CANARY_API_KEY");
        Files.writeString(root.resolve("library.json"), "CANARY_PRIVATE_NOTE");
        Files.writeString(root.resolve("history.json"), "CANARY_RAW_HISTORY");
        Files.writeString(root.resolve("service.log"), root + " CANARY_LOCAL_PATH");
        var log = new DiagnosticLog(root.resolve("diagnostics.xml"), CLOCK);
        log.record("UPSTREAM_TIMEOUT");
        log.record("CANARY_API_KEY");
        log.record(root + " CANARY_LOCAL_PATH");
        Path output = root.resolve("share.zip");
        log.export(output, new DiagnosticLog.Snapshot(DiagnosticLog.Engine.VLC,
                JavaFxAudioPlayer.State.PAUSED, true, true, false, true));
        try (var zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(1, zip.size());
            String content = new String(zip.getInputStream(zip.getEntry("diagnostics.txt")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("Application: " + BuildInfo.current().version()));
            assertTrue(content.contains("Engine: VLC"));
            assertTrue(content.contains("UPSTREAM_TIMEOUT"));
            assertTrue(content.contains("UNKNOWN_ERROR"));
            assertFalse(content.contains("CANARY"));
            assertFalse(content.contains(root.toString()));
        }
        assertFalse(Files.readString(root.resolve("diagnostics.xml")).contains("CANARY"));
    }

    @Test void recentCodesAreBoundedAndRestoredWithoutPrivateFields() throws Exception {
        Path file = root.resolve("diagnostics.xml");
        var log = new DiagnosticLog(file, CLOCK);
        for (int index = 0; index < 30; index++) log.record("SEARCH_ERROR");
        assertEquals(20, log.recent().size());
        assertEquals(log.recent(), new DiagnosticLog(file, CLOCK).recent());
        assertThrows(UnsupportedOperationException.class, () -> log.recent().clear());
    }

    @Test void damagedRecordRemainsUntouchedAndFailedExportKeepsExistingFile() throws Exception {
        Path file = root.resolve("diagnostics.xml");
        Files.writeString(file, "broken-record");
        assertThrows(java.io.IOException.class, () -> new DiagnosticLog(file, CLOCK));
        assertEquals("broken-record", Files.readString(file));
        var log = new DiagnosticLog(root.resolve("another.xml"), CLOCK);
        Path parent = root.resolve("not-a-directory"); Files.writeString(parent, "keep");
        assertThrows(java.io.IOException.class, () -> log.export(parent.resolve("share.zip"),
                new DiagnosticLog.Snapshot(DiagnosticLog.Engine.JAVA_FX, JavaFxAudioPlayer.State.READY, false, true, false, false)));
        assertEquals("keep", Files.readString(parent));
    }

    @Test void uncaughtFailureRecordsSafeCodeAndPreservesExistingHandler() throws Exception {
        Path file = root.resolve("uncaught.xml");
        var delegated = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Throwable failure = new IllegalStateException("CANARY_PRIVATE_TITLE CANARY_API_KEY");
        var handler = DiagnosticLog.uncaughtHandler(file, (thread, error) -> delegated.set(error));
        handler.uncaughtException(Thread.currentThread(), failure);
        assertSame(failure, delegated.get());
        assertEquals("UNCAUGHT_ERROR", new DiagnosticLog(file).recent().get(0).code());
        assertFalse(Files.readString(file).contains("CANARY"));
    }

    @Test void separateStartupAndUiLogsRetainEachOthersEvents() throws Exception {
        Path file = root.resolve("shared.xml");
        var ui = new DiagnosticLog(file, CLOCK);
        var worker = new DiagnosticLog(file, CLOCK);
        worker.record("SERVICE_START_ERROR");
        ui.record("VLC_START_ERROR");
        assertEquals(java.util.List.of("SERVICE_START_ERROR", "VLC_START_ERROR"),
                worker.recent().stream().map(DiagnosticLog.Event::code).toList());
        assertEquals(worker.recent(), ui.recent());
    }
}
