package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** End-to-end queue, playlist navigation and quick search checks on an isolated profile. */
public final class QueueNavigationSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private URI service;
    private LibraryClient library;
    private List<Track> first;
    private List<Track> second;
    private String firstId;
    private String secondId;

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        stage = initialStage;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(285_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL queue navigation watchdog: " + phase);
                System.exit(1);
            }
        }, "queue-navigation-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL queue navigation phase: " + phase);
                error.printStackTrace(System.err);
                finish(1);
            }
        }, "queue-navigation-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        prepareFixture();
        phase = "start real local playback";
        fx(() -> {
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(860); stage.setHeight(600);
        });
        await(15_000, () -> fxValue(() -> playlists().size() == 2), "library did not load");
        fx(() -> {
            tabs().getSelectionModel().select(4);
            choosePlaylist(firstId);
            ListView<Track> view = list(stage.getScene().getRoot(), "playlist-tracks");
            view.getSelectionModel().clearAndSelect(0);
            doubleClick(view);
        });
        awaitPlaying(first.get(0).id());
        fx(() -> screenshot(stage.getScene().getRoot(), "main-860x600.png"));
        System.out.println("PASS real local WAV playback");

        phase = "latest next, batch order, tail and row menus";
        fx(() -> {
            showFavorites();
            fireMenu("favorites-list", 1, "track-menu-next");
            fireMenu("favorites-list", 2, "track-menu-next");
            check(ids().subList(0, 3).equals(List.of(first.get(0).id(), first.get(2).id(), first.get(1).id())),
                    "newest play-next item did not take priority");
            tabs().getSelectionModel().select(4);
            choosePlaylist(firstId);
            ListView<Track> playlist = list(stage.getScene().getRoot(), "playlist-tracks");
            playlist.getSelectionModel().clearSelection();
            playlist.getSelectionModel().selectIndices(4, 6);
            fireMenu("playlist-tracks", 4, "track-menu-next");
            check(ids().subList(1, 3).equals(List.of(first.get(4).id(), first.get(6).id())),
                    "batch play-next input order changed");
            showFavorites();
            fireMenu("favorites-list", 3, "track-menu-enqueue");
            check(ids().get(ids().size() - 1).equals(first.get(3).id()),
                    "enqueue was not placed after existing queue tail");
            check(queue().sourceAt(queue().tracks().size() - 1) == PlayerQueue.Source.MANUAL,
                    "queued tail source is not manual");
        });
        checkMenusAcrossPages();
        System.out.println("PASS row menus across pages, latest-next, batch order and queue tail");

        phase = "shuffle manual order and queue reorder";
        fx(() -> {
            if (!node(stage.getScene().getRoot(), "right-rail").isVisible())
                button(stage.getScene().getRoot(), "toggle-queue").fire();
            check(node(stage.getScene().getRoot(), "right-rail").isVisible(), "queue panel did not open");
            ListView<Track> view = list(stage.getScene().getRoot(), "queue-list");
            check(renderedArtist(view, 1).contains("手动安排"), "manual source label not rendered");
            check(renderedArtist(view, 0).contains("歌单后续"), "current-list source label not rendered");
            button(stage.getScene().getRoot(), "repeat-button").fire();
            button(stage.getScene().getRoot(), "repeat-button").fire();
            check(queue().repeatMode() == PlayerQueue.RepeatMode.SHUFFLE, "shuffle mode did not activate");
            fireMenu("queue-list", 2, "track-menu-up");
            check(ids().indexOf(first.get(6).id()) < ids().indexOf(first.get(4).id()),
                    "queue reorder did not change manual sequence");
            button(stage.getScene().getRoot(), "next-button").fire();
        });
        awaitPlaying(first.get(6).id());
        fx(() -> button(stage.getScene().getRoot(), "next-button").fire());
        awaitPlaying(first.get(4).id());
        System.out.println("PASS shuffle drains reordered manual items first");

        phase = "duplicate occurrence, current remove and clear undo";
        fx(() -> {
            showFavorites();
            fireMenu("favorites-list", 1, "track-menu-enqueue");
            fireMenu("favorites-list", 1, "track-menu-enqueue");
            long before = countId(first.get(1).id());
            int position = ids().lastIndexOf(first.get(1).id());
            fireMenu("queue-list", position, "track-menu-queue-remove");
            check(countId(first.get(1).id()) == before - 1, "queue remove erased duplicate ID occurrences");
        });
        PlayerQueue.Snapshot beforeRemove = fxValue(() -> queue().snapshot());
        AudioPlayer originalAudio = fxValue(this::audio);
        double beforePosition = fxValue(() -> audio().position().toSeconds());
        String beforeCurrent = fxValue(() -> queue().current().id());
        fx(() -> fireMenu("queue-list", queue().index(), "track-menu-queue-remove"));
        await(12_000, () -> fxValue(() -> !beforeCurrent.equals(loadedId())
                && audio().state() == JavaFxAudioPlayer.State.PLAYING), "removing current item did not continue playback");
        fx(() -> button(stage.getScene().getRoot(), "queue-undo").fire());
        awaitPlaying(beforeCurrent);
        fx(() -> {
            check(queue().snapshot().equals(beforeRemove), "current removal undo changed queue order or sources");
            check(audio() == originalAudio, "audio player instance changed after queue undo");
            check(audio().position().toSeconds() >= beforePosition - 1.5,
                    "current removal undo lost playback position");
        });
        PlayerQueue.Snapshot beforeClear = fxValue(() -> queue().snapshot());
        double clearPosition = fxValue(() -> audio().position().toSeconds());
        fx(() -> button(stage.getScene().getRoot(), "queue-clear").fire());
        fx(() -> {
            check(queue().tracks().isEmpty() && loadedId() == null, "clear did not stop the queue");
            button(stage.getScene().getRoot(), "queue-undo").fire();
        });
        awaitPlaying(beforeCurrent);
        fx(() -> {
            check(queue().snapshot().equals(beforeClear), "clear undo changed queue sequence or sources");
            check(audio().position().toSeconds() >= clearPosition - 1.5, "clear undo lost playback position");
        });
        System.out.println("PASS duplicate occurrence, current removal and clear undo with audio state");

        phase = "bulk playlist removal and undo";
        List<String> playlistBefore = first.stream().map(Track::id).toList();
        PlayerQueue.Snapshot queueBeforePlaylistEdit = fxValue(() -> queue().snapshot());
        String loadedBeforePlaylistEdit = fxValue(this::loadedId);
        double audioBeforePlaylistEdit = fxValue(() -> audio().position().toSeconds());
        fx(() -> {
            tabs().getSelectionModel().select(4);
            choosePlaylist(firstId);
            ListView<Track> view = list(stage.getScene().getRoot(), "playlist-tracks");
            view.getSelectionModel().clearSelection();
            view.getSelectionModel().selectIndices(8, 10, 12);
            fireMenu("playlist-tracks", 8, "track-menu-remove");
        });
        await(15_000, () -> library.load().playlists().stream().filter(item -> item.id().equals(firstId))
                .findFirst().orElseThrow().tracks().size() == first.size() - 3, "bulk playlist removal was not saved");
        await(8_000, () -> fxValue(() -> !playlistEditing()
                && !button(stage.getScene().getRoot(), "playlist-undo").isDisabled()),
                "playlist undo did not become available");
        fx(() -> button(stage.getScene().getRoot(), "playlist-undo").fire());
        await(15_000, () -> library.load().playlists().stream().filter(item -> item.id().equals(firstId))
                .findFirst().orElseThrow().tracks().stream().map(Track::id).toList().equals(playlistBefore),
                "playlist undo did not restore original order");
        await(8_000, () -> fxValue(() -> playlists().stream().filter(item -> item.id().equals(firstId))
                .findFirst().orElseThrow().tracks().stream().map(Track::id).toList().equals(playlistBefore)),
                "playlist undo did not refresh the app library");
        fx(() -> {
            check(queue().snapshot().equals(queueBeforePlaylistEdit), "playlist edit changed playback queue");
            check(loadedBeforePlaylistEdit.equals(loadedId()), "playlist edit changed loaded audio");
            check(audio() == originalAudio && audio().state() == JavaFxAudioPlayer.State.PLAYING,
                    "playlist edit replaced or stopped audio");
            check(audio().position().toSeconds() >= audioBeforePlaylistEdit - .2,
                    "playlist edit reset playback position");
        });
        System.out.println("PASS playlist batch remove/undo restores order without changing playback");

        phase = "two playlist browse states and immersive return";
        browseStateChecks();
        System.out.println("PASS two playlist selections, VirtualFlow scroll and immersive return");

        phase = "Ctrl+K song playlist and settings";
        quickSearchChecks();
        System.out.println("PASS Ctrl+K song, playlist and settings results; text input shortcut guard");
    }

    private void checkMenusAcrossPages() throws Exception {
        fx(() -> {
            for (String id : List.of("favorites-list", "playlist-tracks", "queue-list")) {
                if (id.equals("favorites-list")) showFavorites();
                else if (id.equals("playlist-tracks")) { tabs().getSelectionModel().select(4); choosePlaylist(firstId); }
                else if (!node(stage.getScene().getRoot(), "right-rail").isVisible())
                    button(stage.getScene().getRoot(), "toggle-queue").fire();
                assertMenuActions(id, 0);
            }
            tabs().getSelectionModel().select(1);
            observable("searchResults").setAll(first.get(0));
            assertMenuActions("search-list", 0);
            tabs().getSelectionModel().select(2);
            observable("localTracks").setAll(first.get(0));
            assertMenuActions("local-list", 0);
            tabs().getSelectionModel().select(0);
            ListView<Track> home = list(stage.getScene().getRoot(), "recommendation-list");
            if (home.getItems().isEmpty()) observable("recommendations").setAll(first.get(0));
            assertMenuActions("recommendation-list", 0);
        });
    }

    private void assertMenuActions(String listId, int index) {
        ListCell<Track> cell = visibleCell(list(stage.getScene().getRoot(), listId), index);
        check(cell.getContextMenu() != null, listId + " has no row menu");
        for (String action : List.of("track-menu-next", "track-menu-enqueue"))
            check(cell.getContextMenu().getItems().stream().anyMatch(item -> action.equals(item.getId())),
                    listId + " is missing " + action);
    }

    private void browseStateChecks() throws Exception {
        fx(() -> {
            tabs().getSelectionModel().select(4);
            choosePlaylist(firstId);
            ListView<Track> view = list(stage.getScene().getRoot(), "playlist-tracks");
            view.getSelectionModel().clearSelection();
            view.getSelectionModel().selectIndices(16, 19);
            view.scrollTo(18);
            view.applyCss(); view.layout();
        });
        pause(250);
        double firstPosition = fxValue(this::flowPosition);
        check(firstPosition > .1, "first playlist did not scroll within VirtualFlow");
        fx(() -> {
            choosePlaylist(secondId);
            ListView<Track> view = list(stage.getScene().getRoot(), "playlist-tracks");
            view.getSelectionModel().clearSelection();
            view.getSelectionModel().selectIndices(14, 17);
            view.scrollTo(15);
            view.applyCss(); view.layout();
        });
        pause(250);
        double secondPosition = fxValue(this::flowPosition);
        check(secondPosition > .1, "second playlist did not scroll within VirtualFlow");
        fx(() -> choosePlaylist(firstId));
        await(8_000, () -> fxValue(() -> selectedIndices().equals(List.of(16, 19))
                && Math.abs(flowPosition() - firstPosition) < .08), "first playlist browse state was lost");
        fx(() -> choosePlaylist(secondId));
        await(8_000, () -> fxValue(() -> selectedIndices().equals(List.of(14, 17))
                && Math.abs(flowPosition() - secondPosition) < .08), "second playlist browse state was lost");
        fx(() -> {
            button(stage.getScene().getRoot(), "open-immersive").fire();
            check(tabs().getSelectionModel().getSelectedIndex() == 5, "immersive page did not open");
            button(stage.getScene().getRoot(), "immersive-collapse").fire();
            check(tabs().getSelectionModel().getSelectedIndex() == 4,
                    "immersive collapse did not return to playlist tab");
        });
    }

    private void quickSearchChecks() throws Exception {
        fx(() -> {
            key(stage.getScene().getRoot(), KeyCode.K, true);
            check(quickDialog() != null, "Ctrl+K did not open quick search");
            quickDialog().getScene().getWindow().setWidth(480);
        });
        pause(250);
        fx(() -> { screenshot(quickDialog(), "quick-dark-480.png"); checkFit(quickDialog()); });
        fx(() -> {
            text(quickDialog(), "quick-search-query").setText(first.get(5).title());
            key(quickDialog(), KeyCode.ENTER, false);
        });
        awaitPlaying(first.get(5).id());
        fx(() -> {
            key(stage.getScene().getRoot(), KeyCode.K, true);
            text(quickDialog(), "quick-search-query").setText("长歌单乙");
            key(quickDialog(), KeyCode.ENTER, false);
            check(tabs().getSelectionModel().getSelectedIndex() == 4
                    && picker().getValue().id().equals(secondId), "playlist search did not navigate");
            key(stage.getScene().getRoot(), KeyCode.K, true);
            text(quickDialog(), "quick-search-query").setText("设置");
            key(quickDialog(), KeyCode.ENTER, false);
            check(tabs().getSelectionModel().getSelectedIndex() == 6, "settings command did not navigate");
            tabs().getSelectionModel().select(1);
            text(stage.getScene().getRoot(), "search-query").requestFocus();
        });
        await(5_000, () -> fxValue(() -> stage.getScene().getFocusOwner()
                == text(stage.getScene().getRoot(), "search-query")),
                "visible search input did not receive focus");
        fx(() -> {
            TextField input = text(stage.getScene().getRoot(), "search-query");
            String playing = loadedId();
            JavaFxAudioPlayer.State state = audio().state();
            System.out.println("INPUT GUARD before focus=" + focusedId()
                    + " loaded=" + playing + " state=" + state);
            key(input, KeyCode.SPACE, false);
            key(input, KeyCode.LEFT, false);
            System.out.println("INPUT GUARD after focus=" + focusedId()
                    + " loaded=" + loadedId() + " state=" + audio().state());
            check(playing.equals(loadedId()) && audio().state() == state,
                    "text editing space/left changed playback");
            button(stage.getScene().getRoot(), "theme-button").fire();
            key(stage.getScene().getRoot(), KeyCode.K, true);
            check(quickDialog() != null, "quick search did not reopen after theme change");
            quickDialog().getScene().getWindow().setWidth(480);
        });
        pause(250);
        fx(() -> { screenshot(quickDialog(), "quick-light-480.png"); checkFit(quickDialog()); });
        fx(() -> key(quickDialog(), KeyCode.ESCAPE, false));
    }

    private void prepareFixture() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("queue-navigation-smoke")
                && !Files.exists(root.resolve("library.json")), "fresh isolated profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), key + " outside isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null,
                "API keys entered isolated smoke");
        service = URI.create(required("LUMEN_SERVICE_URL"));
        check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost())
                && service.getPort() > 0, "isolated loopback demo service required");
        check(new MusicClient(service).search("").size() == 3, "demo catalog unavailable");
        Path wav = root.resolve("queue-local-120s.wav");
        writeWav(wav);
        first = tracks("queue-a", "长歌单甲", wav);
        second = tracks("queue-b", "长歌单乙", wav);
        library = new LibraryClient(service);
        check(library.load().playlists().isEmpty(), "demo library is not empty");
        for (int i = 0; i < 4; i++) library.favorite(first.get(i));
        firstId = library.createPlaylist("长歌单甲").playlists().get(0).id();
        for (Track track : first) library.addToPlaylist(firstId, track);
        secondId = library.createPlaylist("长歌单乙").playlists().stream()
                .filter(item -> item.name().equals("长歌单乙")).findFirst().orElseThrow().id();
        for (Track track : second) library.addToPlaylist(secondId, track);
    }

    private static List<Track> tracks(String prefix, String title, Path wav) {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 30; i++) tracks.add(new Track(prefix + '-' + i,
                title + String.format(" 第%02d首曲目", i), "Fixture", 120, "local", wav.toUri()));
        return List.copyOf(tracks);
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
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, rate * seconds)) {
            check(AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile()) > 0, "WAV not saved");
        }
    }

    private void showFavorites() {
        tabs().getSelectionModel().select(3);
    }

    private void choosePlaylist(String id) {
        LibraryClient.Playlist playlist = playlists().stream().filter(item -> item.id().equals(id))
                .findFirst().orElseThrow();
        picker().setValue(playlist);
    }

    private void fireMenu(String listId, int index, String menuId) {
        ListView<Track> view = list(stage.getScene().getRoot(), listId);
        ListCell<Track> cell = visibleCell(view, index);
        MenuItem item = cell.getContextMenu().getItems().stream()
                .filter(candidate -> menuId.equals(candidate.getId())).findFirst().orElseThrow();
        item.fire();
    }

    @SuppressWarnings("unchecked") private ListCell<Track> visibleCell(ListView<Track> view, int index) {
        view.scrollTo(index);
        view.applyCss(); view.layout();
        return view.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                .map(cell -> (ListCell<Track>) cell).filter(cell -> cell.getIndex() == index
                        && cell.getItem() != null).findFirst()
                .orElseThrow(() -> new AssertionError("row " + index + " not rendered in " + view.getId()));
    }

    private String renderedArtist(ListView<Track> view, int index) {
        Node artist = visibleCell(view, index).lookup(".track-artist");
        check(artist instanceof javafx.scene.control.Label, "queue artist label missing");
        return ((javafx.scene.control.Label) artist).getText();
    }

    private static void doubleClick(ListView<?> view) {
        view.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8,
                MouseButton.PRIMARY, 2, false, false, false, false,
                true, false, false, true, false, false, null));
    }

    private static void key(Node target, KeyCode code, boolean control) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, false, false));
    }

    private void awaitPlaying(String id) throws Exception {
        await(14_000, () -> fxValue(() -> id.equals(loadedId())
                && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() > .25), "local audio did not play " + id);
    }

    private List<String> ids() { return queue().tracks().stream().map(Track::id).toList(); }
    private String focusedId() {
        Node focus = stage.getScene().getFocusOwner();
        return focus == null ? "<none>" : focus.getId();
    }
    private long countId(String id) { return ids().stream().filter(id::equals).count(); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private String loadedId() { Track track = field("loadedTrack", Track.class); return track == null ? null : track.id(); }
    private boolean playlistEditing() { return field("playlistEditing", Boolean.class); }
    @SuppressWarnings("unchecked") private List<LibraryClient.Playlist> playlists() { return field("playlists", List.class); }
    @SuppressWarnings("unchecked") private javafx.collections.ObservableList<Track> observable(String name) {
        return field(name, javafx.collections.ObservableList.class);
    }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() {
        return field("playlistPicker", ComboBox.class);
    }
    private TabPane tabs() { return (TabPane) node(stage.getScene().getRoot(), "main-tabs"); }
    private List<Integer> selectedIndices() {
        return List.copyOf(list(stage.getScene().getRoot(), "playlist-tracks")
                .getSelectionModel().getSelectedIndices());
    }
    private double flowPosition() {
        ListView<?> view = list(stage.getScene().getRoot(), "playlist-tracks");
        Node flow = view.lookup(".virtual-flow");
        return flow instanceof VirtualFlow<?> virtual ? virtual.getPosition() : -1;
    }
    private DialogPane quickDialog() {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()
                && window.getScene() != null && window.getScene().lookup("#quick-search-dialog") != null)
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElse(null);
    }
    private void checkFit(DialogPane pane) {
        pane.applyCss(); pane.layout();
        check(pane.getScene().getWidth() >= 440 && pane.getScene().getWidth() <= 500,
                "quick search width outside 440-500px");
        for (String id : List.of("quick-search-query", "quick-search-results")) {
            Node item = node(pane, id);
            javafx.geometry.Bounds bounds = item.localToScene(item.getBoundsInLocal());
            check(bounds.getMinX() >= -1 && bounds.getMaxX() <= pane.getScene().getWidth() + 1,
                    "quick search horizontal overflow " + id);
        }
        ListView<?> view = list(pane, "quick-search-results");
        view.applyCss(); view.layout();
        List<ScrollBar> bars = view.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast).toList();
        for (ScrollBar bar : bars) {
            System.out.println("QUICK SCROLLBAR orientation=" + bar.getOrientation()
                    + " visible=" + bar.isVisible() + " managed=" + bar.isManaged()
                    + " min=" + bar.getMin() + " max=" + bar.getMax()
                    + " visibleAmount=" + bar.getVisibleAmount() + " value=" + bar.getValue()
                    + " bounds=" + bar.localToScene(bar.getBoundsInLocal()));
        }
        check(bars.stream().anyMatch(item -> item.getOrientation() == Orientation.HORIZONTAL),
                "quick search horizontal scrollbar was not found");
        for (ScrollBar bar : bars.stream()
                .filter(item -> item.getOrientation() == Orientation.HORIZONTAL).toList()) {
            check(!bar.isVisible(), "quick search has horizontal scrolling");
        }
        double right = bars.stream().filter(bar -> bar.getOrientation() == Orientation.VERTICAL && bar.isVisible())
                .mapToDouble(bar -> bar.localToScene(bar.getBoundsInLocal()).getMinX())
                .min().orElse(view.localToScene(view.getBoundsInLocal()).getMaxX() - 4);
        for (Node node : view.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty() || cell.getGraphic() == null) continue;
            var bounds = cell.getGraphic().localToScene(cell.getGraphic().getBoundsInLocal());
            check(bounds.getMaxX() <= right + 1, "quick result extends past viewport: " + bounds);
        }
    }
    private void screenshot(Node node, String name) throws Exception {
        node.applyCss();
        if (node instanceof javafx.scene.Parent parent) parent.layout();
        WritableImage image = node.getScene().snapshot(null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path path = root.resolve(name);
        check(ImageIO.write(png, "png", path.toFile()) && Files.size(path) > 1_000,
                "screenshot not saved: " + name);
        System.out.println("SCREENSHOT: " + path);
    }
    private <T> T field(String name, Class<T> type) {
        try {
            Field field = LumenFxApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(app));
        } catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private static Node node(Node root, String id) {
        Node found = root.lookup("#" + id);
        check(found != null, "missing UI #" + id);
        return found;
    }
    private static Button button(Node root, String id) { return (Button) node(root, id); }
    private static TextField text(Node root, String id) { return (TextField) node(root, id); }
    @SuppressWarnings("unchecked") private static <T> ListView<T> list(Node root, String id) {
        return (ListView<T>) node(root, id);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new AssertionError("missing " + name);
        return value;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private void await(long millis, CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            pause(70);
        }
        throw new AssertionError(message + "; phase=" + phase);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (app != null) app.stop();
            for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
