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
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Labeled;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Native acceptance for the v0.2.1 polish, using only an isolated loopback service. */
public final class V021PolishSmoke extends Application {
    private static final String LONG_LYRIC = "这是一句用于真实时钟滚动验收的超长歌词，".repeat(14);
    private static final String FIXTURE_LRC = "[00:00.00]" + LONG_LYRIC + "\n[00:20.00]下一句歌词\n";
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger coverGets = new AtomicInteger();
    private final AtomicBoolean recoverCover = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private HttpServer coverServer;
    private LibraryClient library;
    private DiaryClient diaryClient;
    private Track first;
    private Track second;
    private String playlistId;
    private String targetPlaylistId;
    private String targetPlaylistName = "批量加入目标";

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(285_000); } catch (InterruptedException interrupted) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL v021 polish watchdog: " + phase);
                System.exit(1);
            }
        }, "v021-polish-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL v021 polish phase: " + phase);
                error.printStackTrace(System.err);
                finish(1);
            }
        }, "v021-polish-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        prepareFixture();
        fx(() -> {
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(1280); stage.setHeight(800);
        });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()));
        await(15_000, () -> fxValue(() -> picker().getItems().size() == 32), "fixture playlists not loaded");

        phase = "failed artwork retry on playlist reopen";
        fx(() -> openPlaylist(playlistId));
        await(8_000, () -> coverGets.get() > 0, "first cover was not requested");
        pause(450);
        int failedRequests = coverGets.get();
        pause(450);
        check(coverGets.get() == failedRequests, "failed cover automatically retried without user interaction");
        fx(() -> click("nav-home"));
        recoverCover.set(true);
        fx(() -> click("nav-playlists"));
        await(8_000, () -> coverGets.get() > failedRequests, "reopening same playlist did not retry failed cover");
        try {
            await(8_000, () -> fxValue(() -> firstPlaylistCell().lookupAll("ImageView").stream()
                    .filter(ImageView.class::isInstance).map(ImageView.class::cast)
                    .anyMatch(this::hasFixtureCoverPixels)), "retried fixture cover pixels not rendered");
        } catch (AssertionError failure) {
            fx(() -> {
                List<ImageView> views = firstPlaylistCell().lookupAll("ImageView").stream()
                        .filter(ImageView.class::isInstance).map(ImageView.class::cast).toList();
                System.err.println("COVER DIAGNOSTICS: cell ImageView count=" + views.size());
                for (ImageView view : views) {
                    var image = view.getImage();
                    System.err.println(image == null ? "COVER IMAGE: null" : "COVER IMAGE: width="
                            + image.getWidth() + ", height=" + image.getHeight() + ", error="
                            + image.isError() + ", pixelReader=" + (image.getPixelReader() != null)
                            + ", exception=" + image.getException());
                }
                screenshot("cover-retry-failed.png");
            });
            throw failure;
        }
        fx(() -> screenshot("cover-reopened.png"));
        System.out.println("PASS failed cover GETs=503 remain stable; same playlist reopen retries and displays local PNG");

        phase = "sidebar text, independent playlist scrolling and collapse animation";
        sidebarChecks();
        System.out.println("PASS sidebar labels, fixed bottom controls, playlist scrolling and animated pet transfer");

        phase = "real local playback and immersive menus";
        fx(() -> {
            openPlaylist(playlistId);
            ListView<Track> tracks = list("playlist-tracks");
            tracks.getSelectionModel().clearAndSelect(0);
            tracks.fireEvent(doubleClick());
        });
        await(15_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING
                && first.id().equals(field("loadedTrack", Track.class).id())
                && audio().position().toSeconds() > .2), "local 30s WAV did not play");
        await(4_000, () -> fxValue(this::manualLyricsRestored), "initial playback did not restore saved manual lyrics");
        AudioPlayer initialAudio = fxValue(this::audio);
        long initialLoad = fxValue(() -> field("loadVersion", Long.class));
        PlayerQueue.Snapshot initialQueue = fxValue(() -> field("queue", PlayerQueue.class).snapshot());
        double initialPosition = fxValue(() -> audio().position().toSeconds());
        fx(() -> {
            invoke("setLyrics", new Class<?>[] {List.class, String.class}, List.of(), "Fixture display loss");
            check(field("activeLyrics", List.class).isEmpty(), "lyric display-loss fixture did not clear lines");
            click("nav-home");
            click("nav-playlists");
        });
        await(4_000, () -> fxValue(this::manualLyricsRestored), "playlist reopen did not restore cached manual lyrics");
        fx(() -> {
            check(audio() == initialAudio && field("loadVersion", Long.class) == initialLoad,
                    "lyric restoration replaced or reloaded audio");
            check(field("queue", PlayerQueue.class).snapshot().equals(initialQueue), "lyric restoration changed playback queue");
            check(audio().state() == JavaFxAudioPlayer.State.PLAYING
                    && audio().position().toSeconds() >= initialPosition - .1, "lyric restoration reset playback progress");
            click("open-immersive");
        });
        System.out.println("PASS real playback and playlist reopen restore locally saved manual lyrics without reloading audio");
        pause(450);
        fx(this::immersiveChecks);
        lyricChecks();
        System.out.println("PASS four main immersive actions plus More; marquee advances, pauses and seeks with real audio");

        phase = "dark and light native screenshot matrix";
        themeMatrix();
        System.out.println("PASS 1280x800 and 860x600 dark/light screenshots, rounded transparent root and trash glyph");

        phase = "discoverable playlist batch actions and real persisted results";
        batchChecks();
        System.out.println("PASS batch select all, favorite, playlist add, enqueue and playlist remove through real service");

        phase = "diary common actions, batch callbacks and private-record deletion";
        diaryChecks();
        System.out.println("PASS diary hearts, More, batch queue callback and deletion with unsaved draft preserved");
    }

    private void sidebarChecks() throws Exception {
        fx(() -> {
            Field width = LumenFxApp.class.getDeclaredField("sidebarWidth");
            width.setAccessible(true);
            width.setDouble(app, 176);
            field("updateSidebarLayout", Runnable.class).run();
            layout();
            check(Math.abs(node("sidebar-scroll").getLayoutBounds().getWidth() - 176) < 1,
                    "sidebar narrow-width acceptance did not apply 176px");
            check(!field("sidebarCollapsed", Boolean.class), "fixture sidebar starts collapsed");
            for (String id : List.of("quick-search-open", "sidebar-settings")) {
                Button button = (Button) node(id);
                check(button.getContentDisplay() == ContentDisplay.LEFT, "sidebar text hidden: " + id);
                Text rendered = (Text) button.lookup(".text");
                check(rendered != null && rendered.getText().equals(button.getText()), "sidebar label truncated: " + id);
                check(rendered.getBoundsInParent().getMaxX() <= button.getWidth() + 1,
                        "sidebar label exceeds button width: " + id);
                fits(id);
            }
            ListView<?> playlists = (ListView<?>) node("playlist-order-list");
            check(playlists.getItems().size() == 32, "many playlist fixture missing");
            if (!node("sidebar-playlists").isManaged()) click("nav-playlists");
            check(node("sidebar-playlists").isManaged(), "sidebar playlist list did not expand");
        });
        pause(350);
        double settingsY = fxValue(() -> node("sidebar-settings").localToScene(node("sidebar-settings").getBoundsInLocal()).getMinY());
        fx(() -> ((ListView<?>) node("playlist-order-list")).scrollTo(31));
        pause(200);
        fx(() -> {
            layout();
            ListView<?> playlists = (ListView<?>) node("playlist-order-list");
            check(playlists.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast).anyMatch(cell -> cell.getIndex() == 31 && !cell.isEmpty()),
                    "last playlist not reachable through independent scroll");
            check(playlists.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
                    .map(ScrollBar.class::cast).noneMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL
                            && bar.isVisible() && bar.getLayoutBounds().getHeight() > 0),
                    "expanded playlist list has a visible horizontal scrollbar");
            double after = node("sidebar-settings").localToScene(node("sidebar-settings").getBoundsInLocal()).getMinY();
            check(Math.abs(settingsY - after) < 1, "bottom settings moved with playlist scroll");
            check(((ScrollPane) node("sidebar-scroll")).getVvalue() == 0, "whole sidebar scrolled");
            fits("quick-search-open"); fits("sidebar-settings"); fits("sidebar-collapse"); fits("pet-dock");
            screenshot("sidebar-many-playlists.png");
        });
        double expanded = fxValue(() -> node("sidebar-scroll").getLayoutBounds().getWidth());
        fx(() -> click("sidebar-collapse"));
        pause(90);
        double middle = fxValue(() -> node("sidebar-scroll").getLayoutBounds().getWidth());
        check(middle > 66.5 && middle < expanded - .5, "collapse has no intermediate width: " + middle);
        pause(330);
        double[] desktopTarget = fxValue(() -> new double[] {
                field("desktopPet", DesktopPetWindow.class).getX(), field("desktopPet", DesktopPetWindow.class).getY()});
        fx(() -> {
            check(Math.abs(node("sidebar-scroll").getLayoutBounds().getWidth() - 66) < 1,
                    "collapsed sidebar width not settled");
            check(field("desktopPet", DesktopPetWindow.class).isShowing(), "pet did not move to desktop");
            PetCompanion pet = field("pet", PetCompanion.class);
            check(pet.getScene() != stage.getScene(), "pet still belongs to main window");
            check(pet.getScene().getWindow().getWidth() >= 86 && pet.getScene().getWindow().getHeight() >= 86,
                    "desktop pet window is clipped");
            Bounds petBounds = pet.localToScene(pet.getBoundsInLocal());
            check(petBounds.getMinX() >= -1 && petBounds.getMaxX() <= pet.getScene().getWidth() + 1,
                    "desktop pet extends beyond its window");
            screenshot(pet.getScene(), "collapsed-desktop-pet.png");
            click("sidebar-collapse");
        });
        pause(350);
        fx(() -> {
            check(!field("desktopPet", DesktopPetWindow.class).isShowing(), "pet did not return after expansion");
            check(field("pet", PetCompanion.class).getParent() == node("pet-dock"), "pet did not return to original dock");
            check(Math.abs(node("sidebar-scroll").getLayoutBounds().getWidth() - expanded) < 1,
                    "expanded width not restored");
        });
        for (int cycle = 0; cycle < 3; cycle++) {
            fx(() -> click("sidebar-collapse"));
            pause(80);
            fx(() -> click("sidebar-collapse"));
            pause(80);
            fx(() -> click("sidebar-collapse"));
            pause(350);
            fx(() -> {
                DesktopPetWindow desktop = field("desktopPet", DesktopPetWindow.class);
                check(desktop.isShowing() && field("pet", PetCompanion.class).getScene() != stage.getScene(),
                        "rapid collapse/reverse left pet in wrong scene");
                check(Math.abs(desktop.getX() - desktopTarget[0]) < 2
                        && Math.abs(desktop.getY() - desktopTarget[1]) < 2,
                        "rapid collapse/reverse changed saved desktop target");
                click("sidebar-collapse");
            });
            pause(350);
            fx(() -> {
                check(!field("desktopPet", DesktopPetWindow.class).isShowing()
                        && field("pet", PetCompanion.class).getParent() == node("pet-dock"),
                        "rapid reverse did not settle back in dock");
            });
        }
    }

    private void immersiveChecks() {
        Parent actions = (Parent) node("immersive-actions");
        check(actions.getChildrenUnmodifiable().size() == 5, "immersive actions are not four commands plus More");
        for (String id : List.of("immersive-open-source", "immersive-favorite", "immersive-add-playlist", "immersive-match-lyrics", "immersive-more")) {
            fits(id);
            if (!id.equals("immersive-more")) {
                Button button = (Button) node(id);
                check(button.getTooltip() != null && button.getTooltip().getShowDuration().toSeconds() >= 60,
                        "primary immersive tooltip disappears too soon: " + id);
            }
        }
        click("immersive-more");
        ContextMenu menu = showingMenu();
        for (String id : List.of("create-segment", "immersive-versions", "immersive-radio",
                "immersive-focus", "immersive-practice", "immersive-segment-batch", "immersive-calibrate"))
            check(findMenuItem(menu, id) != null, "missing immersive More action: " + id);
        findMenuItem(menu, "immersive-calibrate").fire();
        menu.hide();
        check(node("immersive-calibration").isManaged(), "calibration menu command did not reveal controls");
        click("immersive-more");
        menu = showingMenu();
        findMenuItem(menu, "immersive-calibrate").fire(); menu.hide();
    }

    private void lyricChecks() throws Exception {
        fx(() -> invoke("seekToFraction", new Class<?>[] {double.class}, .15));
        await(5_000, () -> fxValue(() -> audio().position().toSeconds() > 4), "seek to lyric progress did not finish");
        pause(220);
        double before = fxValue(() -> marqueeText().getTranslateX());
        check(before < -1, "long active lyric not shifted at 4 seconds");
        pause(600);
        double after = fxValue(() -> marqueeText().getTranslateX());
        check(after < before - 1, "long active lyric did not move with playback");
        fx(() -> click("play-button"));
        await(2_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause did not take effect");
        pause(180);
        double paused = fxValue(() -> marqueeText().getTranslateX());
        double position = fxValue(() -> audio().position().toSeconds());
        pause(550);
        fx(() -> {
            check(Math.abs(marqueeText().getTranslateX() - paused) < .5, "marquee moved while paused");
            check(Math.abs(audio().position().toSeconds() - position) < .08, "audio clock moved while paused");
            invoke("seekToFraction", new Class<?>[] {double.class}, .5);
        });
        await(5_000, () -> fxValue(() -> Math.abs(audio().position().toSeconds() - 15) < 1), "paused seek did not update media clock");
        await(2_000, () -> fxValue(() -> marqueeText().getTranslateX() < paused - 20), "seek did not update marquee progress");
        fx(() -> screenshot("lyric-marquee-paused-seek.png"));
    }

    private Text marqueeText() {
        ListView<?> lyrics = (ListView<?>) node("immersive-lyrics");
        lyrics.applyCss(); lyrics.layout();
        ListCell<?> active = lyrics.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                .map(ListCell.class::cast).filter(cell -> cell.getStyleClass().contains("current-lyric"))
                .findFirst().orElseThrow(() -> new AssertionError("active lyric cell missing"));
        check(active.getGraphic() instanceof LyricMarquee, "active lyric has no marquee");
        Parent marquee = (Parent) active.getGraphic();
        check(marquee.getClip() != null && marquee.getLayoutBounds().getWidth() > 0, "marquee clip has no width");
        return marquee.getChildrenUnmodifiable().stream().filter(Text.class::isInstance).map(Text.class::cast)
                .findFirst().orElseThrow();
    }

    private boolean manualLyricsRestored() {
        List<?> lines = field("activeLyrics", List.class);
        LyricsSelectionStore.Selection selection = field("manualLyricSelection", LyricsSelectionStore.Selection.class);
        return lines.equals(Lyrics.parse(FIXTURE_LRC)) && selection != null
                && selection.origin() == LyricsSelectionStore.Origin.MANUAL
                && selection.offsetMillis() == 0 && selection.startMillis() == 0
                && field("lyricsStatus", Label.class).getText()
                        .equals("已恢复手动匹配：" + first.title() + " · " + first.artist());
    }

    private void themeMatrix() throws Exception {
        for (ThemeController.Mode mode : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT)) {
            fx(() -> {
                for (int i = 0; i < ThemeController.Mode.values().length
                        && field("themeMode", ThemeController.Mode.class) != mode; i++) click("theme-button");
                check(field("themeMode", ThemeController.Mode.class) == mode, "cannot choose theme " + mode);
            });
            for (int[] size : new int[][] {{1280, 800}, {860, 600}}) {
                fx(() -> { stage.setWidth(size[0]); stage.setHeight(size[1]); click("open-immersive"); });
                pause(450);
                fx(() -> {
                    layout();
                    fits("immersive-cover"); fits("immersive-lyrics"); fits("immersive-actions"); fits("immersive-collapse");
                    check(stage.getStyle() == StageStyle.TRANSPARENT && Color.TRANSPARENT.equals(stage.getScene().getFill()),
                            "native window is not transparent");
                    check(stage.getScene().getRoot().getClip() instanceof Rectangle, "window has no rectangle clip");
                    Rectangle clip = (Rectangle) stage.getScene().getRoot().getClip();
                    check(clip.getArcWidth() > 0 && clip.getArcHeight() > 0, "normal window clip is not rounded");
                    WritableImage snapshot = stage.getScene().snapshot(null);
                    check(snapshot.getPixelReader().getColor(0, 0).getOpacity() < .05, "rounded window corner is opaque");
                    check(snapshot.getPixelReader().getColor((int) snapshot.getWidth() / 2,
                            (int) snapshot.getHeight() / 2).getOpacity() > .95, "window center is transparent");
                    screenshot(mode.name().toLowerCase() + "-immersive-" + size[0] + "x" + size[1] + ".png");
                    click("immersive-collapse"); click("toggle-queue");
                });
                pause(400);
                fx(() -> {
                    layout(); fits("queue-clear"); fits("queue-list");
                    check(node("queue-clear") instanceof Button, "queue clear command missing");
                    SVGPath glyph = (SVGPath) ((Button) node("queue-clear")).getGraphic();
                    check(glyph.getContent().equals(invokeStatic("iconPath", new Class<?>[] {String.class}, "trash")),
                            "queue clear does not use trash glyph");
                    if (mode == ThemeController.Mode.DARK) {
                        Labeled toggle = (Labeled) node("queue-list-batch-mode");
                        Label label = (Label) node("queue-list-selection-count");
                        check(toggle.getTextFill() instanceof Color && label.getTextFill() instanceof Color,
                                "batch text colors cannot be compared");
                        double toggleBrightness = ((Color) toggle.getTextFill()).getBrightness();
                        double labelBrightness = ((Color) label.getTextFill()).getBrightness();
                        check(toggleBrightness >= .65 && Math.abs(toggleBrightness - labelBrightness) < .15,
                                "dark batch toggle text differs from readable label color: " + toggle.getTextFill());
                    }
                    screenshot(mode.name().toLowerCase() + "-queue-" + size[0] + "x" + size[1] + ".png");
                    click("toggle-queue");
                });
                pause(300);
            }
        }
        fx(() -> { stage.setWidth(1280); stage.setHeight(800); });
        pause(350);
        fx(() -> stage.setMaximized(true));
        pause(350);
        fx(() -> {
            Rectangle clip = (Rectangle) stage.getScene().getRoot().getClip();
            check(clip.getArcWidth() == 0 && clip.getArcHeight() == 0, "maximized window clip is rounded");
            stage.setMaximized(false);
        });
        pause(350);
        fx(() -> {
            Rectangle clip = (Rectangle) stage.getScene().getRoot().getClip();
            check(clip.getArcWidth() == 24 && clip.getArcHeight() == 24, "restored window lost 24px corner arcs");
        });
    }

    private void batchChecks() throws Exception {
        fx(() -> {
            openPlaylist(playlistId); layout();
            fits("playlist-tracks-batch-mode");
            click("playlist-tracks-batch-mode");
            click("playlist-tracks-select-all");
            check(list("playlist-tracks").getSelectionModel().getSelectedItems().size() == 2, "batch select all did not select two tracks");
            check(((Label) node("playlist-tracks-selection-count")).getText().contains("2"), "selection count missing");
            fireBatch("playlist-tracks", "batch-menu-favorite");
        });
        await(8_000, () -> library.load().favorites().size() == 2, "batch favorites not persisted");
        fx(() -> {
            click("playlist-tracks-batch-more");
            ContextMenu menu = showingMenu();
            Menu add = (Menu) findMenuItem(menu, "batch-menu-playlist");
            check(add != null, "batch playlist submenu missing");
            add.getItems().stream().filter(item -> item.getText().equals(targetPlaylistName)).findFirst().orElseThrow().fire();
            menu.hide();
        });
        await(8_000, () -> library.load().playlists().stream().filter(item -> item.id().equals(targetPlaylistId))
                .findFirst().orElseThrow().tracks().size() == 2, "batch playlist add not persisted");
        int queuedBefore = fxValue(() -> field("queue", PlayerQueue.class).tracks().size());
        fx(() -> fireBatch("playlist-tracks", "batch-menu-enqueue"));
        await(3_000, () -> fxValue(() -> field("queue", PlayerQueue.class).tracks().size() == queuedBefore + 2),
                "batch enqueue did not append both songs");
        fx(() -> fireBatch("playlist-tracks", "batch-menu-remove"));
        await(8_000, () -> library.load().playlists().stream().filter(item -> item.id().equals(playlistId))
                .findFirst().orElseThrow().tracks().isEmpty(), "batch playlist removal not persisted");
        fx(() -> { click("nav-favorites"); layout(); fits("favorites-list-batch-mode"); screenshot("batch-favorites.png"); });
    }

    private void diaryChecks() throws Exception {
        fx(() -> {
            click("nav-diary");
            field("diaryPane", DiaryPane.class).tabs().getSelectionModel().select(1);
        });
        await(8_000, () -> fxValue(() -> node("diary-notes").lookupAll("#diary-note-row").size() == 2), "fixture notes did not load");
        fx(() -> {
            Parent row = (Parent) node("diary-notes").lookup("#diary-note-row");
            check(row.lookup("#diary-track-favorite") != null && row.lookup("#diary-track-more") != null,
                    "diary row lacks common heart/More actions");
            check(row.getChildrenUnmodifiable().stream().filter(Button.class::isInstance).count() == 2,
                    "diary row has extra direct actions");
            ((Button) row.lookup("#diary-track-more")).fire();
            ContextMenu menu = showingMenu();
            check(findMenuItem(menu, "track-menu-play") != null && findMenuItem(menu, "track-menu-diary-note") != null,
                    "diary More does not share track actions");
            menu.hide();
            click("diary-batch-mode"); click("diary-select-all");
            check(field("diaryPane", DiaryPane.class).selectedTracks().size() == 2, "diary select all did not select two unique songs");
        });
        int before = fxValue(() -> field("queue", PlayerQueue.class).tracks().size());
        fx(() -> {
            click("diary-batch-more");
            ContextMenu menu = showingMenu();
            findMenuItem(menu, "batch-menu-enqueue").fire(); menu.hide();
        });
        await(3_000, () -> fxValue(() -> field("queue", PlayerQueue.class).tracks().size() == before + 2),
                "diary batch callback did not queue both songs");
        fx(() -> {
            field("diaryPane", DiaryPane.class).editTrackNote(first);
            ((TextArea) node("diary-note-input")).setText("批量删除期间保留的未保存草稿");
            click("diary-batch-more");
            ContextMenu menu = showingMenu();
            findMenuItem(menu, "diary-batch-delete-notes").fire(); menu.hide();
        });
        await(8_000, () -> diaryClient.load().notes().isEmpty(), "batch notes deletion not persisted");
        fx(() -> {
            check(((TextArea) node("diary-note-input")).getText().equals("批量删除期间保留的未保存草稿"),
                    "batch note deletion overwrote editor draft");
            field("diaryPane", DiaryPane.class).tabs().getSelectionModel().select(2);
            click("diary-select-all"); click("diary-batch-more");
            ContextMenu menu = showingMenu();
            findMenuItem(menu, "diary-batch-delete-lyrics").fire(); menu.hide();
        });
        await(8_000, () -> diaryClient.load().lyricFavorites().isEmpty(), "batch lyric deletion not persisted");
        fx(() -> screenshot("diary-batch-draft-preserved.png"));
    }

    private void prepareFixture() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("v021-polish-smoke") && !Files.exists(root.resolve("library.json")),
                "fresh v021-polish-smoke isolated profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), key + " escapes isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null,
                "isolated smoke must not contain AI credentials");
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost()), "loopback service required");
        library = new LibraryClient(service); diaryClient = new DiaryClient(service);
        check(library.load().playlists().isEmpty(), "fixture requires empty library");
        Path wav = root.resolve("polish-local-30s.wav");
        int rate = 11_025, seconds = 30;
        byte[] pcm = new byte[rate * seconds * 2];
        for (int frame = 0; frame < rate * seconds; frame++) {
            short sample = (short) (1_000 * Math.sin(frame * Math.PI * 440 / rate));
            pcm[frame * 2] = (byte) sample; pcm[frame * 2 + 1] = (byte) (sample >>> 8);
        }
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(rate, 16, 1, true, false), rate * seconds)) {
            check(AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile()) > 0, "fixture WAV missing");
        }
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) image.setRGB(x, y, x < 64 ? 0xFF26A69A : 0xFFE46A8A);
        Path png = root.resolve("polish-cover.png");
        check(ImageIO.write(image, "png", png.toFile()), "fixture PNG missing");
        byte[] coverBytes = Files.readAllBytes(png);
        coverServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        coverServer.createContext("/retry-cover.png", exchange -> {
            try (exchange) {
                if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); return; }
                coverGets.incrementAndGet();
                if (!recoverCover.get()) { exchange.sendResponseHeaders(503, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, coverBytes.length); exchange.getResponseBody().write(coverBytes);
            }
        });
        coverServer.createContext("/stable-cover.png", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); return; }
                exchange.sendResponseHeaders(200, coverBytes.length); exchange.getResponseBody().write(coverBytes);
            }
        });
        coverServer.start();
        URI cover = URI.create("http://127.0.0.1:" + coverServer.getAddress().getPort() + "/retry-cover.png");
        first = new Track("polish-first", "验收第一首", "Fixture", 30, "local", wav.toUri(), null, 0, cover);
        URI stableCover = URI.create("http://127.0.0.1:" + coverServer.getAddress().getPort() + "/stable-cover.png");
        second = new Track("polish-second", "验收第二首", "Fixture", 30, "local", wav.toUri(), null, 0, stableCover);
        Path lyricDirectory = LyricsSelectionStore.defaultDirectory();
        check(lyricDirectory.equals(root.resolve("lyrics-selections")), "lyric fixture path is outside isolated profile");
        new LyricsSelectionStore(lyricDirectory).save(first, new LyricsSelectionStore.Selection(
                first.title(), first.artist(), FIXTURE_LRC, 0, LyricsSelectionStore.Origin.MANUAL, 0));
        playlistId = library.createPlaylist("界面验收歌单").playlists().get(0).id();
        targetPlaylistId = library.createPlaylist(targetPlaylistName).playlists().stream()
                .filter(item -> item.name().equals(targetPlaylistName)).findFirst().orElseThrow().id();
        for (int i = 0; i < 30; i++) library.createPlaylist("独立滚动歌单 " + String.format("%02d", i));
        library.addToPlaylist(playlistId, first); library.addToPlaylist(playlistId, second);
        diaryClient.saveNote(first, "第一首私人备注"); diaryClient.saveNote(second, "第二首私人备注");
        diaryClient.saveLyric(first, 4_000, "收藏的歌词片段");
        Properties preferences = new Properties();
        preferences.setProperty("theme", "dark"); preferences.setProperty("sidebar.collapsed", "false");
        preferences.setProperty("sidebar.width", "176"); preferences.setProperty("pet", "BLACK_CAT");
        try (var output = Files.newOutputStream(Path.of(required("LUMEN_PREFERENCES_PATH")))) {
            preferences.store(output, "V021 native polish fixture");
        }
    }

    private void fireBatch(String listId, String menuId) {
        click(listId + "-batch-more");
        ContextMenu menu = showingMenu();
        MenuItem item = findMenuItem(menu, menuId);
        check(item != null && !item.isDisable(), "missing/disabled batch command " + menuId);
        item.fire(); menu.hide();
    }
    private ContextMenu showingMenu() {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(Window::isShowing).findFirst().orElseThrow(() -> new AssertionError("no showing ContextMenu"));
    }
    private static MenuItem findMenuItem(ContextMenu menu, String id) {
        return menu.getItems().stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
    }
    private void openPlaylist(String id) {
        click("nav-playlists");
        picker().setValue(picker().getItems().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow());
        layout();
    }
    private ListCell<?> firstPlaylistCell() {
        ListView<Track> list = list("playlist-tracks"); list.scrollTo(0); list.applyCss(); list.layout();
        return list.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance).map(ListCell.class::cast)
                .filter(cell -> cell.getIndex() == 0 && !cell.isEmpty()).findFirst().orElseThrow();
    }
    private boolean hasFixtureCoverPixels(ImageView view) {
        var image = view.getImage();
        if (image == null || image.isError() || image.getPixelReader() == null
                || image.getWidth() < 4 || image.getHeight() < 2) return false;
        int y = (int) (image.getHeight() / 2);
        int left = (int) (image.getWidth() / 4);
        int right = (int) (image.getWidth() * 3 / 4);
        return image.getPixelReader().getArgb(left, y) == 0xFF26A69A
                && image.getPixelReader().getArgb(right, y) == 0xFFE46A8A;
    }
    private static MouseEvent doubleClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, true, false, false, null);
    }
    private void click(String id) { ((ButtonBase) node(id)).fire(); }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private Node node(String id) {
        Node found = stage.getScene().lookup("#" + id);
        check(found != null, "missing UI #" + id); return found;
    }
    @SuppressWarnings("unchecked") private ListView<Track> list(String id) { return (ListView<Track>) node(id); }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return (ComboBox<LibraryClient.Playlist>) node("playlist-picker"); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private <T> T field(String name, Class<T> type) {
        try { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private Object invoke(String name, Class<?>[] types, Object... values) {
        try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(app, values); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot invoke " + name, error); }
    }
    private Object invokeStatic(String name, Class<?>[] types, Object... values) {
        try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, values); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect static " + name, error); }
    }
    private void fits(String id) {
        Node item = node(id); check(item.isVisible() && item.isManaged(), "hidden/unmanaged " + id);
        Bounds b = item.localToScene(item.getLayoutBounds());
        check(b.getMinX() >= -1 && b.getMaxX() <= stage.getScene().getWidth() + 1
                && b.getMinY() >= -1 && b.getMaxY() <= stage.getScene().getHeight() + 1, "UI overflow " + id + ": " + b);
    }
    private void screenshot(String name) { screenshot(stage.getScene(), name); }
    private void screenshot(Scene scene, String name) {
        try {
            WritableImage snapshot = scene.snapshot(null);
            BufferedImage png = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
                png.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            Path target = root.resolve(name);
            check(ImageIO.write(png, "png", target.toFile()) && Files.size(target) > 1_000, "screenshot missing " + name);
            System.out.println("SCREENSHOT: " + target);
        } catch (Exception error) { throw new AssertionError("screenshot failed " + name, error); }
    }
    private static String required(String key) {
        String value = System.getenv(key); check(value != null && !value.isBlank(), "missing " + key); return value;
    }
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
