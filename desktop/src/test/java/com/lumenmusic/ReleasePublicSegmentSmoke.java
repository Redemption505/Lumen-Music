package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.VlcAudioPlayer;
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
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Public Bilibili source offsets and natural segment boundaries, with isolated lyric fixtures. */
public final class ReleasePublicSegmentSmoke extends Application {
    private final List<String> samples = new ArrayList<>(List.of("nanos,phase,index,loadVersion,state,physicalSeconds,relativeSeconds,visibleDurationSeconds"));
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private volatile String phase = "startup";
    private volatile boolean finished;
    private Track source, first, second;
    private double firstMaximum, secondMaximum;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(190_000); } catch (InterruptedException ignored) { return; }
            if (!finished) { System.err.println("FAIL public segment watchdog: " + phase); System.exit(1); }
        }, "public-segment-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int result = 0;
            try { exercise(); }
            catch (Throwable error) { result = 1; System.err.println("FAIL public segment phase: " + phase); error.printStackTrace(System.err); }
            finally {
                try { if (root != null) Files.write(root.resolve("public-segment-native.csv"), samples); }
                catch (Throwable error) { result = 1; error.printStackTrace(System.err); }
                try { fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); }); }
                catch (Throwable error) { result = 1; error.printStackTrace(System.err); }
                finished = true;
                System.out.flush(); System.err.flush(); Platform.exit();
                if (result != 0) System.exit(result);
            }
        }, "public-segment-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        validateIsolation();
        value(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); return null; }, 30);
        await(10000, () -> value(() -> label("library-status").getText().equals("\u4e2a\u4eba\u66f2\u5e93\u5df2\u8f7d\u5165")), "empty library readiness");
        String bvid = System.getenv("LUMEN_SMOKE_BV_PRIMARY");
        check(bvid != null && bvid.matches("BV[0-9A-Za-z]{10}"), "missing public BV");
        phase = "live public BV search";
        fx(() -> {
            button("nav-search").fire();
            ((TextField) stage.getScene().lookup("#search-query")).setText("https://www.bilibili.com/video/" + bvid + "/");
            button("search-button").fire();
        });
        await(25000, () -> value(() -> searchTracks().stream().anyMatch(track -> bvid.equals(track.bvid()))), "public source search");
        source = value(() -> searchTracks().stream().filter(track -> bvid.equals(track.bvid())).findFirst().orElseThrow());
        check("bilibili".equals(source.source()) && source.mediaUri() == null && source.page() > 0 && source.durationSeconds() > 50,
                "live source identity/duration is invalid: " + source);
        first = Track.segment(source, "Public excerpt 17-23", source.artist(), 17000, 23000);
        second = Track.segment(source, "Public excerpt 35-41", source.artist(), 35000, 41000);
        LyricsSelectionStore lyrics = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory());
        lyrics.save(source, new LyricsSelectionStore.Selection(source.title(), source.artist(), "[00:00.00]public-whole-only\n[01:00.00]whole-later", 0));
        lyrics.save(first, new LyricsSelectionStore.Selection(first.title(), first.artist(), "[00:00.00]public-first-only\n[00:05.00]first-end", 0));
        lyrics.save(second, new LyricsSelectionStore.Selection(second.title(), second.artist(), "[00:00.00]public-second-only\n[00:05.00]second-end", 0));
        log("PUBLIC_SOURCE id=" + source.id() + " bvid=" + source.bvid() + " duration=" + source.durationSeconds());
        fx(() -> {
            try { disk().configure(true, 64L * 1024 * 1024); check(disk().entryCount() == 0, "profile already contains audio cache"); }
            catch (Exception error) { throw new AssertionError(error); }
            queue().select(List.of(first, second, source), 0);
            queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] { boolean.class }, true);
        });
        phase = "uncached public first excerpt physical source offset";
        await(35000, () -> value(() -> playing(first) && physical() >= 17.15 && physical() < 21), "public offset 17 seconds");
        fx(() -> {
            verifyTimeline(first, 17);
            check("public-first-only".equals(label("current-lyric").getText()), "first excerpt did not use independent relative lyric clock");
            log("FIRST_START physical=" + physical() + " relative=" + relative() + " visibleDuration=" + visibleDuration() + " nativeUri=" + nativeUri());
            button("play-button").fire();
        });
        phase = "paused public excerpt relative seek";
        await(3000, () -> value(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause");
        fx(() -> {
            Slider slider = (Slider) stage.getScene().lookup("#progress-slider");
            slider.setValue(.55);
            slider.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.RIGHT, false, false, false, false));
        });
        await(10000, () -> value(() -> Math.abs(physical() - 20.3) < .8 && Math.abs(relative() - 3.3) < .8), "paused 55 percent relative seek");
        double paused = value(this::physical);
        Thread.sleep(1000);
        check(value(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(physical() - paused) < .5), "paused seek resumed or native clock advanced");
        long beforeEnd = value(() -> field("loadVersion", Long.class));
        log("PAUSED_SEEK physical=" + paused + " relative=" + value(this::relative));
        phase = "first natural range end advances once";
        fx(() -> button("play-button").fire());
        await(15000, () -> value(() -> queue().index() == 1 && playing(second) && physical() >= 35.15 && physical() < 38.5), "first endpoint and second source offset");
        fx(() -> {
            check(field("loadVersion", Long.class) == beforeEnd + 1, "first endpoint loaded next track more than once");
            verifyTimeline(second, 35);
            check("public-second-only".equals(label("current-lyric").getText()), "first lyrics leaked into second excerpt");
            log("SECOND_START physical=" + physical() + " relative=" + relative() + " loadVersion=" + field("loadVersion", Long.class));
        });
        phase = "second natural range end advances once to whole source";
        await(18000, () -> value(() -> queue().index() == 2 && playing(source) && physical() > .1 && physical() < 5
                && "public-whole-only".equals(label("current-lyric").getText())), "second endpoint and independent whole source");
        fx(() -> {
            check(field("loadVersion", Long.class) == beforeEnd + 2, "second endpoint advanced more than once");
            check(Math.abs(relative() - physical()) < .5, "whole source inherited an excerpt offset");
            check(visibleDuration() > 50, "whole source inherited six-second duration");
            log("WHOLE_RETURN physical=" + physical() + " relative=" + relative() + " visibleDuration=" + visibleDuration());
        });
        check(firstMaximum >= 22.3 && firstMaximum <= 23.6, "first native endpoint outside 23s boundary: " + firstMaximum);
        check(secondMaximum >= 40.3 && secondMaximum <= 41.6, "second native endpoint outside 41s boundary: " + secondMaximum);
        phase = "one complete public source cache shared by whole and both excerpts";
        await(45000, () -> value(() -> {
            try { return disk().lookup(source).isPresent(); } catch (Exception error) { throw new AssertionError(error); }
        }), "complete public source cache");
        URI cached = value(() -> {
            try {
                URI whole = disk().lookup(source).orElseThrow();
                check(disk().entryCount() == 1 && whole.equals(disk().lookup(first).orElseThrow())
                        && whole.equals(disk().lookup(second).orElseThrow()), "public excerpts did not share one full cache file");
                return whole;
            } catch (Exception error) { throw new AssertionError(error); }
        });
        check(Files.size(Path.of(cached)) > 1000, "complete public cache is empty");
        log("PUBLIC_SEGMENT_PASS firstMaximum=" + firstMaximum + " secondMaximum=" + secondMaximum + " cacheBytes=" + Files.size(Path.of(cached)));
        System.out.println("PASS: release public segment JavaFX live BV search/stream, physical 17-23 and 35-41 offsets, relative seek/pause, natural endpoints once, whole lyric isolation and shared complete source cache");
    }

    private void verifyTimeline(Track expected, double offset) {
        check(audio() instanceof VlcAudioPlayer && playing(expected), "public excerpt is not playing in real VLC");
        check(Math.abs(relative() - (physical() - offset)) < .65, "source/relative clock mapping is wrong");
        check(Math.abs(visibleDuration() - 6) < .15, "visible excerpt duration is not six seconds");
    }
    private void sample() {
        value(() -> {
            if (app == null || queue().current() == null) return null;
            double physical = physical();
            if (first != null && queue().current().id().equals(first.id())) firstMaximum = Math.max(firstMaximum, physical);
            if (second != null && queue().current().id().equals(second.id())) secondMaximum = Math.max(secondMaximum, physical);
            samples.add(System.nanoTime() + "," + phase.replace(' ', '_') + "," + queue().index() + "," + field("loadVersion", Long.class)
                    + "," + audio().state() + "," + physical + "," + relative() + "," + visibleDuration());
            return null;
        });
    }
    private void validateIsolation() throws Exception {
        root = Path.of(System.getenv("LUMEN_SMOKE_ROOT")).toRealPath();
        check(Files.isRegularFile(root.resolve("isolated-online-smoke.marker")), "missing isolation marker");
        for (String name : List.of("LUMEN_LIBRARY_PATH", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(Path.of(System.getenv(name)).toAbsolutePath().normalize().getParent().equals(root), "nonisolated " + name);
        check(Path.of(System.getenv("LOCALAPPDATA")).toRealPath().equals(root), "LOCALAPPDATA is not isolated");
        for (String name : List.of("LUMEN_AI_API_KEY", "OPENAI_API_KEY", "LUMEN_AI_CHAT_COMPLETIONS_URL", "LUMEN_AI_MODEL"))
            check(System.getenv(name) == null || System.getenv(name).isBlank(), "personal AI environment: " + name);
        Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(actual.equals(Path.of(System.getProperty("lumen.smoke.installed.jar")).toRealPath()), "wrong final JAR origin");
        log("PRODUCTION_ORIGIN " + actual);
    }
    private URI nativeUri() {
        try { Field uri = VlcAudioPlayer.class.getDeclaredField("currentUri"); uri.setAccessible(true); return (URI) uri.get(audio()); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private List<Track> searchTracks() { return ((ListView<?>) stage.getScene().lookup("#search-list")).getItems().stream().filter(Track.class::isInstance).map(Track.class::cast).toList(); }
    private boolean playing(Track expected) { return queue().current() != null && queue().current().id().equals(expected.id()) && audio().state() == JavaFxAudioPlayer.State.PLAYING; }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private PersistentAudioCache disk() { return field("persistentCache", PersistentAudioCache.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private double physical() { return audio().position().toSeconds(); }
    private double relative() { return ((Duration) invoke("playbackPosition", new Class<?>[0])).toSeconds(); }
    private double visibleDuration() { return ((Duration) invoke("playbackDuration", new Class<?>[0])).toSeconds(); }
    private Button button(String id) { Button node = (Button) stage.getScene().lookup("#" + id); check(node != null, "missing " + id); return node; }
    private Label label(String id) { Label node = (Label) stage.getScene().lookup("#" + id); check(node != null, "missing " + id); return node; }
    private <T> T field(String name, Class<T> type) {
        try { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app)); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private Object invoke(String name, Class<?>[] types, Object... arguments) {
        try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(app, arguments); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void await(int millis, BooleanSupplier condition, String context) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            sample();
            check(System.nanoTime() < deadline, "timeout " + context + "; status=" + value(() -> label("status").getText()) + "; search=" + value(() -> label("search-status").getText()));
            Thread.sleep(75);
        }
        sample();
    }
    private static <T> T value(Supplier<T> supplier) { return value(supplier, 10); }
    private static <T> T value(Supplier<T> supplier, int seconds) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> { try { future.complete(supplier.get()); } catch (Throwable error) { future.completeExceptionally(error); } });
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError("JavaFX action failed", error); }
    }
    private static void fx(Runnable action) { value(() -> { action.run(); return null; }); }
    private static void log(String message) { System.out.println(message); System.out.flush(); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) { launch(args); }
}
