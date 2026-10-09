package com.lumenmusic.audio;

import com.sun.jna.NativeLibrary;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.util.Duration;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.Equalizer;

/** VLC-backed audio player. Public operations run on the JavaFX application thread. */
public final class VlcAudioPlayer implements AudioPlayer {
    // VLC's equalizer filter has a fixed 0.25 multiplier, so 0 dB preamp is not unity gain.
    public static final double NORMALIZATION_UNITY_PREAMP_DB = 20 * Math.log10(4);
    private static final long NORMALIZATION_RAMP_MILLIS = 1500;
    private final Consumer<JavaFxAudioPlayer.State> stateListener;
    private final Consumer<String> errorListener;
    private final MediaPlayerFactory factory;
    private final LoudnessAnalyzer loudnessAnalyzer = new LoudnessAnalyzer();
    private final Map<URI, LoudnessMeter.Result> measurements = new LinkedHashMap<>();
    private MediaPlayer player;
    private Equalizer normalizationEqualizer;
    private final GainRamp normalizationGain = new GainRamp(0);
    private final AnimationTimer normalizationClock = new AnimationTimer() {
        @Override public void handle(long now) {
            if (player == null || normalizationEqualizer == null) { stop(); return; }
            updateNormalizationPreamp(normalizationGain.valueAt(now));
            if (normalizationGain.settled(now)) stop();
        }
    };
    private CompletableFuture<LoudnessMeter.Result> analysis;
    private URI currentUri;
    private boolean normalizationEnabled;
    private long normalizationVersion;
    private String normalizationStatus = "已关闭";
    private long requestVersion;
    private JavaFxAudioPlayer.State state = JavaFxAudioPlayer.State.IDLE;
    private boolean playWhenReady;
    private long lastVolumeAttempt;
    private final AnimationTimer outputReadyClock = new AnimationTimer() {
        @Override public void handle(long now) {
            if (closed || player == null || state == JavaFxAudioPlayer.State.IDLE
                    || state == JavaFxAudioPlayer.State.FAILED || state == JavaFxAudioPlayer.State.ENDED
                    || state == JavaFxAudioPlayer.State.PAUSED) { stop(); return; }
            if (now - lastVolumeAttempt < 100_000_000) return;
            lastVolumeAttempt = now;
            if (player.audio().setVolume((int) Math.round(volume.current() * 100))) stop();
        }
    };
    private final FxVolumeSmoother volume = new FxVolumeSmoother(this::writeVolume);
    private double playbackRate = 1.0;
    private long rangeStartMillis;
    private double rangeEndSeconds;
    private long endedSeekMillis = -1;
    private long knownLengthMillis = -1;
    private long knownTimeMillis;
    private boolean closed;

    public VlcAudioPlayer(Consumer<JavaFxAudioPlayer.State> stateListener,
                          Consumer<String> errorListener) {
        this(stateListener, errorListener, prepareFactory());
    }

    /** Native plug-in discovery belongs on a worker, before constructing FX state. */
    public static MediaPlayerFactory prepareFactory() {
        configureNativePath();
        return new MediaPlayerFactory("--no-video", "--no-metadata-network-access", "--audio-filter=scaletempo");
    }

    public VlcAudioPlayer(Consumer<JavaFxAudioPlayer.State> stateListener,
                          Consumer<String> errorListener, MediaPlayerFactory preparedFactory) {
        this.stateListener = Objects.requireNonNull(stateListener);
        this.errorListener = Objects.requireNonNull(errorListener);
        factory = Objects.requireNonNull(preparedFactory);
    }

    public JavaFxAudioPlayer.State state() { return state; }
    public Duration position() {
        if (player == null) return Duration.ZERO;
        long time = player.status().time();
        // The native input disappears just before its queued EOF event reaches JavaFX.
        if (time >= 0) knownTimeMillis = time;
        return Duration.millis(knownTimeMillis);
    }
    public Duration duration() {
        long length = sourceLengthMillis();
        return length <= 0 ? Duration.UNKNOWN : Duration.millis(length);
    }

