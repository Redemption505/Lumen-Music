package com.lumenmusic.audio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import javax.sound.sampled.*;

/** Generates clearly labeled, original eight-second audio for demo metadata. */
public final class DemoAudioLibrary implements AutoCloseable {
    private static final int SAMPLE_RATE = 22_050;
    private static final Map<String, int[]> MELODIES = Map.of(
            "demo-1", new int[] {262, 330, 392, 523, 392, 330, 294, 262},
            "demo-2", new int[] {220, 277, 330, 440, 494, 440, 330, 277},
            "demo-3", new int[] {294, 349, 440, 392, 349, 294, 262, 220});

    private final Map<String, Path> generated = new HashMap<>();
    private Path directory;

    public URI resolve(String trackId) throws IOException {
        int[] melody = MELODIES.get(trackId);
        if (melody == null) return null;
        Path existing = generated.get(trackId);
        if (existing != null) return existing.toUri();
        if (directory == null) directory = Files.createTempDirectory("lumen-demo-");
        Path file = directory.resolve(trackId + ".wav");
        byte[] pcm = new byte[SAMPLE_RATE * melody.length * 2];
        for (int frame = 0; frame < SAMPLE_RATE * melody.length; frame++) {
            int note = frame / SAMPLE_RATE;
            double position = (frame % SAMPLE_RATE) / (double) SAMPLE_RATE;
            double envelope = Math.min(1, Math.min(position / .035, (1 - position) / .10));
            double phase = 2 * Math.PI * melody[note] * frame / SAMPLE_RATE;
            short sample = (short) (Math.max(0, envelope) * 3_900
                    * (Math.sin(phase) + .22 * Math.sin(2 * phase)));
            pcm[frame * 2] = (byte) sample;
            pcm[frame * 2 + 1] = (byte) (sample >>> 8);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
        file.toFile().deleteOnExit();
        generated.put(trackId, file);
        return file.toUri();
    }

    @Override public void close() {
        for (Path file : generated.values()) {
            try { Files.deleteIfExists(file); }
            catch (IOException ignored) { file.toFile().deleteOnExit(); }
        }
        generated.clear();
        if (directory != null) {
            try { Files.deleteIfExists(directory); }
            catch (IOException ignored) { directory.toFile().deleteOnExit(); }
            directory = null;
        }
    }
}
