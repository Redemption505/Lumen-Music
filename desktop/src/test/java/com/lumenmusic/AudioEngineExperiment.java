package com.lumenmusic;

import com.lumenmusic.audio.ClipAudioPlayer;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import javax.sound.sampled.*;

/** Generates small PCM WAV fixtures and exercises an actual Java Sound output line. */
public final class AudioEngineExperiment {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("build", "audio-experiment");
        Files.createDirectories(output);
        Path first = tone(output.resolve("tone-440.wav"), 440);
        Path second = tone(output.resolve("tone-660.wav"), 660);
        System.out.println("Generated PCM WAV samples: " + first + ", " + second);

        try (ClipAudioPlayer player = new ClipAudioPlayer()) {
            player.load(first);
            check(player.hasOpenLine(), "音频输出未打开");
            player.play();
            Thread.sleep(180);
            check(player.isPlaying(), "播放未启动");
            long beforePause = player.positionMicros();
            check(beforePause > 0, "播放位置没有推进");

            player.pause();
            long paused = player.positionMicros();
            Thread.sleep(150);
            check(!player.isPlaying(), "暂停后仍在播放");
            check(player.positionMicros() == paused, "暂停时位置仍在推进");

            player.play();
            Thread.sleep(180);
            check(player.positionMicros() > paused, "恢复后位置未推进");

            player.load(second);
            check(player.positionMicros() == 0, "切换曲目时未从头开始");
            player.play();
            Thread.sleep(180);
            check(player.isPlaying() && player.positionMicros() > 0, "切换后的曲目未播放");
            player.stop();
            check(!player.isPlaying() && player.positionMicros() == 0, "停止后未复位");
        }

        try (ClipAudioPlayer player = new ClipAudioPlayer()) {
            player.load(second);
            player.play();
            Thread.sleep(80);
            check(player.isPlaying(), "关闭实验后无法重新打开音频设备");
        }
        System.out.println("PASS: output line opened; play, pause, resume, switch, stop, close and reopen");
        System.out.println("Physical audibility cannot be checked automatically; output line and playback clock were verified.");
    }

    private static Path tone(Path file, int hertz) throws Exception {
        int sampleRate = 22_050;
        int frames = sampleRate * 2;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            // 12% amplitude keeps the short device test quiet.
            short sample = (short) (Math.sin(2 * Math.PI * hertz * i / sampleRate) * 3_900);
            pcm[i * 2] = (byte) sample;
            pcm[i * 2 + 1] = (byte) (sample >>> 8);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
        try (var stream = AudioSystem.getAudioInputStream(file.toFile())) {
            check(stream.getFormat().getEncoding() == AudioFormat.Encoding.PCM_SIGNED,
                    "生成的测试音频不是 PCM");
        }
        return file;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
