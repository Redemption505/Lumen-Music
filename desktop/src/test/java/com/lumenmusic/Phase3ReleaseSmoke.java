package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Release-JAR UI workflow, independent process restart, and real playlist viewport samples. */
public final class Phase3ReleaseSmoke extends Application {
    private Path root;
    private URI service;
    private LibraryClient library;
    private LumenFxApp app;
    private Stage stage;
    private volatile String phase = "startup";
    private final List<String> samples = new ArrayList<>(List.of(
            "round,target,firstIndex,lastIndex,firstId,lastId,visibleCells,activeCells,flowPosition,responseMs,pulses,pulseMeanMs,pulseMaxMs,heapUsed,heapCommitted,nonHeapUsed"));
    private final List<Long> pulses = new ArrayList<>();
    private AnimationTimer timer;
    private double scrollBefore;
    private long scrollRequested;
    private volatile long scrollResponded;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(210_000); } catch (InterruptedException ignored) { return; }
            System.err.println("FAIL release watchdog: " + phase); System.exit(1);
        }, "release-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int code = 0;
            try { exercise(); }
            catch (Throwable error) { code = 1; System.err.println("FAIL release phase: " + phase); error.printStackTrace(); }
            finally {
                try { if (samples.size() > 1) Files.write(root.resolve("playlist-scroll.csv"), samples); }
                catch (Exception error) { error.printStackTrace(); code = 1; }
                try { fx(() -> { if (timer != null) timer.stop(); if (app != null) app.stop(); stage.close(); }); }
                catch (Exception error) { error.printStackTrace(); code = 1; }
                System.exit(code);
            }
        }, "release-smoke-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(root.toString().contains("phase3-release-smoke"), "isolated profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath();
            check(path.equals(root) || root.equals(path.getParent()), key + " escaped profile");
        }
        for (String key : List.of("OPENAI_API_KEY", "LUMEN_AI_API_KEY")) {
            String value = System.getenv(key);
            check(value == null || value.isBlank(), "AI credential present");
        }
        Path production = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(production.equals(Path.of(required("LUMEN_RELEASE_JAR")).toRealPath()) && production.toString().endsWith(".jar"),
                "production class did not come from the specified release JAR: " + production);
        System.out.println("PRODUCTION JAR: " + production);
        service = URI.create(required("LUMEN_SERVICE_URL"));
        check(service.getHost().equals("127.0.0.1"), "loopback service required");
        library = new LibraryClient(service);
        fx(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(1280); stage.setHeight(800); });
        await(15_000, () -> fxValue(() -> picker().getItems().size() >= 2), "library UI did not load");
        check(fxValue(() -> field("themeMode", ThemeController.Mode.class)) == ThemeController.Mode.LIGHT
                && fxValue(() -> field("normalizationEnabled", Boolean.class)), "legacy settings were not read");
        double expectedScale = Double.parseDouble(required("LUMEN_RELEASE_SCALE"));
        check(Math.abs(fxValue(() -> stage.getOutputScaleX()) - expectedScale) < .01, "actual DPI differs from requested scale");
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()));
        check(library.load().favorites().stream().map(Track::id).toList()
                .equals(List.of("release-old-source", "release-old-manual")), "legacy favorite identity/order lost");
        if ("restart".equals(required("LUMEN_RELEASE_MODE"))) { verifyRestart(); return; }
        if ("performance".equals(required("LUMEN_RELEASE_MODE"))) { measurePlaylist(); verifyRestart(); return; }
        workflow(); measurePlaylist();
        System.out.println("PASS release workflow and actual 2000-track playlist; memory samples are observations without a baseline failure threshold");
    }

    private void workflow() throws Exception {
        phase = "batch paste and normal playlist creation";
        Track source = library.load().favorites().get(0);
        fx(() -> {
            selectPlaylist("release-old-playlist");
            ContextMenu menu = trackMenu(source); fire(menu, "track-menu-segment-batch");
            DialogPane pane = dialog("segment-batch-dialog");
            textArea(pane, "segment-batch-input").setText("00:02 - 00:12 Release first\n00:14 - 00:24 Release second\n00:26 - 00:36 Release third");
            button(pane, "segment-batch-parse").fire();
            TableView<?> table = (TableView<?>) node(pane, "segment-batch-table");
            check(table.getItems().size() == 3, "actual parser candidate count wrong");
            table.getSelectionModel().select(1);
            text(pane, "segment-batch-title").setText("Release second edited");
            combo(pane, "segment-batch-destination").setValue("新建歌单");
            text(pane, "segment-batch-playlist-name").setText("Release integrated playlist");
            screenshot(pane, "batch-confirm.png");
            button(pane, "segment-batch-save").fire();
        });
        await(15_000, () -> library.load().playlists().stream().anyMatch(item -> item.name().equals("Release integrated playlist")), "batch playlist did not save");
        await(10_000, () -> fxValue(() -> dialogOrNull("segment-batch-dialog") == null), "batch dialog not closed");
        var playlist = integrated();
        List<Track> clips = playlist.tracks();
        check(clips.size() == 3 && clips.stream().allMatch(Track::isSegment), "batch did not generate normal segment playlist");
        check(clips.get(1).title().equals("Release second edited"), "batch edit lost");
        for (int i = 0; i < 3; i++) check(clips.get(i).sourceTrackId().equals(source.id())
                && clips.get(i).segmentStartMillis() == (2 + i * 12) * 1000L
                && clips.get(i).segmentEndMillis() == (12 + i * 12) * 1000L, "batch range/provenance wrong");

        phase = "preferred version in real version editor";
        VersionCatalogClient versions = new VersionCatalogClient(service);
        var song = versions.create("Release grouped song", "Integrated version note", List.of(
                new VersionCatalog.Version("First segment", "Fixture", clips.get(0)),
                new VersionCatalog.Version("Second segment", "Fixture", clips.get(1))))
                .findByResourceId(clips.get(0).id()).orElseThrow();
        fx(() -> invoke("showVersions", new Class<?>[] {Track.class}, clips.get(0)));
        await(10_000, () -> fxValue(() -> field("versionCatalog", VersionCatalog.class).findByResourceId(clips.get(1).id()).isPresent()), "version catalog not loaded");
        fx(() -> {
            DialogPane pane = dialog("version-dialog");
            ListView<VersionCatalog.Version> list = list(pane, "version-list");
            list.getSelectionModel().select(list.getItems().stream().filter(v -> v.track().id().equals(clips.get(1).id())).findFirst().orElseThrow());
            button(pane, "version-prefer").fire();
        });
        await(10_000, () -> versions.load().preferredTrack(clips.get(0)).id().equals(clips.get(1).id()), "preferred version not persisted");
        fx(() -> { screenshot(dialog("version-dialog"), "preferred-version.png"); close(dialog("version-dialog")); button("smart-playlists").fire(); });

        phase = "real organizer tag and smart rule";
        await(15_000, () -> fxValue(() -> dialogOrNull("smart-playlist-dialog") != null
                && list(dialog("smart-playlist-dialog"), "organization-track-list").getItems().size() >= 2005), "organizer did not load release entries");
        fx(() -> {
            DialogPane pane = dialog("smart-playlist-dialog");
            ((TabPane) node(pane, "organization-tabs")).getSelectionModel().select(1);
            ListView<SmartPlaylistClient.Entry> entries = list(pane, "organization-track-list");
            entries.getSelectionModel().clearSelection();
            for (int i = 0; i < entries.getItems().size(); i++) {
                String id = entries.getItems().get(i).track().id();
                if (clips.stream().anyMatch(t -> t.id().equals(id))) entries.getSelectionModel().select(i);
            }
            text(pane, "organization-tag-input").setText("ReleaseFocus"); button(pane, "organization-tag-add").fire();
        });
        SmartPlaylistClient organizer = new SmartPlaylistClient(service);
        await(15_000, () -> organizer.load().entries().stream().filter(e -> clips.stream().anyMatch(t -> t.id().equals(e.track().id()))).allMatch(e -> e.tags().contains("ReleaseFocus"))
                && fxValue(() -> !button(dialog("smart-playlist-dialog"), "organization-tag-add").isDisabled()), "UI tags did not persist");
        fx(() -> {
            DialogPane pane = dialog("smart-playlist-dialog");
            ((TabPane) node(pane, "organization-tabs")).getSelectionModel().select(0);
            button(pane, "smart-new").fire(); text(pane, "smart-name").setText("ReleaseFocus rule");
            text(pane, "smart-tags-all").setText("ReleaseFocus"); text(pane, "smart-max-duration").setText("12");
            button(pane, "smart-preview").fire();
        });
        await(15_000, () -> fxValue(() -> list(dialog("smart-playlist-dialog"), "smart-preview-results").getItems().size() == 3
                && !button(dialog("smart-playlist-dialog"), "smart-save").isDisabled()), "smart rule preview wrong");
        fx(() -> button(dialog("smart-playlist-dialog"), "smart-save").fire());
        await(15_000, () -> organizer.load().smartPlaylists().stream().anyMatch(s -> s.name().equals("ReleaseFocus rule")), "smart rule not saved");
        fx(() -> { screenshot(dialog("smart-playlist-dialog"), "smart-rule.png"); close(dialog("smart-playlist-dialog")); selectPlaylist(playlist.id());
            queue().select(List.of(clips.get(0)), 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true); });
        awaitPlaying(clips.get(0));

        phase = "playlist-seeded real radio and manual priority";
        fx(() -> { button("playlist-radio").fire(); ((CheckBox) node(dialog("radio-dialog"), "radio-neutral")).setSelected(false); button(dialog("radio-dialog"), "radio-start").fire(); });
        await(15_000, () -> fxValue(() -> field("radio", RadioSession.class).active()
                && !list(dialog("radio-dialog"), "radio-list").getItems().isEmpty()), "real service radio returned no candidates");
        fx(() -> {
            RadioSession radio = field("radio", RadioSession.class);
            check(radio.seeds().stream().map(Track::id).toList().equals(clips.stream().map(Track::id).toList()), "radio lost playlist seed identity/ranges");
            ListView<Track> candidates = list(dialog("radio-dialog"), "radio-list");
            check(candidates.getItems().stream().allMatch(t -> t.source().equals("local") && Files.isRegularFile(Path.of(t.mediaUri()))), "radio candidate lacks a real local resource");
            selectPlaylist("release-old-playlist");
            fire(trackMenu(source), "track-menu-next");
            check(queue().tracks().get(queue().index() + 1).id().equals(source.id())
                    && queue().sources().get(queue().index() + 1) == PlayerQueue.Source.MANUAL, "manual next not ahead of radio");
            screenshot(dialog("radio-dialog"), "playlist-radio.png"); close(dialog("radio-dialog")); button("next-button").fire();
        });
        awaitPlaying(source);
        fx(() -> { invoke("stopRadio", new Class<?>[] {}); button("play-button").fire(); });
        await(4000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "manual playback did not pause");

        phase = "real diary note and foreground preservation";
        long load = fxValue(() -> field("loadVersion", Long.class));
        var snapshot = fxValue(() -> queue().snapshot());
        fx(() -> { button("nav-diary").fire(); field("diaryPane", DiaryPane.class).editTrackNote(clips.get(0));
            textArea(stage.getScene().getRoot(), "diary-note-input").setText("Release integrated private memory"); button("diary-note-save").fire(); });
        DiaryClient diary = new DiaryClient(service);
        await(15_000, () -> diary.load().notes().stream().anyMatch(n -> n.track().id().equals(clips.get(0).id()) && n.text().equals("Release integrated private memory")), "diary note lost");
        fx(() -> { check(queue().snapshot().equals(snapshot) && field("loadVersion", Long.class) == load && audio().state() == JavaFxAudioPlayer.State.PAUSED, "diary edit altered foreground"); screenshot(stage.getScene().getRoot(), "integrated-diary.png"); });
        Properties expected = new Properties();
        expected.setProperty("playlist", playlist.id()); expected.setProperty("song", song.id());
        expected.setProperty("order", String.join(",", clips.stream().map(Track::id).toList()));
        expected.setProperty("preferred", clips.get(1).id());
        try (var output = Files.newOutputStream(root.resolve("restart-expected.properties"))) { expected.store(output, "First process identity evidence"); }
        System.out.println("PASS old library/settings -> UI batch/edit/playlist -> version preference -> UI tag/rule -> seeded radio/manual next -> private diary");
    }

    private void verifyRestart() throws Exception {
        phase = "independent desktop and service restart";
        Properties expected = new Properties();
        try (var input = Files.newInputStream(root.resolve("restart-expected.properties"))) { expected.load(input); }
        var playlist = integrated();
        check(playlist.id().equals(expected.getProperty("playlist"))
                && String.join(",", playlist.tracks().stream().map(Track::id).toList()).equals(expected.getProperty("order")), "restart playlist identity/order changed");
        for (int i = 0; i < 3; i++) check(playlist.tracks().get(i).sourceTrackId().equals("release-old-source")
                && playlist.tracks().get(i).segmentStartMillis() == (2 + i * 12) * 1000L
                && playlist.tracks().get(i).segmentEndMillis() == (12 + i * 12) * 1000L, "restart source range changed");
        var song = new VersionCatalogClient(service).load().findByResourceId(playlist.tracks().get(0).id()).orElseThrow();
        check(song.id().equals(expected.getProperty("song")) && song.preferredTrack().orElseThrow().id().equals(expected.getProperty("preferred")), "restart song/preference lost");
        SmartPlaylistClient organizer = new SmartPlaylistClient(service);
        var state = organizer.load();
        var smart = state.smartPlaylists().stream().filter(s -> s.name().equals("ReleaseFocus rule")).findFirst().orElseThrow();
        check(organizer.preview(smart.id(), null, Set.of()).results().stream().map(Track::id).toList()
                .equals(playlist.tracks().stream().map(Track::id).toList()), "restart tags/rule result changed");
        check(new DiaryClient(service).load().notes().stream().anyMatch(n -> n.track().id().equals(playlist.tracks().get(0).id())
                && n.text().equals("Release integrated private memory")), "restart diary lost");
        fx(() -> selectPlaylist(playlist.id()));
        await(4000, () -> fxValue(this::playlistVisible), "restart playlist did not become visible");
        fx(() -> screenshot(stage.getScene().getRoot(), "restart-playlist.png"));
        Files.writeString(root.resolve("restart-pass.txt"), "PASS independent new desktop and service processes: legacy settings/favorites, segment identities/ranges/order, song identity/preference, tags/rule and diary note\n");
        System.out.println("PASS independent process restart: identity, range, order, preferred version, rule/tags and private diary");
    }

    private void measurePlaylist() throws Exception {
        phase = "actual 2000-track playlist warm-up and scroll samples";
        fx(() -> { selectPlaylist("release-performance"); timer = new AnimationTimer() {
            @Override public void handle(long now) {
                pulses.add(now);
                if (scrollRequested != 0 && scrollResponded == 0
                        && Math.abs(flow().getPosition() - scrollBefore) > .000001) scrollResponded = System.nanoTime();
            }
        }; timer.start(); });
        await(8000, () -> fxValue(() -> playlistVisible() && playlistView().getItems().size() == 2000 && flow().getFirstVisibleCell() != null), "actual large playlist not visible and laid out");
        for (int index : new int[] {0, 1000, 1999, 0}) { fx(() -> playlistView().scrollTo(index)); Thread.sleep(300); }
        for (int round = 1; round <= 3; round++) for (int target : new int[] {0, 1000, 1999}) {
            fx(() -> playlistView().scrollTo(target)); Thread.sleep(300);
            final int run = round;
            fx(() -> {
                playlistView().applyCss(); playlistView().layout();
                int first = flow().getFirstVisibleCell().getIndex(), last = flow().getLastVisibleCell().getIndex();
                check(target == 0 ? first == 0 : target == 1999 ? last == 1999 : first <= 1000 && last >= 1000, "target outside actual VirtualFlow viewport");
                screenshot(stage.getScene().getRoot(), "playlist-round-" + run + "-" + target + ".png");
                pulses.clear();
            });
            fx(() -> { scrollBefore = flow().getPosition(); scrollResponded = 0; scrollRequested = System.nanoTime(); wheel(target == 1999 ? 3 : -3); });
            await(2500, () -> scrollResponded != 0, "real playlist wheel produced no visible scroll response");
            double response = (scrollResponded - scrollRequested) / 1_000_000.0;
            Thread.sleep(400);
            fx(() -> {
                var viewport = flow();
                int first = viewport.getFirstVisibleCell().getIndex(), last = viewport.getLastVisibleCell().getIndex();
                check(viewport.getFirstVisibleCell().getItem() instanceof Track && viewport.getLastVisibleCell().getItem() instanceof Track, "visible cells lack actual Track items");
                String firstId = ((Track) viewport.getFirstVisibleCell().getItem()).id();
                String lastId = ((Track) viewport.getLastVisibleCell().getItem()).id();
                check(firstId.equals(playlistView().getItems().get(first).id()) && lastId.equals(playlistView().getItems().get(last).id()), "visible cell item/index mismatch");
                long active = playlistView().lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                        .map(ListCell.class::cast).filter(cell -> !cell.isEmpty() && cell.getItem() != null).count();
                check(active < 2000, "large playlist materialized every track cell");
                long max = 0, sum = 0;
                for (int i = 1; i < pulses.size(); i++) { long gap = pulses.get(i) - pulses.get(i - 1); max = Math.max(max, gap); sum += gap; }
                check(pulses.size() >= 2, "no real FX pulses during scroll");
                var memory = ManagementFactory.getMemoryMXBean();
                samples.add(run + "," + target + "," + first + "," + last + "," + firstId
                        + "," + lastId + "," + (last - first + 1) + "," + active + "," + viewport.getPosition()
                        + "," + response + "," + pulses.size() + "," + (sum / (double) (pulses.size() - 1) / 1_000_000) + "," + max / 1_000_000.0
                        + "," + memory.getHeapMemoryUsage().getUsed() + "," + memory.getHeapMemoryUsage().getCommitted() + "," + memory.getNonHeapMemoryUsage().getUsed());
                scrollRequested = 0;
            });
        }
        fx(() -> timer.stop());
        Files.write(root.resolve("playlist-scroll.csv"), samples);
        System.out.println("PASS 9 measured real viewport scrolls after warm-up; actual response/FX pulses and MemoryMXBean samples: " + root.resolve("playlist-scroll.csv"));
    }

    private void wheel(double lines) {
        Event.fireEvent(playlistView(), new ScrollEvent(ScrollEvent.SCROLL, 100, 100, 100, 100,
                false, false, false, false, false, false, 0, lines * 40, 0, 0,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.LINES, lines, 0, null));
    }
    private LibraryClient.Playlist integrated() throws Exception { return library.load().playlists().stream().filter(p -> p.name().equals("Release integrated playlist")).findFirst().orElseThrow(); }
    private void awaitPlaying(Track track) throws Exception { await(12_000, () -> fxValue(() -> queue().current() != null && queue().current().id().equals(track.id()) && audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > .3), "native WAV did not play " + track.id()); }
    private void selectPlaylist(String id) {
        if (!node(stage.getScene().getRoot(), "sidebar-playlists").isVisible()) button("nav-playlists").fire();
        ListView<LibraryClient.Playlist> sidebar = list(stage.getScene().getRoot(), "playlist-order-list");
        sidebar.getSelectionModel().select(sidebar.getItems().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow());
        sidebar.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        check(playlistView().localToScreen(playlistView().getBoundsInLocal()) != null, "real playlist viewport is not on screen");
    }
    private boolean playlistVisible() {
        for (Node current = playlistView(); current != null; current = current.getParent()) if (!current.isVisible() || current.getOpacity() < .99) return false;
        return playlistView().localToScreen(playlistView().getBoundsInLocal()) != null;
    }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return (ComboBox<LibraryClient.Playlist>) node(stage.getScene().getRoot(), "playlist-picker"); }
    private ListView<Track> playlistView() { return list(stage.getScene().getRoot(), "playlist-tracks"); }
    private VirtualFlow<?> flow() { return (VirtualFlow<?>) playlistView().lookup(".virtual-flow"); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private ContextMenu trackMenu(Track track) {
        ListView<Track> view = playlistView();
        int index = -1;
        for (int i = 0; i < view.getItems().size(); i++) if (view.getItems().get(i).id().equals(track.id())) { index = i; break; }
        check(index >= 0, "menu resource is not in the real current playlist");
        view.getSelectionModel().clearAndSelect(index);
        ContextMenu menu = new ContextMenu();
        invoke("populateTrackMenu", new Class<?>[] {ContextMenu.class, ListView.class, Track.class, int.class}, menu, view, track, index);
        return menu;
    }
    private static void fire(ContextMenu menu, String id) { menu.getItems().stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow().fire(); }
    private DialogPane dialogOrNull(String id) { return Window.getWindows().stream().filter(w -> w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane && w.getScene().lookup("#" + id) != null).map(w -> (DialogPane) w.getScene().getRoot()).findFirst().orElse(null); }
    private DialogPane dialog(String id) { DialogPane pane = dialogOrNull(id); check(pane != null, "dialog missing " + id); return pane; }
    private static void close(DialogPane pane) { ((Stage) pane.getScene().getWindow()).close(); }
    private static Node node(Node parent, String id) { Node result = parent.lookup("#" + id); check(result != null, "control missing #" + id); return result; }
    private Button button(String id) { return button(stage.getScene().getRoot(), id); }
    private static Button button(Node parent, String id) { return (Button) node(parent, id); }
    private static TextField text(Node parent, String id) { return (TextField) node(parent, id); }
    private static TextArea textArea(Node parent, String id) { return (TextArea) node(parent, id); }
    @SuppressWarnings("unchecked") private static ComboBox<String> combo(Node parent, String id) { return (ComboBox<String>) node(parent, id); }
    @SuppressWarnings("unchecked") private static <T> ListView<T> list(Node parent, String id) { return (ListView<T>) node(parent, id); }
    private <T> T field(String name, Class<T> type) { try { Field f = LumenFxApp.class.getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(app)); } catch (Exception e) { throw new AssertionError(e); } }
    private void invoke(String name, Class<?>[] types, Object... arguments) { try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, arguments); } catch (Exception e) { throw new AssertionError(e); } }
    private void screenshot(Node parent, String filename) throws Exception {
        parent.applyCss();
        var image = parent.getScene().snapshot(null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        check(ImageIO.write(png, "png", root.resolve(filename).toFile()), "screenshot writer unavailable");
    }
    private void await(long millis, CheckedValue<Boolean> condition, String error) throws Exception { long end = System.nanoTime() + millis * 1_000_000; while (!condition.get()) { check(System.nanoTime() < end, error); Thread.sleep(50); } }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception { CompletableFuture<T> result = new CompletableFuture<>(); Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } }); return result.get(15, TimeUnit.SECONDS); }
    private static String required(String key) { String value = System.getenv(key); check(value != null && !value.isBlank(), "missing " + key); return value; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
}
