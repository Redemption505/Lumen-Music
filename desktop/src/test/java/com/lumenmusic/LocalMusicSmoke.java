package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Real production UI and audio acceptance; inputs and data stay in the runner's isolated profile. */
public final class LocalMusicSmoke extends Application {
    private static final String TIMED = "[00:00.000]内嵌同步第一句\n[00:12.000]内嵌同步第二句\n[00:24.000]内嵌同步第三句";
    private static final String PLAIN = "内嵌纯文本第一行\n纯文本第二行：完整内容可以浏览\n末行也必须保留";
    private static final String MANUAL = "[00:00.000]用户已校准歌词\n[00:12.000]手动歌词第二句";
    private static final int FILE_COUNT = 5;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger assertions = new AtomicInteger();
    private volatile String phase = "prepare isolated profile";
    private Path root, music, nested, timedFile, plainFile, manualFile, removableFile, missingFile;
    private Stage stage;
    private LumenFxApp app;
    private LibraryClient library;
    private AnimationTimer heartbeat;
    private Track timed, plain, manual, removable, missing;
    private String playlistId;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(180_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL local music watchdog: " + phase);
                System.exit(1);
            }
        }, "local-music-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL local music phase: " + phase);
                error.printStackTrace(System.err);
                try { fx(() -> { if (root != null && stage.getScene() != null) snapshot("failure-current-scene.png"); }); }
                catch (Throwable captureError) { captureError.printStackTrace(System.err); }
                finish(1);
            }
        }, "local-music-smoke-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        prepare();
        startUi();
        phase = "folder import runs off the FX thread";
        AtomicInteger pulses = new AtomicInteger();
        CountDownLatch workerStarted = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
        ExecutorService worker = fxValue(() -> field(app, "localTasks", ExecutorService.class));
        worker.submit(() -> {
            workerStarted.countDown();
            try { releaseWorker.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        check(workerStarted.await(3, TimeUnit.SECONDS), "local worker fixture gate did not start");
        try {
            fx(() -> {
                heartbeat = new AnimationTimer() { @Override public void handle(long now) { pulses.incrementAndGet(); } };
                heartbeat.start();
                invoke("addFiles", new Class<?>[]{List.class}, List.of(music.toFile(), nested.toFile(), timedFile.toFile()));
            });
            await(3_000, () -> fxValue(() -> field(app, "localImportBusy", Boolean.class)), "import did not enter background state");
            await(2_000, () -> pulses.get() >= 5, "FX animation heartbeat stopped while import waited for worker");
            check(fxValue(() -> tracks().isEmpty()), "scanner bypassed the occupied local worker");
        } finally { releaseWorker.countDown(); }
        awaitImport(FILE_COUNT);
        fx(() -> heartbeat.stop());
        timed = find(timedFile); plain = find(plainFile); manual = find(manualFile);
        removable = find(removableFile); missing = find(missingFile);
        check(timed.title().equals("标签同步歌曲") && timed.artist().equals("内嵌歌手"), "title/artist did not come from audio tags");
        check(timed.durationSeconds() == 60, "PCM duration was not extracted");
        check(timed.coverUri() != null && Files.isRegularFile(Path.of(timed.coverUri())), "embedded cover was not persisted");
        check(fxValue(() -> embeddedLyrics().get(timed.id()).equals(TIMED)), "embedded synchronized text was not imported");
        await(5_000, () -> saved().tracks().size() == FILE_COUNT, "local-music.xml was not persisted");
        fx(() -> invoke("addFiles", new Class<?>[]{List.class}, List.of(music.toFile(), timedFile.toFile())));
        await(5_000, () -> fxValue(() -> !field(app, "localImportBusy", Boolean.class)
                && field(app, "localStatus", Label.class).getText().contains("重复 5")), "repeat import did not report duplicates");
        check(fxValue(() -> tracks().size()) == FILE_COUNT, "duplicate import changed catalogue size");
        check(fxValue(() -> tracks().stream().map(Track::id).distinct().count()) == FILE_COUNT, "catalogue contains duplicate paths");
        System.out.println("PASS recursive folder and overlapping selections; duplicate suppression; FX heartbeat=" + pulses.get());

        phase = "local search and folder filtering";
        fx(() -> {
            click("nav-local");
            field(app, "localSearch", TextField.class).setText("内嵌歌手");
            check(visibleTracks().size() == 1 && visibleTracks().get(0).id().equals(timed.id()), "artist search ignored embedded metadata");
            field(app, "localSearch", TextField.class).setText("filename-timed");
            check(visibleTracks().size() == 1, "local filename search did not match");
            field(app, "localSearch", TextField.class).clear();
            folderFilter().setValue(nested.toString());
            check(visibleTracks().size() == 2 && visibleTracks().stream().allMatch(t -> Path.of(t.mediaUri()).getParent().equals(nested)),
                    "folder filter did not include exactly the selected folder");
            folderFilter().setValue("全部文件夹");
            check(visibleTracks().size() == FILE_COUNT, "clearing folder filter lost tracks");
        });
        awaitLayoutSettled(() -> node("local-list"), "local folder page entrance");
        fx(() -> {
            fits(node("import-local-folder"), "folder import button");
            fits(node("local-list"), "local catalogue");
            snapshot("local-folder-860x600.png");
        });

        phase = "real WAV playback with automatic embedded artwork and synchronized lyrics";
        play(timed);
        await(12_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > .2),
                "tagged PCM WAV did not play through the real audio backend");
        try {
            await(8_000, () -> fxValue(() -> field(app, "activeLyrics", List.class).equals(Lyrics.parse(TIMED)) && coverReady()),
                    "automatic embedded cover or timed lyrics were not displayed");
        } catch (AssertionError error) {
            fx(() -> {
                var image = field(app, "cover", ImageView.class).getImage();
                System.err.println("EMBEDDED DISPLAY DIAGNOSTIC activeLyrics=" + field(app, "activeLyrics", List.class)
                        + " status=" + field(app, "status", Label.class).getText()
                        + " lyricsStatus=" + field(app, "lyricsStatus", Label.class).getText()
                        + " cover=" + (image == null ? "null" : image.getWidth() + "x" + image.getHeight()
                                + " error=" + image.isError() + " progress=" + image.getProgress())
                        + " coverPlaceholder=" + field(app, "coverPlaceholder", Label.class).isVisible());
            });
            throw error;
        }
        fx(() -> click("toggle-queue"));
        awaitLayoutSettled(() -> stage.getScene().lookup(".queue-pane"), "queue rail entrance");
        fx(() -> {
            TitledPane lyricsSection = (TitledPane) stage.getScene().lookup(".lyrics-section");
            check(lyricsSection != null, "queue lyrics section is missing");
            lyricsSection.setExpanded(true);
            layout();
        });
        awaitLayoutSettled(() -> field(app, "currentLyric", Label.class), "expanded player lyrics");
        fx(() -> {
            check(field(app, "currentLyric", Label.class).getText().equals("内嵌同步第一句"), "player lyric did not follow initial playback clock");
            check(field(app, "lyricsView", ListView.class).getItems().equals(Lyrics.parse(TIMED).stream().map(Lyrics.Line::text).toList()),
                    "player lyric list is not the imported lyric");
            fits(field(app, "cover", ImageView.class), "player artwork");
            fits(field(app, "currentLyric", Label.class), "player current lyric");
            snapshot("local-timed-player-860x600.png");
            click("open-immersive");
        });
        awaitLayoutSettled(() -> node("immersive-lyrics"), "immersive lyric entrance");
        fx(() -> {
            layout();
            check(((ImageView) node("immersive-artwork-image")).getImage() == field(app, "cover", ImageView.class).getImage(),
                    "immersive artwork differs from the automatic player cover");
            check(((ListView<?>) node("immersive-lyrics")).getItems().contains("内嵌同步第一句"), "immersive view omitted embedded timed lyric");
            fits(node("immersive-cover"), "immersive artwork");
            snapshot("local-timed-immersive-860x600.png");
            invoke("seekToFraction", new Class<?>[]{double.class}, .25);
        });
        await(6_000, () -> fxValue(() -> audio().position().toSeconds() >= 13
                && ((Label) node("immersive-current-lyric")).getText().equals("内嵌同步第二句")), "embedded lyric did not follow real backend seek");
        fx(() -> {
            stage.setWidth(1280); stage.setHeight(800); layout();
            snapshot("local-timed-immersive-1280x800.png");
            click("nav-local");
        });
        System.out.println("PASS real PCM playback; automatic embedded artwork; normal and immersive lyrics follow backend clock/seek");

        phase = "plain embedded lyrics and full text dialog";
        play(plain);
        await(10_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING
                && field(app, "plainLyrics", String.class).equals(PLAIN)), "plain embedded lyrics were not loaded");
        fx(() -> {
            check(field(app, "activeLyrics", List.class).isEmpty(), "plain lyric was incorrectly treated as synchronized");
            check(field(app, "lyricsView", ListView.class).getItems().equals(PLAIN.lines().toList()), "player omitted plain lyric lines");
            click("open-immersive"); layout();
            check(((ListView<?>) node("immersive-lyrics")).getItems().equals(PLAIN.lines().toList()), "immersive omitted plain lyric lines");
            invoke("showFullLyrics", new Class<?>[0]);
        });
        await(3_000, () -> fxValue(() -> fullTextDialog() != null), "full lyric dialog did not open");
        fx(() -> {
            DialogPane dialog = fullTextDialog();
            check(dialog.getContent() instanceof TextArea, "full lyric dialog has no text area");
            TextArea content = (TextArea) dialog.getContent();
            check(content.getText().equals(PLAIN) && !content.isEditable() && content.isWrapText(), "full lyric dialog changed or truncated plain text");
            snapshot(dialog.getScene(), "local-plain-full-text.png");
            dialog.getScene().getWindow().hide();
            snapshot("local-plain-immersive-1280x800.png");
            click("nav-local");
        });

        phase = "manual calibrated lyric is preserved during playback and metadata refresh";
        LyricsSelectionStore selections = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory());
        LyricsSelectionStore.Selection manualSelection = new LyricsSelectionStore.Selection(manual.title(), manual.artist(), MANUAL,
                750, LyricsSelectionStore.Origin.MANUAL, 300);
        selections.save(manual, manualSelection);
        play(manual);
        await(8_000, () -> fxValue(() -> field(app, "activeLyrics", List.class).equals(Lyrics.parse(MANUAL))), "manual selection did not take priority");
        fx(() -> {
            check(field(app, "lyricOffsetMillis", Long.class) == 750L && field(app, "lyricStartMillis", Long.class) == 300L,
                    "manual calibration was reset by embedded lyric");
            invoke("refreshLocalMetadata", new Class<?>[0]);
        });
        await(8_000, () -> fxValue(() -> !field(app, "localImportBusy", Boolean.class)
                && field(app, "localStatus", Label.class).getText().startsWith("已刷新")), "tag refresh did not complete");
        check(selections.load(manual).orElseThrow().equals(manualSelection), "tag refresh overwrote stored manual lyric/calibration");
        check(fxValue(() -> field(app, "activeLyrics", List.class).equals(Lyrics.parse(MANUAL))), "tag refresh replaced displayed manual lyric");

        phase = "cancel and confirm remove; referenced audio and library data are retained";
        library.favorite(removable);
        playlistId = library.createPlaylist("本地移除保护验收").playlists().get(0).id();
        library.addToPlaylist(playlistId, removable);
        LibraryClient.Snapshot libraryBefore = library.load();
        fxValue(() -> field(app, "localTasks", ExecutorService.class)).submit(() -> { }).get(4, TimeUnit.SECONDS);
        byte[] catalogueBefore = Files.readAllBytes(root.resolve("local-music.xml"));
        byte[] audioBefore = Files.readAllBytes(removableFile);
        fx(() -> respond(() -> invokeUnchecked("removeLocalTracks", new Class<?>[]{List.class}, List.of(removable)), false));
        // Flush the same worker to prove that cancellation did not enqueue a deferred catalogue write.
        fxValue(() -> field(app, "localTasks", ExecutorService.class)).submit(() -> { }).get(4, TimeUnit.SECONDS);
        check(fxValue(() -> tracks().size()) == FILE_COUNT, "cancelled local removal changed the visible catalogue");
        check(Arrays.equals(catalogueBefore, Files.readAllBytes(root.resolve("local-music.xml"))), "cancelled local removal wrote catalogue data");
        fx(() -> respond(() -> invokeUnchecked("removeLocalTracks", new Class<?>[]{List.class}, List.of(removable)), true));
        await(5_000, () -> saved().tracks().size() == FILE_COUNT - 1, "confirmed local removal was not persisted");
        check(Files.isRegularFile(removableFile) && Arrays.equals(audioBefore, Files.readAllBytes(removableFile)), "local removal deleted or rewrote audio");
        check(libraryBefore.equals(library.load()), "local removal changed favorites or playlists");
        check(fxValue(() -> tracks().stream().noneMatch(track -> track.id().equals(removable.id()))), "confirmed removal remained visible");

        phase = "missing local file error leaves catalogue intact";
        Files.move(missingFile, missingFile.resolveSibling("missing-moved.wav"));
        play(missing);
        await(5_000, () -> fxValue(() -> field(app, "failureNotice", Label.class).getText().contains("文件")), "missing file did not show playback failure");
        check(fxValue(() -> tracks().stream().anyMatch(track -> track.id().equals(missing.id()))), "playback error removed missing reference");
        fx(() -> invoke("refreshLocalMetadata", new Class<?>[0]));
        await(8_000, () -> fxValue(() -> !field(app, "localImportBusy", Boolean.class)
                && field(app, "localStatus", Label.class).getText().contains("无法读取 1")), "refresh did not report missing file");
        check(fxValue(() -> tracks().size()) == FILE_COUNT - 1, "refresh dropped a missing reference");
        await(5_000, () -> saved().tracks().size() == FILE_COUNT - 1, "missing-file catalogue was not persisted");

        phase = "actual new Main instance restores local catalogue and raw embedded lyrics";
        List<Track> beforeRestart = fxValue(() -> List.copyOf(tracks()));
        Map<String, String> lyricsBeforeRestart = saved().lyrics();
        fx(() -> { app.stop(); stage.hide(); stage = new Stage(); });
        startUi();
        check(fxValue(() -> List.copyOf(tracks())).equals(beforeRestart), "restart did not restore local-music.xml tracks/artwork/durations");
        check(fxValue(() -> Map.copyOf(embeddedLyrics())).equals(lyricsBeforeRestart), "restart did not restore raw embedded lyrics");
        check(fxValue(() -> tracks().stream().noneMatch(track -> track.id().equals(removable.id()))), "removed track reappeared after restart");
        check(fxValue(() -> tracks().stream().anyMatch(track -> track.id().equals(missing.id()))), "missing reference was lost on restart");
        play(manual);
        await(8_000, () -> fxValue(() -> field(app, "activeLyrics", List.class).equals(Lyrics.parse(MANUAL))), "restart lost manual lyric priority");
        check(selections.load(manual).orElseThrow().equals(manualSelection), "restart altered manual calibration");
        fx(() -> { click("nav-local"); stage.setWidth(860); stage.setHeight(600); layout(); });
        awaitLayoutSettled(() -> node("local-list"), "restored local page entrance");
        fx(() -> snapshot("local-restored-860x600.png"));
        System.out.println("PASS plain/full-text lyrics; manual calibration; cancellation; retained audio/favorites/playlists; missing-file handling; new Main catalogue restore");
        System.out.println("PASS local music smoke assertions=" + assertions.get());
    }

    private void prepare() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("build") && !Files.exists(root.resolve("local-music.xml")), "fresh isolated profile required");
        for (String name : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(name)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), "profile escapes fixture " + name);
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "personal API key entered fixture");
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(service.getHost()) && "http".equals(service.getScheme()), "isolated loopback service required");
        check(new MusicClient(service).search("").size() == 3, "real demo service fixture unavailable");
        library = new LibraryClient(service);
        String installed = System.getProperty("lumen.test.installedJar");
        if (installed != null) {
            Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(Files.isSameFile(Path.of(installed), actual), "production LumenFxApp did not load from configured final JAR");
            for (Class<?> type : List.of(LocalAudioMetadata.class, LocalAudioImporter.class, LocalMusicStore.class))
                check(Files.isSameFile(Path.of(installed), Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())),
                        "production helper did not load from configured final JAR: " + type.getSimpleName());
            System.out.println("PRODUCTION CLASS ORIGIN: " + actual);
        }
        music = Files.createDirectories(root.resolve("music-fixture"));
        nested = Files.createDirectories(music.resolve("nested"));
        timedFile = music.resolve("filename-timed.wav");
        plainFile = nested.resolve("filename-plain.wav");
        manualFile = nested.resolve("filename-manual.wav");
        removableFile = music.resolve("filename-remove.wav");
        missingFile = music.resolve("filename-missing.wav");
        byte[] artwork = artwork();
        writeWave(timedFile, "标签同步歌曲", "内嵌歌手", TIMED, artwork);
        writeWave(plainFile, "标签纯文本歌曲", "纯文本歌手", PLAIN, artwork);
        writeWave(manualFile, "标签手动校准歌曲", "校准歌手", TIMED, artwork);
        writeWave(removableFile, "可移除歌曲", "隔离验收", "", null);
        writeWave(missingFile, "缺失文件歌曲", "隔离验收", "", null);
        Files.writeString(nested.resolve("ignored.txt"), "not an audio file");
    }

    private void startUi() throws Exception {
        fx(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(860); stage.setHeight(600); });
        fxValue(() -> field(app, "localCatalogueReady", CompletableFuture.class)).get(8, TimeUnit.SECONDS);
        await(12_000, () -> fxValue(() -> field(app, "serviceInitialized", Boolean.class)), "production UI service initialization did not complete");
        CompletableFuture<?> vlc = fxValue(() -> field(app, "vlcReady", CompletableFuture.class));
        check(Boolean.TRUE.equals(vlc.get(12, TimeUnit.SECONDS)), "bundled VLC engine did not initialize");
        double actual = fxValue(() -> stage.getOutputScaleX());
        double expected = Double.parseDouble(System.getProperty("glass.win.uiScale", "1.25"));
        check(Math.abs(actual - expected) < .02, "actual window DPI differs from requested scale: " + actual + " expected " + expected);
        System.out.println("OUTPUT SCALE: " + actual);
    }

    private void awaitImport(int size) throws Exception {
        await(12_000, () -> fxValue(() -> !field(app, "localImportBusy", Boolean.class) && tracks().size() == size), "folder import did not complete");
    }
    private LocalMusicStore.State saved() throws Exception { return new LocalMusicStore(root.resolve("local-music.xml")).read(); }
    private Track find(Path file) throws Exception { return fxValue(() -> tracks().stream().filter(t -> t.mediaUri().equals(file.toUri())).findFirst().orElseThrow()); }
    private void play(Track track) throws Exception {
        fx(() -> {
            click("nav-local"); field(app, "localSearch", TextField.class).clear(); folderFilter().setValue("全部文件夹");
            invoke("select", new Class<?>[]{List.class, int.class}, List.of(track), 0);
        });
    }
    @SuppressWarnings("unchecked") private List<Track> tracks() { return field(app, "localTracks", List.class); }
    @SuppressWarnings("unchecked") private List<Track> visibleTracks() { return field(app, "visibleLocalTracks", List.class); }
    @SuppressWarnings("unchecked") private Map<String, String> embeddedLyrics() { return field(app, "localLyrics", Map.class); }
    @SuppressWarnings("unchecked") private ComboBox<String> folderFilter() { return field(app, "localFolder", ComboBox.class); }
    private AudioPlayer audio() { return field(app, "audio", AudioPlayer.class); }
    private boolean coverReady() {
        var image = field(app, "cover", ImageView.class).getImage();
        if (image == null || image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getPixelReader() == null || field(app, "coverPlaceholder", Label.class).isVisible()
                || Math.abs(image.getWidth() / image.getHeight() - 1) > .01) return false;
        // CoverCache may resize the image. Verify the fixture's actual pixels at interior
        // positions, where scaling cannot mix the teal corner with the yellow center.
        int corner = image.getPixelReader().getArgb((int)(image.getWidth() * .05), (int)(image.getHeight() * .05));
        int center = image.getPixelReader().getArgb((int)(image.getWidth() * .5), (int)(image.getHeight() * .5));
        return colorMatches(corner, 0xFF26A69A) && colorMatches(center, 0xFFFFE082);
    }
    private static boolean colorMatches(int actual, int expected) {
        for (int shift : new int[]{24, 16, 8, 0})
            if (Math.abs((actual >>> shift & 255) - (expected >>> shift & 255)) > 12) return false;
        return true;
    }
    private DialogPane fullTextDialog() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(w -> w instanceof Stage s && "歌词全文".equals(s.getTitle()))
                .filter(w -> w.getScene() != null && w.getScene().getRoot() instanceof DialogPane)
                .map(w -> (DialogPane) w.getScene().getRoot()).findFirst().orElse(null);
    }
    private void respond(Runnable action, boolean accept) {
        Platform.runLater(() -> {
            DialogPane pane = Window.getWindows().stream().filter(Window::isShowing).filter(w -> w.getScene() != null)
                    .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                    .filter(p -> "destructive-confirmation".equals(p.getId())).findFirst().orElseThrow();
            Button cancel = (Button) pane.lookup("#destructive-cancel"), confirm = (Button) pane.lookup("#destructive-confirm");
            check(cancel.isDefaultButton() && !confirm.isDefaultButton(), "local removal confirmation default is not cancellation");
            (accept ? confirm : cancel).fire();
        });
        action.run();
    }
    private void click(String id) { ((ButtonBase) node(id)).fire(); }
    private Node node(String id) { Node node = stage.getScene().lookup("#" + id); check(node != null, "missing UI #" + id); return node; }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private boolean isDisplayed(Node node) {
        if (node.getScene() == null || !node.getScene().getWindow().isShowing()) return false;
        for (Node at = node; at != null; at = at.getParent()) if (!at.isVisible() || at.getOpacity() < .02) return false;
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds.getWidth() > 0 && bounds.getHeight() > 0 && bounds.getMaxX() > 0 && bounds.getMaxY() > 0
                && bounds.getMinX() < node.getScene().getWidth() && bounds.getMinY() < node.getScene().getHeight();
    }
    private void fits(Node node, String description) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        boolean visible = isDisplayed(node) && bounds.getMinX() >= -1 && bounds.getMinY() >= -1
                && bounds.getMaxX() <= node.getScene().getWidth() + 1 && bounds.getMaxY() <= node.getScene().getHeight() + 1;
        if (!visible) visibilityDiagnostic(node, description);
        check(visible, description + " is outside the visible scene: " + bounds);
    }
    private void awaitLayoutSettled(CheckedValue<Node> target, String description) throws Exception {
        CompletableFuture<Void> complete = new CompletableFuture<>();
        AnimationTimer timer = fxValue(() -> {
            AnimationTimer observer = new AnimationTimer() {
                private Bounds previous;
                private int stablePulses;
                @Override public void handle(long now) {
                    try {
                        layout();
                        Node item = target.get();
                        boolean settled = item != null && item.getScene() != null;
                        for (Node ancestor = item; ancestor != null; ancestor = ancestor.getParent())
                            settled &= ancestor.isVisible() && ancestor.getOpacity() > .99
                                    && Math.abs(ancestor.getTranslateX()) < .5 && Math.abs(ancestor.getTranslateY()) < .5;
                        Bounds current = item == null ? null : item.localToScene(item.getBoundsInLocal());
                        settled &= current != null && current.getWidth() > 0 && current.getHeight() > 0;
                        boolean sameBounds = previous != null && current != null
                                && Math.abs(previous.getMinX() - current.getMinX()) < .25
                                && Math.abs(previous.getMinY() - current.getMinY()) < .25
                                && Math.abs(previous.getWidth() - current.getWidth()) < .25
                                && Math.abs(previous.getHeight() - current.getHeight()) < .25;
                        stablePulses = settled && sameBounds ? stablePulses + 1 : 0;
                        previous = current;
                        if (stablePulses >= 3) { stop(); complete.complete(null); }
                    } catch (Throwable error) { stop(); complete.completeExceptionally(error); }
                }
            };
            observer.start();
            return observer;
        });
        try { complete.get(5, TimeUnit.SECONDS); }
        catch (Exception error) {
            fx(() -> { Node item = target.get(); if (item != null) visibilityDiagnostic(item, description); });
            throw error;
        } finally { fx(timer::stop); }
    }
    private void visibilityDiagnostic(Node node, String description) {
        System.err.println("VISIBILITY DIAGNOSTIC " + description + " scene="
                + (node.getScene() == null ? "none" : node.getScene().getWidth() + "x" + node.getScene().getHeight()));
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.getParent())
            System.err.println("  " + ancestor.getClass().getSimpleName() + "#" + ancestor.getId()
                    + " sceneBounds=" + ancestor.localToScene(ancestor.getBoundsInLocal())
                    + " visible=" + ancestor.isVisible() + " opacity=" + ancestor.getOpacity()
                    + " translate=" + ancestor.getTranslateX() + "," + ancestor.getTranslateY()
                    + " clip=" + (ancestor.getClip() == null ? "none" : ancestor.getClip().getBoundsInParent()));
    }
    private void snapshot(String name) throws Exception { layout(); snapshot(stage.getScene(), name); }
    private void snapshot(Scene scene, String name) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path file = root.resolve(name);
        check(ImageIO.write(png, "png", file.toFile()) && Files.size(file) > 300, "screenshot was not saved");
        System.out.println("SCREENSHOT: " + file);
    }
    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private void invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, values);
    }
    private void invokeUnchecked(String name, Class<?>[] types, Object... values) {
        try { invoke(name, types, values); } catch (Exception error) { throw new AssertionError(error); }
    }
    private void await(long timeout, CheckedBoolean predicate, String message) throws Exception {
        long end = System.nanoTime() + timeout * 1_000_000L;
        while (System.nanoTime() < end) { if (predicate.get()) return; Thread.sleep(70); }
        throw new AssertionError(message);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (heartbeat != null) heartbeat.stop(); if (app != null) app.stop(); for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        System.exit(code);
    }
    private String required(String name) { String value = System.getenv(name); check(value != null && !value.isBlank(), "missing " + name); return value; }
    private void check(boolean condition, String message) { assertions.incrementAndGet(); if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }

    private static byte[] artwork() throws Exception {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(new java.awt.Color(0x26A69A)); graphics.fillRect(0, 0, 96, 96);
            graphics.setColor(new java.awt.Color(0xFFE082)); graphics.fillOval(18, 18, 60, 60); }
        finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
        return output.toByteArray();
    }
    private static void writeWave(Path path, String title, String artist, String lyrics, byte[] artwork) throws Exception {
        int rate = 8000;
        byte[] pcm = new byte[rate * 60 * 2];
        for (int i = 0; i < rate * 60; i++) {
            short sample = (short)(160 * Math.sin(i * 2 * Math.PI * 220 / rate));
            pcm[i * 2] = (byte)sample; pcm[i * 2 + 1] = (byte)(sample >>> 8);
        }
        byte[] format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).putShort((short)1)
                .putInt(rate).putInt(rate * 2).putShort((short)2).putShort((short)16).array();
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(id3Frame("TIT2", join(new byte[]{3}, utf8(title))));
        frames.writeBytes(id3Frame("TPE1", join(new byte[]{3}, utf8(artist))));
        if (!lyrics.isBlank()) frames.writeBytes(id3Frame("USLT", join(new byte[]{3}, latin("zho"), new byte[]{0}, utf8(lyrics))));
        if (artwork != null) frames.writeBytes(id3Frame("APIC", join(new byte[]{3}, latin("image/png"), new byte[]{0, 3, 0}, artwork)));
        byte[] tags = join(latin("ID3"), new byte[]{3, 0, 0}, syncSafe(frames.size()), frames.toByteArray());
        byte[] wave = join(latin("WAVE"), riffChunk("fmt ", format), riffChunk("data", pcm), riffChunk("id3 ", tags));
        Files.write(path, join(latin("RIFF"), le(wave.length), wave));
        try (var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(path.toFile())) {
            if (stream.getFrameLength() != rate * 60L) throw new AssertionError("generated tagged WAV is not valid PCM");
        }
    }
    private static byte[] id3Frame(String name, byte[] body) { return join(latin(name), be(body.length), new byte[2], body); }
    private static byte[] riffChunk(String name, byte[] body) { return join(latin(name), le(body.length), body, new byte[body.length & 1]); }
    private static byte[] syncSafe(int value) { return new byte[]{(byte)(value >>> 21 & 127), (byte)(value >>> 14 & 127), (byte)(value >>> 7 & 127), (byte)(value & 127)}; }
    private static byte[] be(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    private static byte[] le(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] latin(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    private static byte[] join(byte[]... chunks) { ByteArrayOutputStream output = new ByteArrayOutputStream(); for (byte[] chunk : chunks) output.writeBytes(chunk); return output.toByteArray(); }
}
