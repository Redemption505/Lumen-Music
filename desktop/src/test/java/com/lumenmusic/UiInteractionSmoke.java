package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Isolated acceptance for per-track actions, queue sizing and transitions. */
public final class UiInteractionSmoke extends Application {
    private final DemoAudioLibrary demo = new DemoAudioLibrary();
    private final List<Track> tracks = new ArrayList<>();
    private final Map<String, String> saved = new LinkedHashMap<>();
    private final Map<String, String> playlist = new LinkedHashMap<>();
    private LumenFxApp app;
    private Stage stage;
    private HttpServer service;
    private Path output;
    private PauseTransition timeout;

    @Override public void start(Stage window) throws Exception {
        stage = window;
        output = Path.of(System.getenv("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(output.toString().contains("build"), "Isolated build profile required");
        Files.createDirectories(output);
        String installed = System.getProperty("lumen.test.installedJar", "");
        if (!installed.isBlank()) {
            Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(actual.equals(Path.of(installed)), "Expected installed production classes");
            System.out.println("INSTALLED CODE SOURCE: " + actual);
        }
        for (int i = 0; i < 40; i++) tracks.add(new Track("ui-" + i,
                "流动的旋律 · 一首值得完整显示标题的测试歌曲 " + i, "Fixture artist", 180,
                "local", demo.resolve("demo-1")));
        Track first = tracks.get(0);
        playlist.put(first.id(), "<track id=\"" + first.id() + "\" title=\"" + first.title()
                + "\" artist=\"Fixture artist\" durationSeconds=\"180\" source=\"local\" mediaUri=\""
                + first.mediaUri() + "\"/>");
        URI base = URI.create(System.getenv("LUMEN_SERVICE_URL"));
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", base.getPort()), 0);
        service.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (method.equals("POST") && path.startsWith("/api/library")) {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(new ByteArrayInputStream(bytes));
                    String id = document.getDocumentElement().getAttribute("id");
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    if (path.equals("/api/library/favorites")) saved.put(id, xml);
                    else if (path.endsWith("/tracks")) playlist.put(id, xml);
                } else if (method.equals("DELETE")) {
                    String id = path.substring(path.lastIndexOf('/') + 1);
                    if (path.contains("/favorites/")) saved.remove(id);
                    else playlist.remove(id);
                }
                String response = path.startsWith("/api/library") ? library()
                        : path.equals("/api/ai-settings")
                        ? "<ai-settings enabled=\"false\" keyConfigured=\"false\" model=\"fixture\" baseUrl=\"http://127.0.0.1\"/>"
                        : path.contains("lyrics") ? "<lyrics/>" : "<tracks/>";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception error) { error.printStackTrace(); }
            finally { exchange.close(); }
        });
        service.start();
        app = new LumenFxApp();
        app.startUi(stage, List.of());
        timeout = after(40_000, () -> { throw new AssertionError("Interaction acceptance timed out"); });
        after(850, this::recommendationActions);
    }

    private String library() {
        return "<library version=\"1\"><favorites>" + String.join("", saved.values())
                + "</favorites><playlists><playlist id=\"ui-list\" name=\"测试歌单\">"
                + String.join("", playlist.values()) + "</playlist></playlists></library>";
    }

    @SuppressWarnings("unchecked") private void recommendationActions() throws Exception {
        ListView<Track> recommendations = (ListView<Track>) node("recommendation-list");
        recommendations.getItems().setAll(tracks);
        PlayerQueue queue = field("queue", PlayerQueue.class);
        queue.select(List.of(tracks.get(0)), 0);
        ((ListView<Track>) node("queue-list")).getItems().setAll(queue.tracks());
        layout();
        ContextMenu menu = openRow(recommendations, 1);
        check(menu.getItems().stream().anyMatch(item -> "track-menu-playlist".equals(item.getId())), "Row playlist action missing");
        menuItem(menu, "track-menu-next").fire();
        menu.hide();
        check(queue.current().id().equals("ui-0") && queue.tracks().get(1).id().equals("ui-1"),
                "Recommendation next action replaced current track or inserted wrong item");
        ContextMenu favoriteAction = openRow(recommendations, 1);
        menuItem(favoriteAction, "track-menu-favorite").fire();
        favoriteAction.hide();
        waitFor(() -> field("favorites", List.class).stream().anyMatch(track -> ((Track) track).id().equals("ui-1")), () -> {
            ContextMenu favoriteMenu = openRow(recommendations, 1);
            check(menuItem(favoriteMenu, "track-menu-favorite").getText().equals("取消收藏"), "Favorite menu state did not refresh");
            favoriteMenu.hide();
            ContextMenu addMenu = openRow(recommendations, 1);
            ((Menu) menuItem(addMenu, "track-menu-playlist")).getItems().get(0).fire();
            addMenu.hide();
            waitFor(() -> field("playlistTracks", List.class).stream().anyMatch(track -> ((Track) track).id().equals("ui-1")), this::playlistActions);
        });
    }

    private void playlistActions() throws Exception {
        button("nav-playlists").fire();
        after(280, () -> {
            ListView<?> list = (ListView<?>) node("playlist-tracks");
            ContextMenu menu = openRow(list, 1);
            screenshot(menu.getScene(), "interaction-row-menu-dark.png");
            menuItem(menu, "track-menu-remove").fire();
            menu.hide();
            waitFor(() -> list.getItems().size() == 1, () -> {
                button("nav-home").fire();
                after(280, this::queueAndSidebar);
            });
        });
    }

    private void queueAndSidebar() throws Exception {
        screenshot(stage.getScene(), "interaction-recommendations-1280.png");
        button("sidebar-collapse").fire();
        layout();
        for (String id : List.of("nav-home", "nav-search", "nav-local", "nav-favorites", "nav-playlists")) {
            Button nav = button(id);
            double graphicCenter = nav.getGraphic().localToScene(nav.getGraphic().getBoundsInLocal()).getCenterX();
            double buttonCenter = nav.localToScene(nav.getLayoutBounds()).getCenterX();
            check(Math.abs(graphicCenter - buttonCenter) < 2, "Collapsed icon is off-center: " + id);
        }
        screenshot(stage.getScene(), "interaction-sidebar-collapsed.png");
        button("toggle-queue").fire();
        check(node("queue-list").getParent().getOpacity() < 1, "Queue entrance did not animate");
        after(280, () -> {
            layout();
            checkQueueTitle(110);
            dragQueue(-130);
            check(field("queueWidth", Double.class) >= 480, "Queue did not expand when dragged left");
            screenshot(stage.getScene(), "interaction-queue-wide.png");
            stage.setWidth(860);
            stage.setHeight(600);
            after(280, () -> {
                layout();
                check(node("main-tabs").getLayoutBounds().getWidth() >= 319, "Queue consumed the primary page");
                dragQueue(300);
                check(Math.abs(node("right-rail").getLayoutBounds().getWidth() - 300) < 2, "Queue minimum clamp failed");
                checkQueueTitle(85);
                screenshot(stage.getScene(), "interaction-queue-860.png");
                button("toggle-queue").fire();
                check(node("queue-exit-snapshot") != null, "Queue exit did not retain animated frame");
                after(280, () -> {
                    check(node("queue-exit-snapshot") == null, "Queue exit snapshot remained");
                    ContextMenu menu = openRow((ListView<?>) node("recommendation-list"), 1);
                    menuItem(menu, "track-menu-play").fire();
                    menu.hide();
                    waitFor(() -> field("audio", AudioPlayer.class).state() == JavaFxAudioPlayer.State.PLAYING, this::immersiveTransition);
                });
            });
        });
    }

    private void immersiveTransition() throws Exception {
        mouse(node("player-track-entry"), MouseEvent.MOUSE_CLICKED, 100, 100);
        check(node("immersive-shell").getOpacity() < 1, "Footer opening did not animate immersive page");
        after(300, () -> {
            check(Math.abs(node("immersive-shell").getOpacity() - 1) < .01, "Immersive entrance did not finish");
            screenshot(stage.getScene(), "interaction-immersive-860.png");
            settingsAndPet();
        });
    }

    private void settingsAndPet() throws Exception {
        button("sidebar-settings").fire();
        after(280, () -> {
            check(node("settings-page").isVisible(), "Separate settings page did not open");
            check(node("queue-manage") == null && node("unfavorite-button") == null
                    && node("remove-from-playlist") == null, "Old bottom track toolbars remain");
            screenshot(stage.getScene(), "v011-settings-860.png");
            ((CheckBox) node("pet-desktop-immersive")).fire();
            CheckBox desktop = (CheckBox) node("pet-desktop-minimized");
            desktop.fire();
            stage.setIconified(true);
            after(200, () -> {
                check(field("desktopPet", DesktopPetWindow.class).isShowing(), "Pet did not transfer on minimize");
                stage.setIconified(false);
                after(200, () -> {
                    check(!field("desktopPet", DesktopPetWindow.class).isShowing(), "Pet did not return to dock");
                    button("settings-back").fire();
                    button("open-immersive").fire();
                    after(280, () -> {
                        check(field("desktopPet", DesktopPetWindow.class).isShowing(), "Pet did not transfer in immersive view");
                        button("immersive-collapse").fire();
                        check(!field("desktopPet", DesktopPetWindow.class).isShowing(), "Pet did not return after immersive view");
                        after(280, this::nativeTextMenu);
                    });
                });
            });
        });
    }

    private void nativeTextMenu() throws Exception {
        TextField text = (TextField) node("home-query");
        text.setText("copy paste contrast");
        text.selectAll();
        var point = text.localToScreen(12, 12);
        Event.fireEvent(text, new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 12, 12,
                point.getX(), point.getY(), false, null));
        after(120, () -> {
            var popup = javafx.stage.Window.getWindows().stream()
                    .filter(window -> window instanceof javafx.stage.PopupWindow && window.isShowing())
                    .findFirst().orElseThrow();
            popup.getScene().getRoot().applyCss();
            javafx.scene.layout.Region surface = popup instanceof ContextMenu menu
                    ? (javafx.scene.layout.Region) menu.getSkin().getNode()
                    : (javafx.scene.layout.Region) popup.getScene().lookup(".context-menu");
            check(surface != null, "Native text menu not found");
            javafx.scene.paint.Color background = (javafx.scene.paint.Color) surface.getBackground().getFills().get(0).getFill();
            check(background.getBrightness() < .6, "Native text menu still has a white background in dark theme");
            screenshot(popup.getScene(), "v011-native-text-menu-dark.png");
            popup.hide();
            batchAndLyrics();
        });
    }

    private void batchAndLyrics() throws Exception {
        var importMethod = LumenFxApp.class.getDeclaredMethod("addFiles", List.class);
        importMethod.setAccessible(true);
        importMethod.invoke(app, List.of(Path.of(demo.resolve("demo-1")).toFile(), Path.of(demo.resolve("demo-2")).toFile()));
        check(field("localTracks", List.class).size() >= 2, "Batch import did not add both audio files");
        var set = LumenFxApp.class.getDeclaredMethod("setLyrics", List.class, String.class);
        set.setAccessible(true);
        set.invoke(app, Lyrics.parse("[00:00.00]One\n[00:10.00]Two\n[00:20.00]Three"), "Fixture");
        Field selectionField = LumenFxApp.class.getDeclaredField("manualLyricSelection");
        selectionField.setAccessible(true);
        selectionField.set(app, new LyricsSelectionStore.Selection("Fixture", "Test",
                "[00:00.00]One\n[00:10.00]Two\n[00:20.00]Three", 0));
        Track lyricTrack = field("queue", PlayerQueue.class).current();
        var shift = LumenFxApp.class.getDeclaredMethod("shiftLyrics", long.class);
        shift.setAccessible(true);
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) shift.invoke(app, -100L);
        check((System.nanoTime() - start) / 1_000_000 < 300, "Lyric adjustment blocked the UI");
        check(field("activeLyricIndex", Integer.class) >= 0, "Lyric calibration blanked the current line");
            timeout.stop();
            app.stop();
            check(field("lyricSelectionStore", LyricsSelectionStore.class).load(lyricTrack)
                    .orElseThrow().offsetMillis() == -1000, "Last lyric offset was not persisted in order");
            stage.close();
            service.stop(0);
            demo.close();
            System.out.println("PASS: v0.1.1 settings, pet transfer, native menu contrast, batch import, lyric continuity, recommendation actions, queue sizing and transitions");
            Platform.exit();
    }

    private void checkQueueTitle(double minimum) {
        layout();
        Label title = (Label) node("queue-list").lookup(".track-title");
        check(title != null && title.getWidth() >= minimum, "Queue title squeezed to " + (title == null ? 0 : title.getWidth()));
        check(node("right-rail").localToScene(node("right-rail").getLayoutBounds()).getMaxX() <= stage.getScene().getWidth() + 1, "Queue escaped window");
    }

    private void dragQueue(double delta) {
        Node handle = node("queue-resize-handle");
        double x = handle.localToScreen(handle.getBoundsInLocal()).getCenterX();
        mouse(handle, MouseEvent.MOUSE_PRESSED, x, 200);
        mouse(handle, MouseEvent.MOUSE_DRAGGED, x + delta, 200);
        mouse(handle, MouseEvent.MOUSE_RELEASED, x + delta, 200);
        layout();
    }

    private ContextMenu openRow(ListView<?> view, int index) {
        view.scrollTo(index);
        layout();
        ListCell<?> cell = view.lookupAll(".list-cell").stream()
                .filter(node -> node instanceof ListCell<?> item && item.getIndex() == index && !item.isEmpty())
                .map(node -> (ListCell<?>) node).findFirst().orElseThrow();
        ((Button) cell.lookup(".track-more")).fire();
        ContextMenu menu = cell.getContextMenu();
        check(menu.isShowing(), "Per-row menu did not open");
        javafx.scene.layout.Region surface = (javafx.scene.layout.Region) menu.getSkin().getNode();
        javafx.scene.paint.Color background = (javafx.scene.paint.Color) surface.getBackground().getFills().get(0).getFill();
        check(background.getBrightness() < .5, "Dark row menu reverted to a white surface");
        return menu;
    }

    private static MenuItem menuItem(ContextMenu menu, String id) {
        return menu.getItems().stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
    }
    private Node node(String id) { return stage.getScene().lookup("#" + id); }
    private Button button(String id) { return (Button) node(id); }
    private <T> T field(String name, Class<T> type) throws Exception {
        Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app));
    }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private void waitFor(Condition condition, Action action) throws Exception {
        if (condition.get()) action.run(); else after(80, () -> waitFor(condition, action));
    }
    private PauseTransition after(int millis, Action action) {
        PauseTransition timer = new PauseTransition(Duration.millis(millis));
        timer.setOnFinished(event -> { try { action.run(); } catch (Throwable error) { fail(error); } });
        timer.play(); return timer;
    }
    private void screenshot(javafx.scene.Scene scene, String name) throws Exception {
        var image = scene.snapshot(null);
        var png = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        ImageIO.write(png, "png", output.resolve(name).toFile());
        System.out.println("SCREENSHOT: " + output.resolve(name));
    }
    private static void mouse(Node node, javafx.event.EventType<MouseEvent> type, double x, double y) {
        Event.fireEvent(node, new MouseEvent(type, 10, 10, x, y, MouseButton.PRIMARY, 1, false, false, false, false,
                type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED, false, false, false, false, true, null));
    }
    private void fail(Throwable error) {
        error.printStackTrace(); if (app != null) app.stop(); if (service != null) service.stop(0); stage.close(); Platform.exit(); System.exit(1);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Condition { boolean get() throws Exception; }
}
