package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
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
import java.util.List;
import java.util.EnumMap;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TabPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Stage 4 / 3.1: real window, rendered pixels and uninterrupted local playback. */
public final class VisualLanguageSmoke extends Application {
    private static final String LONG_TITLE = "漫长的中文与 English title — ".repeat(10).strip();
    private static final int[] COVER_MARKERS = {0xFF00FFFF, 0xFFFFFF00, 0xFFFF00FF, 0xFF00FF00};
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private HttpServer coverServer;
    private Track first;
    private Track missing;
    private String playlistId;
    private String emptyPlaylistId;
    private AudioPlayer originalAudio;
    private PlayerQueue.Snapshot originalQueue;
    private long originalLoad;
    private boolean alignmentNegativeChecked;
    private final EnumMap<ThemeController.Mode, Paint> savedFavoriteColors = new EnumMap<>(ThemeController.Mode.class);

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(285_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL visual language watchdog: " + phase);
                System.exit(1);
            }
        }, "visual-language-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL visual language phase: " + phase);
                error.printStackTrace(System.err);
                try { fx(() -> screenshot(stage.getScene(), "failure.png")); } catch (Exception ignored) { }
                finish(1);
            }
        }, "visual-language-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        prepareFixture();
        fx(() -> {
            app = new LumenFxApp(); app.startUi(stage, List.of());
            stage.setWidth(1280); stage.setHeight(800);
        });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX())
                + " x " + fxValue(() -> stage.getOutputScaleY()));
        activateForFocusCheck(stage);
        await(15_000, () -> fxValue(() -> picker().getItems().size() == 2), "fixture playlists not loaded");
        await(15_000, () -> fxValue(() -> field("versionCatalog", VersionCatalog.class).findByResourceId(first.id()).isPresent()), "fixture performer metadata not loaded");
        playlistLocatorCheck();
        phase = "real local playback and browse restoration";
        fx(() -> {
            openPlaylist(playlistId);
            ListView<Track> tracks = list("playlist-tracks");
            tracks.getSelectionModel().clearAndSelect(0); tracks.fireEvent(doubleClick());
        });
        awaitPlaying(first.id());
        await(4_000, () -> fxValue(() -> ((ButtonBase) node("favorite-current")).getText().equals("取消收藏")),
                "pre-favorited local fixture did not reach playback controls");
        originalAudio = fxValue(this::audio);
        originalLoad = fxValue(() -> field("loadVersion", Long.class));
        originalQueue = fxValue(() -> field("queue", PlayerQueue.class).snapshot());
        check(originalQueue.tracks().size() == 24, "fixture queue lacks enough rows to exercise scrolling");
        await(8_000, () -> fxValue(() -> ((ImageView) node("immersive-artwork-image")).getImage() != null
                && ((ImageView) node("immersive-artwork-image")).getImage().getProgress() >= 1), "cover not loaded");
        fx(this::checkRowIdentity);
        fx(() -> {
            list("playlist-tracks").scrollTo(18);
            ((ScrollPane) node("playlist-scroll")).setVvalue(.7);
            layout();
        });
        pause(300);
        double scroll = fxValue(() -> ((ScrollPane) node("playlist-scroll")).getVvalue());
        int firstVisible = fxValue(this::firstVisibleRow);
        double before = fxValue(() -> audio().position().toSeconds());
        fx(() -> click("open-immersive")); settle();
        fx(() -> {
            checkFullArtwork(); checkFavoriteState(true); checkDisabledSource();
            click("immersive-collapse");
        }); settle();
        fx(() -> {
            check(((TabPane) node("main-tabs")).getSelectionModel().getSelectedIndex() == 4,
                    "immersive return lost playlist browse page");
            check(Math.abs(((ScrollPane) node("playlist-scroll")).getVvalue() - scroll) < .03,
                    "immersive return lost page scroll position");
            check(firstVisibleRow() == firstVisible, "immersive return lost list scroll position");
            preserved(before, true);
        });
        System.out.println("PASS local WAV browse/immersive/return preserves audio, loadVersion, queue and both scroll positions; clock advances");

        if (getParameters().getRaw().contains("--focus-only")) {
            focusEnvironmentCheck();
            for (ThemeController.Mode mode : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT)) {
                fx(() -> { chooseTheme(mode); openScene("home"); }); settle();
                keyboardFocusCheck(mode);
            }
            miniStyleCheck();
            return;
        }

        frontendRenderingCheck();
        frontendInteractionCheck();
        phase = "four scenes five themes two sizes";
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> chooseTheme(mode));
            for (int[] size : new int[][] {{1280, 800}, {860, 600}}) {
                fx(() -> { stage.setWidth(size[0]); stage.setHeight(size[1]); });
                for (String scene : List.of("home", "playlist", "immersive", "settings")) {
                    phase = mode + " " + scene + " " + size[0] + "x" + size[1];
                    fx(() -> openScene(scene)); settle();
                    fx(() -> {
                        checkScene(scene);
                        if (scene.equals("home") || scene.equals("playlist")) transportAlignmentCheck();
                    });
                    // Let restored glyph opacity reach the next paint before retaining QA screenshots.
                    pause(100);
                    fx(() -> {
                        if (size[0] == 1280 || mode == ThemeController.Mode.DARK || mode == ThemeController.Mode.LIGHT)
                            screenshot(stage.getScene(), mode.name().toLowerCase() + "-" + scene + "-" + size[0] + "x" + size[1] + ".png");
                    });
                    if (scene.equals("immersive")) {
                        popupCheck(mode, size[0]);
                        fx(() -> click("immersive-collapse"));
                    }
                }
            }
        }
        fx(() -> preserved(before, true));
        System.out.println("PASS home/playlist/immersive/settings, five themes, 1280x800 + 860x600, rendered contrast, long bilingual titles and popup boundaries");
        focusEnvironmentCheck();
        phase = "keyboard focus remains visible";
        for (ThemeController.Mode mode : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT)) {
            fx(() -> { chooseTheme(mode); openScene("home"); }); settle();
            keyboardFocusCheck(mode);
        }

        phase = "empty playlist and missing artwork/lyrics";
        fx(() -> { chooseTheme(ThemeController.Mode.LIGHT); openPlaylist(emptyPlaylistId); }); settle();
        fx(() -> {
            check(list("playlist-tracks").getItems().isEmpty(), "empty playlist fixture not selected");
            Node placeholder = list("playlist-tracks").getPlaceholder();
            check(placeholder != null && placeholder.isVisible(), "empty playlist has no visible explanation");
            screenshot(stage.getScene(), "empty-playlist-small.png");
            openPlaylist(playlistId);
            list("playlist-tracks").getSelectionModel().clearAndSelect(1);
            list("playlist-tracks").fireEvent(doubleClick());
        });
        awaitPlaying(missing.id());
        fx(() -> {
            invoke("setLyrics", new Class<?>[] {List.class, String.class}, List.of(), "暂无同步歌词");
            click("open-immersive");
        }); settle();
        fx(() -> {
            checkFavoriteState(false);
            ImageView image = (ImageView) node("immersive-artwork-image");
            check(image.getImage() == null, "missing-artwork track reused preceding artwork");
            Label placeholder = node("immersive-cover").lookupAll(".artwork-placeholder").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast).findFirst().orElseThrow();
            check(placeholder.isVisible() && !placeholder.getText().isBlank(), "missing cover has no visible placeholder");
            Node lyricPlaceholder = list("immersive-lyrics").getPlaceholder();
            check(lyricPlaceholder != null && lyricPlaceholder.isVisible(), "missing lyrics has no visible explanation");
            fits(node("immersive-cover")); fits(node("immersive-lyrics"));
            screenshot(stage.getScene(), "missing-cover-lyrics-small.png");
            click("immersive-collapse");
        });
        System.out.println("PASS empty playlist, missing cover and no-lyrics states remain readable in small window");

        phase = "reduced motion immediately updates active window";
        fx(() -> {
            chooseTheme(ThemeController.Mode.DYNAMIC); click("sidebar-settings");
            ScrollPane settings = (ScrollPane) node("settings-scroll"); settings.setVvalue(0); layout();
            CheckBox reduced = (CheckBox) node("reduce-motion");
            if (reduced.isSelected()) reduced.fire();
        });
        String moving = fxValue(() -> stage.getScene().getRoot().getStyle());
        pause(550);
        check(!moving.equals(fxValue(() -> stage.getScene().getRoot().getStyle())), "dynamic theme did not animate before reduced motion");
        fx(() -> {
            ((CheckBox) node("reduce-motion")).fire();
            check(MotionPreferences.reduced(), "UI reduced-motion preference did not apply immediately");
            click("open-immersive"); click("immersive-collapse");
            click("toggle-queue"); click("toggle-queue");
            check(stage.getScene().getRoot().lookupAll("#queue-exit-snapshot").isEmpty(), "reduced motion left queue exit animation");
        });
        String stopped = fxValue(() -> stage.getScene().getRoot().getStyle()); pause(600);
        fx(() -> {
            check(stopped.equals(stage.getScene().getRoot().getStyle()), "reduced motion did not freeze dynamic background immediately");
            check(audio().state() == JavaFxAudioPlayer.State.PLAYING, "reduced motion interrupted playback");
            check(Window.getWindows().stream().filter(Window::isShowing).count() == 1, "navigation created extra windows");
        });
        System.out.println("PASS reduced motion immediately freezes background and removes transition while navigation/audio remain usable");
        miniStyleCheck();
    }

    private void miniStyleCheck() throws Exception {
        phase = "mini return keyboard focus and shared playback";
        AudioPlayer playing = fxValue(this::audio);
        long version = fxValue(() -> field("loadVersion", Long.class));
        PlayerQueue.Snapshot queue = fxValue(() -> field("queue", PlayerQueue.class).snapshot());
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> { chooseTheme(mode); click("open-mini"); });
            Stage mini = fxValue(() -> field("miniStage", Stage.class));
            for (int width : new int[] {540, 460}) {
                fx(() -> { mini.setWidth(width); mini.setHeight(244); });
                activateForFocusCheck(mini);
                Node anchor = fxValue(() -> mini.getScene().lookup("#mini-minimize"));
                Node target = fxValue(() -> mini.getScene().lookup("#mini-return"));
                focusAnchor(anchor);
                FocusPixelAssertions.Baseline baseline = fxValue(() -> {
                    FrontendStyleAssertions.mini(mini.getScene());
                    FrontendRenderAssertions.sliderTrack((Slider) mini.getScene().lookup("#mini-progress-slider"));
                    return FocusPixelAssertions.capture(anchor, target);
                });
                fx(() -> anchor.fireEvent(new KeyEvent(
                        KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, true, false, false, false)));
                awaitFocused(target, "Shift+Tab did not reach mini return");
                fx(() -> {
                    FocusPixelAssertions.verify(target, baseline);
                    screenshot(mini.getScene(), mode.name().toLowerCase() + "-mini-focus-" + width + ".png");
                });
            }
            fx(() -> {
                ((ButtonBase) mini.getScene().lookup("#mini-return")).fire();
                check(!mini.isShowing() && stage.isShowing() && field("miniStage", Stage.class) == null, "mini return did not restore main window");
                check(audio() == playing && field("loadVersion", Long.class) == version, "mini style check reloaded audio");
                check(field("queue", PlayerQueue.class).snapshot().equals(queue), "mini return changed queue");
                check(audio().state() == JavaFxAudioPlayer.State.PLAYING, "mini return interrupted playback");
            });
        }
        System.out.println("PASS actual playlist action styles and mini five-theme 540/460x244 hover/focus/disabled states; internal Shift+Tab renders stable focus and return preserves playing audio/queue");
    }

    private void checkScene(String scene) {
        layout();
        FrontendRenderAssertions.sliderTrack((Slider) node("progress-slider"));
        FrontendRenderAssertions.sliderTrack((Slider) node("volume-slider"));
        fits(node("playback-bar")); fits(node("play-button")); fits(node("progress-slider"));
        fits(node("now-playing")); fits(node("timing"));
        if (scene.equals("home") || scene.equals("playlist"))
            checkArtworkCorners(node("player-track-entry").lookup(".cover-pane"), 4, "playback artwork");
        readable((Labeled) node("now-playing"), 4.5);
        readable((Labeled) node("timing"), 3.0);
        switch (scene) {
            case "home" -> {
                fits(node("home-page")); fits(node("home-scroll"));
                noHorizontalScroll(node("home-scroll"));
                revealHomeDiscovery();
                fits(node("home-query")); fits(node("home-search-button"));
                readable((Labeled) node("home-search-button"), 4.5);
            }
            case "playlist" -> {
                FrontendStyleAssertions.playlist(stage.getScene());
                fits(node("playlist-scroll")); fits(node("playlist-page-name"));
                readable((Labeled) node("playlist-page-name"), 4.5);
                readable((Labeled) node("playlist-page-count"), 3.0);
                check(((Label) node("playlist-page-name")).getText().equals(LONG_TITLE), "long playlist title changed");
                Bounds listArea = node("playlist-tracks").localToScene(node("playlist-tracks").getBoundsInLocal());
                Bounds scrollArea = node("playlist-scroll").localToScene(node("playlist-scroll").getBoundsInLocal());
                check(listArea.intersects(scrollArea) && listArea.getWidth() >= 100,
                        "playlist tracks cannot be reached through page viewport");
                noHorizontalScroll(list("playlist-tracks"));
            }
            case "immersive" -> {
                for (String id : List.of("immersive-shell", "immersive-cover", "immersive-lyrics", "immersive-actions", "immersive-collapse")) fits(node(id));
                Label title = field("immersiveTrackTitle", Label.class);
                fits(title); readable(title, 4.5);
                check(title.getText().equals(LONG_TITLE), "long track title changed");
                check(title.getTooltip() != null && title.getTooltip().getText().equals(LONG_TITLE), "long title cannot be read in full");
                check(field("immersiveTrackArtist", Label.class).getText().equals("Fixture 演唱者 · 录音室版"),
                        "version performer was replaced by resource author");
                checkFullArtwork();
                checkFavoriteState(true);
            }
            case "settings" -> {
                fits(node("settings-page")); fits(node("settings-scroll")); fits(node("settings-back"));
                readable((Labeled) node("settings-back"), 4.5);
                noHorizontalScroll(node("settings-scroll"));
            }
            default -> throw new AssertionError("unknown scene " + scene);
        }
    }

    private void popupCheck(ThemeController.Mode mode, int width) throws Exception {
        // A retained Scene focus owner does not prove the native owner window is active.
        // Request activation once, click once, then observe this button's popup; never retry the action.
        activateForFocusCheck(stage);
        Node trigger = fxValue(() -> node("immersive-more"));
        focusAnchor(trigger);
        fx(() -> {
            FocusPixelAssertions.requireFocused(trigger);
            System.out.println("POPUP before: " + FocusPixelAssertions.state(trigger));
            click("immersive-more");
        });
        await(3_000, () -> fxValue(() -> shownMoreMenu() != null),
                "MORE_POPUP_NOT_SHOWN " + fxValue(() -> FocusPixelAssertions.state(trigger)));
        fx(() -> {
            ContextMenu menu = shownMoreMenu();
            check(menu != null, "MORE_POPUP_DISAPPEARED before bounds check");
            System.out.println("POPUP shown: ownerFocused=" + stage.isFocused()
                    + ", popupFocused=" + menu.isFocused() + ", bounds="
                    + menu.getX() + "," + menu.getY() + "," + menu.getWidth() + "," + menu.getHeight());
            Scene popup = menu.getScene(); popup.getRoot().applyCss(); popup.getRoot().layout();
            check(menu.getWidth() > 100 && menu.getHeight() > 40, "More popup has no usable bounds");
            check(menu.getX() >= stage.getX() - 1 && menu.getX() + menu.getWidth() <= stage.getX() + stage.getWidth() + 1,
                    "More popup extends beyond main-window width");
            int readableItems = 0;
            for (Node item : popup.getRoot().lookupAll(".label")) if (item instanceof Labeled label
                    && label.isVisible() && !label.getText().isBlank()) { readable(label, 4.5); readableItems++; }
            check(readableItems >= 3, "More popup has no readable actions");
        });
        pause(100);
        fx(() -> {
            ContextMenu menu = shownMoreMenu();
            check(menu != null, "MORE_POPUP_DISAPPEARED before screenshot");
            if (width == 1280) screenshot(menu.getScene(), mode.name().toLowerCase() + "-more-popup.png");
            menu.hide();
        });
    }

    private ContextMenu shownMoreMenu() {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance)
                .map(ContextMenu.class::cast).filter(Window::isShowing)
                .filter(menu -> menu.getOwnerNode() == node("immersive-more"))
                .findFirst().orElse(null);
    }

    private void playlistLocatorCheck() throws Exception {
        phase = "playlist play all and locator preserve list identity";
        fx(() -> {
            openPlaylist(emptyPlaylistId);
            check(node("playlist-play-all").isDisabled() && node("playlist-quick-filter").isDisabled(),
                    "empty playlist actions must be disabled");
            openPlaylist(playlistId);
            check(!node("playlist-play-all").isDisabled() && !node("playlist-quick-filter").isDisabled(),
                    "populated playlist actions must be enabled");
            var locator = (javafx.scene.control.TextField) node("playlist-quick-filter");
            ListView<Track> tracks = list("playlist-tracks");
            List<Track> before = List.copyOf(tracks.getItems());
            PlayerQueue.Snapshot queueBefore = field("queue", PlayerQueue.class).snapshot();
            locator.setText("Scroll row 23");
            check(((Label) node("playlist-filter-count")).getText().equals("找到 1 首")
                            && tracks.getSelectionModel().getSelectedItem().id().equals("visual-row-23"),
                    "title locator did not select its match");
            locator.setText("independent-no-match");
            check(((Label) node("playlist-filter-count")).getText().equals("无匹配"),
                    "locator lacks no-match feedback");
            locator.setText("FixtureUploader");
            check(tracks.getSelectionModel().getSelectedItem().id().equals("visual-upload"),
                    "artist locator did not select its match");
            check(tracks.getItems().equals(before) && field("queue", PlayerQueue.class).snapshot().equals(queueBefore),
                    "locator filtered/reordered the list or changed playback");
            locator.clear();
            check(!node("playlist-filter-count").isVisible() && !node("playlist-filter-count").isManaged(),
                    "cleared locator left its count visible");
            click("playlist-play-all");
            PlayerQueue queue = field("queue", PlayerQueue.class);
            ListeningReporter.Context context = field("queueListeningContext", ListeningReporter.Context.class);
            check(queue.tracks().stream().map(Track::id).toList().equals(before.stream().map(Track::id).toList())
                            && queue.current().id().equals(first.id()) && context != null
                            && context.playlistId().equals(playlistId),
                    "play all lost playlist order, first track or listening context");
        });
        awaitPlaying(first.id());
        System.out.println("PASS playlist locator title/artist/no-match/clear preserves all rows and queue; play all starts full ordered playlist with context");
    }

    private void transportAlignmentCheck() {
        Label time = (Label) node("timing");
        String original = time.getText();
        try {
            for (String sample : List.of("00:00 / 03:00", "1:23:45 / 12:34:56")) {
                time.setText(sample); layout(); layout();
                assertTransportCentred();
            }
            if (!alignmentNegativeChecked) {
                Node button = node("play-button");
                double originalX = button.getTranslateX();
                try {
                    button.setTranslateX(originalX + 8);
                    try {
                        assertTransportCentred();
                        throw new AssertionError("alignment negative fixture was accepted");
                    } catch (AssertionError expected) {
                        check(expected.getMessage().startsWith("TRANSPORT_ALIGNMENT"),
                                "unexpected alignment negative failure: " + expected.getMessage());
                    }
                } finally { button.setTranslateX(originalX); }
                alignmentNegativeChecked = true;
            }
        } finally { time.setText(original); layout(); }
    }

    private void assertTransportCentred() {
        Node play = node("play-button"), track = node("progress-slider").lookup(".track");
        check(track != null, "progress track not rendered");
        Bounds button = play.localToScene(play.getLayoutBounds()), rail = track.localToScene(track.getLayoutBounds());
        double delta = (button.getMinX() + button.getMaxX() - rail.getMinX() - rail.getMaxX()) / 2;
        check(Math.abs(delta) <= 1.0, "TRANSPORT_ALIGNMENT delta=" + delta + ", button=" + button + ", track=" + rail);
        System.out.println("PASS transport alignment width=" + stage.getWidth() + " delta=" + delta + " time=" + ((Label) node("timing")).getText());
    }

    private void frontendRenderingCheck() throws Exception {
        phase = "progress track bounds and rendered lyric context";
        fx(() -> click("play-button"));
        await(3_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED),
                "render fixture did not pause after native audio callback");
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> {
                click("sidebar-settings");
                click("theme-swatch-" + mode.name().toLowerCase(java.util.Locale.ROOT));
                check(field("themeMode", ThemeController.Mode.class) == mode, "theme shortcut did not apply");
                check(stage.getScene().getRoot().lookupAll(".theme-swatch-button").stream()
                        .filter(n -> n.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("active"))).count() == 1,
                        "theme shortcut selected state is ambiguous");
            }); settle();
            for (int width : new int[] {1280, 860}) {
                fx(() -> {
                    stage.setWidth(width); stage.setHeight(width == 860 ? 600 : 800);
                    openScene("home"); layout();
                    Slider progress = (Slider) node("progress-slider");
                    for (double fraction : new double[] {0, .25, .5, 1}) {
                        progress.setValue(fraction); layout();
                        FrontendRenderAssertions.sliderTrack(progress);
                        Paint fill = ((javafx.scene.layout.Region) progress.lookup(".track")).getBackground().getFills().get(0).getFill();
                        check(fill instanceof javafx.scene.paint.LinearGradient gradient
                                && gradient.getStops().stream().anyMatch(stop -> Math.abs(stop.getOffset() - fraction) < .001),
                                "progress fill does not follow value " + fraction);
                    }
                    FrontendRenderAssertions.sliderTrack((Slider) node("volume-slider"));
                    invoke("setLyrics", new Class<?>[] {List.class, String.class},
                            Lyrics.parse("[00:00.00]当前演唱行 Current singing\n[00:30.00]上下文歌词 Context lyrics\n[01:00.00]下一句 Next line\n"),
                            "合成验收歌词");
                    click("open-immersive");
                }); settle();
                for (String tier : List.of("SMALL", "MEDIUM", "LARGE")) {
                fx(() -> {
                    for (int step = 0; step < 3 && !field("currentLyricSize", Enum.class).name().equals(tier); step++)
                        click("immersive-lyric-size-toggle");
                });
                settle();
                fx(() -> {
                    var cells = list("immersive-lyrics").lookupAll(".list-cell").stream()
                            .filter(ListCell.class::isInstance).map(n -> (ListCell<?>) n)
                            .filter(cell -> "上下文歌词 Context lyrics".equals(cell.getItem())).toList();
                    check(cells.size() == 1, "context lyric fixture not visible");
                    double contextSize = switch (tier) { case "SMALL" -> 13.5; case "LARGE" -> 18.5; default -> 15; };
                    check(Math.abs(cells.get(0).getFont().getSize() - contextSize) < .01,
                            "lyric tier overridden: " + mode + " " + width + " " + tier);
                    ListCell<?> current = list("immersive-lyrics").lookupAll(".list-cell").stream()
                            .filter(ListCell.class::isInstance).map(n -> (ListCell<?>) n)
                            .filter(cell -> "当前演唱行 Current singing".equals(cell.getItem())).findFirst().orElseThrow();
                    double currentSize = switch (tier) { case "SMALL" -> 17.5; case "LARGE" -> 25; default -> 20; };
                    check(current.getStyleClass().contains("current-lyric")
                                    && Math.abs(current.getFont().getSize() - currentSize) < .01,
                            "current lyric tier overridden: " + mode + " " + width + " " + tier);
                    double contrast = FrontendRenderAssertions.lyricContrast(cells.get(0), 4.5);
                    System.out.println("PASS lyric context " + mode + " width=" + width + " tier=" + tier + " contrast=" + contrast);
                    if (tier.equals("MEDIUM")) screenshot(stage.getScene(), mode.name().toLowerCase() + "-lyrics-" + width + ".png");
                });
                }
                fx(() -> click("immersive-collapse"));
            }
        }
        fx(() -> {
            stage.setWidth(1280); stage.setHeight(800);
            click("play-button");
        });
        awaitPlaying(first.id());
        System.out.println("PASS actual main/volume tracks remain bounded at four fractions; five-theme lyric context readable at both sizes");
    }

    private void frontendInteractionCheck() throws Exception {
        phase = "volume memory, lyric menu synchronization and current-row overlay";
        double before = fxValue(() -> audio().position().toSeconds());
        fx(() -> {
            openScene("home");
            Slider volume = (Slider) node("volume-slider");
            double saved = volume.getValue();
            for (double value : new double[] {.0005, .001, .02, .05, .5, 1}) {
                volume.setValue(value);
                node("volume-toggle").fireEvent(singleClick());
                check(volume.getValue() == 0, "volume icon did not mute " + value);
                node("volume-toggle").fireEvent(singleClick());
                check(Math.abs(volume.getValue() - value) < .000001, "volume restore jumped from " + value);
                check(((Label) node("volume-toggle")).getTooltip().getText().contains(Math.round(value * 100) + "%"),
                        "volume percentage tooltip is stale");
            }
            volume.setValue(.02); volume.setValue(0);
            node("volume-toggle").fireEvent(singleClick());
            check(volume.getValue() == .02, "slider zero lost remembered low volume");
            volume.setValue(.5); volume.fireEvent(wheel(40));
            check(Math.abs(volume.getValue() - .55) < .000001, "slider wheel step changed");
            node("volume-toggle").fireEvent(wheel(-40));
            check(Math.abs(volume.getValue() - .5) < .000001, "icon wheel step changed");
            volume.setValue(1); volume.fireEvent(wheel(40)); check(volume.getValue() == 1, "volume upper clamp");
            volume.setValue(0); volume.fireEvent(wheel(-40)); check(volume.getValue() == 0, "volume lower clamp");
            volume.setValue(saved);
            click("open-immersive");
            for (int step = 0; step < 3 && !field("currentLyricSize", Enum.class).name().equals("MEDIUM"); step++)
                click("immersive-lyric-size-toggle");
            click("immersive-lyric-size-toggle");
        }); settle();
        fx(() -> click("immersive-more"));
        await(3_000, () -> fxValue(() -> shownMoreMenu() != null), "lyric menu did not appear");
        fx(() -> {
            ContextMenu popup = shownMoreMenu();
            var sizes = (javafx.scene.control.Menu) popup.getItems().stream()
                    .filter(item -> "immersive-lyric-size-menu".equals(item.getId())).findFirst().orElseThrow();
            checkSelectedLyricSize("LARGE");
            node("immersive-lyric-size-toggle").fireEvent(wheel(-40)); checkSelectedLyricSize("MEDIUM");
            sizes.getItems().get(0).fire(); checkSelectedLyricSize("SMALL");
            check(((ButtonBase) node("immersive-lyric-size-toggle")).getText().equals("A"), "menu did not update size chip");
            click("immersive-lyric-size-toggle"); checkSelectedLyricSize("MEDIUM");
            popup.hide(); openScene("playlist");
        }); settle();
        fx(() -> checkCurrentRowOverlay(true));
        fx(() -> click("play-button"));
        await(3_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "overlay fixture did not pause");
        fx(() -> {
            checkCurrentRowOverlay(false);
            currentTrackRow().lookup(".track-artwork").fireEvent(singleClick());
        });
        awaitPlaying(first.id());
        fx(() -> {
            checkCurrentRowOverlay(true);
            list("playlist-tracks").scrollTo(20); layout();
        });
        pause(150);
        fx(() -> { list("playlist-tracks").scrollTo(0); layout(); }); pause(150);
        await(3_000, () -> fxValue(() -> audio().position().toSeconds() >= before + .2), "cover playback clock did not advance");
        fx(() -> { checkCurrentRowOverlay(true); preserved(before, true); });
        System.out.println("PASS volume mute restores positive values including 0.05%/2%/5%; wheel/tooltip/clamps, lyric chip/wheel/menu synchronization and live/recycled row icon+tooltip; cover click preserves current audio/queue");
    }

    private void checkSelectedLyricSize(String name) {
        var group = field("lyricSizeGroup", javafx.scene.control.ToggleGroup.class);
        check(group.getSelectedToggle() != null && group.getSelectedToggle().getUserData().toString().equals(name)
                        && field("currentLyricSize", Enum.class).name().equals(name), "lyric menu selection is stale: " + name);
        check(group.getToggles().stream().filter(javafx.scene.control.Toggle::isSelected).count() == 1, "lyric menu selected count");
    }

    private ListCell<?> currentTrackRow() {
        return list("playlist-tracks").lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                .map(n -> (ListCell<?>) n).filter(cell -> cell.getItem() instanceof Track track && first.id().equals(track.id()))
                .findFirst().orElseThrow();
    }

    private void checkCurrentRowOverlay(boolean playing) {
        Node artwork = currentTrackRow().lookup(".track-artwork");
        String path = ((javafx.scene.shape.SVGPath) artwork.lookup(".track-cover-play-icon")).getContent();
        check(path.equals(invoke("iconPath", new Class<?>[] {String.class}, playing ? "pause" : "play")), "current-row overlay icon is stale");
        var tooltip = artwork.getProperties().values().stream().filter(javafx.scene.control.Tooltip.class::isInstance)
                .map(javafx.scene.control.Tooltip.class::cast).findFirst().orElseThrow();
        check(tooltip.getText().equals(playing ? "正在播放 · 点击暂停" : "已暂停 · 点击继续播放"), "current-row overlay tooltip is stale");
    }

    private static MouseEvent singleClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }

    private static javafx.scene.input.ScrollEvent wheel(double delta) {
        return new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL, 8, 8, 8, 8,
                false, false, false, false, false, false, 0, delta, 0, delta,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
    }

    /** Compare actual glyph color against the composited window behind that same label. */
    private void readable(Labeled label, double minimum) {
        check(label.getText() != null && !label.getText().isBlank(), "empty key label " + label.getId());
        Text text = (Text) label.lookup(".text");
        check(text != null && text.isVisible() && text.getOpacity() > 0 && !text.getText().isBlank(),
                "key label did not render " + label.getId());
        Paint foreground = text.getFill();
        check(foreground instanceof Color, "unsupported text paint " + label.getId());
        Bounds bounds = text.localToScene(text.getBoundsInLocal());
        Scene scene = label.getScene();
        check(bounds.getMaxX() <= scene.getWidth() + 1 && bounds.getMinX() >= -1,
                "rendered text exceeds scene " + label.getId());
        Bounds labelBounds = label.localToScene(label.getBoundsInLocal());
        check(bounds.getMaxX() <= labelBounds.getMaxX() + 1 && bounds.getMinX() >= labelBounds.getMinX() - 1,
                "rendered text exceeds its label " + label.getId());
        double originalOpacity = text.getOpacity();
        WritableImage shown = scene.snapshot(null);
        try {
            // Opacity leaves the skin's managed text layout intact while sampling its backdrop.
            text.setOpacity(0);
            WritableImage background = scene.snapshot(null);
            int x = (int) Math.max(0, Math.min(background.getWidth() - 1, bounds.getCenterX()));
            int y = (int) Math.max(0, Math.min(background.getHeight() - 1, bounds.getCenterY()));
            Color backdrop = background.getPixelReader().getColor(x, y);
            Color glyph = composite((Color) foreground, backdrop);
            double light = luminance(glyph), dark = luminance(backdrop);
            double contrast = (Math.max(light, dark) + .05) / (Math.min(light, dark) + .05);
            check(contrast >= minimum - .1, "text contrast " + label.getId() + "/" + label.getText()
                    + " is " + String.format(java.util.Locale.ROOT, "%.2f", contrast) + ", expected " + minimum);
            int glyphPixels = 0;
            for (int row = (int) Math.max(0, bounds.getMinY()); row < Math.min(shown.getHeight(), bounds.getMaxY()); row++)
                for (int column = (int) Math.max(0, bounds.getMinX()); column < Math.min(shown.getWidth(), bounds.getMaxX()); column++)
                    if (shown.getPixelReader().getArgb(column, row) != background.getPixelReader().getArgb(column, row)) glyphPixels++;
            check(glyphPixels >= 10, "label has no rendered glyphs " + label.getId());
        } finally { text.setOpacity(originalOpacity); }
    }

    private static Color composite(Color front, Color back) {
        double alpha = front.getOpacity();
        return new Color(front.getRed() * alpha + back.getRed() * (1 - alpha),
                front.getGreen() * alpha + back.getGreen() * (1 - alpha),
                front.getBlue() * alpha + back.getBlue() * (1 - alpha), 1);
    }
    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }
    private static double linear(double value) { return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4); }

    /** Four saturated corner markers prove the entire portrait artwork survived rendering. */
    private void checkFullArtwork() {
        // Snapshot the clipped artwork frame, not the ImageView alone: a parent crop must fail too.
        checkArtworkCorners(node("immersive-cover"), 10, "immersive artwork");
    }
    private void checkArtworkCorners(Node artwork, int minimumPixels, String description) {
        check(artwork != null, "missing " + description);
        WritableImage rendered = artwork.snapshot(null, null);
        int[] counts = new int[4];
        for (int y = 0; y < rendered.getHeight(); y++) for (int x = 0; x < rendered.getWidth(); x++) {
            int pixel = rendered.getPixelReader().getArgb(x, y);
            for (int i = 0; i < COVER_MARKERS.length; i++) if (pixel == COVER_MARKERS[i]) counts[i]++;
        }
        for (int i = 0; i < counts.length; i++) check(counts[i] > minimumPixels, description + " corner " + i + " cropped from rendered cover; counts=" + java.util.Arrays.toString(counts) + ", opacity=" + artwork.getOpacity()
                + ", artworkTransition=" + (stage.getScene().lookup("#artwork-transition-image") != null));
    }

    private void checkFavoriteState(boolean saved) {
        ButtonBase main = (ButtonBase) node("favorite-current");
        ButtonBase immersive = (ButtonBase) node("immersive-favorite");
        String expected = saved ? "取消收藏" : "收藏当前";
        check(main.getTooltip() != null && expected.equals(main.getTooltip().getText()), "main favorite tooltip does not describe current action");
        check(immersive.getTooltip() != null && expected.equals(immersive.getTooltip().getText()), "immersive favorite tooltip is stale");
        Paint mainColor = ((Shape) main.lookup(".ui-icon")).getFill();
        Paint immersiveColor = ((Shape) immersive.lookup(".ui-icon")).getFill();
        check(mainColor.equals(immersiveColor), "immersive favorite color disagrees with playback bar");
        ThemeController.Mode mode = field("themeMode", ThemeController.Mode.class);
        if (saved) savedFavoriteColors.put(mode, mainColor);
        else {
            Paint selected = savedFavoriteColors.get(mode);
            check(selected != null && !selected.equals(mainColor), "saved and unsaved favorites are visually indistinguishable");
        }
    }

    private void checkDisabledSource() {
        ButtonBase source = (ButtonBase) node("immersive-open-source");
        check(source.isDisabled(), "local fixture unexpectedly enables external source button");
        int[] actions = {0}; EventHandler<ActionEvent> observer = event -> actions[0]++;
        source.addEventHandler(ActionEvent.ACTION, observer);
        long load = field("loadVersion", Long.class);
        PlayerQueue.Snapshot queue = field("queue", PlayerQueue.class).snapshot();
        try { source.fire(); } finally { source.removeEventHandler(ActionEvent.ACTION, observer); }
        check(actions[0] == 0, "disabled source button dispatched an action");
        check(load == field("loadVersion", Long.class) && queue.equals(field("queue", PlayerQueue.class).snapshot()), "disabled action changed playback state");
        check(source.getOpacity() < .8 && source.getOpacity() > .2, "disabled source state cannot be distinguished visually");
        check(source.getTooltip() != null && !source.getTooltip().getText().isBlank(), "disabled source has no readable purpose");
        renderedIconContrast((Shape) source.lookup(".ui-icon"), 2.0);
    }

    private void renderedIconContrast(Shape icon, double minimum) {
        check(icon != null && icon.isVisible(), "missing interaction icon");
        Scene scene = icon.getScene(); Bounds bounds = icon.localToScene(icon.getBoundsInLocal());
        WritableImage shown = scene.snapshot(null);
        try {
            icon.setVisible(false); WritableImage hidden = scene.snapshot(null);
            int changedPixels = 0; double maximumContrast = 1;
            for (int y = (int) Math.max(0, bounds.getMinY()); y < Math.min(shown.getHeight(), bounds.getMaxY()); y++)
                for (int x = (int) Math.max(0, bounds.getMinX()); x < Math.min(shown.getWidth(), bounds.getMaxX()); x++) {
                    Color glyph = shown.getPixelReader().getColor(x, y), background = hidden.getPixelReader().getColor(x, y);
                    double first = luminance(glyph), second = luminance(background);
                    double ratio = (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
                    if (ratio > 1.25) changedPixels++;
                    maximumContrast = Math.max(maximumContrast, ratio);
                }
            check(changedPixels > 10 && maximumContrast >= minimum,
                    "disabled icon is unreadable after compositing opacity: pixels=" + changedPixels + ", contrast=" + maximumContrast);
        } finally { icon.setVisible(true); }
    }

    private void keyboardFocusCheck(ThemeController.Mode mode) throws Exception {
        activateForFocusCheck(stage);
        fx(this::revealHomeDiscovery);
        Node anchor = fxValue(() -> node("home-query"));
        Node target = fxValue(() -> node("home-search-button"));
        focusAnchor(anchor);
        FocusPixelAssertions.Baseline baseline = fxValue(() -> FocusPixelAssertions.capture(anchor, target));
        fx(() -> anchor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false)));
        awaitFocused(target, "Tab traversal did not reach recommendation action");
        fx(() -> {
            FocusPixelAssertions.verify(target, baseline);
            readable((Labeled) target, 4.5);
            fits(target);
            screenshot(stage.getScene(), mode.name().toLowerCase() + "-keyboard-focus.png");
        });
    }

    private void activateForFocusCheck(Window window) throws Exception {
        // One request, then bounded observation. Never force topmost or mask lost focus.
        fx(window::requestFocus);
        await(3_000, () -> fxValue(() -> window.isShowing() && window.isFocused()), "FOCUS_WINDOW_NOT_ACTIVE");
    }

    private void focusAnchor(Node anchor) throws Exception {
        fx(anchor::requestFocus);
        awaitFocused(anchor, "focus anchor did not receive input focus");
    }

    private void awaitFocused(Node target, String message) throws Exception {
        await(3_000, () -> fxValue(() -> target.getScene().getFocusOwner() == target && target.isFocused()),
                message + "; " + fxValue(() -> FocusPixelAssertions.state(target)));
    }

    private void focusEnvironmentCheck() throws Exception {
        phase = "controlled inactive focus and missing border regression";
        fx(() -> { chooseTheme(ThemeController.Mode.DARK); openScene("home"); revealHomeDiscovery(); }); settle();
        activateForFocusCheck(stage);
        Node anchor = fxValue(() -> node("home-query"));
        Node target = fxValue(() -> node("home-search-button"));
        focusAnchor(anchor);
        FocusPixelAssertions.Baseline baseline = fxValue(() -> FocusPixelAssertions.capture(anchor, target));
        Stage blocker = fxValue(() -> {
            Stage value = new Stage(); value.initOwner(stage);
            value.setTitle("Lumen visual regression · controlled focus");
            value.setScene(new Scene(new javafx.scene.layout.StackPane(new Label("Controlled focus regression")), 360, 100));
            value.show(); value.requestFocus(); return value;
        });
        try {
            await(3_000, () -> fxValue(() -> blocker.isFocused() && !stage.isFocused()), "controlled window did not take focus");
            fx(() -> anchor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false)));
            fx(() -> {
                check(stage.getScene().getFocusOwner() == target && !target.isFocused(), "inactive Scene focus-owner condition not reproduced");
                target.applyCss();
                int oldPixels = FocusPixelAssertions.perimeterChanges(baseline.pixels(), target.snapshot(null, null));
                check(oldPixels == 0, "inactive control unexpectedly rendered focus: " + oldPixels);
                expectFocusFailure("FOCUS_WINDOW_INACTIVE", () -> FocusPixelAssertions.verify(target, baseline));
                System.out.println("PASS controlled old predicate: Scene owner retained, node unfocused, perimeter pixels=0; inactive capture rejected");
            });
        } finally { fx(blocker::close); }
        activateForFocusCheck(stage);
        focusAnchor(anchor);
        FocusPixelAssertions.Baseline negative = fxValue(() -> FocusPixelAssertions.capture(anchor, target));
        String originalStyle = fxValue(target::getStyle);
        try {
            fx(() -> {
                target.setStyle(originalStyle + "; -fx-border-color: transparent;");
                anchor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            });
            awaitFocused(target, "missing-border fixture did not receive focus");
            fx(() -> expectFocusFailure("FOCUS_BORDER_MISSING", () -> FocusPixelAssertions.verify(target, negative)));
            System.out.println("PASS actual focused control with hidden border is still rejected by pixel threshold");
        } finally { fx(() -> target.setStyle(originalStyle)); }
    }

    private void expectFocusFailure(String code, Checked action) throws Exception {
        try { action.run(); }
        catch (AssertionError error) {
            check(error.getMessage().startsWith(code), "wrong focus failure: " + error.getMessage());
            return;
        }
        throw new AssertionError("focus regression not detected: " + code);
    }

    private void noHorizontalScroll(Node item) {
        check(item.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast).noneMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL
                        && bar.isVisible() && bar.getLayoutBounds().getHeight() > 0), "horizontal scrollbar in " + item.getId());
    }
    /** Stage 3.2 wraps the original discovery controls in the personal-home viewport. */
    private void revealHomeDiscovery() {
        ScrollPane scroll = (ScrollPane) node("home-scroll");
        for (int pass = 0; pass < 3; pass++) {
            layout();
            Node viewportNode = scroll.lookup(".viewport");
            Bounds viewport = viewportNode.localToScene(viewportNode.getLayoutBounds());
            Bounds query = node("home-query").localToScene(node("home-query").getLayoutBounds());
            double extent = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
            if (extent <= 0) return;
            double delta = query.getMinY() < viewport.getMinY() ? query.getMinY() - viewport.getMinY() - 12
                    : query.getMaxY() > viewport.getMaxY() ? query.getMaxY() - viewport.getMaxY() + 12 : 0;
            scroll.setVvalue(Math.max(0, Math.min(1, scroll.getVvalue() + delta / extent)));
        }
        layout();
    }
    private void checkRowIdentity() {
        ListView<Track> tracks = list("playlist-tracks"); tracks.scrollTo(0); layout();
        ListCell<?> known = renderedCell(0);
        Label knownPerformer = (Label) known.lookup(".track-artist");
        check(knownPerformer.getText().equals("Fixture 演唱者 · 录音室版"), "list replaced known performer with resource author");
        readable(knownPerformer, 3.0);
        ButtonBase rowFavorite = (ButtonBase) known.lookup(".track-favorite");
        check(rowFavorite.getTooltip() != null && rowFavorite.getTooltip().getText().equals("取消收藏"), "favorite row tooltip is stale");
        check(((Shape) rowFavorite.lookup(".ui-icon")).getFill().equals(((Shape) node("favorite-current").lookup(".ui-icon")).getFill()),
                "favorite row and playback bar use different saved-state colors");
        checkArtworkCorners(known.lookup(".track-artwork"), 2, "playlist artwork");
        checkArtworkCorners(node("player-track-entry").lookup(".cover-pane"), 4, "playback artwork");
        ListCell<?> uploaded = renderedCell(2);
        check(((Label) uploaded.lookup(".track-artist")).getText().equals("UP 主：FixtureUploader"),
                "Bilibili author is shown as an unqualified performer");
        Label title = (Label) uploaded.lookup(".track-title");
        check(title.getText().equals("上传资源演示") && !title.getText().contains("BV"), "resource identity replaced readable song title");
        check(title.getTooltip() != null && title.getTooltip().getText().contains("BV1FixtureVisual"),
                "resource identity is unavailable from tooltip");
    }
    private ListCell<?> renderedCell(int index) {
        return list("playlist-tracks").lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                .map(ListCell.class::cast).filter(cell -> cell.getIndex() == index && !cell.isEmpty()).findFirst().orElseThrow();
    }
    private int firstVisibleRow() {
        ListView<Track> tracks = list("playlist-tracks");
        Bounds viewport = tracks.localToScene(tracks.getBoundsInLocal());
        return tracks.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance).map(ListCell.class::cast)
                .filter(cell -> !cell.isEmpty() && cell.getIndex() >= 0
                        && cell.localToScene(cell.getBoundsInLocal()).getMaxY() > viewport.getMinY() + 2
                        && cell.localToScene(cell.getBoundsInLocal()).getMinY() < viewport.getMaxY() - 2)
                .mapToInt(ListCell::getIndex).min().orElse(-1);
    }
    private void preserved(double before, boolean advancing) {
        check(audio() == originalAudio, "navigation replaced AudioPlayer");
        check(field("loadVersion", Long.class) == originalLoad, "navigation reloaded audio");
        check(field("queue", PlayerQueue.class).snapshot().equals(originalQueue), "navigation changed queue");
        check(audio().state() == JavaFxAudioPlayer.State.PLAYING, "navigation stopped playback");
        check(audio().position().toSeconds() >= before + (advancing ? .2 : -.1), "playback clock stopped or reset");
    }
    private void chooseTheme(ThemeController.Mode mode) {
        for (int i = 0; i < ThemeController.Mode.values().length && field("themeMode", ThemeController.Mode.class) != mode; i++) click("theme-button");
        check(field("themeMode", ThemeController.Mode.class) == mode, "theme selection failed " + mode);
    }
    private void openScene(String name) {
        switch (name) {
            case "home" -> click("nav-home");
            case "playlist" -> { openPlaylist(playlistId); ((ScrollPane) node("playlist-scroll")).setVvalue(0); list("playlist-tracks").scrollTo(0); }
            case "immersive" -> click("open-immersive");
            case "settings" -> { click("sidebar-settings"); ((ScrollPane) node("settings-scroll")).setVvalue(0); }
            default -> throw new AssertionError(name);
        }
    }
    private void openPlaylist(String id) {
        click("nav-playlists"); picker().setValue(picker().getItems().stream().filter(item -> id.equals(item.id())).findFirst().orElseThrow()); layout();
    }
    private void prepareFixture() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("visual-language-smoke") && !Files.exists(root.resolve("library.json")), "fresh isolated visual profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), key + " outside isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "API keys entered smoke profile");
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost()) && service.getPort() > 0, "loopback demo service required");
        Path wav = root.resolve("visual-local-180s.wav");
        int rate = 11_025, seconds = 180; byte[] pcm = new byte[rate * seconds * 2];
        for (int frame = 0; frame < rate * seconds; frame++) {
            short value = (short) (1600 * Math.sin(frame * 2 * Math.PI * 220 / rate));
            pcm[frame * 2] = (byte) value; pcm[frame * 2 + 1] = (byte) (value >>> 8);
        }
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), rate * seconds)) {
            check(AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile()) > 0, "WAV not written");
        }
        Path cover = root.resolve("portrait-cover.png");
        BufferedImage png = new BufferedImage(300, 500, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 500; y++) for (int x = 0; x < 300; x++) {
            int color = 0xFFEB2D3A;
            if (x < 60 && y < 60) color = COVER_MARKERS[0];
            if (x >= 240 && y < 60) color = COVER_MARKERS[1];
            if (x < 60 && y >= 440) color = COVER_MARKERS[2];
            if (x >= 240 && y >= 440) color = COVER_MARKERS[3];
            png.setRGB(x, y, color);
        }
        check(ImageIO.write(png, "png", cover.toFile()), "cover not written");
        Path background = root.resolve("background.png"); Files.copy(cover, background);
        Properties preferences = new Properties(); preferences.setProperty("theme", "dark");
        preferences.setProperty("background", background.toString()); preferences.setProperty("pet", "HIDDEN");
        preferences.setProperty("sidebar.collapsed", "false"); preferences.setProperty("sidebar.width", "176");
        try (var output = Files.newOutputStream(Path.of(required("LUMEN_PREFERENCES_PATH")))) { preferences.store(output, "Visual language fixture"); }
        coverServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        coverServer.createContext("/cover.png", exchange -> {
            try (exchange) {
                byte[] bytes = Files.readAllBytes(cover); exchange.getResponseHeaders().set("Content-Type", "image/png");
                if ("HEAD".equals(exchange.getRequestMethod())) exchange.sendResponseHeaders(200, -1);
                else { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            }
        }); coverServer.start();
        URI artwork = URI.create("http://127.0.0.1:" + coverServer.getAddress().getPort() + "/cover.png");
        first = new Track("visual-first", LONG_TITLE, "Fixture 源作者", seconds, "local", wav.toUri(), null, 0, artwork);
        missing = new Track("visual-missing", "缺封面与歌词 Missing artwork", "Fixture", seconds, "local", wav.toUri(), null, 0, null);
        LibraryClient library = new LibraryClient(service);
        check(library.load().playlists().isEmpty(), "demo library not empty");
        playlistId = library.createPlaylist(LONG_TITLE).playlists().get(0).id();
        library.addToPlaylist(playlistId, first); library.addToPlaylist(playlistId, missing);
        library.favorite(first);
        library.addToPlaylist(playlistId, new Track("visual-upload", "上传资源演示", "FixtureUploader", seconds,
                "bilibili", wav.toUri(), "BV1FixtureVisual", 1, artwork));
        for (int i = 3; i < 24; i++) library.addToPlaylist(playlistId, new Track("visual-row-" + i, "Scroll row " + i + " 曲目", "Fixture", seconds, "local", wav.toUri(), null, 0, artwork));
        new VersionCatalogClient(service).create(LONG_TITLE, "视觉身份验收", List.of(new VersionCatalog.Version("录音室版", "Fixture 演唱者", first)));
        emptyPlaylistId = library.createPlaylist("空歌单 Empty playlist").playlists().stream().filter(item -> !item.id().equals(playlistId)).findFirst().orElseThrow().id();
    }

    private void fits(Node item) {
        check(item.isVisible() && item.isManaged(), "hidden key control " + item.getId());
        Bounds bounds = item.localToScene(item.getLayoutBounds()); Scene scene = item.getScene();
        check(bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1 && bounds.getMinY() >= -1 && bounds.getMaxY() <= scene.getHeight() + 1,
                "control overflow " + item.getId() + ": " + bounds);
    }
    private void screenshot(Scene scene, String name) {
        try {
            WritableImage image = scene.snapshot(null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Path target = root.resolve(name);
            check(ImageIO.write(png, "png", target.toFile()) && Files.size(target) > 1_000, "screenshot missing " + name);
            System.out.println("SCREENSHOT: " + target);
        } catch (Exception error) { throw new AssertionError("screenshot failed " + name, error); }
    }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private void click(String id) { ((ButtonBase) node(id)).fire(); }
    private Node node(String id) { Node value = stage.getScene().lookup("#" + id); check(value != null, "missing UI #" + id); return value; }
    @SuppressWarnings("unchecked") private ListView<Track> list(String id) { return (ListView<Track>) node(id); }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return (ComboBox<LibraryClient.Playlist>) node("playlist-picker"); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private <T> T field(String name, Class<T> type) {
        try { Field value = LumenFxApp.class.getDeclaredField(name); value.setAccessible(true); return type.cast(value.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try { Method value = LumenFxApp.class.getDeclaredMethod(name, types); value.setAccessible(true); return value.invoke(app, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot invoke " + name, error); }
    }
    private static MouseEvent doubleClick() { return new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, true, false, false, null); }
    private void settle() throws Exception {
        fx(this::layout);
        // ArtworkTransition temporarily hides the destination image while its overlay is visible.
        // A fixed sleep can end before the FX pulse starts/finishes that transition on a busy host.
        try {
            await(3_000, () -> fxValue(() -> {
                Node content = ((TabPane) node("main-tabs")).getSelectionModel().getSelectedItem().getContent();
                return stage.getScene().lookup("#artwork-transition-image") == null
                        && content.getOpacity() == 1 && content.getTranslateX() == 0 && content.getTranslateY() == 0;
            }), "SCENE_TRANSITION_NOT_FINISHED");
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + fxValue(() -> {
                Node content = ((TabPane) node("main-tabs")).getSelectionModel().getSelectedItem().getContent();
                Node image = stage.getScene().lookup("#immersive-artwork-image");
                return "; showing=" + stage.isShowing() + ", focused=" + stage.isFocused()
                        + ", iconified=" + stage.isIconified() + ", contentOpacity=" + content.getOpacity()
                        + ", translate=" + content.getTranslateX() + "," + content.getTranslateY()
                        + ", overlay=" + (stage.getScene().lookup("#artwork-transition-image") != null)
                        + ", imageOpacity=" + (image == null ? "absent" : image.getOpacity());
            }), failure);
        }
        fx(this::layout);
    }
    private void awaitPlaying(String id) throws Exception {
        await(15_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && id.equals(field("loadedTrack", Track.class).id()) && audio().position().toSeconds() > .2), "local audio did not play " + id);
    }
    private static String required(String key) { String value = System.getenv(key); check(value != null && !value.isBlank(), "missing " + key); return value; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private void await(long millis, CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) { if (condition.get()) return; pause(70); }
        throw new AssertionError(message + "; phase=" + phase);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (app != null) app.stop(); for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        if (coverServer != null) coverServer.stop(0);
        System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
