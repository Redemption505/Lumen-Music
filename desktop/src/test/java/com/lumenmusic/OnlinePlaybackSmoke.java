package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Opt-in installed-application acceptance against real Python, Axum and Bilibili. */
public final class OnlinePlaybackSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private final List<PauseTransition> timers = new ArrayList<>();
    private final long startedNanos = System.nanoTime();
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private Track first;
    private Track second;
    private volatile String phase = "initialization";

    @Override public void start(Stage stage) {
        this.stage = stage;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(90_000); }
            catch (InterruptedException interrupted) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL: installed online playback exceeded 90 seconds; phase=" + phase);
                System.err.flush();
                System.exit(1);
            }
        }, "installed-online-smoke-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        safely(() -> {
            validateIsolation();
            String installed = System.getProperty("lumen.smoke.installed.jar", "");
            check(!installed.isBlank(), "Missing installed production JAR identity");
            Path expected = Path.of(installed).toRealPath();
            Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toRealPath();
            check(actual.equals(expected), "LumenFxApp loaded from " + actual + ", expected " + expected);
            log("PRODUCTION_ORIGIN: " + actual);
            check(!System.getProperty("lumen.stream.base", "").isBlank(), "Missing external Axum stream URL");
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            check(field("streamingAudio", VlcAudioPlayer.class) != null,
                    "Installed VLC runtime is unavailable");
            enter("load isolated empty library");
            waitFor(6_000, () -> label("library-status").getText().equals("\u4e2a\u4eba\u66f2\u5e93\u5df2\u8f7d\u5165"), () -> {
                check(field("favorites", List.class).isEmpty(), "Isolated favorites must start empty");
                search(bvid("LUMEN_SMOKE_BV_PRIMARY", "BV1BZbSzZEGT"), track -> {
                    first = track;
                    doubleClick(list("search-list"), track.id());
                    observePlayback("search double-click", first, () -> pauseAndResume());
                });
            });
        });
    }

    private void validateIsolation() throws Exception {
        String configured = System.getenv("LUMEN_SMOKE_ROOT");
        check(configured != null && !configured.isBlank(), "LUMEN_SMOKE_ROOT is required");
        root = Path.of(configured).toRealPath();
        check(Files.isRegularFile(root.resolve("isolated-online-smoke.marker")),
                "Missing dedicated isolation marker");
        for (String name : List.of("LUMEN_LIBRARY_PATH", "LUMEN_HISTORY_PATH",
                "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            String value = System.getenv(name);
            check(value != null && !value.isBlank(), name + " is required");
            Path path = Path.of(value).toAbsolutePath().normalize();
            check(path.getParent().equals(root), name + " must be directly inside the isolated root");
        }
        for (String name : List.of("LUMEN_AI_API_KEY", "LUMEN_AI_CHAT_COMPLETIONS_URL", "LUMEN_AI_MODEL"))
            check(System.getenv(name) == null || System.getenv(name).isBlank(), name + " must be cleared");
        for (String file : List.of("preferences.properties", "recommendations.xml", "playback-state.xml"))
            check(!Files.exists(root.resolve(file)), "Refusing existing desktop state: " + file);
        for (String name : List.of("LUMEN_SERVICE_URL", "LUMEN_STREAM_URL")) {
            URI uri = URI.create(System.getenv(name));
            check(uri.getScheme().equals("http") && uri.getHost().equals("127.0.0.1") && uri.getPort() > 0,
                    name + " must identify an isolated loopback port");
        }
        log("ISOLATED_ROOT: " + root);
    }

    private void search(String bvid, TrackAction next) {
        enter("public link search " + bvid);
        button("nav-search").fire();
        node("search-query", TextField.class).setText("https://www.bilibili.com/video/" + bvid + "/");
        button("search-button").fire();
        waitFor(20_000, () -> tracks("search-list").stream()
                .anyMatch(track -> bvid.equals(track.bvid()) && "bilibili".equals(track.source())), () -> {
            Track track = tracks("search-list").stream().filter(item -> bvid.equals(item.bvid()))
                    .findFirst().orElseThrow();
            check(track.page() >= 1 && track.mediaUri() == null, "Expected real online BV identity: " + track);
            log("SEARCH: bvid=" + track.bvid() + " page=" + track.page() + " id=" + track.id()
                    + " title=" + track.title());
            next.run(track);
        });
    }

    private void observePlayback(String context, Track expected, CheckedAction next) {
        enter(context + " real VLC progress " + expected.bvid());
        waitFor(25_000, () -> audio() instanceof VlcAudioPlayer
                && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && currentIs(expected) && audio().position().toSeconds() > 0, () -> {
            double before = audio().position().toSeconds();
            check(Double.isFinite(audio().duration().toSeconds()) && audio().duration().toSeconds() > 5,
                    "Real audio duration is invalid: " + audio().duration());
            waitFor(8_000, () -> audio().state() == JavaFxAudioPlayer.State.PLAYING
                    && currentIs(expected) && audio().position().toSeconds() > before + 2.0, () -> {
                double after = audio().position().toSeconds();
                log("PLAYBACK: context=" + context + " bvid=" + expected.bvid() + " before=" + before
                        + " after=" + after + " delta=" + (after - before)
                        + " duration=" + audio().duration().toSeconds());
                next.run();
            });
        });
    }

    private void pauseAndResume() {
        enter("pause real playback");
        button("play-button").fire();
        waitFor(3_000, () -> audio().state() == JavaFxAudioPlayer.State.PAUSED, () -> {
            double before = audio().position().toSeconds();
            after(1_000, () -> {
                double after = audio().position().toSeconds();
                check(audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(after - before) < .5,
                        "Paused VLC position moved: " + before + " -> " + after);
                log("PAUSE: before=" + before + " after=" + after);
                button("play-button").fire();
                observePlayback("resume", first, this::favoriteAndSwitch);
            });
        });
    }

    private void favoriteAndSwitch() {
        enter("favorite first real track through current-track control");
        button("favorite-current").fire();
        waitFor(8_000, () -> field("favorites", List.class).stream()
                .anyMatch(item -> item instanceof Track track && track.id().equals(first.id())), () -> {
            check(Files.isRegularFile(Path.of(System.getenv("LUMEN_LIBRARY_PATH"))),
                    "Isolated library was not persisted");
            log("FAVORITE: persisted id=" + first.id());
            search(bvid("LUMEN_SMOKE_BV_SECONDARY", "BV1hRNe6wEzV"), track -> {
                second = track;
                check(!second.id().equals(first.id()), "Secondary track must differ from primary");
                doubleClick(list("search-list"), second.id());
                observePlayback("second track switch", second, this::playFavorite);
            });
        });
    }

    private void playFavorite() {
        enter("favorite list double-click");
        button("nav-favorites").fire();
        ListView<?> favorites = list("favorites-list");
        check(favorites.getItems().size() == 1, "Expected exactly one isolated favorite");
        doubleClick(favorites, first.id());
        observePlayback("favorite double-click", first, this::succeed);
    }

    private void doubleClick(ListView<?> list, String id) {
        int index = -1;
        for (int i = 0; i < list.getItems().size(); i++)
            if (list.getItems().get(i) instanceof Track track && track.id().equals(id)) { index = i; break; }
        check(index >= 0, "Track not present in " + list.getId() + ": " + id);
        list.getSelectionModel().select(index);
        Event.fireEvent(list, new MouseEvent(MouseEvent.MOUSE_CLICKED, 10, 10, 10, 10,
                MouseButton.PRIMARY, 2, false, false, false, false,
                false, false, false, false, false, true, null));
        log("DOUBLE_CLICK: list=" + list.getId() + " id=" + id);
    }

    private boolean currentIs(Track expected) {
        Track current = field("queue", PlayerQueue.class).current();
        return current != null && current.id().equals(expected.id());
    }

    private List<Track> tracks(String id) {
        return list(id).getItems().stream().filter(Track.class::isInstance).map(Track.class::cast).toList();
    }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private Button button(String id) { return node(id, Button.class); }
    private Label label(String id) { return node(id, Label.class); }
    private ListView<?> list(String id) { return node(id, ListView.class); }

    private <T extends Node> T node(String id, Class<T> type) {
        Node node = stage.getScene().lookup("#" + id);
        check(type.isInstance(node), "Missing " + type.getSimpleName() + " #" + id);
        return type.cast(node);
    }
    private <T> T field(String name, Class<T> type) {
        try {
            Field field = LumenFxApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(app));
        } catch (ReflectiveOperationException error) { throw new AssertionError("Unable to inspect " + name, error); }
    }

    private void waitFor(int milliseconds, BooleanSupplier condition, CheckedAction next) {
        poll(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds), condition, next);
    }
    private void poll(long deadline, BooleanSupplier condition, CheckedAction next) {
        safely(() -> {
            if (condition.getAsBoolean()) next.run();
            else {
                check(System.nanoTime() < deadline, "Timed out at " + phase + "; audio=" + audio().state()
                        + "; position=" + audio().position().toSeconds() + "; status=" + label("status").getText()
                        + "; search=" + label("search-status").getText());
                after(100, () -> poll(deadline, condition, next));
            }
        });
    }
    private void after(int milliseconds, CheckedAction action) {
        PauseTransition timer = new PauseTransition(Duration.millis(milliseconds));
        timers.add(timer);
        timer.setOnFinished(event -> {
            timers.remove(timer);
            safely(action);
        });
        timer.play();
    }
    private void safely(CheckedAction action) {
        if (finished.get()) return;
        try { action.run(); }
        catch (Throwable error) { fail(error); }
    }
    private void succeed() throws Exception {
        app.stop();
        stage.close();
        for (PauseTransition timer : timers) timer.stop();
        finished.set(true);
        System.out.println("PASS: installed online JavaFX public-link search, search double-click, VLC progress >2s,"
                + " pause/resume, second-track switch, isolated favorite persistence and favorite double-click");
        System.out.flush();
        Platform.exit();
    }
    private void fail(Throwable error) {
        if (!finished.compareAndSet(false, true)) return;
        System.err.println("FAIL: " + phase + ": " + error);
        error.printStackTrace(System.err);
        try { if (app != null) app.stop(); }
        catch (Throwable cleanupError) { cleanupError.printStackTrace(System.err); }
        System.err.flush();
        System.exit(1);
    }
    private void enter(String next) { phase = next; log("PHASE: " + next); }
    private void log(String message) {
        System.out.println("ONLINE +" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) + "ms " + message);
        System.out.flush();
    }
    private static String bvid(String variable, String fallback) {
        String value = System.getenv().getOrDefault(variable, fallback);
        check(value.matches("BV[0-9A-Za-z]{10}"), "Invalid smoke BV identity: " + variable);
        return value;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    @FunctionalInterface private interface TrackAction { void run(Track track) throws Exception; }
    public static void main(String[] args) { launch(args); }
}
