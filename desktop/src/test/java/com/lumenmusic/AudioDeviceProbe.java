package com.lumenmusic;

import javax.sound.sampled.*;

/** Reports the sound output available to the current Java process. */
public final class AudioDeviceProbe {
    public static void main(String[] args) {
        System.out.println("Java " + System.getProperty("java.version"));
        for (var type : AudioSystem.getAudioFileTypes()) System.out.println("WRITER " + type);
        for (var mixer : AudioSystem.getMixerInfo()) System.out.println("MIXER " + mixer.getName());
        try (Clip clip = AudioSystem.getClip()) {
            System.out.println("CLIP available: " + clip.getLineInfo());
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.out.println("CLIP unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(2);
        }
    }
}