    private long sourceLengthMillis() {
        if (player == null) return -1;
        long length = player.status().length();
        // VLC discards the native input length at EOF, before the next seek can be mapped.
        if (length > 0) knownLengthMillis = length;
        return knownLengthMillis;
    }

    public void setVolume(double value) {
        requireFxThread();
        volume.setImmediate(value);
    }

    @Override public void setOutputGain(double userVolume, double envelopeGain) {
        requireFxThread();
        if (!closed) volume.set(userVolume, envelopeGain);
    }

    @Override public double playbackRate() { return playbackRate; }

    @Override public boolean setPlaybackRate(double rate) {
        requireFxThread();
        if (closed || !AudioPlayer.isSupportedPlaybackRate(rate)) return false;
        if (player != null && state != JavaFxAudioPlayer.State.LOADING) {
            if (!player.controls().setRate((float) rate)) {
                errorListener.accept("VLC 当前音频不支持此播放速度");
                return false;
            }
        }
        playbackRate = rate;
        return true;
    }

    private void applyPlaybackRate(MediaPlayer target) {
        if (target.controls().setRate((float) playbackRate)) return;
        playbackRate = 1.0;
        target.controls().setRate(1.0f);
        errorListener.accept("VLC 当前音频不支持所选播放速度，已恢复原速");
    }

    @Override public String normalizationStatus() { return normalizationStatus; }

    @Override public void setNormalizationEnabled(boolean enabled) {
        requireFxThread();
        if (closed || normalizationEnabled == enabled) return;
        normalizationEnabled = enabled;
        cancelAnalysis();
        if (!enabled) {
            applyNormalizationGain(0);
            normalizationStatus = "已关闭";
        } else if (player != null && currentUri != null) startAnalysis();
        else normalizationStatus = "等待曲目";
    }

    private void cancelAnalysis() {
        ++normalizationVersion;
        if (analysis != null) { analysis.cancel(true); analysis = null; }
    }

