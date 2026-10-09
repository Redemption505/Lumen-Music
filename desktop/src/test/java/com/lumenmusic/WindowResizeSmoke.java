package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Exercises real scene event filters and checks responsive controls against an isolated service. */
public final class WindowResizeSmoke extends Application {
    private final Queue<Runnable> steps = new ArrayDeque<>();
    private LumenFxApp app;
    private Stage stage;
    private HttpServer service;
    private Path root;
    private int assertions;

    @Override public void start(Stage stage) {
        this.stage = stage;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            root = isolatedRoot();
            List<Track> tracks = List.of(
                    new Track("resize-1", "\u591c\u8272\u91cc\u7684\u94a2\u7434\uff1a\u4e00\u9996\u7ed9\u5b89\u9759\u65f6\u523b\u7684\u957f\u66f2\u540d\u6d4b\u8bd5", "\u97f3\u4e50\u4eba\u4e0e\u5ba2\u5ea7\u6f14\u594f\u8005", 247, "demo"),
                    new Track("resize-2", "\u6625\u65e5\u5c0f\u8c03", "\u6f14\u594f\u8005", 205, "demo"),
                    new Track("resize-3", "\u665a\u98ce\u4e0e\u56de\u5bb6\u7684\u8def", "\u97f3\u4e50\u4eba", 183, "demo"));
            new RecommendationStore(root.resolve("recommendations.xml")).save(
                    new MusicClient.RecommendationResult(tracks, "", "popular"));
            new PlaybackStateStore(root.resolve("playback-state.xml")).save(
                    new PlaybackStateStore.State(tracks, 0, PlayerQueue.RepeatMode.ALL, 30));
            URI uri = URI.create(System.getenv("LUMEN_SERVICE_URL"));
            check("127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0, "Isolated local service required");
            service = HttpServer.create(new InetSocketAddress("127.0.0.1", uri.getPort()), 0);
            service.createContext("/api/", exchange -> {
                String xml = switch (exchange.getRequestURI().getPath()) {
                    case "/api/library" -> "<library version=\"1\"><favorites/><playlists/></library>";
                    case "/api/ai-settings" -> "<ai-settings enabled=\"false\" keyConfigured=\"false\" model=\"smoke\" baseUrl=\"http://127.0.0.1\"/>";
                    default -> "<tracks><track id=\"resize-1\" title=\"" + tracks.get(0).title()
                            + "\" artist=\"" + tracks.get(0).artist() + "\" durationSeconds=\"247\" source=\"demo\"/></tracks>";
                };
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            service.start();
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            @SuppressWarnings("unchecked") var lyricList = (javafx.scene.control.ListView<String>) node("lyrics-list");
            lyricList.getItems().setAll("\u591c\u8272\u6e29\u67d4\uff0c\u97f3\u4e50\u6162\u6162\u54cd\u8d77", "\u98ce\u7a7f\u8fc7\u5b89\u9759\u7684\u8857\u9053", "\u4e00\u6bb5\u65cb\u5f8b\u966a\u6211\u56de\u5bb6");
            prepareSteps();
            after(700, this::next);
        } catch (Throwable error) { fail(error); }
    }

    private Path isolatedRoot() throws Exception {
        Path library = Path.of(System.getenv("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize();
        Path preferences = Path.of(System.getenv("LUMEN_PREFERENCES_PATH")).toAbsolutePath().normalize();
        check(library.getParent() != null && preferences.startsWith(library.getParent()), "Preferences must be isolated");
        check(!Files.exists(library) && !Files.exists(preferences), "Refusing existing test state");
        Files.createDirectories(library.getParent());
        return library.getParent();
    }

    private void prepareSteps() {
        if (getParameters().getRaw().contains("--filled-compact")) {
            steps.add(() -> resetSize(860, 600));
            steps.add(() -> selectView("immersive"));
            steps.add(() -> verifyLayoutAndSnapshot("immersive", 860, 600));
            return;
        }
        for (WindowResize.Edge edge : WindowResize.Edge.values()) {
            if (edge == WindowResize.Edge.NONE) continue;
            steps.add(() -> resetSize(1100, 740));
            steps.add(() -> verifyDrag(edge));
        }
        steps.add(() -> resetSize(1100, 740));
        steps.add(this::verifyMinimumAndOutsideDrag);
        steps.add(() -> resetSize(1100, 740));
        steps.add(this::verifyInteriorAndSecondary);
        steps.add(() -> stage.setMaximized(true));
        steps.add(this::verifyMaximized);
        steps.add(() -> stage.setMaximized(false));
        for (int[] size : new int[][] {{860, 600}, {1280, 800}, {1600, 900}}) {
            steps.add(() -> resetSize(size[0], size[1]));
            for (String view : List.of("home", "search", "local", "favorites", "playlists", "settings", "queue", "immersive")) {
                steps.add(() -> selectView(view));
                steps.add(() -> verifyLayoutAndSnapshot(view, size[0], size[1]));
            }
        }
    }

    private void resetSize(int width, int height) {
        stage.setX(80);
        stage.setY(60);
        stage.setWidth(width);
        stage.setHeight(height);
    }

    private void verifyDrag(WindowResize.Edge edge) {
        layout();
        double width = stage.getWidth(), height = stage.getHeight();
        double x = stage.getX(), y = stage.getY();
        double px = edge.west ? 2 : edge.east ? width - 2 : width / 2;
        double py = edge.north ? 2 : edge.south ? height - 2 : height / 2;
        fire(MouseEvent.MOUSE_MOVED, px, py, x + px, y + py, MouseButton.NONE, false);
        check(scene().getCursor() == edge.cursor, "Cursor for " + edge);
        fire(MouseEvent.MOUSE_PRESSED, px, py, x + px, y + py, MouseButton.PRIMARY, true);
        double dx = edge.west ? -40 : 40, dy = edge.north ? -25 : 25;
        fire(MouseEvent.MOUSE_DRAGGED, px + dx, py + dy, x + px + dx, y + py + dy, MouseButton.PRIMARY, true);
        checkNear(stage.getWidth(), width + (edge.west || edge.east ? 40 : 0), "Width for " + edge);
        checkNear(stage.getHeight(), height + (edge.north || edge.south ? 25 : 0), "Height for " + edge);
        checkNear(stage.getX(), x - (edge.west ? 40 : 0), "X for " + edge);
        checkNear(stage.getY(), y - (edge.north ? 25 : 0), "Y for " + edge);
        fire(MouseEvent.MOUSE_RELEASED, px + dx, py + dy, x + px + dx, y + py + dy, MouseButton.PRIMARY, false);
        check(scene().getCursor() == null || scene().getCursor() == Cursor.DEFAULT, "Release restores cursor");
        System.out.println("PASS edge " + edge);
    }

    private void verifyMinimumAndOutsideDrag() {
        double right = stage.getX() + stage.getWidth(), bottom = stage.getY() + stage.getHeight();
        double sx = stage.getX() + 2, sy = stage.getY() + 2;
        fire(MouseEvent.MOUSE_PRESSED, 2, 2, sx, sy, MouseButton.PRIMARY, true);
        fire(MouseEvent.MOUSE_DRAGGED, 2002, 2002, sx + 2000, sy + 2000, MouseButton.PRIMARY, true);
        checkNear(stage.getWidth(), 860, "Minimum width");
        checkNear(stage.getHeight(), 600, "Minimum height");
        checkNear(stage.getX() + stage.getWidth(), right, "Fixed right at minimum");
        checkNear(stage.getY() + stage.getHeight(), bottom, "Fixed bottom at minimum");
        fire(MouseEvent.MOUSE_DRAGGED, -98, -98, sx - 100, sy - 100, MouseButton.PRIMARY, true);
        checkNear(stage.getWidth(), 1200, "Outside drag retains capture and initial anchor");
        checkNear(stage.getHeight(), 840, "Outside vertical drag retains capture");
        fire(MouseEvent.MOUSE_RELEASED, -98, -98, sx - 100, sy - 100, MouseButton.PRIMARY, false);
    }

    private void verifyInteriorAndSecondary() {
        double width = stage.getWidth(), x = stage.getX();
        fire(MouseEvent.MOUSE_MOVED, 2, 100, x + 2, stage.getY() + 100, MouseButton.NONE, false);
        fire(MouseEvent.MOUSE_EXITED, -1, 100, x - 1, stage.getY() + 100, MouseButton.NONE, false);
        check(scene().getCursor() == null || scene().getCursor() == Cursor.DEFAULT, "Exit restores cursor");
        fire(MouseEvent.MOUSE_PRESSED, 2, 100, x + 2, stage.getY() + 100, MouseButton.SECONDARY, false);
        fire(MouseEvent.MOUSE_DRAGGED, -50, 100, x - 50, stage.getY() + 100, MouseButton.SECONDARY, false);
        checkNear(stage.getWidth(), width, "Secondary press cannot resize");
        fire(MouseEvent.MOUSE_PRESSED, 20, 100, x + 20, stage.getY() + 100, MouseButton.PRIMARY, true);
        fire(MouseEvent.MOUSE_DRAGGED, 100, 100, x + 100, stage.getY() + 100, MouseButton.PRIMARY, true);
        checkNear(stage.getWidth(), width, "Interior press cannot resize");
        checkNear(stage.getX(), x, "Interior press cannot move stage");
        layout();
        Node title = scene().lookup(".brand").getParent();
        Bounds titleBounds = title.localToScene(title.getLayoutBounds());
        double tx = titleBounds.getCenterX(), ty = titleBounds.getCenterY(), y = stage.getY();
        fire(title, MouseEvent.MOUSE_PRESSED, tx, ty, x + tx, y + ty, MouseButton.PRIMARY, true);
        fire(title, MouseEvent.MOUSE_DRAGGED, tx + 30, ty + 20, x + tx + 30, y + ty + 20, MouseButton.PRIMARY, true);
        fire(title, MouseEvent.MOUSE_RELEASED, tx + 30, ty + 20, x + tx + 30, y + ty + 20, MouseButton.PRIMARY, false);
        checkNear(stage.getX(), x + 30, "Title bar drag remains active");
        checkNear(stage.getY(), y + 20, "Title bar vertical drag remains active");
        checkNear(stage.getWidth(), width, "Title bar does not resize");
        Bounds close = node("window-close").localToScene(node("window-close").getLayoutBounds());
        check(WindowResize.edgeAt(close.getCenterX(), close.getCenterY(), scene().getWidth(), scene().getHeight())
                == WindowResize.Edge.NONE, "Close button center stays out of resize border");
    }

    private void verifyMaximized() {
        check(stage.isMaximized(), "Maximize completed before check");
        double width = stage.getWidth(), height = stage.getHeight(), x = stage.getX(), y = stage.getY();
        fire(MouseEvent.MOUSE_MOVED, 2, 2, x + 2, y + 2, MouseButton.NONE, false);
        check(scene().getCursor() == null || scene().getCursor() == Cursor.DEFAULT, "Maximized cursor is normal");
        fire(MouseEvent.MOUSE_PRESSED, 2, 2, x + 2, y + 2, MouseButton.PRIMARY, true);
        fire(MouseEvent.MOUSE_DRAGGED, 102, 102, x + 102, y + 102, MouseButton.PRIMARY, true);
        checkNear(stage.getWidth(), width, "Maximized width ignores resize");
        checkNear(stage.getHeight(), height, "Maximized height ignores resize");
        checkNear(stage.getX(), x, "Maximized X ignores resize");
    }

    private void selectView(String view) {
        ((TitledPane) node("sidebar-settings")).setExpanded(false);
        if (node("right-rail").isVisible()) button("toggle-queue").fire();
        switch (view) {
            case "settings" -> {
                button("nav-home").fire();
                ((TitledPane) node("sidebar-settings")).setExpanded(true);
                ((TitledPane) node("settings-ai")).setExpanded(true);
                ((TitledPane) node("settings-data")).setExpanded(true);
            }
            case "queue" -> { button("nav-search").fire(); button("toggle-queue").fire(); }
            case "immersive" -> button("open-immersive").fire();
            default -> button("nav-" + view).fire();
        }
    }

    private void verifyLayoutAndSnapshot(String view, int width, int height) {
        layout();
        checkNear(scene().getWidth(), width, "Scene width " + view);
        checkNear(scene().getHeight(), height, "Scene height " + view);
        for (String id : List.of("player-track-entry", "now-playing", "status", "library-status", "previous-button",
                "play-button", "next-button", "repeat-button", "timing", "progress-slider", "favorite-current",
                "open-immersive", "open-mini", "toggle-queue", "volume-slider")) fitsScene(node(id));
        checkSiblings(node("previous-button").getParent());
        checkSiblings(node("favorite-current").getParent());
        switch (view) {
            case "home" -> { fitsScene(node("home-query")); fitsScene(node("home-search-button")); }
            case "search", "queue" -> {
                for (String id : List.of("search-query", "search-kind", "search-button", "search-list")) fitsScene(node(id));
                checkSiblings(node("search-query").getParent());
                if (view.equals("queue")) { fitsScene(node("right-rail")); fitsScene(node("queue-list")); fitsScene(node("queue-manage")); }
            }
            case "settings" -> {
                ScrollPane scroll = (ScrollPane) node("settings-scroll");
                check(scroll.getContent().getLayoutBounds().getWidth() <= scroll.getWidth() + 1, "Settings content fits width");
                checkWidths((Parent) node("main-toolbar"), scroll.getContent().getLayoutBounds().getWidth());
                fitsScene(node("sidebar-scroll"));
            }
            case "immersive" -> {
                for (String id : List.of("immersive-cover", "immersive-collapse", "immersive-now-playing", "immersive-lyrics",
                        "immersive-open-source", "immersive-favorite", "immersive-add-playlist", "immersive-match-lyrics",
                        "immersive-earlier", "immersive-later", "immersive-full-text")) fitsScene(node(id));
                checkSiblings(node("immersive-collapse").getParent());
                Node title = scene().lookup(".immersive-title");
                fitsScene(title);
                fitsScene(scene().lookup(".immersive-artist"));
                checkVerticalSiblings(title.getParent());
                Bounds lyrics = node("immersive-lyrics").localToScene(node("immersive-lyrics").getLayoutBounds());
                Bounds footer = node("player-track-entry").localToScene(node("player-track-entry").getLayoutBounds());
                check(lyrics.getMaxY() < footer.getMinY(), "Lyrics end before the footer");
            }
            case "local" -> fitsScene(node("local-list"));
            case "favorites" -> fitsScene(node("favorites-list"));
            case "playlists" -> fitsScene(node("playlist-scroll"));
        }
        try { snapshot("resize-" + view + "-" + width + "x" + height + ".png"); }
        catch (Exception error) { throw new RuntimeException(error); }
    }

    private void checkWidths(Parent parent, double maxWidth) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isManaged() || !child.isVisible()) continue;
            check(child.getLayoutBounds().getWidth() <= maxWidth + 1, "Settings child width: " + child.getId());
            if (child instanceof TitledPane titled && titled.isExpanded() && titled.getContent() instanceof Parent content)
                checkWidths(content, maxWidth);
            else if (child instanceof javafx.scene.layout.Pane pane) checkWidths(pane, maxWidth);
        }
    }

    private void fitsScene(Node node) {
        check(node.isVisible() && node.isManaged(), "Visible/managed " + node.getId());
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        // JavaFX rounds child extents to the physical pixel grid on scaled Windows displays.
        double toleranceX = 1 / stage.getOutputScaleX() + .01;
        double toleranceY = 1 / stage.getOutputScaleY() + .01;
        check(bounds.getMinX() >= -toleranceX && bounds.getMaxX() <= scene().getWidth() + toleranceX,
                "Horizontal overflow " + node.getId() + ": " + bounds);
        check(bounds.getMinY() >= -toleranceY && bounds.getMaxY() <= scene().getHeight() + toleranceY,
                "Vertical overflow " + node.getId() + ": " + bounds);
    }

    private void checkSiblings(Parent parent) {
        Bounds previous = null;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isVisible() || !child.isManaged()) continue;
            Bounds bounds = child.localToScene(child.getLayoutBounds());
            if (previous != null) check(previous.getMaxX() <= bounds.getMinX() + .5, "Overlapping controls in " + parent.getStyleClass());
            previous = bounds;
        }
    }

    private void checkVerticalSiblings(Parent parent) {
        Bounds previous = null;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isVisible() || !child.isManaged()) continue;
            Bounds bounds = child.localToScene(child.getLayoutBounds());
            if (previous != null) check(previous.getMaxY() <= bounds.getMinY() + .01,
                    "Overlapping title, artist or lyrics in " + parent.getStyleClass());
            previous = bounds;
        }
    }

    private void snapshot(String name) throws Exception {
        WritableImage image = scene().snapshot(null);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++)
            for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path path = root.resolve(name);
        check(ImageIO.write(output, "png", path.toFile()), "PNG writer available");
        System.out.println("SCREENSHOT: " + path);
    }

    private void fire(javafx.event.EventType<MouseEvent> type, double x, double y, double sx, double sy,
                      MouseButton button, boolean primaryDown) {
        fire(scene().getRoot(), type, x, y, sx, sy, button, primaryDown);
    }

    private void fire(Node target, javafx.event.EventType<MouseEvent> type, double x, double y, double sx, double sy,
                      MouseButton button, boolean primaryDown) {
        Event.fireEvent(target, new MouseEvent(type, x, y, sx, sy, button, 1,
                false, false, false, false, primaryDown, false, button == MouseButton.SECONDARY,
                false, false, type != MouseEvent.MOUSE_DRAGGED, new PickResult(target, x, y)));
    }

    private Scene scene() { return stage.getScene(); }
    private Node node(String id) {
        Node result = scene().lookup("#" + id);
        check(result != null, "Node exists: " + id);
        return result;
    }
    private Button button(String id) { return (Button) node(id); }
    private void layout() { scene().getRoot().applyCss(); scene().getRoot().layout(); }
    private void checkNear(double actual, double expected, String message) {
        check(Math.abs(actual - expected) <= 1, message + ": " + actual + " expected " + expected);
    }
    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private void after(int millis, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.millis(millis));
        pause.setOnFinished(event -> {
            try { action.run(); } catch (Throwable error) { fail(error); }
        });
        pause.play();
    }
    private void next() {
        if (steps.isEmpty()) {
            System.out.println("PASS WindowResizeSmoke: " + assertions + " assertions; "
                    + (getParameters().getRaw().contains("--filled-compact") ? "filled compact immersive view"
                    : "8 edge drags; 24 view/size screenshots"));
            shutdown();
            Platform.exit();
            return;
        }
        steps.remove().run();
        after(180, this::next);
    }
    private void shutdown() {
        if (app != null) app.stop();
        if (stage != null) stage.hide();
        if (service != null) service.stop(0);
    }
    private void fail(Throwable error) {
        error.printStackTrace(System.err);
        try { shutdown(); } catch (Throwable ignored) { }
        System.exit(1);
    }
    public static void main(String[] args) { launch(args); }
}
