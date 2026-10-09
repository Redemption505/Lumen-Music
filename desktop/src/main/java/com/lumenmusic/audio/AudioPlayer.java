package com.lumenmusic.audio;

import java.net.URI;
import javafx.util.Duration;

/** Common controls for local JavaFX media and online VLC streams. */
public interface AudioPlayer extends AutoCloseable {
    JavaFxAudioPlayer.State state();
    Duration position();
    Duration duration();
    void setVolume(double value);
    /** Smooth user changes while applying fades and forced silence without delay. */
    default void setOutputGain(double userVolume, double envelopeGain) {
        setVolume(userVolume * envelopeGain);
    }
    /** Returns false when the engine cannot accept the requested rate. */
    default boolean setPlaybackRate(double rate) { return false; }
    default double playbackRate() { return 1.0; }
    static boolean isSupportedPlaybackRate(double rate) {
        return rate == 0.75 || rate == 1.0 || rate == 1.25 || rate == 1.5;
    }
    default void setNormalizationEnabled(boolean enabled) { }
    default String normalizationStatus() { return "此播放引擎不支持响度归一化"; }
    void seekFraction(double fraction);
    void load(URI uri, boolean autoplay);
    default void loadRange(URI uri, boolean autoplay, double startSeconds, double endSeconds) {
        load(uri, autoplay);
    }
    void toggle();
    void stop();
    @Override void close();
}
