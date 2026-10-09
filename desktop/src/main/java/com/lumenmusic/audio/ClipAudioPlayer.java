package com.lumenmusic.audio;

import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.*;

/** Small Java Sound player for local files supported by the installed JDK. */
public final class ClipAudioPlayer implements AutoCloseable {
    private Clip clip;

    public synchronized void load(Path file)
            throws IOException, UnsupportedAudioFileException, LineUnavailableException {
        Clip replacement = AudioSystem.getClip();
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(file.toFile())) {
            replacement.open(stream);
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException | RuntimeException e) {
            replacement.close();
            throw e;
        }
        if (clip != null) clip.close();
        clip = replacement;
    }

    public synchronized void play() {
        requireClip();
        if (clip.getFramePosition() >= clip.getFrameLength()) clip.setFramePosition(0);
        clip.start();
    }

    public synchronized void pause() {
        requireClip();
        clip.stop();
    }

    public synchronized void stop() {
        requireClip();
        clip.stop();
        clip.setFramePosition(0);
    }

    public synchronized boolean isPlaying() { return clip != null && clip.isRunning(); }
    public synchronized boolean hasOpenLine() { return clip != null && clip.isOpen(); }
    public synchronized long positionMicros() { return clip == null ? 0 : clip.getMicrosecondPosition(); }

    private void requireClip() {
        if (clip == null) throw new IllegalStateException("请先加载音频文件");
    }

    @Override public synchronized void close() {
        if (clip != null) {
            clip.close();
            clip = null;
        }
    }
}
