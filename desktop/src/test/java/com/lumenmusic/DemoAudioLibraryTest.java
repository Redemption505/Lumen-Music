package com.lumenmusic;

import com.lumenmusic.audio.DemoAudioLibrary;
import java.nio.file.Files;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemoAudioLibraryTest {
    @Test void createsDistinctEightSecondAudioAndCleansUp() throws Exception {
        var library = new DemoAudioLibrary();
        var first = library.resolve("demo-1");
        var second = library.resolve("demo-2");
        try {
            assertEquals(first, library.resolve("demo-1"));
            assertNull(library.resolve("bilibili:BV123"));
            assertFalse(java.util.Arrays.equals(
                    Files.readAllBytes(java.nio.file.Path.of(first)),
                    Files.readAllBytes(java.nio.file.Path.of(second))));
            try (var audio = AudioSystem.getAudioInputStream(java.nio.file.Path.of(first).toFile())) {
                assertEquals(22_050 * 8, audio.getFrameLength());
                assertEquals(22_050, audio.getFormat().getSampleRate());
            }
        } finally { library.close(); }
        assertFalse(Files.exists(java.nio.file.Path.of(first)));
        assertFalse(Files.exists(java.nio.file.Path.of(second)));
    }
}
