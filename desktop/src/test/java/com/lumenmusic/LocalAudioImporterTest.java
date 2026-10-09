package com.lumenmusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalAudioImporterTest {
    @TempDir Path directory;

    @Test void recursiveImportDeduplicatesOverlappingSelectionsExistingPathsAndKeepsStableOrder() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("album/nested"));
        Path tagged = Files.write(folder.resolve("b.MP3"), LocalAudioMetadataTest.id3(3,
                LocalAudioMetadataTest.frame(3, "TIT2", LocalAudioMetadataTest.text("Embedded title")),
                LocalAudioMetadataTest.frame(3, "TPE1", LocalAudioMetadataTest.text("Embedded artist"))));
        Path first = Files.write(directory.resolve("album/a.wav"), new byte[]{0});
        Path existingPath = Files.write(folder.resolve("existing.flac"), new byte[]{0});
        Files.write(folder.resolve("readme.txt"), new byte[]{0});
        Track existing = new Track(existingPath.toUri().toString(), "existing", "artist", 0, "local", existingPath.toUri());
        var result = LocalAudioImporter.scan(List.of(directory.resolve("album"), folder, tagged), List.of(existing), directory.resolve("data"));
        assertEquals(2, result.imported().size());
        assertEquals(first.toUri(), result.imported().get(0).track().mediaUri());
        assertEquals("a", result.imported().get(0).track().title());
        assertEquals("Embedded title", result.imported().get(1).track().title());
        assertEquals("Embedded artist", result.imported().get(1).track().artist());
        assertEquals(1, result.duplicates());
        assertEquals(0, result.failures());
        assertThrows(UnsupportedOperationException.class, () -> result.imported().clear());
    }

    @Test void corruptTagAndMissingSelectionDoNotPreventOtherFilesImporting() throws Exception {
        Path corrupt = Files.write(directory.resolve("bad.mp3"), new byte[]{'I', 'D', '3', 4, 0, 0, 127, 127, 127, 127});
        Path normal = Files.write(directory.resolve("good.ogg"), new byte[]{0});
        var result = LocalAudioImporter.scan(List.of(corrupt, directory.resolve("missing"), normal), List.of(), directory.resolve("data"));
        assertEquals(2, result.imported().size());
        assertEquals("bad", result.imported().get(0).track().title());
        assertEquals(1, result.failures());
    }

    @Test void librarySegmentsDoNotPreventImportingTheirOriginalFile() throws Exception {
        Path file = Files.write(directory.resolve("source.mp3"), new byte[]{0});
        Track source = new Track(file.toUri().toString(), "source", "artist", 20, "local", file.toUri());
        Track segment = Track.segment(source, "segment", "artist", 0, 5000);
        var result = LocalAudioImporter.scan(List.of(file), List.of(segment), directory.resolve("data"));
        assertEquals(1, result.imported().size());
    }

    @Test void supportedExtensionsIncludeLosslessAndVorbisWithCaseInsensitivity() {
        for (String name : List.of("a.mp3", "a.FLAC", "a.m4a", "a.mp4", "a.wav", "a.aiff", "a.aif", "a.ogg", "a.OPUS"))
            assertTrue(LocalAudioImporter.supports(Path.of(name)), name);
        assertFalse(LocalAudioImporter.supports(Path.of("a.lrc")));
    }
}
