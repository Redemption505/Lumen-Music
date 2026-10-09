package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Native playback plus the user-facing segment editor against a disposable HTTP library. */
public final class SegmentPlaybackSmoke extends Application {
    private static final String SOURCE_ID = "bilibili:BV1nhMuzaEJr:p1";
    private static final String SOURCE_LRC = "[00:00.00]source-only\n[00:03.00]source-later";
    private static final String SEGMENT_LRC = "[00:00.00]segment-only\n[00:01.00]segment-later";
    private final Track source = new Track(SOURCE_ID, "Fixture source", "Fixture", 8,
            "bilibili", null, "BV1nhMuzaEJr", 1);
    private final Track first = Track.segment(source, "First segment", "Fixture", 2_000, 5_500);
    private final Track second = Track.segment(source, "Second segment", "Fixture", 4_200, 6_800);
    private final DemoAudioLibrary demo = new DemoAudioLibrary();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger heads = new AtomicInteger(), gets = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "segment-smoke-http"); thread.setDaemon(true); return thread;
    });
    private HttpServer server;
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private byte[] wave;
    private String audioContentType;
    private volatile String favoriteXml;
    private volatile String phase = "startup";
    private int firstEndVersion;

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(95_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL segment timeout: " + phase);
                cleanup(); System.exit(1);
            }
        }, "segment-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        safely(() -> {
            stage = initialStage;
            root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
            check(root != null && root.toString().contains("build") && !Files.exists(root.resolve("library.json")),
                    "A fresh isolated build profile is required");
            for (String name : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                    "LUMEN_AI_SETTINGS_PATH")) {
                Path value = Path.of(required(name)).toAbsolutePath().normalize();
                check(value.equals(root) || value.getParent().equals(root), name + " is outside the smoke profile");
            }
            String installed = System.getProperty("lumen.test.installedJar", "");
            Path codeSource = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (!installed.isBlank()) check(codeSource.equals(Path.of(installed).toAbsolutePath().normalize()),
                    "Installed acceptance loaded the wrong production code: " + codeSource);
            System.out.println("Production CodeSource: " + codeSource);
            wave = Files.readAllBytes(getParameters().getRaw().isEmpty()
                    ? Path.of(demo.resolve("demo-1"))
                    : Path.of(getParameters().getRaw().get(0)));
            audioContentType = wave.length >= 12 && wave[0] == 'R' && wave[1] == 'I'
                    && wave[2] == 'F' && wave[3] == 'F' && wave[8] == 'W' && wave[9] == 'A'
                    && wave[10] == 'V' && wave[11] == 'E' ? "audio/wav" : "audio/mp4";
            URI service = URI.create(required("LUMEN_SERVICE_URL"));
            check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost())
                    && service.getPort() > 0, "The fixture must use localhost and a fresh port");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", service.getPort()), 0);
            server.setExecutor(workers);
            server.createContext("/api/", this::respond);
            server.start();
            new HotkeySettingsStore(root.resolve("hotkeys.properties")).save(Map.of());
            LyricsSelectionStore lyrics = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory());
            lyrics.save(source, new LyricsSelectionStore.Selection(source.title(), source.artist(), SOURCE_LRC, 0));
            lyrics.save(first, new LyricsSelectionStore.Selection(first.title(), first.artist(), SEGMENT_LRC, 0));
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(1440); stage.setHeight(860);
            PersistentAudioCache cache = field("persistentCache", PersistentAudioCache.class);
            cache.configure(true, 16L * 1024 * 1024);
            awaitVlc(this::startFirstPlayback);
        });
    }

    private void startFirstPlayback() throws Exception {
        select(List.of(first, second), 0, PlayerQueue.RepeatMode.OFF, false);
        phase = "real VLC starts the first segment at the source offset";
        // Prime the native player while paused, so initial scene rendering cannot consume
        // the entire short segment before the first FX poll inspects it.
        waitFor(12_000, () -> isLoaded(first) && audio() instanceof VlcAudioPlayer
                && audio().state() == JavaFxAudioPlayer.State.PAUSED
                && !field("segmentSeeking", Boolean.class) && Math.abs(physical() - 2) < .45, () -> {
            button("play-button").fire();
            waitFor(6_000, () -> isLoaded(first) && playing() && physical() >= 2.15 && physical() < 5.3,
                    () -> pauseForInspection(first, this::verifyFirstPlayback));
        });
    }

    private void verifyFirstPlayback() throws Exception {
        check(isLoaded(first), "First-playback verification inspected a different track");
        check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "First-playback inspection was not frozen");
        double sourcePosition = physical(), visiblePosition = relative();
        System.out.println("FIRST SAMPLE: queue=" + queue().index() + ", loaded=" + first.id()
                + ", physical=" + sourcePosition + ", relative=" + visiblePosition);
        check(audio() instanceof VlcAudioPlayer, "HTTP fixture did not use real VLC");
        check(heads.get() > 0 && gets.get() > 0, "VLC did not request HTTP audio");
        check(Math.abs(visiblePosition - (sourcePosition - 2)) < .65,
                "Visible position is not relative to the source offset: physical=" + sourcePosition
                        + " relative=" + visiblePosition);
        check(Math.abs(duration() - 3.5) < .15, "Visible segment duration is wrong: " + duration());
        check(((Label) node("timing")).getText().endsWith("00:03"), "Transport shows source duration");
        check("segment-only".equals(((Label) node("current-lyric")).getText()),
                "Segment lyrics did not use their own relative clock");
        snapshot("segment-dark-860.png");
        check(isLoaded(first) && audio().state() == JavaFxAudioPlayer.State.PAUSED,
                "Taking a screenshot changed the inspected segment");
        check(Math.abs(physical() - sourcePosition) < .2, "Paused screenshot advanced native playback");
        PersistentAudioCache cache = field("persistentCache", PersistentAudioCache.class);
        phase = "source and segments share a complete cache entry";
        waitFor(8_000, () -> cache.entryCount() == 1, () -> {
            URI sourceFile = cache.lookup(source).orElseThrow();
            check(sourceFile.equals(cache.lookup(first).orElseThrow())
                    && sourceFile.equals(cache.lookup(second).orElseThrow()),
                    "Segments of one source did not share its complete cache entry");
            pauseAndSeek();
        });
    }

    private void pauseAndSeek() throws Exception {
        if (audio().state() != JavaFxAudioPlayer.State.PAUSED) button("play-button").fire();
        phase = "pause real playback";
        waitFor(3_000, () -> isLoaded(first) && audio().state() == JavaFxAudioPlayer.State.PAUSED,
                this::seekWhilePaused);
    }

    private void seekWhilePaused() throws Exception {
        Slider slider = (Slider) node("progress-slider");
        slider.setValue(.72);
        slider.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.RIGHT,
                false, false, false, false));
        phase = "relative seek while paused";
        waitFor(6_000, () -> isLoaded(first) && Math.abs(physical() - 4.52) < .8, () -> {
            check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "Seek resumed a paused segment");
            check(Math.abs(relative() - 2.52) < .8, "Seek did not update relative progress");
            check(Math.abs(slider.getValue() - .72) < .2, "Progress slider is not segment-relative");
            firstEndVersion = field("loadVersion", Long.class).intValue();
            button("play-button").fire();
            phase = "first segment end advances exactly once";
            waitFor(6_000, () -> isLoaded(second) && queue().index() == 1 && playing() && physical() >= 4.25,
                    this::verifyEndAndRepeat);
        });
    }

    private void verifyEndAndRepeat() throws Exception {
        check(field("loadVersion", Long.class).intValue() == firstEndVersion + 1,
                "Segment end loaded the next track more than once");
        check(queue().current().id().equals(second.id()), "Segment end chose the wrong next track");
        check(Math.abs(duration() - 2.6) < .15, "Next segment retained the first duration");
        queue().setRepeatMode(PlayerQueue.RepeatMode.ONE);
        invoke("seekToFraction", new Class<?>[] {double.class}, .8);
        int beforeRepeat = field("loadVersion", Long.class).intValue();
        phase = "single repeat restarts at segment start";
        waitFor(6_000, () -> field("loadVersion", Long.class).intValue() == beforeRepeat + 1
                && isLoaded(second) && queue().index() == 1 && playing() && physical() < 5.2, () -> {
            check(physical() >= 4.15, "Single repeat restarted before the segment range");
            check(relative() < 1.0, "Single repeat retained the prior segment position");
            verifyIndependentLyrics();
        });
    }

    private void verifyIndependentLyrics() throws Exception {
        select(List.of(source), 0, PlayerQueue.RepeatMode.OFF);
        phase = "switch to full source without leaking segment lyrics";
        waitFor(8_000, () -> isLoaded(source) && playing() && physical() < 2.2
                && "source-only".equals(((Label) node("current-lyric")).getText()), () -> {
            check(Math.abs(relative() - physical()) < .5, "Full source inherited a segment offset");
            select(List.of(first), 0, PlayerQueue.RepeatMode.OFF);
            phase = "rapid switch back to segment";
            waitFor(8_000, () -> isLoaded(first) && playing() && physical() >= 2.1
                    && "segment-only".equals(((Label) node("current-lyric")).getText()),
                    () -> pauseForInspection(first, this::verifySnapshotAndEditor));
        });
    }

    private void verifySnapshotAndEditor() throws Exception {
        check(isLoaded(first) && audio().state() == JavaFxAudioPlayer.State.PAUSED,
                "Restart snapshot was not captured from the paused first segment");
        PlaybackStateStore saved = field("playbackStateStore", PlaybackStateStore.class);
        invoke("savePlaybackState", new Class<?>[0]);
        PlaybackStateStore.State state = saved.read().orElseThrow();
        check(state.tracks().get(state.index()).isSegment(), "Playback snapshot lost segment identity");
        check(Math.abs(state.positionSeconds() - relative()) < .7,
                "Playback snapshot saved source position instead of segment position");
        check(state.tracks().get(0).segmentStartMillis() == 2_000,
                "Playback snapshot lost the source range");
        double resumeAt = state.positionSeconds();
        int priorGets = gets.get();
        app.stop(); stage.close();
        app = new LumenFxApp(); stage = new Stage();
        app.startUi(stage, List.of());
        stage.setWidth(1440); stage.setHeight(860);
        check(queue().current() != null && queue().current().isSegment(),
                "Fresh app did not restore the segment queue");
        check(Math.abs(((Slider) node("progress-slider")).getValue() - resumeAt / 3.5) < .2,
                "Fresh app restored source progress instead of segment progress");
        awaitVlc(() -> {
            button("play-button").fire();
            phase = "fresh app resumes segment from relative snapshot and shared cache";
            waitFor(8_000, () -> isLoaded(first) && playing() && physical() >= 2 + resumeAt - .45, () -> {
                check(physical() >= 2 && physical() <= 5.5,
                        "Fresh app resumed outside the segment range");
                check(Math.abs(relative() - (physical() - 2)) < .7,
                        "Fresh app resumed with source-relative display time");
                check(gets.get() == priorGets, "Fresh app refetched cached audio");
                System.out.println("PASS native offset, relative seek/end/repeat, lyrics, shared cache, restart snapshot");
                select(List.of(source), 0, PlayerQueue.RepeatMode.OFF);
                phase = "create dialog preview and cancel restore";
                waitFor(8_000, () -> isLoaded(source) && playing() && physical() > .6 && physical() < 2,
                        this::previewAndRestore);
            });
        });
    }

    private void previewAndRestore() throws Exception {
        double before = physical();
        openSegmentEditor();
        DialogPane pane = segmentDialog();
        // The editor has already captured a playing source for its cancel/restore contract.
        // Freeze only the screenshot interval, then let preview exercise that original contract.
        pauseForInspection(source, () -> previewFrozenDialog(pane, before));
    }

    private void previewFrozenDialog(DialogPane pane, double before) throws Exception {
        snapshotDialog(pane);
        TextField start = text(pane, "segment-start");
        TextField end = text(pane, "segment-end");
        check(!start.getText().isBlank() && !end.getText().isBlank(), "Dialog did not prefill start/end");
        button(pane, "segment-start-current").fire();
        check(!start.getText().isBlank(), "Current-position start button did not fill a time");
        button(pane, "segment-end-current").fire();
        check(!end.getText().isBlank(), "Current-position end button did not fill a time");
        text(pane, "segment-title").setText("Saved excerpt");
        text(pane, "segment-artist").setText("Fixture artist");
        start.setText("00:02"); end.setText("00:05");
        button(pane, "segment-preview").fire();
        phase = "dialog preview plays the requested source range";
        waitFor(8_000, () -> queue().current() != null && queue().current().isSegment()
                && field("loadedTrack", Track.class) != null
                && queue().current().id().equals(field("loadedTrack", Track.class).id())
                && playing() && physical() >= 2.05, () -> {
            check(Math.abs(relative() - (physical() - 2)) < .7, "Preview used source progress");
            closeDialog(pane);
            phase = "closing preview restores source and its position";
            waitFor(8_000, () -> isLoaded(source)
                    && playing() && Math.abs(physical() - before) < 1.25,
                    this::createThroughUi);
        });
    }

    private void createThroughUi() throws Exception {
        ContextMenu sourceMenu = new ContextMenu();
        @SuppressWarnings("unchecked") ListView<Track> sourceList = (ListView<Track>) node("favorites-list");
        invoke("populateTrackMenu", new Class<?>[] {ContextMenu.class, ListView.class, Track.class, int.class},
                sourceMenu, sourceList, source, sourceList.getItems().indexOf(source));
        check(sourceMenu.getItems().stream().anyMatch(item -> "track-menu-segment".equals(item.getId())),
                "Ordinary track row has no segment creation action");
        openSegmentEditor();
        DialogPane pane = segmentDialog();
        text(pane, "segment-title").setText("Saved excerpt");
        text(pane, "segment-artist").setText("Fixture artist");
        text(pane, "segment-start").setText("00:06");
        text(pane, "segment-end").setText("00:05");
        button(pane, "segment-save").fire();
        check(segmentDialog() == pane && (favoriteXml == null || favoriteXml.isBlank()),
                "Invalid start/end range was submitted");
        check(pane.lookup("#segment-message") != null, "Missing segment validation message");
        text(pane, "segment-start").setText("00:02");
        @SuppressWarnings("unchecked") ComboBox<Object> destination =
                (ComboBox<Object>) pane.lookup("#segment-destination");
        check(destination != null && !destination.getItems().isEmpty(), "Missing segment destination choices");
        destination.getSelectionModel().selectFirst();
        button(pane, "segment-save").fire();
        phase = "UI save reaches the library and returns a segment row";
        waitFor(6_000, () -> favoriteXml != null && !favoriteXml.isBlank()
                && !field("favorites", javafx.collections.ObservableList.class).isEmpty(), () -> {
            @SuppressWarnings("unchecked") List<Track> favorites = field("favorites", List.class);
            Track saved = favorites.get(0);
            check(saved.isSegment() && saved.segmentStartMillis() == 2_000
                    && saved.segmentEndMillis() == 5_000, "Saved UI fields were not round-tripped");
            check("Saved excerpt".equals(saved.title()) && "Fixture artist".equals(saved.artist()),
                    "Saved title/artist were not round-tripped");
            editThroughMenu(saved);
        });
    }

    private void editThroughMenu(Track saved) throws Exception {
        @SuppressWarnings("unchecked") ListView<Track> list = (ListView<Track>) node("favorites-list");
        ContextMenu menu = new ContextMenu();
        invoke("populateTrackMenu", new Class<?>[] {ContextMenu.class, ListView.class, Track.class, int.class},
                menu, list, saved, list.getItems().indexOf(saved));
        MenuItem edit = menu.getItems().stream().filter(item -> "track-menu-segment-edit".equals(item.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing segment edit action"));
        edit.fire();
        DialogPane pane = segmentDialog();
        check("Saved excerpt".equals(text(pane, "segment-title").getText()), "Edit did not prefill title");
        text(pane, "segment-title").setText("Edited excerpt");
        text(pane, "segment-end").setText("00:06");
        button(pane, "segment-save").fire();
        phase = "UI edit updates the stored segment";
        waitFor(6_000, () -> {
            @SuppressWarnings("unchecked") List<Track> favorites = field("favorites", List.class);
            return !favorites.isEmpty() && "Edited excerpt".equals(favorites.get(0).title());
        }, () -> {
            @SuppressWarnings("unchecked") List<Track> favorites = field("favorites", List.class);
            Track edited = favorites.get(0);
            check(edited.segmentEndMillis() == 6_000, "Edit did not preserve the changed endpoint");
            deleteThroughMenu(list, edited);
        });
    }

    private void deleteThroughMenu(ListView<Track> list, Track edited) throws Exception {
        ContextMenu menu = new ContextMenu();
        invoke("populateTrackMenu", new Class<?>[] {ContextMenu.class, ListView.class, Track.class, int.class},
                menu, list, edited, list.getItems().indexOf(edited));
        MenuItem delete = menu.getItems().stream()
                .filter(item -> "track-menu-segment-delete".equals(item.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing segment delete action"));
        // 删除确认用 showAndWait，不能在心跳动画回调里触发；改由普通 FX 事件驱动，
        // 确认按钮在确认框自己的嵌套事件循环中就绪后点击。
        Platform.runLater(() -> {
            Platform.runLater(this::confirmDeletion);
            delete.fire();
        });
        phase = "UI delete removes the stored segment";
        waitFor(6_000, () -> favoriteXml == null
                && field("favorites", javafx.collections.ObservableList.class).isEmpty(), () -> {
            System.out.println("PASS UI preview/cancel restore, create, edit, delete");
            succeed();
        });
    }

    private void confirmDeletion() {
        for (Window window : Window.getWindows()) {
            if (window == stage || !window.isShowing() || window.getScene() == null) continue;
            if (!(window.getScene().getRoot() instanceof DialogPane pane)) continue;
            Button ok = (Button) pane.lookup("#destructive-confirm");
            if (ok != null) { ok.fire(); return; }
        }
        throw new AssertionError("Segment delete confirmation did not open");
    }

    private void select(List<Track> tracks, int index, PlayerQueue.RepeatMode mode) throws Exception {
        select(tracks, index, mode, true);
    }

    private void select(List<Track> tracks, int index, PlayerQueue.RepeatMode mode, boolean autoplay) throws Exception {
        queue().select(tracks, index); queue().setRepeatMode(mode);
        invoke("loadCurrent", new Class<?>[] {boolean.class}, autoplay);
    }

    private void pauseForInspection(Track track, Checked next) throws Exception {
        check(isLoaded(track), "Cannot pause inspection of a different track");
        if (audio().state() != JavaFxAudioPlayer.State.PAUSED) button("play-button").fire();
        waitFor(3_000, () -> isLoaded(track) && audio().state() == JavaFxAudioPlayer.State.PAUSED, next);
    }

    private void respond(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.endsWith("/stream")) { respondAudio(exchange); return; }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (path.equals("/api/library/favorites") && method.equals("POST")) {
                favoriteXml = body;
                response = libraryXml();
            } else if (path.startsWith("/api/library/tracks/") && method.equals("PATCH")) {
                check(URLDecoder.decode(path.substring("/api/library/tracks/".length()), StandardCharsets.UTF_8)
                        .startsWith("segment:"), "Edit did not address a segment ID");
                favoriteXml = body;
                response = libraryXml();
            } else if (path.startsWith("/api/library/tracks/") && method.equals("DELETE")) {
                favoriteXml = null;
                response = libraryXml();
            } else response = switch (path) {
                case "/api/library" -> libraryXml();
                case "/api/ai-settings" -> "<ai-settings enabled=\"false\" keyConfigured=\"false\" model=\"fixture\" baseUrl=\"http://127.0.0.1\"/>";
                case "/api/listening" -> "<listening accepted=\"true\"/>";
                case "/api/lyrics" -> "<lyrics/>";
                default -> "<tracks/>";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Throwable error) { error.printStackTrace(System.err); }
        finally { exchange.close(); }
    }

    private String libraryXml() {
        return "<library version=\"1\"><favorites>" + (favoriteXml == null ? "" : favoriteXml)
                + "</favorites><playlists/></library>";
    }

    private void respondAudio(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        int start = 0, end = wave.length - 1;
        if (range != null && range.startsWith("bytes=")) {
            String[] values = range.substring(6).split("-", -1);
            if (values[0].isEmpty()) start = Math.max(0, wave.length - Integer.parseInt(values[1]));
            else {
                start = Integer.parseInt(values[0]);
                if (!values[1].isEmpty()) end = Math.min(end, Integer.parseInt(values[1]));
            }
        }
        exchange.getResponseHeaders().set("Content-Type", audioContentType);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (start > end || start >= wave.length) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + wave.length);
            exchange.sendResponseHeaders(416, -1); return;
        }
        int length = end - start + 1;
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
        if (exchange.getRequestMethod().equals("HEAD")) { heads.incrementAndGet(); exchange.sendResponseHeaders(200, -1); }
        else {
            gets.incrementAndGet();
            if (range != null) exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + start + "-" + end + "/" + wave.length);
            exchange.sendResponseHeaders(range == null ? 200 : 206, length);
            exchange.getResponseBody().write(wave, start, length);
        }
    }

    private void snapshot(String name) throws Exception {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        var image = stage.getScene().snapshot(null);
        check(image.getHeight() >= 850 && image.getHeight() <= 870, "Screenshot is not 860 pixels high");
        saveSnapshot(name, image);
    }

    private void snapshotDialog(DialogPane pane) throws Exception {
        pane.applyCss(); pane.layout();
        WritableImage image = pane.getScene().snapshot(null);
        check(image.getWidth() >= 400 && image.getHeight() >= 300, "Segment editor is too small");
        saveSnapshot("segment-editor.png", image);
    }

    private void saveSnapshot(String name, WritableImage image) throws Exception {
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path file = root.resolve(name);
        check(ImageIO.write(png, "png", file.toFile()) && Files.size(file) > 1_000,
                "Could not save segment screenshot");
        System.out.println("SCREENSHOT: " + file);
    }

    private DialogPane segmentDialog() {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()
                && window.getScene() != null && window.getScene().lookup("#segment-title") != null)
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst()
                .orElseThrow(() -> new AssertionError("Segment editor dialog is missing"));
    }

    private void closeDialog(DialogPane pane) {
        Button cancel = (Button) pane.lookupButton(javafx.scene.control.ButtonType.CANCEL);
        check(cancel != null && cancel.isVisible(), "Segment dialog has no cancel action");
        cancel.fire();
    }

    private TextField text(DialogPane pane, String id) {
        TextField value = (TextField) pane.lookup("#" + id);
        check(value != null, "Missing #" + id); return value;
    }

    private Button button(DialogPane pane, String id) {
        Button value = (Button) pane.lookup("#" + id);
        check(value != null, "Missing #" + id); return value;
    }

    private Node node(String id) {
        Node value = stage.getScene().lookup("#" + id);
        check(value != null, "Missing #" + id); return value;
    }

    private Button button(String id) { return (Button) node(id); }

    private void openSegmentEditor() {
        button("immersive-more").fire();
        ContextMenu menu = Window.getWindows().stream().filter(ContextMenu.class::isInstance)
                .map(ContextMenu.class::cast).filter(Window::isShowing).findFirst()
                .orElseThrow(() -> new AssertionError("Immersive More menu did not open"));
        MenuItem action = menu.getItems().stream().filter(item -> "create-segment".equals(item.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("More menu has no segment creation action"));
        check(!action.isDisable(), "Segment creation action is disabled");
        action.fire();
        menu.hide();
    }

    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private void awaitVlc(Checked next) {
        phase = "production native VLC initialization before selecting HTTP audio";
        waitFor(12_000, () -> {
            CompletableFuture<?> ready = field("vlcReady", CompletableFuture.class);
            return ready != null && ready.isDone() && !ready.isCompletedExceptionally()
                    && Boolean.TRUE.equals(ready.join())
                    && field("streamingAudio", AudioPlayer.class) instanceof VlcAudioPlayer;
        }, () -> {
            System.out.println("NATIVE READY: " + nativeStatus());
            next.run();
        });
    }

    private String nativeStatus() {
        CompletableFuture<?> ready = field("vlcReady", CompletableFuture.class);
        AudioPlayer streaming = field("streamingAudio", AudioPlayer.class);
        AudioPlayer active = audio();
        String nativeHome = System.getenv("LUMEN_VLC_HOME");
        boolean libraryPresent = nativeHome != null && Files.isRegularFile(Path.of(nativeHome).resolve("libvlc.dll"));
        return "active=" + (active == null ? "null" : active.getClass().getSimpleName())
                + ", streaming=" + (streaming == null ? "null" : streaming.getClass().getSimpleName())
                + ", vlcReady=" + (ready == null ? "null" : ready.isDone()
                ? ready.isCompletedExceptionally() ? "exceptional" : ready.join() : "pending")
                + ", configuredNativeLibraryPresent=" + libraryPresent;
    }

    private boolean isLoaded(Track track) {
        Track loaded = field("loadedTrack", Track.class);
        return queue().current() != null && loaded != null && queue().current().id().equals(track.id())
                && loaded.id().equals(track.id());
    }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private boolean playing() { return audio() instanceof VlcAudioPlayer
            && audio().state() == JavaFxAudioPlayer.State.PLAYING; }
    private double physical() { return audio().position().toSeconds(); }
    private double relative() { return ((Duration) invokeValue("playbackPosition")).toSeconds(); }
    private double duration() { return ((Duration) invokeValue("playbackDuration")).toSeconds(); }

    private <T> T field(String name, Class<T> type) {
        try { Field value = LumenFxApp.class.getDeclaredField(name); value.setAccessible(true); return type.cast(value.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect " + name, error); }
    }

    private Object invokeValue(String name) {
        try { Method method = LumenFxApp.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(app); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Cannot invoke " + name, error); }
    }

    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true);
        method.invoke(app, arguments);
    }

    private void waitFor(int millis, BooleanSupplier condition, Checked next) {
        poll(System.nanoTime() + millis * 1_000_000L, condition, next);
    }

    private void poll(long deadline, BooleanSupplier condition, Checked next) {
        safely(() -> {
            if (condition.getAsBoolean()) { next.run(); return; }
            check(System.nanoTime() < deadline, "Timeout in " + phase + "; state=" + audio().state()
                    + ", physical=" + physical() + ", relative=" + relative()
                    + ", queue=" + (queue().current() == null ? "empty" : queue().current().id())
                    + ", loaded=" + field("loadedTrack", Track.class)
                    + ", version=" + field("loadVersion", Long.class)
                    + ", native=" + nativeStatus()
                    + ", HTTP=" + heads.get() + "/" + gets.get());
            PauseTransition timer = new PauseTransition(Duration.millis(60));
            timer.setOnFinished(event -> poll(deadline, condition, next)); timer.play();
        });
    }

    private void safely(Checked action) {
        if (finished.get()) return;
        try { action.run(); }
        catch (Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            System.err.println("FAIL segment phase: " + phase); error.printStackTrace(System.err);
            if (app != null) System.err.println("NATIVE FAILURE: " + nativeStatus());
            cleanup(); System.exit(1);
        }
    }

    private void succeed() { finished.set(true); cleanup(); Platform.exit(); }
    private void cleanup() {
        try { if (app != null) app.stop(); } catch (Exception ignored) { }
        try { if (stage != null) stage.close(); } catch (Exception ignored) { }
        if (server != null) server.stop(0);
        workers.shutdownNow(); demo.close();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        check(value != null && !value.isBlank(), "Missing " + name); return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
