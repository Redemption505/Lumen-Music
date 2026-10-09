package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.xml.stream.XMLOutputFactory;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Two real desktop sessions against an isolated, deterministic local HTTP service. */
public final class Phase3DesktopSmoke extends Application {
    private static final String HINT = "\u60f3\u542c\u70b9\u5b89\u9759\u7684\u94a2\u7434";
    private static final String PLAY = "\u64ad\u653e";
    private static final String GENERATING = "\u751f\u6210\u4e2d";
    private static final String GENERATE = "\u751f\u6210\u63a8\u8350";
    private static final double INITIAL_POSITION = 2.4;
    private final DemoAudioLibrary demoAudio = new DemoAudioLibrary();
    private final AtomicInteger recommendationRequests = new AtomicInteger();
    private final AtomicInteger listeningRequests = new AtomicInteger();
    private final AtomicInteger lyricsRequests = new AtomicInteger();
    private final AtomicBoolean recommendationInFlight = new AtomicBoolean();
    private final AtomicReference<String> receivedHint = new AtomicReference<>();
    private final AtomicReference<Throwable> serviceFailure = new AtomicReference<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger timerIds = new AtomicInteger();
    private final AtomicInteger callbacksExecuted = new AtomicInteger();
    private final AtomicInteger fxHeartbeats = new AtomicInteger();
    private final Map<Integer, PauseTransition> pendingTimers = new ConcurrentHashMap<>();
    private final long startedNanos = System.nanoTime();
    private ExecutorService serviceThreads;
    private HttpServer service;
    private Path root;
    private LumenFxApp app;
    private Stage stage;
    private List<Track> savedTracks;
    private List<Track> freshTracks;
    private double pausedPosition;
    private int requestsBeforeRestart;
    private int listeningBeforeRestart;
    private volatile String phase = "initialization";

