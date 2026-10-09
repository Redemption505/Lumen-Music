package com.lumenmusic.audio;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

/** MediaPlayer lifecycle and state notifications. Call from the JavaFX application thread. */
public final class JavaFxAudioPlayer implements AudioPlayer {
    public enum State { IDLE, LOADING, READY, PLAYING, PAUSED, STALLED, ENDED, FAILED }

    private final Consumer<State> stateListener;
    private final Consumer<String> errorListener;
    private MediaPlayer player;
    private long requestVersion;
    private long endedVersion = -1;
    private State state = State.IDLE;
    private boolean playWhenReady;
    private final FxVolumeSmoother volume = new FxVolumeSmoother(value -> {
        if (player != null) player.setVolume(value);
    });
    private double playbackRate = 1.0;
    private boolean endedSeek;

    public JavaFxAudioPlayer(Consumer<State> stateListener, Consumer<String> errorListener) {
        this.stateListener = Objects.requireNonNull(stateListener);
        this.errorListener = Objects.requireNonNull(errorListener);
    }

    public State state() { return state; }
    public Duration position() { return player == null ? Duration.ZERO : player.getCurrentTime(); }
    public Duration duration() { return player == null ? Duration.UNKNOWN : player.getMedia().getDuration(); }

    public void setVolume(double value) {
        requireFxThread();
        volume.setImmediate(value);
    }

    @Override public void setOutputGain(double userVolume, double envelopeGain) {
        requireFxThread();
        volume.set(userVolume, envelopeGain);
    }

    @Override public double playbackRate() { return playbackRate; }

    @Override public boolean setPlaybackRate(double rate) {
        requireFxThread();
        if (!AudioPlayer.isSupportedPlaybackRate(rate)) return false;
        double previous = playbackRate;
        playbackRate = rate;
        if (player == null || state == State.LOADING) return true;
        if (applyPlaybackRate(player)) return true;
        playbackRate = previous;
        player.setRate(previous);
        return false;
    }

    private boolean applyPlaybackRate(MediaPlayer target) {
        try {
            target.setRate(playbackRate);
            if (Double.compare(target.getRate(), playbackRate) == 0) return true;
        } catch (RuntimeException error) {
            errorListener.accept("JavaFX 无法设置播放速度：" + error.getMessage());
            return false;
        }
        errorListener.accept("JavaFX 无法设置播放速度");
        return false;
    }

    public void seekFraction(double fraction) {
        requireFxThread();
        if (player == null || !Double.isFinite(fraction)) return;
        var total = player.getMedia().getDuration();
        if (total.isUnknown() || total.isIndefinite() || total.toMillis() <= 0) return;
        player.seek(total.multiply(Math.max(0, Math.min(1, fraction))));
        if (state == State.ENDED) endedSeek = true;
    }

    public void load(URI uri, boolean autoplay) {
        loadRange(uri, autoplay, 0, 0);
    }

    @Override public void loadRange(URI uri, boolean autoplay, double startSeconds, double endSeconds) {
        requireFxThread();
        Objects.requireNonNull(uri);
        long version = ++requestVersion;
        disposeCurrent();
        playWhenReady = autoplay;
        setState(State.LOADING);
        try {
            var replacement = new MediaPlayer(new Media(uri.toString()));
            if (endSeconds > startSeconds) {
                replacement.setStartTime(Duration.seconds(startSeconds));
                replacement.setStopTime(Duration.seconds(endSeconds));
            }
            replacement.setVolume(volume.current());
            player = replacement;
            replacement.getMedia().setOnError(() -> {
                if (isCurrent(version, replacement)) fail(replacement.getMedia().getError());
            });
            replacement.setOnReady(() -> {
                if (!isCurrent(version, replacement)) return;
                if (!applyPlaybackRate(replacement)) {
                    playbackRate = 1.0;
                    replacement.setRate(playbackRate);
                }
                setState(State.READY);
                if (playWhenReady && isCurrent(version, replacement)) replacement.play();
            });
            replacement.setOnPlaying(() -> {
                if (isCurrent(version, replacement)) setState(State.PLAYING);
            });
            replacement.setOnPaused(() -> {
                if (isCurrent(version, replacement)) setState(State.PAUSED);
            });
            replacement.setOnStalled(() -> {
                if (isCurrent(version, replacement)) setState(State.STALLED);
            });
            replacement.setOnStopped(() -> {
                if (isCurrent(version, replacement)) setState(State.READY);
            });
            replacement.setOnEndOfMedia(() -> {
                if (isCurrent(version, replacement) && endedVersion != version) {
                    endedVersion = version;
                    endedSeek = false;
                    setState(State.ENDED);
                }
            });
            replacement.setOnError(() -> {
                if (isCurrent(version, replacement)) fail(replacement.getError());
            });
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    public void toggle() {
        requireFxThread();
        if (player == null) return;
        if (state == State.PLAYING || state == State.STALLED) player.pause();
        else if (state == State.LOADING) playWhenReady = !playWhenReady;
        else if (state == State.READY || state == State.PAUSED || state == State.ENDED) {
            boolean replay = state == State.ENDED;
            if (replay) {
                endedVersion = -1;
                if (!endedSeek) player.seek(player.getStartTime());
                endedSeek = false;
            }
            player.play();
            // JavaFX may remain in native PLAYING status across end-of-media and omit onPlaying.
            if (replay && player.getStatus() == MediaPlayer.Status.PLAYING) setState(State.PLAYING);
        }
    }

    public void stop() {
        requireFxThread();
        ++requestVersion;
        volume.stop();
        disposeCurrent();
        setState(State.IDLE);
    }

    private boolean isCurrent(long version, MediaPlayer expected) {
        return version == requestVersion && player == expected;
    }

    private void fail(Throwable error) {
        setState(State.FAILED);
        errorListener.accept(error == null ? "未知音频错误" : error.getMessage());
    }

    private void setState(State next) {
        state = next;
        stateListener.accept(next);
    }

    private void disposeCurrent() {
        endedSeek = false;
        if (player != null) {
            player.dispose();
            player = null;
        }
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("音频操作须在 JavaFX 线程执行");
    }

    @Override public void close() { stop(); }
}
