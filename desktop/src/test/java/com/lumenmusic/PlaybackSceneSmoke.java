package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Real JavaFX playback-scene acceptance on a disposable profile and local WAV. */
public final class PlaybackSceneSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private HttpServer coverServer;
    private Track first;
    private Track second;
    private String playlistId;
    private AudioPlayer originalAudio;
    private PlayerQueue.Snapshot originalQueue;
    private long originalLoadVersion;
    private volatile long lastFxQueueNanos, maxFxQueueNanos;

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        stage = initialStage;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(285_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL playback scene watchdog: " + phase);
                System.exit(1);
            }
        }, "playback-scene-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                try { dumpArtworkFailure(); }
                catch (Exception diagnosticError) {
                    System.err.println("ARTWORK_DIAGNOSTIC_FAILED " + diagnosticError.getClass().getName());
                }
                dumpWindowFailure();
                System.err.println("FAIL playback scene phase: " + phase);
                error.printStackTrace(System.err);
                finish(1);
            }
        }, "playback-scene-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        prepareFixture();
        phase = "local WAV playback";
        fx(() -> {
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(1280);
            stage.setHeight(800);
        });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX())
                + " x " + fxValue(() -> stage.getOutputScaleY())
                + "; requested=" + System.getProperty("glass.win.uiScale", "system"));
        await(15_000, () -> fxValue(() -> picker().getItems().stream()
                .anyMatch(item -> playlistId.equals(item.id()))), "fixture playlist did not load");
        fx(() -> {
            tabs().getSelectionModel().select(4);
            picker().getSelectionModel().select(picker().getItems().stream()
                    .filter(item -> playlistId.equals(item.id())).findFirst().orElseThrow());
            ListView<Track> list = list("playlist-tracks");
            list.getSelectionModel().clearAndSelect(0);
            list.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8,
                    MouseButton.PRIMARY, 2, false, false, false, false,
                    true, false, false, true, false, false, null));
        });
        awaitPlaying(first.id());
        originalAudio = fxValue(this::audio);
        originalQueue = fxValue(() -> queue().snapshot());
        originalLoadVersion = fxValue(() -> field("loadVersion", Long.class));
        check(originalQueue.tracks().size() == 2, "fixture queue did not contain both tracks");
        System.out.println("PASS real local WAV playback");

        if (Boolean.getBoolean("lumen.scene.smoke.visualOnly")) {
            phase = "visual-only theme matrix";
            double position = fxValue(() -> audio().position().toSeconds());
            themeMatrix();
            fx(() -> assertPlaybackPreserved(position));
            System.out.println("PASS final visual matrix with uninterrupted playback");
            return;
        }

        phase = "scene traversal and fast queue toggles";
        double beforeTraversal = fxValue(() -> audio().position().toSeconds());
        fx(() -> {
            button("open-immersive").fire();
            check(node("immersive-shell").isVisible(), "immersive scene did not open");
            button("nav-focus").fire();
            check(node("focus-pane").isVisible(), "focus scene did not open");
            button("focus-back").fire();
            button("immersive-collapse").fire();
            check(tabs().getSelectionModel().getSelectedIndex() == 4,
                    "scene traversal did not return to playlist browse state");
            for (int i = 0; i < 12; i++) button("toggle-queue").fire();
        });
        pause(650);
        fx(() -> {
            check(!node("right-rail").isVisible(), "queue remained open after even toggles");
            check(stage.getScene().getRoot().lookupAll("#queue-exit-snapshot").isEmpty(),
                    "queue exit snapshot remained after settling");
            check(showingWindows() == 1, "scene traversal created duplicate windows");
            assertPlaybackPreserved(beforeTraversal);
        });
        for (int i = 0; i < 5; i++) {
            fx(() -> { button("open-immersive").fire(); button("immersive-collapse").fire(); });
            pause(350);
            fx(() -> {
                assertSettled(node("immersive-shell"));
                check(showingWindows() == 1, "immersive traversal created duplicate windows");
            });
        }
        fx(() -> assertPlaybackPreserved(beforeTraversal));
        fx(() -> button("open-immersive").fire());
        await(1_000, () -> fxValue(() -> field("artworkTransition", ArtworkTransition.class).active()),
                "normal scene switch did not start cover transition");
        await(2_000, () -> fxValue(() -> !field("artworkTransition", ArtworkTransition.class).active()),
                "cover transition did not finish");
        fx(() -> button("immersive-collapse").fire());
        System.out.println("PASS browse/immersive/focus continuity and queue animation cleanup");

        phase = "focus timers and sleep cancellation";
        focusChecks();
        System.out.println("PASS focus timer completion/cancellation and sleep cancellation/completion");

        phase = "lyric center and idle controls";
        fx(() -> {
            button("open-immersive").fire();
            invokeSetLyrics(Lyrics.parse("[00:00.00]第一句\n[00:20.00]第二句\n[00:40.00]第三句\n[01:00.00]第四句"));
            node("immersive-lyrics").requestFocus();
        });
        await(3_000, () -> fxValue(() -> ((Label) node("immersive-current-lyric"))
                .getText().contains("第一句")), "current lyric was not displayed");
        pause(650);
        fx(this::checkCurrentLyricCentered);
        pause(5_400);
        fx(() -> {
            check(node("immersive-actions").getOpacity() < .15,
                    "playing idle actions did not fade");
            check(node("immersive-calibration").getOpacity() < .15,
                    "playing idle lyric controls did not fade");
            stage.getScene().getRoot().fireEvent(new MouseEvent(MouseEvent.MOUSE_MOVED,
                    120, 120, 120, 120, MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, false, false, false, null));
        });
        await(2_000, () -> fxValue(() -> node("immersive-actions").getOpacity() > .9
                && node("immersive-calibration").getOpacity() > .9),
                "pointer movement did not reveal immersive controls");
        System.out.println("PASS current lyric centering and pointer-revealed controls");

        phase = "cover transition";
        Image previousCover = fxValue(() -> ((ImageView) node("immersive-artwork-image")).getImage());
        fx(() -> button("next-button").fire());
        awaitPlaying(second.id());
        await(4_000, () -> fxValue(() -> ((ImageView) node("immersive-artwork-image")).getImage()
                != previousCover), "cover did not change with the track");
        await(4_000, () -> fxValue(() -> stage.getScene().getRoot().lookupAll("#artwork-transition-image").isEmpty()),
                "cover transition overlay did not clear");
        fx(() -> check(showingWindows() == 1, "cover change created another window"));
        System.out.println("PASS cover change and transition cleanup");

        phase = "themes and sizes";
        themeMatrix();
        System.out.println("PASS all five themes at 860x600 and 1280x800");

        phase = "reduced motion";
        fx(() -> {
            CheckBox reduced = (CheckBox) node("reduce-motion");
            if (!reduced.isSelected()) reduced.fire();
            check(MotionPreferences.reduced(), "reduced motion preference did not activate");
            check(field("pet", PetCompanion.class).isReducedMotion(),
                    "pet did not adopt reduced motion");
            button("open-immersive").fire();
            button("immersive-collapse").fire();
            button("toggle-queue").fire();
            button("toggle-queue").fire();
            check(stage.getScene().getRoot().lookupAll("#queue-exit-snapshot").isEmpty(),
                    "reduced motion left queue exit animation");
            check(node("immersive-shell").getProperties().get("lumen-motion") == null,
                    "reduced motion started entrance animation");
            check(showingWindows() == 1, "reduced motion created duplicate windows");
            button("open-immersive").fire();
            check(node("immersive-shell").isVisible(), "reduced motion broke immersive navigation");
            button("immersive-collapse").fire();
        });
        fx(() -> button("next-button").fire());
        awaitPlaying(first.id());
        fx(() -> {
            check(!field("artworkTransition", ArtworkTransition.class).active(),
                    "reduced motion started artwork transition");
            check(stage.getScene().getRoot().lookupAll("#artwork-transition-image").isEmpty(),
                    "reduced motion left artwork overlay");
        });
        fx(() -> check(audio().state() == JavaFxAudioPlayer.State.PLAYING,
                "reduced motion affected playback"));
        fx(() -> {
            while (field("themeMode", ThemeController.Mode.class) != ThemeController.Mode.DYNAMIC)
                button("theme-button").fire();
        });
        String stillBackground = fxValue(() -> stage.getScene().getRoot().getStyle());
        pause(650);
        fx(() -> check(stillBackground.equals(stage.getScene().getRoot().getStyle()),
                "dynamic background kept animating with reduced motion"));
        System.out.println("PASS reduced motion stops animation without disabling navigation");
    }

    private void focusChecks() throws Exception {
        fx(() -> {
            @SuppressWarnings("unchecked") var petPicker =
                    (javafx.scene.control.ComboBox<PetCompanion.Species>) node("pet-picker");
            petPicker.getSelectionModel().select(PetCompanion.Species.HIDDEN);
            PetCompanion pet = field("pet", PetCompanion.class);
            check(pet.species() == PetCompanion.Species.HIDDEN && !pet.isVisible(),
                    "hidden pet was visible before focus");
            button("nav-focus").fire();
            check(pet.isQuiet(), "pet was not quiet in focus");
            check(!pet.isVisible(), "hidden pet reappeared in focus");
        });
        FocusPane pane = fxValue(() -> field("focusPane", FocusPane.class));
        check(pane != null, "focus pane is not wired");
        fx(() -> {
            pane.focusSession().setDuration(Duration.ofMillis(350));
            button("focus-start").fire();
            check(pane.focusSession().state() == FocusSession.State.RUNNING,
                    "focus timer did not start from UI");
        });
        await(2_000, () -> fxValue(() -> pane.focusSession().state() == FocusSession.State.COMPLETED),
                "focus timer did not complete");
        fx(() -> {
            pane.focusSession().setDuration(Duration.ofSeconds(5));
            button("focus-start").fire();
            button("focus-end").fire();
            check(pane.focusSession().state() == FocusSession.State.IDLE,
                    "focus timer did not cancel");
            check(audio().state() == JavaFxAudioPlayer.State.PLAYING,
                    "cancelling focus timer interrupted playback");
            pane.sleepSession().setDuration(Duration.ofSeconds(5));
            button("focus-sleep-toggle").fire();
            button("focus-sleep-toggle").fire();
            check(pane.sleepSession().state() == FocusSession.State.IDLE,
                    "sleep timer did not cancel");
            check(audio().state() == JavaFxAudioPlayer.State.PLAYING,
                    "cancelling sleep timer interrupted playback");
            pane.sleepSession().setDuration(Duration.ofMillis(350));
            button("focus-sleep-toggle").fire();
        });
        await(2_000, () -> fxValue(() -> pane.sleepSession().state() == FocusSession.State.COMPLETED),
                "sleep timer did not complete");
        fx(() -> {
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING,
                    "sleep completion did not stop/pause playback");
            button("focus-back").fire();
            PetCompanion pet = field("pet", PetCompanion.class);
            check(!pet.isQuiet(), "pet stayed quiet after focus");
            check(!pet.isVisible(), "hidden pet reappeared after focus");
            @SuppressWarnings("unchecked") var petPicker =
                    (javafx.scene.control.ComboBox<PetCompanion.Species>) node("pet-picker");
            petPicker.getSelectionModel().select(PetCompanion.Species.BLACK_CAT);
            check(pet.isVisible(), "pet was not restored for scene screenshots");
            button("play-button").fire();
        });
        awaitPlaying(first.id());
        originalQueue = fxValue(() -> queue().snapshot());
    }

    private void themeMatrix() throws Exception {
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> {
                for (int i = 0; i < ThemeController.Mode.values().length
                        && field("themeMode", ThemeController.Mode.class) != mode; i++) {
                    button("theme-button").fire();
                }
                check(field("themeMode", ThemeController.Mode.class) == mode,
                        "cannot select theme " + mode);
                check(stage.getScene().getRoot().getStyleClass()
                        .contains(mode.name().toLowerCase() + "-theme"),
                        "theme root style missing: " + mode);
                if (mode == ThemeController.Mode.BACKGROUND) {
                    check(field("backgroundUri", URI.class) != null,
                            "background theme has no fixture image");
                    check(stage.getScene().getRoot().getStyle().contains("background.png"),
                            "background theme did not apply fixture image");
                }
            });
            for (int[] size : new int[][] {{860, 600}, {1280, 800}}) {
                fx(() -> {
                    stage.setWidth(size[0]); stage.setHeight(size[1]);
                    button("open-immersive").fire();
                });
                settleScene();
                fx(() -> {
                    layout();
                    for (String id : List.of("immersive-shell", "immersive-cover",
                            "immersive-lyrics", "immersive-collapse", "playback-bar")) fits(id);
                    screenshot(mode.name().toLowerCase() + "-immersive-" + size[0] + "x" + size[1] + ".png");
                    button("immersive-collapse").fire();
                    button("toggle-queue").fire();
                });
                settleScene();
                fx(() -> {
                    layout();
                    for (String id : List.of("right-rail", "queue-list", "toggle-queue")) fits(id);
                    screenshot(mode.name().toLowerCase() + "-queue-" + size[0] + "x" + size[1] + ".png");
                    if (size[0] == 1280 && (mode == ThemeController.Mode.DARK
                            || mode == ThemeController.Mode.LIGHT)) checkQueueMenu(mode);
                    button("toggle-queue").fire();
                    button("nav-focus").fire();
                });
                settleScene();
                fx(() -> {
                    layout();
                    fits("focus-pane");
                    fits("focus-back");
                    screenshot(mode.name().toLowerCase() + "-focus-" + size[0] + "x" + size[1] + ".png");
                    button("focus-back").fire();
                });
            }
        }
    }

    private void settleScene() throws Exception {
        pause(400);
        await(2_000, () -> fxValue(() ->
                !field("artworkTransition", ArtworkTransition.class).active()
                && stage.getScene().getRoot().lookupAll("#artwork-transition-image").isEmpty()
                && stage.getScene().getRoot().lookupAll("#queue-exit-snapshot").isEmpty()),
                "scene transition did not settle before screenshot");
    }

    private void checkQueueMenu(ThemeController.Mode mode) {
        ListView<Track> queueList = list("queue-list");
        queueList.scrollTo(0);
        queueList.applyCss(); queueList.layout();
        @SuppressWarnings("unchecked") ListCell<Track> cell = queueList.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance).map(item -> (ListCell<Track>) item)
                .filter(item -> item.getIndex() == 0 && !item.isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("queue row not rendered"));
        ContextMenu menu = cell.getContextMenu();
        check(menu != null, "queue row has no menu");
        menu.show(cell, Side.LEFT, 0, 0);
        check(menu.isShowing(), "queue menu did not open");
        String style = mode.name().toLowerCase() + "-theme";
        check(menu.getStyleClass().contains(style)
                || menu.getScene().getRoot().getStyleClass().contains(style),
                "queue menu did not inherit " + style);
        screenshot(menu.getScene(), mode.name().toLowerCase() + "-queue-menu.png");
        menu.hide();
    }

    private void checkCurrentLyricCentered() {
        @SuppressWarnings("unchecked") ListView<String> lyrics = (ListView<String>) node("immersive-lyrics");
        lyrics.applyCss(); lyrics.layout();
        ListCell<?> current = lyrics.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance).map(ListCell.class::cast)
                .filter(cell -> cell.getStyleClass().contains("current-lyric") && cell.isVisible())
                .findFirst().orElseThrow(() -> new AssertionError("current lyric cell not rendered"));
        Bounds line = current.localToScene(current.getBoundsInLocal());
        Bounds viewport = lyrics.localToScene(lyrics.getBoundsInLocal());
        check(Math.abs(line.getCenterY() - viewport.getCenterY()) <= lyrics.getFixedCellSize() * 1.2,
                "current lyric is not centered: " + line + " in " + viewport);
    }

    private void assertPlaybackPreserved(double baseline) {
        check(audio() == originalAudio, "scene traversal replaced audio player");
        check(field("loadVersion", Long.class) == originalLoadVersion,
                "scene traversal reloaded audio");
        check(first.id().equals(loadedId()), "scene traversal reloaded another track");
        check(queue().snapshot().equals(originalQueue), "scene traversal changed queue");
        check(audio().state() == JavaFxAudioPlayer.State.PLAYING, "scene traversal stopped playback");
        check(audio().position().toSeconds() >= baseline - .3,
                "scene traversal reset playback position");
    }

    private void assertSettled(Node node) {
        check(Math.abs(node.getOpacity() - 1) < .02, "ghost opacity on " + node.getId());
        check(Math.abs(node.getTranslateX()) < .5 && Math.abs(node.getTranslateY()) < .5,
                "ghost translate on " + node.getId());
    }

    private void prepareFixture() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("playback-scene-smoke")
                && !Files.exists(root.resolve("library.json")), "fresh isolated profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), key + " outside isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null,
                "API keys entered isolated smoke");
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost())
                && service.getPort() > 0, "isolated loopback demo service required");
        check(new MusicClient(service).search("").size() == 3, "demo catalog unavailable");
        Path wav = root.resolve("scene-local-120s.wav");
        writeWav(wav);
        Path firstCover = root.resolve("cover-one.png");
        Path secondCover = root.resolve("cover-two.png");
        writeCover(firstCover, 0xFF26A69A);
        writeCover(secondCover, 0xFFE4A04D);
        Path background = root.resolve("background.png");
        Files.copy(firstCover, background);
        Properties appearance = new Properties();
        appearance.setProperty("theme", "dark");
        // This suite asserts one window; default desktop-pet placement has its own acceptance.
        appearance.setProperty("pet.desktop.immersive", "false");
        appearance.setProperty("background", background.toString());
        try (var output = Files.newOutputStream(Path.of(required("LUMEN_PREFERENCES_PATH")))) {
            appearance.store(output, "Playback scene fixture");
        }
        coverServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        coverServer.createContext("/cover-first.png", exchange -> serveCover(exchange, firstCover));
        coverServer.createContext("/cover-second.png", exchange -> serveCover(exchange, secondCover));
        coverServer.start();
        URI firstCoverUrl = URI.create("http://127.0.0.1:" + coverServer.getAddress().getPort()
                + "/cover-first.png");
        URI secondCoverUrl = URI.create("http://127.0.0.1:" + coverServer.getAddress().getPort()
                + "/cover-second.png");
        first = new Track("scene-one", "场景验收第一首", "Fixture", 120,
                "local", wav.toUri(), null, 0, firstCoverUrl);
        second = new Track("scene-two", "场景验收第二首", "Fixture", 120,
                "local", wav.toUri(), null, 0, secondCoverUrl);
        LibraryClient library = new LibraryClient(service);
        check(library.load().playlists().isEmpty(), "demo library is not empty");
        playlistId = library.createPlaylist("场景验收").playlists().get(0).id();
        library.addToPlaylist(playlistId, first);
        library.addToPlaylist(playlistId, second);
    }

    private static void writeWav(Path path) throws Exception {
        int rate = 11_025, seconds = 120;
        byte[] pcm = new byte[rate * seconds * 2];
        for (int frame = 0; frame < rate * seconds; frame++) {
            short value = (short) (1600 * Math.sin(frame * 2 * Math.PI * 220 / rate));
            pcm[frame * 2] = (byte) value;
            pcm[frame * 2 + 1] = (byte) (value >>> 8);
        }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm),
                format, rate * seconds)) {
            check(AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile()) > 0,
                    "WAV not saved");
        }
    }

    private static void writeCover(Path path, int argb) throws Exception {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            int shade = (x + y) / 8;
            int color = 0xFF000000 | (Math.min(255, ((argb >>> 16) & 255) + shade) << 16)
                    | (Math.min(255, ((argb >>> 8) & 255) + shade) << 8)
                    | Math.min(255, (argb & 255) + shade);
            image.setRGB(x, y, color);
        }
        check(ImageIO.write(image, "png", path.toFile()), "cover PNG not saved");
    }

    private static void serveCover(HttpExchange exchange, Path path) throws java.io.IOException {
        try (exchange) {
            byte[] bytes = Files.readAllBytes(path);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private void invokeSetLyrics(List<Lyrics.Line> lines) {
        try {
            Method method = LumenFxApp.class.getDeclaredMethod("setLyrics", List.class, String.class);
            method.setAccessible(true);
            method.invoke(app, lines, "Fixture lyrics");
        } catch (ReflectiveOperationException error) { throw new AssertionError("cannot set fixture lyrics", error); }
    }

    private void screenshot(String name) {
        screenshot(stage.getScene(), name);
    }

    private void screenshot(Scene scene, String name) {
        try {
            WritableImage image = scene.snapshot(null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
                png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Path path = root.resolve(name);
            check(ImageIO.write(png, "png", path.toFile()) && Files.size(path) > 1_000,
                    "screenshot not saved: " + name);
            System.out.println("SCREENSHOT: " + path);
        } catch (Exception error) { throw new AssertionError("screenshot failed: " + name, error); }
    }

    private void fits(String id) {
        Node item = node(id);
        check(item.isVisible() && item.isManaged(), "hidden/unmanaged " + id);
        Bounds bounds = item.localToScene(item.getLayoutBounds());
        double toleranceX = 1 / stage.getOutputScaleX() + .01;
        double toleranceY = 1 / stage.getOutputScaleY() + .01;
        check(bounds.getMinX() >= -toleranceX && bounds.getMaxX() <= stage.getScene().getWidth() + toleranceX,
                "horizontal overflow " + id + ": " + bounds);
        check(bounds.getMinY() >= -toleranceY && bounds.getMaxY() <= stage.getScene().getHeight() + toleranceY,
                "vertical overflow " + id + ": " + bounds);
    }

    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private long showingWindows() { return Window.getWindows().stream().filter(Window::isShowing).count(); }
    private Node node(String id) {
        Node found = stage.getScene().lookup("#" + id);
        check(found != null, "missing UI #" + id);
        return found;
    }
    private Button button(String id) { return (Button) node(id); }
    @SuppressWarnings("unchecked") private ListView<Track> list(String id) { return (ListView<Track>) node(id); }
    @SuppressWarnings("unchecked") private javafx.scene.control.ComboBox<LibraryClient.Playlist> picker() {
        return (javafx.scene.control.ComboBox<LibraryClient.Playlist>) node("playlist-picker");
    }
    private TabPane tabs() { return (TabPane) node("main-tabs"); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private String loadedId() {
        Track track = field("loadedTrack", Track.class);
        return track == null ? null : track.id();
    }
    private <T> T field(String name, Class<T> type) {
        try {
            Field field = LumenFxApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(app));
        } catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new AssertionError("missing " + key);
        return value;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private void await(long millis, CheckedBoolean condition, String message) throws Exception {
        long startedAt = System.nanoTime();
        long deadline = startedAt + millis * 1_000_000L;
        int polls = 0;
        long lastConditionNanos = 0, maxAwaitFxQueueNanos = 0;
        while (System.nanoTime() < deadline) {
            long conditionStartedAt = System.nanoTime();
            ++polls;
            if (condition.get()) return;
            lastConditionNanos = System.nanoTime() - conditionStartedAt;
            maxAwaitFxQueueNanos = Math.max(maxAwaitFxQueueNanos, lastFxQueueNanos);
            pause(70);
        }
        System.err.println("AWAIT_FAILURE message=" + message + " deadlineMs=" + millis
                + " elapsedMs=" + (System.nanoTime() - startedAt) / 1_000_000.0 + " polls=" + polls
                + " lastConditionMs=" + lastConditionNanos / 1_000_000.0
                + " maxAwaitFxQueueMs=" + maxAwaitFxQueueNanos / 1_000_000.0);
        if (message.equals("cover transition did not finish")) dumpArtworkFailure();
        throw new AssertionError(message + "; phase=" + phase);
    }
    private void dumpWindowFailure() {
        try { fx(() -> {
            System.err.println("WINDOW_FAILURE phase=" + phase + " mainFocused=" + stage.isFocused()
                    + " mainIconified=" + stage.isIconified());
            for (Window window : List.copyOf(Window.getWindows())) {
                System.err.println("WINDOW type=" + window.getClass().getName() + " showing=" + window.isShowing()
                        + " focused=" + window.isFocused() + " isMain=" + (window == stage)
                        + " width=" + window.getWidth() + " height=" + window.getHeight()
                        + " ownerType=" + (window instanceof Stage other && other.getOwner() != null
                                ? other.getOwner().getClass().getName() : "none"));
            }
        }); } catch (Exception error) {
            System.err.println("WINDOW_DIAGNOSTIC_FAILED " + error.getClass().getName());
        }
    }
    private void dumpArtworkFailure() throws Exception {
        fx(() -> {
            try {
                IdlePlaybackControls idle = field("idleControls", IdlePlaybackControls.class);
                if (idle != null) {
                    StringBuilder idleText = new StringBuilder("IDLE_FAILURE focusOwner=")
                            .append(stage.getScene().getFocusOwner() == null ? "none" : stage.getScene().getFocusOwner().getId())
                            .append(" playing=").append(audio().state()).append(" reduced=").append(MotionPreferences.reduced());
                    for (String name : List.of("active", "closed", "idle", "fade", "editing")) {
                        Field idleField = IdlePlaybackControls.class.getDeclaredField(name);
                        idleField.setAccessible(true);
                        Object value = idleField.get(idle);
                        idleText.append(' ').append(name).append('=');
                        if (value instanceof javafx.animation.Animation animation)
                            idleText.append(animation.getStatus()).append('/').append(animation.getCurrentTime().toMillis())
                                    .append('/').append(animation.getTotalDuration().toMillis());
                        else if (value instanceof java.util.function.BooleanSupplier supplier) idleText.append(supplier.getAsBoolean());
                        else idleText.append(value);
                    }
                    for (String id : List.of("immersive-actions", "immersive-calibration", "playback")) {
                        Node control = stage.getScene().lookup("#" + id);
                        if (control != null) idleText.append(' ').append(id).append(":hover=")
                                .append(control.isHover()).append(",opacity=").append(control.getOpacity());
                    }
                    System.err.println(idleText);
                }
                ArtworkTransition transition = field("artworkTransition", ArtworkTransition.class);
                Field animationField = ArtworkTransition.class.getDeclaredField("animation");
                animationField.setAccessible(true);
                javafx.animation.Timeline animation = (javafx.animation.Timeline) animationField.get(transition);
                System.err.println("ARTWORK_FAILURE active=" + transition.active()
                        + (animation == null ? "" : " status=" + animation.getStatus()
                                + " currentMs=" + animation.getCurrentTime().toMillis()
                                + " cycleMs=" + animation.getCycleDuration().toMillis()
                                + " totalMs=" + animation.getTotalDuration().toMillis()
                                + " cycleCount=" + animation.getCycleCount()
                                + " autoReverse=" + animation.isAutoReverse()
                                + " rate=" + animation.getRate() + " currentRate=" + animation.getCurrentRate())
                        + " sceneVersion=" + field("sceneMotionVersion", Long.class)
                        + " stageShowing=" + stage.isShowing() + " stageFocused=" + stage.isFocused()
                        + " stageIconified=" + stage.isIconified()
                        + " lastFxQueueMs=" + lastFxQueueNanos / 1_000_000.0
                        + " maxFxQueueMs=" + maxFxQueueNanos / 1_000_000.0);
            } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        });
    }
    private void awaitPlaying(String id) throws Exception {
        await(14_000, () -> fxValue(() -> id.equals(loadedId())
                && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() > .25), "local audio did not play " + id);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        long enqueuedAt = System.nanoTime();
        Platform.runLater(() -> {
            lastFxQueueNanos = System.nanoTime() - enqueuedAt;
            maxFxQueueNanos = Math.max(maxFxQueueNanos, lastFxQueueNanos);
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> {
            if (app != null) app.stop();
            for (Window window : List.copyOf(Window.getWindows())) window.hide();
        }); } catch (Exception error) { error.printStackTrace(System.err); }
        if (coverServer != null) coverServer.stop(0);
        System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