    @Override public void start(Stage firstStage) {
        enterPhase("Application.start entered");
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (!finished.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.SECONDS.toNanos(5))); }
                catch (InterruptedException interrupted) { return; }
                if (finished.get()) return;
                if (System.nanoTime() >= deadline) break;
                log("WATCHDOG phase=" + phase + ", FX heartbeats=" + fxHeartbeats.get()
                        + ", callbacks=" + callbacksExecuted.get() + ", pending timers=" + pendingTimers.keySet());
                Platform.runLater(fxHeartbeats::incrementAndGet);
            }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL: Phase 3 desktop smoke exceeded 45 seconds; phase=" + phase
                        + ", FX heartbeats=" + fxHeartbeats.get() + ", callbacks=" + callbacksExecuted.get()
                        + ", pending timers=" + pendingTimers.keySet());
                dumpThreads();
                System.exit(1);
            }
        }, "phase3-smoke-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            System.err.println("FAIL: Uncaught exception on " + thread.getName());
            dumpThreads();
            System.exit(1);
        });
        safely(() -> {
            enterPhase("validate isolated environment");
            root = isolatedRoot();
            log("Isolated root=" + root);
            enterPhase("generate real demo audio");
            savedTracks = demoTracks("Saved");
            freshTracks = demoTracks("Generated");
            enterPhase("write initial recommendation and playback snapshots");
            new RecommendationStore(root.resolve("recommendations.xml")).save(
                    new MusicClient.RecommendationResult(savedTracks, "saved-restore", "popular"));
            new PlaybackStateStore(root.resolve("playback-state.xml")).save(
                    new PlaybackStateStore.State(savedTracks, 1, PlayerQueue.RepeatMode.ONE,
                            INITIAL_POSITION));
            new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory()).save(savedTracks.get(1),
                    new LyricsSelectionStore.Selection("Cached second track", "Smoke Artist",
                            "[00:00.00]Cached opening\n[00:02.00]Cached middle\n[00:05.00]Cached ending",
                            0, LyricsSelectionStore.Origin.AUTOMATIC));
            enterPhase("start local HTTP service");
            startService();
            log("Local HTTP service listening at " + service.getAddress());
            enterPhase("construct first LumenFxApp");
            app = new LumenFxApp();
            stage = firstStage;
            enterPhase("first LumenFxApp.startUi");
            app.startUi(stage, List.of());
            enterPhase("verify first restored UI");
            verifyRestoredSession(savedTracks, INITIAL_POSITION);
            double idlePosition = audio().position().toSeconds();
            enterPhase("first session remains paused");
            after(2_400, () -> {
                verifyRestoredSession(savedTracks, INITIAL_POSITION);
                check(Math.abs(audio().position().toSeconds() - idlePosition) < .15,
                        "Restored media position advanced before play");
                check(listeningRequests.get() == 0, "Startup emitted listening samples");
                check(recommendationRequests.get() == 0, "Startup generated recommendations");
                check(lyricsRequests.get() == 0, "Startup requested lyrics");
                savePreview("phase3-home.png");
                startPlayback();
            });
        });
    }

    private Path isolatedRoot() throws IOException {
        String libraryPath = System.getenv("LUMEN_LIBRARY_PATH");
        check(libraryPath != null && !libraryPath.isBlank(),
                "LUMEN_LIBRARY_PATH must identify an isolated smoke-test library");
        Path library = Path.of(libraryPath).toAbsolutePath().normalize();
        Path directory = library.getParent();
        check(directory != null && directory.getParent() != null,
                "An isolated library parent directory is required");
        Files.createDirectories(directory);
        for (String file : List.of("recommendations.xml", "playback-state.xml"))
            check(!Files.exists(directory.resolve(file)), "Refusing to overwrite existing state: " + file);
        check(!Files.exists(library), "Refusing to use an existing library: " + library);
        check(!Files.exists(directory.resolve("lyrics-selections")),
                "Refusing to overwrite existing lyric selections");
        String preferencesPath = System.getenv("LUMEN_PREFERENCES_PATH");
        check(preferencesPath != null && !preferencesPath.isBlank(),
                "LUMEN_PREFERENCES_PATH must isolate the pet/theme preferences");
        Path preferences = Path.of(preferencesPath).toAbsolutePath().normalize();
        check(preferences.startsWith(directory) && !preferences.equals(directory),
                "LUMEN_PREFERENCES_PATH must be inside the isolated library directory");
        check(!Files.exists(preferences), "Refusing to overwrite existing preferences: " + preferences);
        return directory;
    }

    private List<Track> demoTracks(String prefix) throws IOException {
        return List.of(
                new Track("demo-1", prefix + " first track", "Smoke Artist", 8, "demo",
                        demoAudio.resolve("demo-1")),
                new Track("demo-2", prefix + " second track", "Smoke Artist", 8, "demo",
                        demoAudio.resolve("demo-2")),
                new Track("demo-3", prefix + " third track", "Smoke Artist", 8, "demo",
                        demoAudio.resolve("demo-3")));
    }

    private void startService() throws Exception {
        String configured = System.getenv("LUMEN_SERVICE_URL");
        check(configured != null && !configured.isBlank(), "LUMEN_SERVICE_URL must specify a free local port");
        URI base = URI.create(configured);
        check("http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0,
                "LUMEN_SERVICE_URL must be http://127.0.0.1:<free-port>");
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", base.getPort()), 0);
        serviceThreads = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "phase3-smoke-http");
            thread.setDaemon(true);
            return thread;
        });
        service.setExecutor(serviceThreads);
        service.createContext("/api/", this::respond);
        service.start();
    }

    private void respond(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            log("HTTP received " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String xml;
            switch (path) {
                case "/api/library" -> xml = "<library version=\"1\"><favorites/><playlists/></library>";
                case "/api/library/versions" -> {
                    check("GET".equals(exchange.getRequestMethod()), "Unexpected version mutation");
                    xml = "<catalog/>";
                }
                case "/api/library/home" -> {
                    check("GET".equals(exchange.getRequestMethod()), "Unexpected home mutation");
                    xml = "<home version=\"1\"><sections><section id=\"continue\"/><section id=\"pinned\"/>"
                            + "<section id=\"reunion\"/><section id=\"discover\"/></sections><pinned/></home>";
                }
                case "/api/library/organization" -> {
                    check("GET".equals(exchange.getRequestMethod()), "Unexpected organization mutation");
                    xml = "<organization version=\"1\"><tracks/><smartPlaylists/><folders/><playlistFolders/><results/></organization>";
                }
                case "/api/diary" -> {
                    check("GET".equals(exchange.getRequestMethod()), "Unexpected diary mutation");
                    xml = "<diary zone=\"Asia/Shanghai\"><summary totalPlays=\"0\" thisMonthPlays=\"0\""
                            + " sourceCount=\"0\" trackCount=\"0\" songCount=\"0\" unknownFirstCount=\"0\"/>"
                            + "<notes/><lyrics/></diary>";
                }
                case "/api/tracks" -> xml = tracksXml(savedTracks, "", "");
                case "/api/ai-settings" -> xml = "<ai-settings enabled=\"false\" keyConfigured=\"false\""
                        + " model=\"smoke\" baseUrl=\"http://127.0.0.1\"/>";
                case "/api/recommendations" -> {
                    recommendationRequests.incrementAndGet();
                    receivedHint.set(queryValue(exchange.getRequestURI().getRawQuery(), "hint"));
                    recommendationInFlight.set(true);
                    Thread.sleep(1_100);
                    xml = tracksXml(freshTracks, "quiet piano", "popular");
                }
                case "/api/listening" -> {
                    listeningRequests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    xml = "<listening accepted=\"true\"/>";
                }
                case "/api/lyrics" -> {
                    lyricsRequests.incrementAndGet();
                    xml = "<lyrics/>";
                }
                default -> throw new IllegalStateException("Unexpected smoke HTTP request: " + path);
            }
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            log("HTTP completed " + path);
        } catch (Throwable error) {
            serviceFailure.compareAndSet(null, error);
            error.printStackTrace(System.err);
        } finally {
            exchange.close();
            if ("/api/recommendations".equals(exchange.getRequestURI().getPath()))
                recommendationInFlight.set(false);
        }
    }

    private static String tracksXml(List<Track> tracks, String intents, String tasteSource) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
        xml.writeStartElement("tracks");
        xml.writeAttribute("intents", intents);
        xml.writeAttribute("tasteSource", tasteSource);
        for (Track track : tracks) {
            xml.writeEmptyElement("track");
            xml.writeAttribute("id", track.id());
            xml.writeAttribute("title", track.title());
            xml.writeAttribute("artist", track.artist());
            xml.writeAttribute("durationSeconds", Integer.toString(track.durationSeconds()));
            xml.writeAttribute("source", track.source());
        }
        xml.writeEndElement();
        xml.close();
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String queryValue(String query, String name) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name))
                return pair.length < 2 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    private void verifyRestoredSession(List<Track> expectedRecommendations, double expectedPosition) {
        checkTracks(list("recommendation-list").getItems(), expectedRecommendations,
                "Restored recommendation list");
        PlayerQueue queue = field("queue", PlayerQueue.class);
        checkTracks(queue.tracks(), savedTracks, "Restored queue");
        check(list("queue-list").getItems().size() == 3, "Queue UI did not restore three tracks");
        check(queue.index() == 1 && queue.current().id().equals("demo-2"), "Wrong restored current track");
        check(queue.repeatMode() == PlayerQueue.RepeatMode.ONE, "Repeat ONE was not restored");
        check(label("now-playing").getText().contains(savedTracks.get(1).title()),
                "Restored title does not show the second track");
        check(PLAY.equals(button("play-button").getText()) && !button("play-button").isDisabled(),
                "Restored playback control is not ready to play");
        check(audio().state() != JavaFxAudioPlayer.State.PLAYING
                && audio().state() != JavaFxAudioPlayer.State.STALLED,
                "Restored session started playback automatically");
        Double restored = field("restoredPositionSeconds", Double.class);
        check(restored != null && Math.abs(restored - expectedPosition) < .2,
                "Saved resume position was not restored: " + restored);
    }

    private void startPlayback() {
        enterPhase("play and resume saved position");
        button("play-button").fire();
        log("Play fired; audio=" + audio().state() + ", position=" + audio().position().toSeconds());
        check(list("lyrics-list").getItems().size() == 3, "Cached automatic lyrics were not displayed");
        check(label("lyrics-status").getText().contains("Cached second track"),
                "Cached lyric status was not restored");
        waitFor(7_000, () -> audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() >= INITIAL_POSITION - .3, () -> {
            double position = audio().position().toSeconds();
            check(position < INITIAL_POSITION + 1.2,
                    "Playback did not resume near 2.4 seconds: " + position);
            check(Math.abs(audio().duration().toSeconds() - 8) < .3, "Demo audio is not eight seconds");
            check(lyricsRequests.get() == 0, "Playback requested lyrics despite valid local cache");
            after(350, () -> {
                check(audio().position().toSeconds() > position + .1, "Actual media did not advance");
                button("play-button").fire();
                enterPhase("pause playback");
                waitFor(3_000, () -> audio().state() == JavaFxAudioPlayer.State.PAUSED, () -> {
                    pausedPosition = audio().position().toSeconds();
                    check(pausedPosition >= INITIAL_POSITION - .3 && pausedPosition < 6,
                            "Unexpected paused position: " + pausedPosition);
                    generateRecommendations();
                });
            });
        });
    }

    private void generateRecommendations() {
        enterPhase("generate hinted recommendations");
        node("home-query", TextField.class).setText(HINT);
        Button generate = button("home-search-button");
        generate.fire();
        check(generate.isDisabled() && generate.getText().startsWith(GENERATING),
                "Generate control did not immediately show a disabled busy state");
        check(button("refresh-recommendations").isDisabled(), "Refresh is enabled during generation");
        checkTracks(list("recommendation-list").getItems(), savedTracks,
                "Recommendation list was cleared when generation began");
        waitFor(3_000, recommendationInFlight::get, () -> after(350, () -> {
            check(recommendationInFlight.get(), "Recommendation service did not retain its simulated delay");
            check(generate.isDisabled(), "Generate control enabled before the HTTP response");
            checkTracks(list("recommendation-list").getItems(), savedTracks,
                    "Old recommendations were replaced during HTTP delay");
            waitFor(5_000, () -> !generate.isDisabled(), () -> {
                check(GENERATE.equals(generate.getText()), "Generate control text was not restored");
                check(!button("refresh-recommendations").isDisabled(), "Refresh was not re-enabled");
                check(recommendationRequests.get() == 1, "Expected exactly one recommendation HTTP request");
                check(HINT.equals(receivedHint.get()), "HTTP hint does not match the entered Chinese intent");
                checkTracks(list("recommendation-list").getItems(), freshTracks, "Generated results");
                var persisted = new RecommendationStore(root.resolve("recommendations.xml")).read()
                        .orElseThrow(() -> new AssertionError("Generated recommendation state was not saved"));
                checkTracks(persisted.tracks(), freshTracks, "Persisted generated results");
                check("popular".equals(persisted.tasteSource()), "Recommendation source was not saved");
                savePreview("phase3-home.png");
                verifySettings();
            });
        }));
    }

    private void verifySettings() {
        enterPhase("pet and compact settings");
        TabPane tabs = node("main-tabs", TabPane.class);
        check(!"settings-page".equals(tabs.getSelectionModel().getSelectedItem().getContent().getId()),
                "Settings page should initially be closed");
        button("sidebar-settings").fire();
        after(350, () -> {
            layout();
            check("settings-page".equals(tabs.getSelectionModel().getSelectedItem().getContent().getId()),
                    "Settings entry did not open the settings page");
            checkInScene(node("settings-page", Node.class));
            verifySettingsPage();
        });
    }

    private void verifySettingsPage() {
        @SuppressWarnings("unchecked")
        ComboBox<PetCompanion.Species> picker = (ComboBox<PetCompanion.Species>) node("pet-picker", ComboBox.class);
        Node pet = node("pet-companion", Node.class);
        picker.setValue(PetCompanion.Species.HIDDEN);
        check(!pet.isVisible() && !pet.isManaged(), "Hidden pet remains visible or managed");
        picker.setValue(PetCompanion.Species.BLACK_CAT);
        check(pet.isVisible() && pet.isManaged(), "Visible pet was not restored after HIDDEN");
        TitledPane ai = node("settings-ai", TitledPane.class);
        TitledPane data = node("settings-data", TitledPane.class);
        check(!ai.isExpanded() && !data.isExpanded(), "AI/data sections should initially be collapsed");
        Node background = node("choose-background", Node.class);
        Button theme = button("theme-button");
        for (int index = 0; index < ThemeController.Mode.values().length; index++) {
            ThemeController.Mode mode = field("themeMode", ThemeController.Mode.class);
            boolean expected = mode == ThemeController.Mode.BACKGROUND;
            check(background.isVisible() == expected && background.isManaged() == expected,
                    "Background controls have wrong visibility in " + mode);
            theme.fire();
        }
        ai.setExpanded(true);
        data.setExpanded(true);
        layout();
        check(button("ai-settings-button") != null && button("export-backup") != null
                && button("restore-backup") != null, "AI or data settings entry is missing");
        stage.setWidth(860);
        stage.setHeight(600);
        after(350, () -> {
            layout();
            ScrollPane sidebar = node("sidebar-scroll", ScrollPane.class);
            sidebar.setVvalue(1);
            ScrollPane settings = node("settings-scroll", ScrollPane.class);
            double excess = settings.getContent().getLayoutBounds().getHeight()
                    - settings.getViewportBounds().getHeight();
            if (excess > 0) settings.setVvalue(Math.max(0, Math.min(1,
                    (ai.getBoundsInParent().getMinY() - 8) / excess)));
            layout();
            log("Compact settings page scrolled to AI/data; vvalue=" + settings.getVvalue());
            check(Math.abs(stage.getWidth() - 860) < 1 && Math.abs(stage.getHeight() - 600) < 1,
                    "Compact settings window is not 860 by 600");
            checkInScene(node("sidebar-settings", Node.class));
            checkInScene(node("settings-scroll", Node.class));
            checkInScene(button("ai-settings-button"));
            checkInScene(button("export-backup"));
            checkInScene(button("restore-backup"));
            checkInScene(button("play-button"));
            savePreview("phase3-settings-860x600.png");
            restart();
        });
    }

    private void restart() throws Exception {
        enterPhase("stop first session and save playback");
        app.stop();
        log("First app.stop completed");
        stage.close();
        var saved = new PlaybackStateStore(root.resolve("playback-state.xml")).read()
                .orElseThrow(() -> new AssertionError("Playback snapshot was not saved"));
        check(saved.index() == 1 && saved.repeatMode() == PlayerQueue.RepeatMode.ONE,
                "Current index or repeat mode was not saved");
        checkTracks(saved.tracks(), savedTracks, "Saved playback queue");
        check(Math.abs(saved.positionSeconds() - pausedPosition) < .25,
                "Stopped session saved a different paused position: " + saved.positionSeconds());
        pausedPosition = saved.positionSeconds();
        requestsBeforeRestart = recommendationRequests.get();
        listeningBeforeRestart = listeningRequests.get();
        check(listeningBeforeRestart > 0, "Real playback did not emit any listening observations");
        enterPhase("construct second LumenFxApp");
        app = new LumenFxApp();
        stage = new Stage();
        enterPhase("second LumenFxApp.startUi");
        app.startUi(stage, List.of());
        enterPhase("verify second restored UI");
        verifyRestoredSession(freshTracks, pausedPosition);
        double idlePosition = audio().position().toSeconds();
        enterPhase("second session remains paused");
        after(2_400, () -> {
            verifyRestoredSession(freshTracks, pausedPosition);
            check(Math.abs(audio().position().toSeconds() - idlePosition) < .15,
                    "Second session advanced media without playback");
            check(recommendationRequests.get() == requestsBeforeRestart,
                    "Second session regenerated saved recommendations");
            check(listeningRequests.get() == listeningBeforeRestart,
                    "Second session emitted listening observations without playback");
            check(lyricsRequests.get() == 0, "Unexpected lyric HTTP request");
            succeed();
        });
    }

    private void checkInScene(Node control) {
        for (Node ancestor = control; ancestor != null; ancestor = ancestor.getParent())
            check(ancestor.isVisible() && ancestor.getOpacity() >= .99,
                    "Compact window hides control " + control.getId());
        var bounds = control.localToScene(control.getBoundsInLocal());
        check(bounds.getWidth() > 0 && bounds.getHeight() > 0 && bounds.getMinX() >= -.5
                && bounds.getMinY() >= -.5 && bounds.getMaxX() <= stage.getScene().getWidth() + .5
                && bounds.getMaxY() <= stage.getScene().getHeight() + .5,
                "Compact window clips control " + control.getId() + ": " + bounds);
    }

    private void savePreview(String name) throws IOException {
        log("Screenshot begin: " + name);
        layout();
        log("Screenshot layout completed: " + name);
        WritableImage image = stage.getScene().snapshot(null);
        log("Scene snapshot completed: " + name);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        var pixels = image.getPixelReader();
        for (int y = 0; y < output.getHeight(); y++)
            for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, pixels.getArgb(x, y));
        Path file = root.resolve(name);
        check(ImageIO.write(output, "png", file.toFile()), "PNG image writer is unavailable");
        check(Files.size(file) > 1_000, "Screenshot is empty: " + file);
        System.out.println("SCREENSHOT: " + file);
    }

    private void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
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
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Unable to inspect " + name, error);
        }
    }

    private static void checkTracks(List<?> actual, List<Track> expected, String context) {
        check(actual.size() == expected.size(), context + ": wrong count " + actual.size());
        for (int index = 0; index < expected.size(); index++) {
            check(actual.get(index) instanceof Track, context + ": item is not a track");
            Track track = (Track) actual.get(index);
            Track target = expected.get(index);
            check(track.id().equals(target.id()) && track.title().equals(target.title())
                    && track.source().equals(target.source()), context + ": wrong item at " + index);
        }
    }

    private void waitFor(int milliseconds, BooleanSupplier condition, CheckedAction action) {
        long deadline = System.nanoTime() + milliseconds * 1_000_000L;
        poll(deadline, condition, action);
    }

    private void poll(long deadline, BooleanSupplier condition, CheckedAction action) {
        safely(() -> {
            if (condition.getAsBoolean()) action.run();
            else {
                check(System.nanoTime() < deadline, "Timed out waiting for " + phase
                        + "; audio=" + audio().state() + "; status=" + label("status").getText());
                after(75, () -> poll(deadline, condition, action));
            }
        });
    }

    private void after(int milliseconds, CheckedAction action) {
        int id = timerIds.incrementAndGet();
        String scheduledPhase = phase;
        PauseTransition timer = new PauseTransition(Duration.millis(milliseconds));
        pendingTimers.put(id, timer);
        log("Timer " + id + " scheduled: " + milliseconds + "ms; phase=" + scheduledPhase);
        timer.setOnFinished(event -> {
            pendingTimers.remove(id);
            callbacksExecuted.incrementAndGet();
            log("Timer " + id + " fired; phase=" + scheduledPhase);
            safely(action);
        });
        timer.play();
    }

    private void safely(CheckedAction action) {
        if (finished.get()) return;
        try {
            Throwable failure = serviceFailure.get();
            if (failure != null) throw new AssertionError("Local HTTP service failed", failure);
            action.run();
        } catch (Throwable error) { fail(error); }
    }

    private void succeed() {
        enterPhase("stop second session and complete");
        app.stop();
        stage.close();
        app = null;
        Throwable failure = serviceFailure.get();
        if (failure != null) throw new AssertionError("Local HTTP service failed", failure);
        cleanup();
        finished.set(true);
        System.out.println("PASS: Phase 3 real JavaFX restore/resume, cached lyrics, hinted recommendations,"
                + " pet visibility, compact settings, and second-session persistence");
        System.out.println("HTTP: recommendation requests=" + recommendationRequests.get()
                + ", hint=" + receivedHint.get() + ", lyric requests=" + lyricsRequests.get());
        Platform.exit();
    }

    private void fail(Throwable error) {
        if (!finished.compareAndSet(false, true)) return;
        System.err.println("FAIL: " + phase + ": " + error);
        error.printStackTrace(System.err);
        dumpThreads();
        try { if (app != null) app.stop(); }
        catch (Throwable cleanupError) { cleanupError.printStackTrace(System.err); }
        cleanup();
        System.exit(1);
    }

    private void cleanup() {
        for (PauseTransition timer : pendingTimers.values()) timer.stop();
        pendingTimers.clear();
        if (service != null) service.stop(0);
        if (serviceThreads != null) serviceThreads.shutdownNow();
        demoAudio.close();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void enterPhase(String nextPhase) {
        phase = nextPhase;
        log("PHASE: " + nextPhase);
    }

    private void log(String message) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        System.out.println("SMOKE +" + elapsed + "ms [" + Thread.currentThread().getName() + "] " + message);
        System.out.flush();
    }

    private static void dumpThreads() {
        System.err.println("THREAD DUMP BEGIN");
        var entries = new ArrayList<>(Thread.getAllStackTraces().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().getName()));
        for (var entry : entries) {
            Thread thread = entry.getKey();
            System.err.println("\"" + thread.getName() + "\" id=" + thread.getId()
                    + " state=" + thread.getState() + " daemon=" + thread.isDaemon());
            for (StackTraceElement frame : entry.getValue()) System.err.println("    at " + frame);
        }
        System.err.println("THREAD DUMP END");
        System.err.flush();
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    public static void main(String[] args) { launch(args); }
}
