package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.VlcAudioPlayer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.stage.Stage;
import javafx.util.Duration;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Long public source offsets: real native VLC position and audible output, without full-file caching. */
public final class LongSegmentPlaybackSmoke extends Application {
    private static final String BV = "BV1bE41127s6";
    private static Throwable failure;
    private final List<String> rows = new ArrayList<>(List.of(
            "elapsedMillis,phase,index,page,trackId,fade,normalization,preload,normalizationStatus,state,nativeState,nativeMillis,appMillis,relativeSeconds,durationSeconds,desiredVolume,outputEnvelope,nativeVolume,mute,segmentSeeking,pendingResume,uri,failure"));
    private final long started = System.nanoTime();
    private volatile Throwable sampleFailure;
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Timeline sampler;
    private Path root;

    public static void main(String[] args) {
        launch(args);
        if (failure != null) throw new AssertionError("Long segment playback failed", failure);
    }

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread driver = new Thread(() -> {
            try { exercise(); }
            catch (Throwable error) { failure = error; error.printStackTrace(System.err); }
            finally {
                try {
                    fx(() -> { if (sampler != null) sampler.stop(); if (app != null) app.stop(); stage.close(); });
                    if (root != null) Files.write(root.resolve("long-segment-native.csv"), rows);
                } catch (Throwable error) { if (failure == null) failure = error; error.printStackTrace(System.err); }
                Platform.exit();
            }
        }, "long-segment-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        validateIsolation();
        fx(() -> {
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            try { disk().configure(false, 64L * 1024 * 1024); }
            catch (Exception error) { throw new AssertionError(error); }
            setOption("audio-preload-enabled", Boolean.getBoolean("lumen.smoke.preload"));
            setOption("audio-normalization-enabled", Boolean.getBoolean("lumen.smoke.normalization"));
            check(field(app, "normalizationEnabled", Boolean.class) == Boolean.getBoolean("lumen.smoke.normalization"), "Normalization preference did not apply");
            check(field(app, "preloadEnabled", Boolean.class) == Boolean.getBoolean("lumen.smoke.preload"), "Preload preference did not apply");
            log("SETTINGS normalization=" + field(app, "normalizationEnabled", Boolean.class)
                    + " preload=" + field(app, "preloadEnabled", Boolean.class) + " persistentCache=" + disk().enabled());
            ((Slider) stage.getScene().lookup("#volume-slider")).setValue(.6);
            check(field(app, "streamingAudio", VlcAudioPlayer.class) != null, "Real native VLC is required");
            sampler = new Timeline(new KeyFrame(Duration.millis(100), event -> {
                try { sample(); } catch (Throwable error) { sampleFailure = error; }
            }));
            sampler.setCycleCount(Timeline.INDEFINITE);
            sampler.play();
        });
        await(10000, () -> fxValue(() -> "个人曲库已载入".equals(field(app, "libraryStatus", Label.class).getText())),
                "isolated empty library readiness");
        phase = "live_metadata";
        MusicClient client = new MusicClient(URI.create(System.getenv("LUMEN_SERVICE_URL")));
        List<Track> parts = client.resolveCollection(BV);
        check(parts.size() == 2, "Live BV must contain exactly two parts: " + parts.size());
        Track p1 = parts.stream().filter(track -> track.page() == 1).findFirst().orElseThrow();
        Track p2 = parts.stream().filter(track -> track.page() == 2).findFirst().orElseThrow();
        for (Track source : List.of(p1, p2)) {
            check(BV.equals(source.bvid()) && "bilibili".equals(source.source()) && source.mediaUri() == null,
                    "Unexpected live source identity: " + source);
            log("LIVE_SOURCE page=" + source.page() + " id=" + source.id() + " duration=" + source.durationSeconds());
        }
        check(p1.durationSeconds() >= 446 && p2.durationSeconds() >= 4420, "Live metadata does not cover requested ranges");
        List<Track> clips = List.of(
                Track.segment(p1, "p1 110-446", p1.artist(), 110000, 446000),
                Track.segment(p2, "p2 260-525", p2.artist(), 260000, 525000),
                Track.segment(p2, "p2 4025-4420", p2.artist(), 4025000, 4420000));
        for (boolean fade : List.of(false, true)) {
            fx(() -> {
                setOption("audio-short-fade-enabled", fade);
                check(field(app, "shortFadeEnabled", Boolean.class) == fade, "Short fade preference did not apply");
                queue().select(List.of(clips.get(0), clips.get(1), clips.get(2), p2), 0);
                queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
                invoke(app, "loadCurrent", new Class<?>[] { boolean.class }, true);
            });
            for (int index = 0; index < clips.size(); index++) {
                Track clip = clips.get(index);
                phase = "fade_" + fade + "_start_p" + clip.page() + "_" + clip.segmentStartMillis();
                if (index > 0) fx(() -> button("next-button").fire());
                await(35000, () -> fxValue(() -> audible(clip)), "native source offset and nonzero volume " + clip.title());
                fx(() -> {
                    check(Math.abs(relative() - (nativeSeconds() - clip.segmentStartMillis() / 1000.0)) < .8,
                            "Relative clock disagrees with native source offset");
                    check(Math.abs(duration() - clip.durationSeconds()) < .2, "Visible duration is not clip duration");
                    log("AUDIBLE_START fade=" + fade + " clip=" + clip.title() + " native=" + nativeSeconds()
                            + " volume=" + nativePlayer().audio().volume() + " envelope=" + field(app, "outputEnvelope", Double.class));
                });
                verifyPauseSeekResume(clip, fade);
            }
            phase = "fade_" + fade + "_whole_restore";
            fx(() -> button("next-button").fire());
            await(35000, () -> fxValue(() -> audible(p2) && nativeSeconds() > .1 && nativeSeconds() < 8), "whole source restores zero origin");
            fx(() -> {
                check(Math.abs(relative() - nativeSeconds()) < .8, "Whole playback inherited clip offset");
                check(duration() >= 4419.5 && Math.abs(duration() - audio().duration().toSeconds()) < .5,
                        "Whole playback inherited clip duration or disagrees with native duration");
            });
        }
        fx(() -> {
            try { check(disk().entryCount() == 0, "Full audio cache unexpectedly populated"); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        check(sampleFailure == null, "Sampling detected failure: " + sampleFailure);
        log("PASS LongSegmentPlaybackSmoke: live 2P; all 3 offsets with short fade off/on; native position and nonzero output; pause/resume; relative seek; manual next; whole restore; no full cache");
    }

    private void verifyPauseSeekResume(Track clip, boolean fade) throws Exception {
        phase = "fade_" + fade + "_pause_" + clip.segmentStartMillis();
        fx(() -> button("play-button").fire());
        await(4000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause");
        double paused = fxValue(this::nativeSeconds);
        Thread.sleep(400);
        check(fxValue(() -> Math.abs(nativeSeconds() - paused) < .7), "Native clock advanced while paused");
        phase = "fade_" + fade + "_relative_seek_" + clip.segmentStartMillis();
        double fraction = .04;
        double target = clip.segmentStartMillis() / 1000.0 + clip.durationSeconds() * fraction;
        fx(() -> invoke(app, "seekToFraction", new Class<?>[] { double.class }, fraction));
        await(10000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED
                && Math.abs(nativeSeconds() - target) < 1.5
                && Math.abs(relative() - clip.durationSeconds() * fraction) < 1.5), "paused relative seek");
        phase = "fade_" + fade + "_resume_" + clip.segmentStartMillis();
        double resumeAt = fxValue(this::nativeSeconds);
        fx(() -> button("play-button").fire());
        await(10000, () -> fxValue(() -> audible(clip) && nativeSeconds() > resumeAt + .15), "resume native clock and sound");
    }

    private boolean audible(Track expected) {
        if (queue().current() == null || !queue().current().id().equals(expected.id())
                || !(audio() instanceof VlcAudioPlayer) || nativePlayer() == null) return false;
        double nativeSeconds = nativeSeconds();
        return audio().state() == JavaFxAudioPlayer.State.PLAYING && nativePlayer().status().isPlaying()
                && !field(app, "segmentSeeking", Boolean.class)
                && nativePlayer().audio().volume() > 0 && !nativePlayer().audio().isMute()
                && field(app, "outputEnvelope", Double.class) > 0
                && (!expected.isSegment() || (nativeSeconds >= expected.segmentStartMillis() / 1000.0 - .2
                && nativeSeconds < expected.segmentEndMillis() / 1000.0))
                && field(app, "failureNotice", Label.class).getText().isBlank();
    }

    private void sample() {
        if (app == null) return;
        Track track = queue().current();
        MediaPlayer nativePlayer = nativePlayer();
        URI uri = audio() instanceof VlcAudioPlayer ? field(audio(), "currentUri", URI.class) : null;
        String notice = field(app, "failureNotice", Label.class).getText();
        rows.add((System.nanoTime() - started) / 1_000_000 + "," + phase + "," + queue().index() + ","
                + (track == null ? "" : track.page()) + "," + csv(track == null ? "" : track.id()) + ","
                + field(app, "shortFadeEnabled", Boolean.class) + "," + field(app, "normalizationEnabled", Boolean.class) + ","
                + field(app, "preloadEnabled", Boolean.class) + "," + csv(audio().normalizationStatus()) + "," + audio().state() + ","
                + (nativePlayer == null ? "" : nativePlayer.status().state()) + ","
                + (nativePlayer == null ? -1 : nativePlayer.status().time()) + "," + audio().position().toMillis() + ","
                + relative() + "," + duration() + "," + field(app, "playbackVolume", Double.class) + ","
                + field(app, "outputEnvelope", Double.class) + "," + (nativePlayer == null ? -1 : nativePlayer.audio().volume()) + ","
                + (nativePlayer != null && nativePlayer.audio().isMute()) + "," + field(app, "segmentSeeking", Boolean.class) + ","
                + field(app, "pendingResumeSeconds", Double.class) + "," + csv(String.valueOf(uri)) + "," + csv(notice));
        check(uri == null || "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()), "Full-file fallback is prohibited: " + uri);
        String status = field(app, "status", Label.class).getText();
        check(!status.contains("完整文件") && !status.contains("完整音频"), "Full-file preparation is prohibited: " + status);
        if (audio().state() == JavaFxAudioPlayer.State.PLAYING && track != null)
            check(audio() instanceof VlcAudioPlayer, "Playback must use real streaming VLC");
    }

    private void validateIsolation() throws Exception {
        root = Path.of(System.getenv("LUMEN_SMOKE_ROOT")).toRealPath();
        check(Files.isRegularFile(root.resolve("isolated-long-segment.marker")), "Missing isolated smoke marker");
        for (String key : List.of("LUMEN_LIBRARY_PATH", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(Path.of(System.getenv(key)).toAbsolutePath().normalize().getParent().equals(root), "Nonisolated " + key);
        check(Path.of(System.getenv("LOCALAPPDATA")).toRealPath().equals(root), "Nonisolated LOCALAPPDATA");
        String installed = System.getProperty("lumen.smoke.installed.jar");
        for (Class<?> production : List.of(LumenFxApp.class, VlcAudioPlayer.class, Track.class)) {
            Path actual = Path.of(production.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (installed != null) check(actual.equals(Path.of(installed).toRealPath()), "Wrong production origin: " + actual);
            log("PRODUCTION_ORIGIN " + production.getSimpleName() + " " + actual);
        }
    }

    private void await(int millis, BooleanSupplier condition, String description) throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (sampleFailure != null) throw new AssertionError("Sample failure in " + phase, sampleFailure);
            check(System.nanoTime() < until, "Timeout " + description + "; phase=" + phase + "; last="
                    + fxValue(() -> rows.isEmpty() ? "" : rows.get(rows.size() - 1)));
            Thread.sleep(100);
        }
    }

    private void setOption(String id, boolean selected) {
        CheckBox option = (CheckBox) stage.getScene().lookup("#" + id);
        check(option != null, "Missing setting: " + id);
        if (option.isSelected() != selected) option.fire();
    }
    private PlayerQueue queue() { return field(app, "queue", PlayerQueue.class); }
    private PersistentAudioCache disk() { return field(app, "persistentCache", PersistentAudioCache.class); }
    private AudioPlayer audio() { return field(app, "audio", AudioPlayer.class); }
    private MediaPlayer nativePlayer() { return audio() instanceof VlcAudioPlayer ? field(audio(), "player", MediaPlayer.class) : null; }
    private double nativeSeconds() { return nativePlayer() == null ? -1 : nativePlayer().status().time() / 1000.0; }
    private double relative() { return ((Duration) invoke(app, "playbackPosition", new Class<?>[0])).toSeconds(); }
    private double duration() { return ((Duration) invoke(app, "playbackDuration", new Class<?>[0])).toSeconds(); }
    private Button button(String id) { Button result = (Button) stage.getScene().lookup("#" + id); check(result != null, "Missing button: " + id); return result; }
    private static <T> T field(Object object, String name, Class<T> type) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object)); }
        catch (Exception error) { throw new AssertionError("Field " + name, error); }
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... arguments) {
        try { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, arguments); }
        catch (Exception error) { throw new AssertionError("Method " + name, error); }
    }
    private static <T> T fxValue(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> { try { future.complete(action.get()); } catch (Throwable error) { future.completeExceptionally(error); } });
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError("JavaFX action failed", error); }
    }
    private static void fx(Runnable action) { fxValue(() -> { action.run(); return null; }); }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""; }
    private static void log(String text) { System.out.println(text); System.out.flush(); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