    private void startAnalysis() {
        long version = normalizationVersion;
        URI uri = currentUri;
        LoudnessMeter.Result cached = measurements.get(uri);
        if (cached != null) { applyMeasurement(cached); return; }
        normalizationStatus = "正在测量曲目响度";
        analysis = loudnessAnalyzer.analyze(uri);
        analysis.whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || !normalizationEnabled || normalizationVersion != version
                    || !Objects.equals(uri, currentUri)) return;
            analysis = null;
            if (error != null) {
                normalizationStatus = "测量失败：" + (error.getMessage() == null ? "无法解码音频" : error.getMessage());
                return;
            }
            if (measurements.size() >= 128) measurements.remove(measurements.keySet().iterator().next());
            measurements.put(uri, result);
            applyMeasurement(result);
        }));
    }

    private void applyMeasurement(LoudnessMeter.Result result) {
        applyNormalizationGain(result.gainDb());
        normalizationStatus = String.format(Locale.ROOT, "已测前 %.1f 秒：%.1f LUFS · 增益 %+.1f dB",
                result.frames() / (double) LoudnessMeter.SAMPLE_RATE, result.integratedLufs(), result.gainDb());
    }

    private void applyNormalizationGain(double gainDb) {
        if (player == null || normalizationEqualizer == null) return;
        long now = System.nanoTime();
        normalizationGain.moveTo(gainDb, now, NORMALIZATION_RAMP_MILLIS);
        if (!normalizationGain.settled(now)) normalizationClock.start();
    }

    private void updateNormalizationPreamp(double gainDb) {
        float preamp = (float) (NORMALIZATION_UNITY_PREAMP_DB + gainDb);
        if (normalizationEqualizer.preamp() != preamp) normalizationEqualizer.setPreamp(preamp);
    }

    private void initializeNormalization(MediaPlayer replacement, URI uri) {
        LoudnessMeter.Result cached = normalizationEnabled ? measurements.get(uri) : null;
        double gainDb = cached == null ? 0 : cached.gainDb();
        normalizationGain.reset(gainDb);
        // Install before playback so completing a measurement cannot rebuild the live filter chain.
        normalizationEqualizer = factory.equalizer().newEqualizer();
        updateNormalizationPreamp(gainDb);
        replacement.audio().setEqualizer(normalizationEqualizer);
    }

    public void seekFraction(double fraction) {
        requireFxThread();
        if (player == null || !Double.isFinite(fraction)) return;
        boolean ended = state == JavaFxAudioPlayer.State.ENDED;
        if (!ended && !player.status().isSeekable()) return;
        long length = sourceLengthMillis();
        if (length > 0) {
            long target = Math.round(length * Math.max(0, Math.min(1, fraction)));
            if (ended) endedSeekMillis = target;
            else player.controls().setTime(target);
        }
    }

    public void load(URI uri, boolean autoplay) {
        loadRange(uri, autoplay, 0, 0);
    }

    @Override public void loadRange(URI uri, boolean autoplay, double startSeconds, double endSeconds) {
        requireFxThread();
        Objects.requireNonNull(uri);
        if (closed) throw new IllegalStateException("VLC 播放器已关闭");
        long version = ++requestVersion;
        cancelAnalysis();
        disposeCurrent();
        currentUri = uri;
        rangeStartMillis = endSeconds > startSeconds ? Math.round(startSeconds * 1000) : 0;
        rangeEndSeconds = endSeconds > startSeconds ? endSeconds : 0;
        playWhenReady = autoplay;
        setState(JavaFxAudioPlayer.State.LOADING);
        try {
            MediaPlayer replacement = factory.mediaPlayers().newMediaPlayer();
            player = replacement;
            writeVolume(volume.current());
            initializeNormalization(replacement, uri);
            replacement.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void mediaPlayerReady(MediaPlayer mediaPlayer) {
                    deliver(version, replacement, () -> {
                        sourceLengthMillis();
                        applyPlaybackRate(replacement);
                        if (state == JavaFxAudioPlayer.State.LOADING)
                            setState(JavaFxAudioPlayer.State.READY);
                    });
                }
                @Override public void playing(MediaPlayer mediaPlayer) {
                    deliver(version, replacement, () -> {
                        sourceLengthMillis();
                        applyPlaybackRate(replacement);
                        if (playWhenReady) setState(JavaFxAudioPlayer.State.PLAYING);
                        else {
                            replacement.controls().pause();
                            setState(JavaFxAudioPlayer.State.PAUSED);
                        }
                        writeVolume(volume.current());
                    });
                }
                @Override public void paused(MediaPlayer mediaPlayer) {
                    deliver(version, replacement, () -> setState(JavaFxAudioPlayer.State.PAUSED));
                }
                @Override public void finished(MediaPlayer mediaPlayer) {
                    deliver(version, replacement, () -> {
                        endedSeekMillis = -1;
                        setState(JavaFxAudioPlayer.State.ENDED);
                    });
                }
                @Override public void lengthChanged(MediaPlayer mediaPlayer, long length) {
                    if (length > 0) deliver(version, replacement, () -> knownLengthMillis = length);
                }
                @Override public void error(MediaPlayer mediaPlayer) {
                    deliver(version, replacement, () -> fail("VLC 无法播放音频"));
                }
            });
            String[] options = playbackOptions(rangeStartMillis / 1000.0);
            if (!replacement.media().play(uri.toString(), options)) fail("VLC 无法打开音频地址");
            if (normalizationEnabled) startAnalysis();
        } catch (RuntimeException error) {
            fail(error.getMessage());
        }
    }

    public void toggle() {
        requireFxThread();
        if (player == null) return;
        if (state == JavaFxAudioPlayer.State.LOADING) playWhenReady = !playWhenReady;
        else if (state == JavaFxAudioPlayer.State.PLAYING || state == JavaFxAudioPlayer.State.STALLED) {
            playWhenReady = false;
            player.controls().pause();
        } else if (state == JavaFxAudioPlayer.State.READY || state == JavaFxAudioPlayer.State.PAUSED
                || state == JavaFxAudioPlayer.State.ENDED) {
            if (state == JavaFxAudioPlayer.State.ENDED) {
                double startSeconds = (endedSeekMillis >= 0 ? endedSeekMillis : rangeStartMillis) / 1000.0;
                endedSeekMillis = -1;
                playWhenReady = true;
                knownTimeMillis = 0;
                setState(JavaFxAudioPlayer.State.LOADING);
                if (!player.media().play(currentUri.toString(), playbackOptions(startSeconds)))
                    fail("VLC 无法重新播放音频");
                return;
            }
            playWhenReady = true;
            player.controls().play();
        }
    }

    private String[] playbackOptions(double startSeconds) {
        if (rangeEndSeconds > 0) return new String[] {
                String.format(Locale.ROOT, ":start-time=%.3f", startSeconds),
                String.format(Locale.ROOT, ":stop-time=%.3f", rangeEndSeconds)
        };
        return startSeconds > 0 ? new String[] {
                String.format(Locale.ROOT, ":start-time=%.3f", startSeconds)
        } : new String[0];
    }

    public void stop() {
        requireFxThread();
        ++requestVersion;
        volume.stop();
        cancelAnalysis();
        disposeCurrent();
        currentUri = null;
        normalizationStatus = normalizationEnabled ? "等待曲目" : "已关闭";
        setState(JavaFxAudioPlayer.State.IDLE);
    }

    private void writeVolume(double value) {
        if (player == null || closed) return;
        // PLAYING and READY may both precede creation of VLC's audio output. A failed
        // native write must remain pending even after the UI's gain envelope settles.
        if (!player.audio().setVolume((int) Math.round(value * 100))) outputReadyClock.start();
    }

    private void deliver(long version, MediaPlayer expected, Runnable action) {
        Platform.runLater(() -> {
            if (!closed && version == requestVersion && player == expected) action.run();
        });
    }

    private void fail(String message) {
        setState(JavaFxAudioPlayer.State.FAILED);
        errorListener.accept(message == null || message.isBlank() ? "未知 VLC 音频错误" : message);
    }

    private void setState(JavaFxAudioPlayer.State next) {
        state = next;
        stateListener.accept(next);
    }

    private void disposeCurrent() {
        outputReadyClock.stop();
        lastVolumeAttempt = 0;
        normalizationClock.stop();
        normalizationGain.reset(0);
        endedSeekMillis = -1;
        knownLengthMillis = -1;
        knownTimeMillis = 0;
        if (player != null) {
            MediaPlayer previous = player;
            player = null;
            normalizationEqualizer = null;
            previous.controls().stop();
            previous.release();
        }
    }

    static void configureNativePath() {
        String configured = System.getProperty("lumen.vlc.home", System.getenv("LUMEN_VLC_HOME"));
        if (configured == null || configured.isBlank()) return;
        Path home = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(home.resolve("libvlc.dll")) || !Files.isDirectory(home.resolve("plugins")))
            throw new IllegalArgumentException("VLC 运行时目录无效：" + home);
        NativeLibrary.addSearchPath("libvlc", home.toString());
        String existing = System.getProperty("jna.library.path", "");
        if (Arrays.stream(existing.split(";")).noneMatch(path -> path.equalsIgnoreCase(home.toString())))
            System.setProperty("jna.library.path", home + (existing.isBlank() ? "" : ";" + existing));
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("音频操作须在 JavaFX 线程执行");
    }

    @Override public void close() {
        requireFxThread();
        if (closed) return;
        stop();
        closed = true;
        loudnessAnalyzer.close();
        factory.release();
    }
}
