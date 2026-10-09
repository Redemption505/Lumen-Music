package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Real service, native hover and audio-clock acceptance for friends' feedback. */
public final class FriendsFeedbackSmoke extends Application {
    private static final String LONG = "这是一句检查迷你播放器和桌面歌词实际播放时钟的长歌词，".repeat(12);
    private static final String LRC = "[00:00.00]" + LONG + "\n[00:20.00]第二句歌词已切换\n[00:40.00]第三句\n";
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean tooltipHidingStackRecorded = new AtomicBoolean();
    private final boolean visualOnly = Boolean.getBoolean("lumen.scene.smoke.visualOnly");
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Robot robot;
    private MouseTrace hoverTrace;
    private MouseTrace miniTrace;
    private Path root;
    private LibraryClient library;
    private Track first;
    private Track second;
    private String playlistId;
    private String deleteId;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(150_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL friends feedback watchdog: " + phase);
                System.exit(1);
            }
        }, "friends-feedback-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL friends feedback phase: " + phase);
                error.printStackTrace(System.err);
                try { fx(() -> { if (root != null && stage.getScene() != null)
                    snapshot(mini() == null ? stage.getScene() : mini().getScene(), "failure-current-scene.png"); }); }
                catch (Throwable captureError) { captureError.printStackTrace(System.err); }
                finish(1);
            }
        }, "friends-feedback-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        prepare();
        fx(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); robot = new Robot();
            stage.setAlwaysOnTop(!visualOnly);
            stage.setWidth(860); stage.setHeight(600); stage.setX(50); stage.setY(50); stage.toFront(); });
        await(12_000, () -> fxValue(() -> picker().getItems().size() == 2), "library fixture did not load");
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()));
        System.out.println("MODE: " + (visualOnly ? "visual preflight; native mouse checks pending" : "full native mouse acceptance"));
        phase = "three themes sidebar alignment and stable native tooltip";
        for (ThemeController.Mode mode : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT, ThemeController.Mode.LUMEN)) {
            fx(() -> {
                for (int i = 0; i < ThemeController.Mode.values().length && field(app, "themeMode", ThemeController.Mode.class) != mode; i++) click("theme-button");
                check(field(app, "themeMode", ThemeController.Mode.class) == mode, "theme did not change");
                click("nav-home"); layout(stage.getScene());
                alignment(); snapshot(stage.getScene(), mode.name().toLowerCase() + "-sidebar-860x600.png");
            });
            if (visualOnly) tooltipVisualPreflight(mode.name().toLowerCase());
            else hover(mode.name().toLowerCase());
        }
        phase = "destructive cancellation has no library write";
        LibraryClient.Snapshot before = library.load();
        byte[] beforeFile = Files.readAllBytes(root.resolve("library.json"));
        fx(() -> { openPlaylist(deleteId); respond(() -> click("delete-playlist"), false); });
        check(before.equals(library.load()) && Arrays.equals(beforeFile, Files.readAllBytes(root.resolve("library.json"))),
                "cancelled playlist deletion wrote library data");
        fx(() -> respond(() -> click("delete-playlist"), true));
        await(8_000, () -> library.load().playlists().stream().noneMatch(item -> item.id().equals(deleteId)), "confirmed deletion did not persist");
        check(library.load().favorites().equals(before.favorites())
                && library.load().playlists().stream().filter(item -> item.id().equals(playlistId)).findFirst().orElseThrow()
                .equals(before.playlists().stream().filter(item -> item.id().equals(playlistId)).findFirst().orElseThrow()),
                "playlist deletion affected retained playlist or favorites");
        await(8_000, () -> fxValue(() -> picker().getItems().size() == 1), "UI did not refresh deleted playlist");
        fx(() -> field(app, "trackPreferences", TrackPreferencesStore.class).markUnavailable(first.id(), "Synthetic confirmed missing resource"));
        before = library.load(); beforeFile = Files.readAllBytes(root.resolve("library.json"));
        fx(() -> { click("sidebar-settings"); respond(() -> click("cleanup-unavailable-all"), false); });
        check(before.equals(library.load()) && Arrays.equals(beforeFile, Files.readAllBytes(root.resolve("library.json"))),
                "cancelled unavailable cleanup wrote library data");
        fx(() -> field(app, "trackPreferences", TrackPreferencesStore.class).clearUnavailable(first.id()));
        System.out.println("PASS cancelled delete/cleanup zero library writes, confirmed deletion preserves other data");

        phase = "default immersive desktop pet and matched lyrics";
        fx(() -> {
            check(field(app, "petOnDesktopImmersive", Boolean.class), "fresh profile did not default desktop immersive pet on");
            openPlaylist(playlistId);
            ListView<Track> tracks = tracks(); tracks.getSelectionModel().clearAndSelect(0);
            tracks.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, MouseButton.PRIMARY, 2,
                    false, false, false, false, true, false, false, true, false, false, null));
        });
        await(12_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > .2), "local WAV did not play");
        await(6_000, () -> fxValue(() -> field(app, "activeLyrics", List.class).equals(Lyrics.parse(LRC))), "saved matched lyrics were not restored");
        fx(() -> {
            petPopup().addEventHandler(WindowEvent.WINDOW_SHOWING, event -> desktopLyricDiagnostic("before native show"));
            petPopup().addEventHandler(WindowEvent.WINDOW_SHOWN, event -> desktopLyricDiagnostic("after native show"));
            click("open-immersive");
        });
        await(6_000, () -> fxValue(() -> pet().isShowing() && petPopup().isShowing()
                && field(pet(), "lyric", String.class).equals(LONG)), "desktop pet did not receive matched long lyric");
        fx(() -> {
            layout(petPopup().getScene()); layout(stage.getScene());
            Label immersiveLine = (Label) node(stage.getScene(), "immersive-current-lyric");
            check(Math.abs(petText().getFont().getSize() - 16) < .05,
                    "desktop lyric actual font size is not 16: " + petText().getFont());
            check(petText().getFont().getFamily().equals(immersiveLine.getFont().getFamily()),
                    "desktop lyric font family differs from immersive current lyric: "
                    + petText().getFont() + " vs " + immersiveLine.getFont());
            String displayed = petText().getText();
            check(displayed.endsWith("…") && LONG.startsWith(displayed.substring(0, displayed.length() - 1)),
                    "desktop long lyric is not a readable prefix of the complete matched line");
            javafx.scene.text.Text rendered = (javafx.scene.text.Text) petText().lookup(".text");
            desktopLyricDiagnostic("settled long lyric");
            snapshot(petPopup().getScene(), "desktop-long-lyric.png");
            check(rendered.getText().equals(displayed), "desktop lyric Skin replaced or clipped measured display text");
            checkTextClip(rendered);
            javafx.scene.text.Text twoLines = new javafx.scene.text.Text("Ag\nAg");
            twoLines.setFont(rendered.getFont());
            twoLines.setBoundsType(rendered.getBoundsType());
            twoLines.setLineSpacing(petText().getLineSpacing());
            check(Math.abs(rendered.getLayoutBounds().getHeight() - twoLines.getLayoutBounds().getHeight())
                            <= 1 / stage.getOutputScaleY(),
                    "desktop long lyric does not render exactly two visible lines");
            snapshot(petPopup().getScene(), "desktop-long-lyric.png");
            invoke("seekToFraction", new Class<?>[]{double.class}, .37);
        });
        await(5_000, () -> fxValue(() -> petText().getText().equals("第二句歌词已切换")), "desktop lyric did not follow actual seek");
        fx(() -> click("play-button"));
        await(3_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED && !petPopup().isShowing()), "pause did not hide desktop lyric");
        fx(() -> { invoke("setLyrics", new Class<?>[]{List.class, String.class}, List.of(), "Synthetic no lyrics");
            check(!petPopup().isShowing(), "cleared lyrics left a desktop popup");
            invoke("setLyrics", new Class<?>[]{List.class, String.class}, Lyrics.parse(LRC), "Synthetic matched lyrics");
            invoke("seekToFraction", new Class<?>[]{double.class}, .08); click("play-button"); click("open-mini"); });
        await(5_000, () -> fxValue(() -> mini() != null && mini().isShowing() && !stage.isShowing()), "mini scene did not open");
        phase = "mini clock, pause, drag seek and close continuity";
        miniChecks();
        System.out.println("PASS desktop default/matched long lyric/line change/pause/clear and mini integrated playback");
    }

    private void alignment() throws Exception {
        double x = Double.NaN;
        for (String id : List.of("nav-home", "nav-search", "nav-local", "nav-favorites", "nav-diary")) {
            ButtonBase button = (ButtonBase) node(stage.getScene(), id);
            Text text = (Text) button.lookup(".text");
            check(text != null && text.isVisible(), "sidebar text missing " + id);
            double current = text.localToScene(text.getLayoutBounds()).getMinX();
            if (Double.isFinite(x)) check(Math.abs(current - x) <= 1.5, "sidebar text origin differs " + id + ": " + current + " vs " + x);
            x = current;
        }
        ButtonBase history = (ButtonBase) node(stage.getScene(), "nav-diary");
        check(history.getText().equals("最近播放"), "recent playback label is stale");
        Node iconNode = history.getGraphic() instanceof SVGPath ? history.getGraphic() : history.getGraphic().lookup(".nav-icon");
        check(iconNode instanceof SVGPath, "history SVG is missing from its fixed-width icon frame");
        SVGPath graphic = (SVGPath) iconNode;
        Method icon = LumenFxApp.class.getDeclaredMethod("iconPath", String.class); icon.setAccessible(true);
        check(graphic.getContent().equals(icon.invoke(null, "history")) && !graphic.getContent().equals(icon.invoke(null, "list")), "history graphic is not the history SVG");
        List<Label> headings = field(app, "diaryPane", DiaryPane.class).lookupAll(".diary-title").stream()
                .filter(Label.class::isInstance).map(Label.class::cast).toList();
        check(!headings.isEmpty() && headings.stream().allMatch(label -> label.getText().equals("最近播放")), "diary heading was not renamed");
    }

    private void hover(String theme) throws Exception {
        fx(() -> {
            stage.show(); stage.toFront(); stage.requestFocus(); layout(stage.getScene());
            if (hoverTrace == null) hoverTrace = traceMouse(stage.getScene(), node(stage.getScene(), "quick-search-open"), "main-quick-search");
        });
        Thread.sleep(220);
        Tooltip tip = fxValue(() -> ((ButtonBase) node(stage.getScene(), "quick-search-open")).getTooltip());
        AtomicInteger shows = new AtomicInteger(), hides = new AtomicInteger();
        fx(() -> {
            tip.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> { shows.incrementAndGet(); hoverDiagnostic(theme, "shown", tip, shows, hides); });
            tip.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
                hoverDiagnostic(theme, "hiding", tip, shows, hides);
                if (tip.getOpacity() > 0 && tooltipHidingStackRecorded.compareAndSet(false, true))
                    new Exception("First visible native tooltip WINDOW_HIDING call stack").printStackTrace(System.out);
            });
            tip.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> { hides.incrementAndGet(); hoverDiagnostic(theme, "hidden", tip, shows, hides); });
            var screen = Screen.getPrimary().getVisualBounds();
            robot.mouseMove(Math.min(screen.getMaxX() - 5, stage.getX() + stage.getWidth() + 25),
                    Math.max(screen.getMinY() + 5, stage.getY() + 60));
        });
        Thread.sleep(300);
        fx(() -> {
            hoverDiagnostic(theme, "outside-before-entry", tip, shows, hides);
            Bounds target = node(stage.getScene(), "quick-search-open").localToScreen(node(stage.getScene(), "quick-search-open").getLayoutBounds());
            check(target != null, "hover target is off screen"); robot.mouseMove(target.getCenterX(), target.getCenterY());
        });
        long[] visibleSince = {0};
        try { await(4_000, () -> {
            boolean visible = fxValue(() -> tip.isShowing() && tip.getOpacity() > 0 && !tip.isActivated());
            if (!visible) { visibleSince[0] = 0; return false; }
            if (visibleSince[0] == 0) visibleSince[0] = System.nanoTime();
            return System.nanoTime() - visibleSince[0] >= 200_000_000L;
        }, "native Robot hover did not show a stable visible tooltip"); }
        catch (AssertionError failure) {
            fx(() -> {
                hoverDiagnostic(theme, "show-timeout", tip, shows, hides);
                screenCapture(theme + "-hover-timeout-primary.png");
                windowDiagnostic();
            });
            throw failure;
        }
        int shownBaseline = shows.get(), hiddenBaseline = hides.get();
        fx(() -> hoverDiagnostic(theme, "visible-baseline", tip, shows, hides));
        for (int i = 0; i < 7; i++) {
            fx(() -> { field(app, "updateSidebarLayout", Runnable.class).run();
                node(stage.getScene(), "nav-home").pseudoClassStateChanged(PseudoClass.getPseudoClass("active"), true);
                hoverDiagnostic(theme, "stable-hover", tip, shows, hides);
                check(((ButtonBase) node(stage.getScene(), "quick-search-open")).getTooltip() == tip, "layout replaced tooltip instance");
                check(tip.isShowing() && tip.getOpacity() > 0 && !tip.isActivated(),
                        "tooltip disappeared or returned to preactivation during stable hover"); });
            Thread.sleep(450);
        }
        fx(() -> {
            check(shows.get() == shownBaseline && hides.get() == hiddenBaseline,
                    "tooltip flickered after actual visibility baseline on sidebar refresh");
            verifyTooltipVisuals(tip, theme + "-native-tooltip.png");
            robot.mouseMove(stage.getX() + stage.getWidth() - 20, stage.getY() + 45);
        });
        await(2_000, () -> fxValue(() -> !tip.isShowing()), "tooltip did not hide after pointer left");
        System.out.println("PASS " + theme + " native hover stable over 3 seconds, popup transparent with rendered text");
    }

    private void tooltipVisualPreflight(String theme) throws Exception {
        Tooltip tip = fxValue(() -> ((ButtonBase) node(stage.getScene(), "quick-search-open")).getTooltip());
        fx(() -> {
            var screen = Screen.getPrimary().getVisualBounds();
            double x = robot.getMouseX() < screen.getMinX() + screen.getWidth() / 2
                    ? screen.getMaxX() - 400 : screen.getMinX() + 20;
            double y = robot.getMouseY() < screen.getMinY() + screen.getHeight() / 2
                    ? screen.getMaxY() - 120 : screen.getMinY() + 20;
            tip.show(stage, x, y);
        });
        try {
            Thread.sleep(130);
            fx(() -> { check(tip.isShowing(), "visual preflight tooltip did not show");
                verifyTooltipVisuals(tip, theme + "-visual-preflight-tooltip.png"); });
        } finally { fx(tip::hide); }
        System.out.println("PASS " + theme + " tooltip visual preflight: rendered text and transparent popup corner");
        System.out.println("SKIP " + theme + " native hover stability pending; preflight used explicit show without moving the mouse");
    }

    private void verifyTooltipVisuals(Tooltip tip, String filename) throws Exception {
        Scene scene = tip.getScene(); layout(scene);
        check(tip.getSkin() != null && tip.getSkin().getNode() instanceof Label label
                && !label.getText().isBlank() && label.getWidth() > 20, "tooltip text was not rendered");
        WritableImage image = scene.snapshot(null);
        check(image.getPixelReader().getColor(0, 0).getOpacity() < .1, "tooltip popup bridge has opaque outer corner");
        check(opaquePixels(image) > 100, "tooltip bubble/text is visually empty");
        snapshot(scene, filename);
    }

    private void hoverDiagnostic(String theme, String event, Tooltip tip, AtomicInteger shows, AtomicInteger hides) {
        ButtonBase target = (ButtonBase) node(stage.getScene(), "quick-search-open");
        Bounds bounds = target.localToScreen(target.getLayoutBounds());
        double mouseX = robot.getMouseX(), mouseY = robot.getMouseY();
        boolean popupContainsMouse = mouseX >= tip.getX() && mouseX < tip.getX() + tip.getWidth()
                && mouseY >= tip.getY() && mouseY < tip.getY() + tip.getHeight();
        Node skin = tip.getSkin() == null ? null : tip.getSkin().getNode();
        System.out.println("HOVER " + theme + " " + event + " targetHover=" + target.isHover()
                + " disabled=" + target.isDisabled() + " visible=" + target.isVisible() + " focusedWindow=" + stage.isFocused()
                + " screenBounds=" + bounds + " robot=(" + robot.getMouseX() + "," + robot.getMouseY() + ")"
                + " tooltipShowing=" + tip.isShowing() + " activated=" + tip.isActivated()
                + " opacity=" + tip.getOpacity()
                + " showDelay=" + tip.getShowDelay() + " showDuration=" + tip.getShowDuration()
                + " shows=" + shows.get() + " hides=" + hides.get());
        System.out.println("HOVER POPUP bounds=(" + tip.getX() + "," + tip.getY() + ","
                + tip.getWidth() + "," + tip.getHeight() + ") containsRobot=" + popupContainsMouse
                + " anchor=(" + tip.getAnchorX() + "," + tip.getAnchorY() + ")"
                + " skinBoundsInParent=" + (skin == null ? null : skin.getBoundsInParent())
                + " skinLayoutBounds=" + (skin == null ? null : skin.getLayoutBounds())
                + " skinScreenBounds=" + (skin == null ? null : skin.localToScreen(skin.getBoundsInLocal()))
                + " skinEffect=" + (skin == null ? null : skin.getEffect()));
        System.out.println("HOVER EVENTS " + (hoverTrace == null ? "uninstalled" : hoverTrace)
                + " stage=(" + stage.getX() + "," + stage.getY() + "," + stage.getWidth() + "," + stage.getHeight() + ")"
                + " outputScale=(" + stage.getOutputScaleX() + "," + stage.getOutputScaleY() + ")"
                + " iconified=" + stage.isIconified());
    }

    private void miniChecks() throws Exception {
        await(4_000, () -> fxValue(() -> miniLyric().getText().equals(LONG)), "mini does not show matched lyric");
        Thread.sleep(220);
        double before = fxValue(() -> miniText().getTranslateX());
        Thread.sleep(900);
        check(fxValue(() -> miniText().getTranslateX()) < before - 1, "mini long lyric did not move with audio clock");
        fx(() -> ((ButtonBase) node(mini().getScene(), "mini-play")).fire());
        await(2_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "mini pause did not control player");
        Thread.sleep(200);
        double paused = fxValue(() -> miniText().getTranslateX()), position = fxValue(() -> audio().position().toSeconds());
        Thread.sleep(650);
        check(Math.abs(fxValue(() -> miniText().getTranslateX()) - paused) < .6
                && Math.abs(fxValue(() -> audio().position().toSeconds()) - position) < .1, "mini paused lyric or media clock continued moving");
        for (ThemeController.Mode mode : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT, ThemeController.Mode.LUMEN)) {
            fx(() -> {
                for (int i = 0; i < ThemeController.Mode.values().length && field(app, "themeMode", ThemeController.Mode.class) != mode; i++) click("theme-button");
                check(field(app, "themeMode", ThemeController.Mode.class) == mode, "mini theme did not change");
            });
            for (int width : List.of(540, 460)) {
                fx(() -> { mini().setWidth(width); });
                Thread.sleep(130);
                fx(() -> verifyMiniVisuals(mode.name().toLowerCase() + "-mini-" + width + "-paused-rounded.png"));
            }
        }
        if (visualOnly) {
            System.out.println("SKIP native mini slider lock/drag/seek pending; visual preflight leaves the mouse untouched");
        } else {
            double x = fxValue(() -> mini().getX()), y = fxValue(() -> mini().getY());
            fx(() -> {
                Slider slider = (Slider) node(mini().getScene(), "mini-progress-slider");
                miniTrace = traceMouse(mini().getScene(), slider, "mini-progress");
                Node thumb = slider.lookup(".thumb"); Bounds target = thumb.localToScreen(thumb.getLayoutBounds());
                System.out.println("MINI ROBOT thumb screenBounds=" + target + " stage=(" + mini().getX() + "," + mini().getY()
                        + ") outputScale=" + mini().getOutputScaleX());
                robot.mouseMove(target.getCenterX(), target.getCenterY()); robot.mousePress(MouseButton.PRIMARY);
            });
            try { await(2_000, () -> fxValue(() -> field(app, "progress", Slider.class).isValueChanging()), "mini slider did not lock main progress during drag"); }
            catch (AssertionError failure) {
                fx(() -> { System.out.println("MINI ROBOT lock-timeout " + miniTrace + " robot=(" + robot.getMouseX() + "," + robot.getMouseY() + ")");
                    screenCapture("mini-slider-lock-timeout-primary.png"); windowDiagnostic(); });
                throw failure;
            }
            fx(() -> {
                Slider slider = (Slider) node(mini().getScene(), "mini-progress-slider");
                Node track = slider.lookup(".track"); Bounds target = track.localToScreen(track.getLayoutBounds());
                System.out.println("MINI ROBOT drag track screenBounds=" + target);
                robot.mouseMove(target.getMinX() + target.getWidth() * .5, target.getCenterY());
            });
            Thread.sleep(650);
            check(Math.abs(fxValue(() -> mini().getX()) - x) < 1 && Math.abs(fxValue(() -> mini().getY()) - y) < 1, "mini slider drag moved the native window");
            double fraction = fxValue(() -> ((Slider) node(mini().getScene(), "mini-progress-slider")).getValue());
            fx(() -> System.out.println("MINI ROBOT drag-state " + miniTrace + " robot=(" + robot.getMouseX() + "," + robot.getMouseY()
                    + ") slider=" + ((Slider) node(mini().getScene(), "mini-progress-slider")).getValue()));
            check(fraction > .35 && fraction < .7, "Robot slider gesture did not change value");
            fx(() -> robot.mouseRelease(MouseButton.PRIMARY));
            await(5_000, () -> fxValue(() -> !field(app, "progress", Slider.class).isValueChanging()
                    && Math.abs(audio().position().toSeconds() - fraction * 60) < 1.2), "mini slider release did not seek actual audio");
        }
        PlayerQueue.Snapshot queue = fxValue(() -> field(app, "queue", PlayerQueue.class).snapshot());
        double seeked = fxValue(() -> audio().position().toSeconds());
        fx(() -> { snapshot(mini().getScene(), visualOnly ? "mini-visual-preflight-before-close.png" : "mini-native-slider-seek.png"); ((ButtonBase) node(mini().getScene(), "mini-close")).fire(); });
        await(4_000, () -> fxValue(() -> stage.isShowing() && mini() == null), "mini close did not return main window");
        check(queue.equals(fxValue(() -> field(app, "queue", PlayerQueue.class).snapshot()))
                && Math.abs(fxValue(() -> audio().position().toSeconds()) - seeked) < .15
                && fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "mini close changed queue, position or paused state");
        fx(() -> snapshot(stage.getScene(), "main-returned-from-mini.png"));
    }

    private void verifyMiniVisuals(String filename) throws Exception {
            Stage mini = mini(); layout(mini.getScene());
            check(mini.getStyle() == StageStyle.TRANSPARENT && Color.TRANSPARENT.equals(mini.getScene().getFill()), "mini native window is opaque");
            check(mini.getScene().getRoot().getClip() instanceof Rectangle clip && clip.getArcWidth() >= 24, "mini has no rounded clip");
            WritableImage image = mini.getScene().snapshot(null);
            check(image.getPixelReader().getColor(0, 0).getOpacity() < .05 && opaquePixels(image) > 10_000, "mini rendered corner/center transparency is wrong");
            for (String id : List.of("mini-play", "mini-next", "mini-previous", "mini-favorite", "mini-return", "mini-close", "mini-current-lyric", "mini-progress-slider")) fits(mini.getScene(), id);
            snapshot(mini.getScene(), filename);
    }

    private void prepare() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("build") && !Files.exists(root.resolve("library.json")), "fresh isolated profile required");
        for (String name : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(name)).toAbsolutePath().normalize(); check(path.equals(root) || root.equals(path.getParent()), "profile escapes fixture " + name);
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "personal API key present");
        URI service = URI.create(required("LUMEN_SERVICE_URL")); check("127.0.0.1".equals(service.getHost()) && "http".equals(service.getScheme()), "loopback service required");
        String installed = System.getProperty("lumen.test.installedJar");
        if (installed != null) check(Files.isSameFile(Path.of(installed), Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())), "AppHome production class origin differs");
        Path wav = root.resolve("friends-feedback-60s.wav"); int rate = 8000;
        byte[] pcm = new byte[rate * 60 * 2];
        for (int i = 0; i < rate * 60; i++) { short sample = (short) (350 * Math.sin(i * 2 * Math.PI * 220 / rate)); pcm[i * 2] = (byte) sample; pcm[i * 2 + 1] = (byte) (sample >>> 8); }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, rate * 60L)) { check(AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile()) > 0, "local WAV generation failed"); }
        first = new Track("friends-first", "长歌词真实播放验收", "本地隔离资料", 60, "local", wav.toUri());
        second = new Track("friends-second", "保留队列的第二首", "本地隔离资料", 60, "local", wav.toUri());
        library = new LibraryClient(service); library.favorite(first); library.favorite(second);
        playlistId = library.createPlaylist("保留的播放歌单").playlists().get(0).id();
        library.addToPlaylist(playlistId, first); library.addToPlaylist(playlistId, second);
        deleteId = library.createPlaylist("明确删除的测试歌单").playlists().stream().filter(item -> !item.id().equals(playlistId)).findFirst().orElseThrow().id();
        library.addToPlaylist(deleteId, first);
        Path selections = LyricsSelectionStore.defaultDirectory(); check(selections.startsWith(root), "lyrics directory escapes fixture");
        new LyricsSelectionStore(selections).save(first, new LyricsSelectionStore.Selection(first.title(), first.artist(), LRC, 0, LyricsSelectionStore.Origin.MANUAL, 0));
    }

    private void respond(Runnable action, boolean accepted) {
        Platform.runLater(() -> {
            DialogPane pane = Window.getWindows().stream().filter(Window::isShowing).filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                    .filter(dialog -> "destructive-confirmation".equals(dialog.getId())).findFirst().orElseThrow();
            Button cancel = (Button) node(pane.getScene(), "destructive-cancel"), confirm = (Button) node(pane.getScene(), "destructive-confirm");
            check(cancel.isDefaultButton() && !confirm.isDefaultButton(), "destructive default is not cancel");
            (accepted ? confirm : cancel).fire();
        }); action.run();
    }
    private void openPlaylist(String id) { click("nav-playlists"); picker().setValue(picker().getItems().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow()); layout(stage.getScene()); }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return (ComboBox<LibraryClient.Playlist>) node(stage.getScene(), "playlist-picker"); }
    @SuppressWarnings("unchecked") private ListView<Track> tracks() { return (ListView<Track>) node(stage.getScene(), "playlist-tracks"); }
    private DesktopPetWindow pet() { return field(app, "desktopPet", DesktopPetWindow.class); }
    private Popup petPopup() { return field(pet(), "noticePopup", Popup.class); }
    private Label petText() { return field(pet(), "notice", Label.class); }
    private Stage mini() { return field(app, "miniStage", Stage.class); }
    private void desktopLyricDiagnostic(String marker) {
        Label label = petText();
        Node skin = label.lookup(".text");
        Text text = skin instanceof Text value ? value : null;
        System.out.println("DESKTOP LYRIC " + marker + " scale=" + petPopup().getOutputScaleY()
                + " font=" + label.getFont() + " boundsType=" + (text == null ? null : text.getBoundsType())
                + " label=" + label.getWidth() + "x" + label.getHeight() + " pref/maxHeight="
                + label.getPrefHeight() + "/" + label.getMaxHeight()
                + " snappedInsets=" + label.snappedTopInset() + "," + label.snappedRightInset()
                + "," + label.snappedBottomInset() + "," + label.snappedLeftInset()
                + " wrapWidth=" + (text == null ? null : text.getWrappingWidth())
                + " renderedBounds=" + (text == null ? null : text.getLayoutBounds())
                + " clip=" + (text == null || text.getClip() == null ? null : text.getClip().getBoundsInParent())
                + " labelText=" + label.getText() + " skinText=" + (text == null ? null : text.getText()));
    }
    private static void checkTextClip(Text text) {
        Bounds body = text.getLayoutBounds();
        Node clip = text.getClip();
        if (clip != null) {
            Bounds visible = clip.getBoundsInParent();
            check(visible.getMinX() <= body.getMinX() + .001 && visible.getMinY() <= body.getMinY() + .001
                            && visible.getMaxX() + .001 >= body.getMaxX() && visible.getMaxY() + .001 >= body.getMaxY(),
                    "desktop lyric clip cuts off text or ellipsis: text=" + body + " clip=" + visible);
        }
        Bounds inScene = text.localToScene(body);
        check(inScene.getMinX() >= -.001 && inScene.getMinY() >= -.001
                        && inScene.getMaxX() <= text.getScene().getWidth() + .001
                        && inScene.getMaxY() <= text.getScene().getHeight() + .001,
                "desktop lyric popup bounds cut off rendered text: " + inScene);
    }
    private Label miniLyric() { return (Label) node(mini().getScene(), "mini-current-lyric"); }
    private Text miniText() { Label lyric = miniLyric(); check(lyric.getGraphic() instanceof LyricMarquee, "mini lyric has no marquee"); return (Text) ((Parent) lyric.getGraphic()).getChildrenUnmodifiable().get(0); }
    private AudioPlayer audio() { return field(app, "audio", AudioPlayer.class); }
    private void click(String id) { ((ButtonBase) node(stage.getScene(), id)).fire(); }
    private static Node node(Scene scene, String id) { Node result = scene.lookup("#" + id); check(result != null, "missing UI #" + id); return result; }
    private static void layout(Scene scene) { scene.getRoot().applyCss(); scene.getRoot().layout(); }
    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return type.cast(value.get(target)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private void invoke(String name, Class<?>[] types, Object... values) throws Exception { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, values); }
    private static void fits(Scene scene, String id) { Node item = node(scene, id); Bounds bounds = item.localToScene(item.getLayoutBounds()); check(item.isVisible() && item.isManaged() && bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1 && bounds.getMinY() >= -1 && bounds.getMaxY() <= scene.getHeight() + 1, "mini control clipped: " + id + " " + bounds); }
    private static int opaquePixels(WritableImage image) { int count = 0; for (int y = 0; y < (int) image.getHeight(); y++) for (int x = 0; x < (int) image.getWidth(); x++) if (image.getPixelReader().getColor(x, y).getOpacity() > .9) count++; return count; }
    private MouseTrace traceMouse(Scene scene, Node target, String name) {
        MouseTrace trace = new MouseTrace(name);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> { trace.sceneMoves.incrementAndGet(); mouseDiagnostic(name, "scene-moved", event); });
        target.addEventHandler(MouseEvent.MOUSE_MOVED, event -> { trace.targetMoves.incrementAndGet(); mouseDiagnostic(name, "target-moved", event); });
        target.addEventHandler(MouseEvent.MOUSE_EXITED, event -> { trace.targetExits.incrementAndGet(); mouseDiagnostic(name, "target-exited", event); });
        target.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> mouseDiagnostic(name, "target-entered", event));
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> { trace.presses.incrementAndGet(); mouseDiagnostic(name, "scene-pressed", event); });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> { trace.drags.incrementAndGet(); mouseDiagnostic(name, "scene-dragged", event); });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> mouseDiagnostic(name, "scene-released", event));
        return trace;
    }
    private static void mouseDiagnostic(String name, String event, MouseEvent mouse) {
        System.out.println("MOUSE " + name + " " + event + " screen=(" + mouse.getScreenX() + "," + mouse.getScreenY()
                + ") scene=(" + mouse.getSceneX() + "," + mouse.getSceneY() + ") source=" + mouse.getSource()
                + " target=" + mouse.getTarget() + " pick=" + mouse.getPickResult().getIntersectedNode());
    }
    private void screenCapture(String filename) throws Exception {
        var bounds = Screen.getPrimary().getVisualBounds();
        WritableImage capture = robot.getScreenCapture(null, bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight(), false);
        saveImage(capture, filename);
        System.out.println("SCREEN CAPTURE bounds=" + bounds + " pixels=" + capture.getWidth() + "x" + capture.getHeight());
    }
    private static void windowDiagnostic() {
        for (Window window : Window.getWindows()) if (window.isShowing())
            System.out.println("WINDOW " + window.getClass().getSimpleName() + " x=" + window.getX() + " y=" + window.getY()
                    + " width=" + window.getWidth() + " height=" + window.getHeight() + " focused=" + window.isFocused()
                    + " opacity=" + window.getOpacity() + " root=" + (window.getScene() == null ? "none" : window.getScene().getRoot())
                    + (window instanceof Stage visible ? " title=" + visible.getTitle() : ""));
    }
    private static final class MouseTrace {
        private final String name;
        private final AtomicInteger sceneMoves = new AtomicInteger(), targetMoves = new AtomicInteger(), targetExits = new AtomicInteger();
        private final AtomicInteger presses = new AtomicInteger(), drags = new AtomicInteger();
        private MouseTrace(String name) { this.name = name; }
        @Override public String toString() { return name + " sceneMoves=" + sceneMoves.get() + " targetMoves=" + targetMoves.get()
                + " targetExits=" + targetExits.get() + " presses=" + presses.get() + " drags=" + drags.get(); }
    }
    private void snapshot(Scene scene, String filename) throws Exception {
        layout(scene); saveImage(scene.snapshot(null), filename);
    }
    private void saveImage(WritableImage image, String filename) throws Exception {
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path file = root.resolve(filename); check(ImageIO.write(png, "png", file.toFile()) && Files.size(file) > 300, "screenshot not saved"); System.out.println("SCREENSHOT: " + file);
    }
    private void await(long timeout, CheckedBoolean predicate, String message) throws Exception { long end = System.nanoTime() + timeout * 1_000_000L; while (System.nanoTime() < end) { if (predicate.get()) return; Thread.sleep(70); } throw new AssertionError(message); }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception { CompletableFuture<T> result = new CompletableFuture<>(); Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } }); return result.get(15, TimeUnit.SECONDS); }
    private void finish(int code) { if (!finished.compareAndSet(false, true)) return; try { fx(() -> { if (robot != null && !visualOnly) robot.mouseRelease(MouseButton.PRIMARY); if (stage != null) stage.setAlwaysOnTop(false); if (app != null) app.stop(); for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); } catch (Exception error) { error.printStackTrace(System.err); } System.exit(code); }
    private static String required(String name) { String value = System.getenv(name); check(value != null && !value.isBlank(), "missing " + name); return value; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
