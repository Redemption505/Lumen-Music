package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javafx.application.Application;

/** Independent native acceptance; every fixture and report stays in the supplied directory. */
public final class V021AudioSmokeLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: V021AudioSmokeLauncher OUTPUT_DIRECTORY");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        writeTone(root.resolve("loud-tone.wav"), .35);
        writeTone(root.resolve("quiet-tone.wav"), .025);
        Application.launch(V021AudioSmoke.class, root.toString());
        if (V021AudioSmoke.failure != null) throw new AssertionError("Native audio acceptance failed", V021AudioSmoke.failure);
    }

    private static void writeTone(Path path, double amplitude) throws Exception {
        if (Files.exists(path)) throw new IllegalStateException("Refusing to replace an existing fixture: " + path);
        int rate = 48_000, frames = rate * 36;
        byte[] pcm = new byte[frames * 4];
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) Math.round(32767 * amplitude * Math.sin(2 * Math.PI * 1000 * frame / rate));
            int offset = frame * 4;
            pcm[offset] = pcm[offset + 2] = (byte) value;
            pcm[offset + 1] = pcm[offset + 3] = (byte) (value >>> 8);
        }
        try (var input = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(rate, 16, 2, true, false), frames)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
