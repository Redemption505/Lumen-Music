package com.lumenmusic;

import java.nio.file.Path;
import javax.sound.sampled.*;

/** Reports what the installed Java Sound providers can read from a real file. */
public final class CompressedFormatProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要一个音频文件路径");
        Path file = Path.of(args[0]);
        try (var stream = AudioSystem.getAudioInputStream(file.toFile())) {
            System.out.println("DECODED " + file.getFileName() + ": " + stream.getFormat());
            try (Clip clip = AudioSystem.getClip()) {
                clip.open(stream);
                System.out.println("PLAYABLE " + file.getFileName() + ": " + clip.getMicrosecondLength() + " us");
            }
        } catch (UnsupportedAudioFileException e) {
            System.out.println("UNSUPPORTED " + file.getFileName() + ": " + e.getMessage());
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.out.println("DECODED_BUT_NOT_PLAYABLE " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
