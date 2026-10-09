package com.lumenmusic;

/** Classpath entry for the JavaFX direct-stream experiment. */
public final class RangePlaybackProbeLauncher {
    public static void main(String[] args) {
        if ("1".equals(System.getenv("LUMEN_MEDIA_SWITCH_TEST"))) RangeSwitchProbe.main(args);
        else com.lumenmusic.audio.JavaFxMediaExperiment.main(args);
    }
}
