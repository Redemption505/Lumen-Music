package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.awt.Robot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Exercises the delivered UI against an isolated HTTP fixture and saves reviewable screenshots. */
public final class FeatureDesktopSmoke extends Application {
    private LumenFxApp app;
    private Stage stage;
    private HttpServer service;
    private Path output;
    private PauseTransition timeout;
    private final DemoAudioLibrary demo = new DemoAudioLibrary();
    private Robot robot;
    private final AtomicInteger openingRetryHeads = new AtomicInteger();
    private final AtomicInteger successfulRetryHeads = new AtomicInteger();
    private final AtomicInteger cancelledRetryHeads = new AtomicInteger();
    private final java.util.Set<String> removedLibraryPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private byte[] audioFixture;

    @Override public void start(Stage stage) throws Exception {
        Platform.setImplicitExit(false);
        this.stage = stage;
        String installedJar = System.getProperty("lumen.test.installedJar", "");
        if (!installedJar.isBlank()) {
            Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            check(actual.equals(Path.of(installedJar).toAbsolutePath().normalize()), "Installed acceptance loaded unexpected production code: " + actual);
            System.out.println("INSTALLED CODE SOURCE: " + actual);
        }
        output = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(output != null && output.toString().contains("build"), "An isolated build directory is required");
        check(Path.of(required("LUMEN_PREFERENCES_PATH")).toAbsolutePath().startsWith(output),
                "Preferences must use the isolated directory");
        Files.createDirectories(output);
        audioFixture = Files.readAllBytes(Path.of(getParameters().getRaw().get(0)));
        URI serviceUri = URI.create(required("LUMEN_SERVICE_URL"));
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", serviceUri.getPort()), 0);
        service.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/library")) {
                if (exchange.getRequestMethod().equals("DELETE")) removedLibraryPaths.add(path);
                byte[] bytes = libraryXml().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            if ((path.contains("BV1retry0001") || path.contains("BV1success01") || path.contains("BV1cancel001"))
                    && path.endsWith("/stream")) {
                AtomicInteger heads = path.contains("BV1success01") ? successfulRetryHeads
                        : path.contains("BV1cancel001") ? cancelledRetryHeads : openingRetryHeads;
                exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
                if (exchange.getRequestMethod().equals("HEAD")) {
                    heads.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(audioFixture.length));
                    exchange.sendResponseHeaders(200, -1);
                } else if (heads == successfulRetryHeads && heads.get() >= 2) {
                    int start = 0;
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if (range != null && range.startsWith("bytes=")) start = Integer.parseInt(range.substring(6).split("-", -1)[0]);
                    if (start >= audioFixture.length) exchange.sendResponseHeaders(416, -1);
                    else {
                        int length = audioFixture.length - start;
                        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                        if (range != null) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-"
                                + (audioFixture.length - 1) + "/" + audioFixture.length);
                        exchange.sendResponseHeaders(range == null ? 200 : 206, length);
                        exchange.getResponseBody().write(audioFixture, start, length);
                    }
                } else exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            if (path.contains("/collections/BV1missing01/")) {
                byte[] error = "<error code=\"VIDEO_NOT_FOUND\" message=\"fixture deleted\"/>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                return;
            }
            boolean direct = exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("BV1fixture01");
            String response = path.equals("/api/tracks") ? direct ? tracksXml() : "<tracks/>"
                    : path.equals("/api/library") ? "<library version=\"1\"><favorites/><playlists/></library>"
                    : path.contains("/recommend") ? "<tracks/>"
                    : path.equals("/api/ai-settings") ? "<ai-settings enabled=\"false\" keyConfigured=\"false\" model=\"fixture\" baseUrl=\"http://127.0.0.1\"/>"
                    : "<error code=\"VIDEO_NOT_FOUND\" message=\"fixture unavailable\"/>";
            if (path.endsWith("/stream")) {
                exchange.getResponseHeaders().set("X-Lumen-Error-Code", "VIDEO_NOT_FOUND");
                exchange.sendResponseHeaders(410, -1);
            } else {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(path.contains("/lyrics") ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        service.start();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            System.err.println("Uncaught feature smoke exception on " + thread.getName());
            fail(failure);
        });
        app = new LumenFxApp();
        app.startUi(stage, List.of());
        timeout = after(45_000, () -> fail(new AssertionError("Feature UI smoke timed out")));
        after(650, this::checkMain);
    }

    private void checkMain() throws Exception {
        layout(stage);
        button("sidebar-settings").fire();
        layout(stage);
        check(node("settings-audio") instanceof TitledPane, "Audio section missing");
        check(node("settings-hotkeys") instanceof TitledPane, "Shortcut section missing");
        ((TitledPane) node("settings-audio")).setExpanded(true);
        layout(stage);
        check(!((CheckBox) node("audio-cache-enabled")).isSelected(), "Fresh cache must be off");
        screenshot(stage, "features-settings.png");
        ((TitledPane) node("settings-audio")).setExpanded(false);
        for (GlobalHotkeys.Action action : GlobalHotkeys.Action.values())
            check(node("hotkey-record-" + action.name().toLowerCase()) instanceof Button, "Shortcut recorder missing");
        button("settings-back").fire();
        double expandedWidth = node("sidebar-scroll").getBoundsInParent().getWidth();
        button("sidebar-collapse").fire();
        layout(stage);
        check(node("sidebar-scroll").getBoundsInParent().getWidth() < 80, "Sidebar did not collapse");
        check(button("sidebar-collapse").isVisible(), "Collapse button disappeared");
        button("sidebar-collapse").fire();
        layout(stage);
        check(Math.abs(node("sidebar-scroll").getBoundsInParent().getWidth() - expandedWidth) < 1,
                "Sidebar did not restore its width");
        screenshot(stage, "features-home-1280.png");
        stage.setWidth(860);
        stage.setHeight(600);
        after(200, () -> {
            layout(stage);
            checkInside(stage, node("play-button"));
            checkInside(stage, node("sidebar-collapse"));
            double initialX = stage.getX() + node("sidebar-resize-handle").localToScene(0, 0).getX();
            mouse(node("sidebar-resize-handle"), MouseEvent.MOUSE_PRESSED, initialX, stage.getY() + 100);
            mouse(node("sidebar-resize-handle"), MouseEvent.MOUSE_DRAGGED, initialX + 110, stage.getY() + 100);
            mouse(node("sidebar-resize-handle"), MouseEvent.MOUSE_RELEASED, initialX + 110, stage.getY() + 100);
            button("toggle-queue").fire();
            layout(stage);
            checkInside(stage, node("main-tabs"));
            checkInside(stage, node("right-rail"));
            check(field("sidebarWidth", Double.class) >= 320, "Preferred sidebar width did not survive visual clamping");
            button("toggle-queue").fire();
            screenshot(stage, "features-home-860.png");
            button("open-immersive").fire();
            layout(stage);
            javafx.scene.layout.Region artwork = (javafx.scene.layout.Region) node("immersive-cover");
            check(Math.abs(artwork.getWidth() / artwork.getHeight() - 1.6) < .01,
                    "Immersive artwork is not 16:10");
            checkInside(stage, node("immersive-close"));
            Method setLyrics = LumenFxApp.class.getDeclaredMethod("setLyrics", List.class, String.class);
            setLyrics.setAccessible(true);
            setLyrics.invoke(app, Lyrics.parse("[00:00.00]First centered line\n[00:03.00]Second line\n"
                    + "[00:06.00]Third line\n[00:09.00]Fourth line\n[00:12.00]Fifth line\n"
                    + "[00:15.00]Sixth line\n[00:18.00]Seventh line\n[00:21.00]Eighth line"), "歌词排版验收");
            after(750, () -> {
                layout(stage);
                ListView<?> lyrics = (ListView<?>) node("immersive-lyrics");
                var current = lyrics.lookupAll(".list-cell.current-lyric").stream()
                        .filter(Node::isVisible).findFirst().orElseThrow(() -> new AssertionError("Current lyric is not visible"));
                double lineCenter = current.localToScene(current.getBoundsInLocal()).getCenterY();
                double viewportCenter = lyrics.localToScene(lyrics.getBoundsInLocal()).getCenterY();
                check(Math.abs(lineCenter - viewportCenter) <= lyrics.getFixedCellSize() / 2 + 10,
                        "Current lyric is not centered: " + lineCenter + " vs " + viewportCenter);
                screenshot(stage, "features-immersive-860.png");
                button("immersive-collapse").fire();
                button("nav-search").fire();
                ((TextField) node("search-query")).setText("BV1fixture01");
                after(1400, this::checkCollection);
            });
        });
    }

    private void checkCollection() throws Exception {
        PlayerQueue queue = field("queue", PlayerQueue.class);
        check(queue.tracks().size() == 3 && queue.current().page() == 1, "BV paste did not select complete collection");
        verifyPlaylistPopup();
        check(queue.current().viewCount() == 154200, "Real metadata view count was lost");
        Stage collection = window("分 P 列表");
        ListView<?> list = (ListView<?>) collection.getScene().lookup("#collection-parts-list");
        check(list.getItems().size() == 3, "Collection popup is incomplete");
        layout(collection);
        for (Node item : list.lookupAll(".track-views")) {
            javafx.scene.control.Label views = (javafx.scene.control.Label) item;
            check(views.getWidth() >= views.prefWidth(-1) - 1, "Collection view count is clipped");
        }
        ((Button) collection.getScene().lookup("#collection-exclude")).fire();
        check(field("trackPreferences", TrackPreferencesStore.class).isExcluded(queue.current().id()), "Exclude was not saved");
        ((Button) collection.getScene().lookup("#collection-exclude")).fire();
        check(!field("trackPreferences", TrackPreferencesStore.class).isExcluded(queue.current().id()), "Restore was not saved");
        check(!field("failureNotice", javafx.scene.control.Label.class).getText().isBlank(), "Failure reason is not visible");
        check(field("trackPreferences", TrackPreferencesStore.class).isUnavailable(queue.current().id()), "Permanent failure was not marked");
        screenshot(collection, "features-collection.png");
        collection.close();
        button("sidebar-settings").fire();
        button("cleanup-unavailable-all").fire();
        waitFor(() -> removedLibraryPaths.size() == 3 && field("favorites", List.class).isEmpty(), 2500, () -> {
            for (Object item : field("playlists", List.class))
                check(((LibraryClient.Playlist) item).tracks().isEmpty(), "Global cleanup left a failed playlist reference");
            button("settings-back").fire();
            afterCollection();
        });
    }

    private void verifyPlaylistPopup() throws Exception {
        button("toggle-queue").fire();
        layout(stage);
        ((ListView<?>) node("queue-list")).getSelectionModel().select(0);
        for (int i = 0; i < 5; i++) {
            ThemeController.Mode mode = field("themeMode", ThemeController.Mode.class);
            ListView<?> queueView = (ListView<?>) node("queue-list");
            javafx.scene.control.ListCell<?> cell = queueView.lookupAll(".list-cell").stream()
                    .filter(item -> item instanceof javafx.scene.control.ListCell<?> row && row.getIndex() == 0 && !row.isEmpty())
                    .map(item -> (javafx.scene.control.ListCell<?>) item).findFirst().orElseThrow();
            ((Button) cell.lookup(".track-more")).fire();
            ContextMenu popup = cell.getContextMenu();
            check(popup != null && popup.isShowing(), "Playlist menu did not open");
            popup.getScene().getRoot().applyCss();
            popup.getScene().getRoot().layout();
            String themeClass = mode.name().toLowerCase() + "-theme";
            check(popup.getScene().getRoot().getStyleClass().contains(themeClass),
                    "Playlist popup did not follow " + mode);
            Label label = popup.getScene().getRoot().lookupAll(".menu-item .label").stream()
                    .filter(node -> node instanceof Label)
                    .map(node -> (Label) node).findFirst()
                    .orElseThrow(() -> new AssertionError("Playlist popup label missing"));
            Color foreground = (Color) label.getTextFill();
            check(popup.getStyleClass().contains(themeClass), "Popup surface did not follow " + mode);
            Color background = (Color) ((Region) popup.getSkin().getNode()).getBackground()
                    .getFills().get(0).getFill();
            check(Math.abs(foreground.getBrightness() - background.getBrightness()) > .45,
                    "Playlist popup contrast is too low in " + mode);
            screenshot(popup.getScene(), "features-playlist-menu-" + mode.name().toLowerCase() + ".png");
            popup.hide();
            button("sidebar-settings").fire();
            button("theme-button").fire();
            button("settings-back").fire();
            layout(stage);
        }
        button("toggle-queue").fire();
    }

    private void afterCollection() throws Exception {
        button("open-immersive").fire();
        layout(stage);
        checkInside(stage, node("immersive-cover"));
        checkInside(stage, stage.getScene().lookup(".immersive-calibration"));
        checkInside(stage, field("immersiveTrackTitle", javafx.scene.control.Label.class));
        checkInside(stage, node("immersive-lyrics"));
        checkInside(stage, node("playback-failure-notice"));
        screenshot(stage, "features-failed-immersive-860.png");
        button("immersive-collapse").fire();
        button("open-mini").fire();
        after(150, this::checkMini);
    }

    private String libraryXml() {
        String id = "bilibili:BV1fixture01:1";
        String track = "<track id=\"" + id + "\" title=\"Fixture library track\" artist=\"Fixture\" durationSeconds=\"120\" source=\"bilibili\" bvid=\"BV1fixture01\" page=\"1\" partCount=\"3\"/>";
        StringBuilder xml = new StringBuilder("<library version=\"1\"><favorites>");
        if (!removedLibraryPaths.contains("/api/library/favorites/" + id)) xml.append(track);
        xml.append("</favorites><playlists>");
        for (String playlist : List.of("fixture-list-1", "fixture-list-2")) {
            xml.append("<playlist id=\"").append(playlist).append("\" name=\"Fixture playlist\">");
            if (!removedLibraryPaths.contains("/api/library/playlists/" + playlist + "/tracks/" + id)) xml.append(track);
            xml.append("</playlist>");
        }
        return xml.append("</playlists></library>").toString();
    }

    private void checkMini() throws Exception {
        Stage mini = window("Lumen Music · 迷你播放器");
        layout(mini);
        for (String id : List.of("mini-minimize", "mini-maximize", "mini-close"))
            checkInside(mini, mini.getScene().lookup("#" + id));
        Node title = mini.getScene().lookup("#mini-now-playing");
        double x = mini.getX(), y = mini.getY();
        mouse(title, MouseEvent.MOUSE_PRESSED, x + 90, y + 80);
        after(350, () -> {
            mouse(title, MouseEvent.MOUSE_DRAGGED, x + 140, y + 110);
            check(Math.abs(mini.getX() - x - 50) < 1 && Math.abs(mini.getY() - y - 30) < 1,
                    "Long press did not move mini window");
            mouse(title, MouseEvent.MOUSE_RELEASED, x + 140, y + 110);
            Node slider = mini.getScene().lookup("#mini-progress-slider");
            double movedX = mini.getX();
            mouse(slider, MouseEvent.MOUSE_PRESSED, x + 50, y + 170);
            after(350, () -> {
                mouse(slider, MouseEvent.MOUSE_DRAGGED, x + 100, y + 170);
                mouse(slider, MouseEvent.MOUSE_RELEASED, x + 100, y + 170);
                check(Math.abs(mini.getX() - movedX) < 1, "Slider press moved mini window");
                screenshot(mini, "features-mini.png");
                ((Button) mini.getScene().lookup("#mini-return")).fire();
                check(stage.isShowing(), "Return did not show main window");
                checkOpeningRetry();
            });
        });
    }

    private void checkOpeningRetry() throws Exception {
        Track track = new Track("bilibili:BV1retry0001:1", "Initial GET interruption", "Fixture", 8,
                "bilibili", null, "BV1retry0001", 1, null, 1);
        Method select = LumenFxApp.class.getDeclaredMethod("select", List.class, int.class);
        select.setAccessible(true);
        select.invoke(app, List.of(track), 0);
        waitFor(() -> openingRetryHeads.get() >= 2 && field("audio", AudioPlayer.class).state() == JavaFxAudioPlayer.State.FAILED,
                8000, () -> {
                    check(openingRetryHeads.get() == 2, "Initial GET preparation retried more than once");
                    check(!field("trackPreferences", TrackPreferencesStore.class).isUnavailable(track.id()),
                            "A playback transport failure was marked as permanent");
                    check(field("failureNotice", javafx.scene.control.Label.class).getText().contains("播放器无法打开音频"),
                            "Final VLC error lost its actual player cause");
                    checkSuccessfulOpeningRetry();
                });
    }

    private void checkSuccessfulOpeningRetry() throws Exception {
        selectFixture(retryTrack("BV1success01"));
        waitFor(() -> successfulRetryHeads.get() == 2 && field("audio", AudioPlayer.class).state() == JavaFxAudioPlayer.State.PLAYING,
                8000, () -> {
                    check(field("failureNotice", javafx.scene.control.Label.class).getText().isEmpty(),
                            "A successful opening retry kept an obsolete failure notice");
                    checkCancelledOpeningRetry();
                });
    }

    private void checkCancelledOpeningRetry() throws Exception {
        selectFixture(retryTrack("BV1cancel001"));
        waitFor(() -> field("openingRetries", Integer.class) == 1
                        && field("failureNotice", javafx.scene.control.Label.class).getText().contains("正在重新准备"),
                8000, () -> {
                    Track replacement = new Track("retry-cancel-local", "Replacement after cancel", "Fixture", 8,
                            "local", demo.resolve("demo-1"));
                    selectFixture(replacement);
                    after(900, () -> {
                        check(cancelledRetryHeads.get() == 1, "A cancelled opening retry issued another HEAD");
                        check(field("queue", PlayerQueue.class).current().id().equals(replacement.id()),
                                "An obsolete retry replaced the selected local track");
                        check(field("failureNotice", javafx.scene.control.Label.class).getText().isEmpty(),
                                "An obsolete retry contaminated the new track's status");
                        checkPermanentCollectionFailure();
                    });
                });
    }

    private void checkPermanentCollectionFailure() throws Exception {
        Track missing = new Track("bilibili:BV1missing01:1", "Deleted collection", "Fixture", 8,
                "bilibili", null, "BV1missing01", 1, null, 3);
        Method collection = LumenFxApp.class.getDeclaredMethod("selectCollection", Track.class);
        collection.setAccessible(true);
        collection.invoke(app, missing);
        waitFor(() -> field("trackPreferences", TrackPreferencesStore.class).isUnavailable(missing.id()), 2000, () -> {
            check(field("failureNotice", javafx.scene.control.Label.class).getText().contains("视频不存在或已删除"),
                    "Known collection failure did not reach the global notice");
            prepareHotkeys();
        });
    }

    private Track retryTrack(String bvid) {
        return new Track("bilibili:" + bvid + ":1", "Opening GET retry fixture", "Fixture", 8,
                "bilibili", null, bvid, 1, null, 1);
    }

    private void selectFixture(Track track) throws Exception {
        Method select = LumenFxApp.class.getDeclaredMethod("select", List.class, int.class);
        select.setAccessible(true);
        select.invoke(app, List.of(track), 0);
    }

    private void prepareHotkeys() throws Exception {
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.toFront();
        stage.requestFocus();
        button("sidebar-settings").fire();
        ((TitledPane) node("settings-hotkeys")).setExpanded(true);
        layout(stage);
        robot = new Robot();
        robot.setAutoDelay(20);
        recordHotkey(0);
    }

    private void recordHotkey(int index) throws Exception {
        GlobalHotkeys.Action[] actions = GlobalHotkeys.Action.values();
        if (index == actions.length) {
            List<Track> tracks = List.of(
                    new Track("fixture-local-1", "Local first", "Fixture", 8, "local", demo.resolve("demo-1")),
                    new Track("fixture-local-2", "Local second", "Fixture", 8, "local", demo.resolve("demo-2")),
                    new Track("fixture-local-3", "Local third", "Fixture", 8, "local", demo.resolve("demo-3")));
            Method select = LumenFxApp.class.getDeclaredMethod("select", List.class, int.class);
            select.setAccessible(true);
            select.invoke(app, tracks, 0);
            ((javafx.scene.control.Slider) node("volume-slider")).setValue(.5);
            waitFor(() -> field("audio", AudioPlayer.class).state() == JavaFxAudioPlayer.State.PLAYING,
                    5000, () -> {
                        button("settings-back").fire();
                        stage.setIconified(true);
                        after(250, () -> backgroundAction(0));
                    });
            return;
        }
        Button recorder = button("hotkey-record-" + actions[index].name().toLowerCase());
        recorder.fire();
        after(220, () -> {
            check(stage.getScene().getFocusOwner() == recorder, "Shortcut recorder did not receive keyboard focus");
            pressHotkey(index);
            after(250, () -> {
                @SuppressWarnings("unchecked")
                var bindings = (java.util.Map<GlobalHotkeys.Action, GlobalHotkeys.Combination>) field("hotkeyBindings", java.util.Map.class);
                check(bindings.get(actions[index]).virtualKey() == KeyCode.F6.getCode() + index,
                        "Shortcut UI did not record real keyboard input for " + actions[index]);
                recordHotkey(index + 1);
            });
        });
    }

    private void backgroundAction(int index) throws Exception {
        int[] order = {0, 2, 1, 3, 4};
        if (index == order.length) {
            stage.setIconified(false);
            after(150, () -> {
                screenshot(stage, "features-after-background-hotkeys.png");
                GlobalHotkeys hotkeys = field("globalHotkeys", GlobalHotkeys.class);
                timeout.stop();
                app.stop();
                hotkeys.termination().whenComplete((ignored, failure) -> Platform.runLater(() -> {
                    if (failure != null) { fail(failure); return; }
                    check(hotkeys.installed().isEmpty(), "Global registrations survived application shutdown");
                    stage.close();
                    service.stop(0);
                    demo.close();
                    System.out.println("PASS: feature UI, BV/parts, exclude/restore, failure visibility, sidebar budget, 16:10 artwork, mini long press, five real background shortcuts and native release");
                    Platform.exit();
                }));
            });
            return;
        }
        check(stage.isIconified(), "Window is not in the background");
        pressHotkey(order[index]);
        after(500, () -> {
            PlayerQueue queue = field("queue", PlayerQueue.class);
            AudioPlayer audio = field("audio", AudioPlayer.class);
            double volume = ((javafx.scene.control.Slider) node("volume-slider")).getValue();
            switch (order[index]) {
                case 0 -> check(queue.index() == 2, "Background previous callback did not update queue");
                case 2 -> check(queue.index() == 0, "Background next callback did not update queue");
                case 1 -> check(audio.state() == JavaFxAudioPlayer.State.PAUSED, "Background toggle did not pause actual audio");
                case 3 -> check(Math.abs(volume - .55) < .001, "Background volume up did not update audio control");
                case 4 -> check(Math.abs(volume - .5) < .001, "Background volume down did not update audio control");
            }
            backgroundAction(index + 1);
        });
    }

    private void pressHotkey(int index) {
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
        robot.keyPress(java.awt.event.KeyEvent.VK_F6 + index);
        robot.keyRelease(java.awt.event.KeyEvent.VK_F6 + index);
        robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
    }

    private void waitFor(CheckedCondition condition, int remaining, CheckedAction action) throws Exception {
        if (condition.get()) { action.run(); return; }
        check(remaining > 0, "Real audio playback did not become ready");
        after(100, () -> waitFor(condition, remaining - 100, action));
    }

    private static String tracksXml() {
        StringBuilder xml = new StringBuilder("<tracks>");
        for (int part = 1; part <= 3; part++) xml.append("<track id=\"bilibili:BV1fixture01:").append(part)
                .append("\" title=\"A very long fixture music title that must stay inside the immersive heading and playback bar, part ").append(part)
                .append("\" artist=\"Fixture artist\" durationSeconds=\"120\" source=\"bilibili\" bvid=\"BV1fixture01\" page=\"")
                .append(part).append("\" partCount=\"3\" viewCount=\"154200\"/>");
        return xml.append("</tracks>").toString();
    }

    private void screenshot(Stage window, String name) throws Exception {
        layout(window);
        screenshot(window.getScene(), name);
    }

    private void screenshot(javafx.scene.Scene scene, String name) throws Exception {
        var image = scene.snapshot(null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        ImageIO.write(png, "png", output.resolve(name).toFile());
        System.out.println("SCREENSHOT: " + output.resolve(name));
    }

    private static void mouse(Node node, javafx.event.EventType<MouseEvent> type, double x, double y) {
        Event.fireEvent(node, new MouseEvent(type, 10, 10, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, type != MouseEvent.MOUSE_RELEASED, false, false,
                false, false, true, null));
    }

    private Stage window(String title) {
        return Window.getWindows().stream().filter(candidate -> candidate instanceof Stage s && s.getTitle().equals(title))
                .map(candidate -> (Stage) candidate).findFirst().orElseThrow(() -> new AssertionError("Window missing: " + title));
    }
    private Node node(String id) { return stage.getScene().lookup("#" + id); }
    private Button button(String id) { return (Button) node(id); }
    private <T> T field(String name, Class<T> type) throws Exception {
        Field field = LumenFxApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(app));
    }
    private static void layout(Stage stage) { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void checkInside(Stage stage, Node node) {
        check(node != null, "Missing layout node");
        var bounds = node.localToScene(node.getBoundsInLocal());
        check(bounds.getMinX() >= -1 && bounds.getMinY() >= -1 && bounds.getMaxX() <= stage.getScene().getWidth() + 1
                && bounds.getMaxY() <= stage.getScene().getHeight() + 1, "Clipped node: " + node.getId() + " " + bounds);
    }
    private PauseTransition after(int millis, CheckedAction action) {
        PauseTransition timer = new PauseTransition(Duration.millis(millis));
        timer.setOnFinished(event -> { try { action.run(); } catch (Throwable failure) { fail(failure); } });
        timer.play();
        return timer;
    }
    private void fail(Throwable failure) {
        failure.printStackTrace();
        if (service != null) service.stop(0);
        System.exit(1);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        check(value != null && !value.isBlank(), "Missing isolated environment: " + name);
        return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    @FunctionalInterface private interface CheckedCondition { boolean get() throws Exception; }
    public static void main(String[] args) { launch(args); }
}
