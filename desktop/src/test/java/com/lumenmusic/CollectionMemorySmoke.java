package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;

/** §3.3 real-window acceptance, with disposable loopback HTTP and audible WAV. */
public final class CollectionMemorySmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private Stage stage;
    private LumenFxApp app;
    private Path root;
    private URI service;
    private LibraryClient library;
    private DiaryClient diary;
    private VersionCatalogClient versions;
    private HttpServer images;
    private Track first;
    private Track other;
    private Track fragment;
    private String playlistId;
    private AudioPlayer originalAudio;
    private PlayerQueue.Snapshot originalQueue;
    private long originalLoad;
    private double originalPosition;
    private String customCover;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(285_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL collection memory watchdog: " + phase); System.exit(1);
            }
        }, "collection-memory-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL collection memory phase: " + phase); error.printStackTrace(System.err);
                try { fx(() -> screenshot(stage.getScene(), "failure.png")); } catch (Exception ignored) { }
                finish(1);
            }
        }, "collection-memory-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(root != null && root.toString().contains("collection-memory-smoke"), "disposable collection profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(Path.of(required(key)).toAbsolutePath().normalize().startsWith(root), "profile escaped fixture " + key);
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "real AI credentials in fixture");
        service = URI.create(required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(service.getHost()), "loopback demo service required");
        library = new LibraryClient(service); diary = new DiaryClient(service); versions = new VersionCatalogClient(service);
        seedArtworkAndVersions(); startApp();
        await(15_000, () -> fxValue(() -> picker().getItems().stream().anyMatch(p -> p.id().equals(playlistId))), "playlist never loaded");
        fx(this::openPlaylist); settle();
        await(10_000, () -> fxValue(() -> artworkImages().size() == 4 && artworkImages().stream()
                .allMatch(i -> i.getImage() != null && !i.getImage().isError())), "four existing covers did not produce collage");
        fx(() -> { fits(node("playlist-cover")); screenshot(stage.getScene(), "automatic-collage.png"); playFirst(); });
        awaitPlaying(first.id()); rememberPlayback();
        System.out.println("PASS four actual HTTP cover images form playlist collage; real isolated WAV plays");

        phase = "local cover and presentation save/cancel/failure";
        customCover = PlaylistCoverData.fromFile(root.resolve("synthetic-wide-cover.png"));
        LibraryClient.Snapshot edited = library.updatePlaylistMetadata(playlistId, "夜色中的私人歌单 · 中文与 English", customCover);
        fx(() -> { applyLibrary(edited); openPlaylist(); }); settle();
        fx(() -> {
            check(label("playlist-description").getText().contains("私人歌单"), "short playlist description absent");
            ImageView image = (ImageView) node("playlist-custom-cover-image");
            check(image.isPreserveRatio() && image.getViewport() == null, "custom cover crops or stretches corners");
            check(image.getImage() != null && Math.abs(image.getImage().getWidth() / image.getImage().getHeight() - 2) < .02,
                    "local cover aspect ratio changed during embedding");
            preserved(); screenshot(stage.getScene(), "local-cover-complete.png");
        });
        openPresentation();
        fx(() -> { descriptionInput().setText("取消的草稿"); click("playlist-presentation-cancel"); });
        await(3_000, () -> fxValue(() -> !hasDialog("playlist-presentation-dialog")), "presentation cancel did not dismiss");
        check(playlist().description().contains("私人歌单"), "cancel unexpectedly saved description");
        openPresentation();
        String draft = "保存后保留的短说明 · 中文 & English";
        fx(() -> field("playlistPresentation", PlaylistPresentationDialog.class).chooseCover(root.resolve("synthetic-wide-cover.png")));
        await(10_000, () -> fxValue(() -> label("playlist-presentation-feedback").getText().contains("封面已选好")), "local image selection/preview conversion failed");
        fx(() -> { check(hasDialog("playlist-custom-cover-image"), "chosen cover preview absent"); preserved(); });
        fx(() -> descriptionInput().setText(draft));
        failAndRetryPresentation(draft);
        fx(() -> { click("nav-favorites"); openPlaylist(); preserved(); }); settle();
        check(playlist().description().equals(draft) && playlist().coverDataUri().equals(customCover), "metadata lost after navigation");
        System.out.println("PASS embedded local cover keeps full ratio/corners; cancel and HTTP 500 draft/retry preserve playback");

        phase = "version identity and explicit preference";
        openVersions();
        fx(() -> {
            versionList().getSelectionModel().select(1);
            check(versionList().getSelectionModel().getSelectedItem().track().id().equals(other.id())
                    && label("version-resource").getText().contains(other.title()), "selected version hides resource identity");
            ((TextField) node("version-label")).setText("现场 · 用户明确标记");
            ((TextField) node("version-performer")).setText("用户指定演唱者"); click("version-save-info");
        });
        await(15_000, () -> versions.load().versionByResourceId(other.id()).orElseThrow().label().equals("现场 · 用户明确标记"), "user version label not saved");
        await(5_000, () -> fxValue(() -> !node("version-list").isDisabled()), "version save remained busy");
        fx(() -> { selectVersion(other.id()); click("version-prefer"); });
        await(15_000, () -> versions.load().findByResourceId(first.id()).orElseThrow().preferredTrack().orElseThrow().id().equals(other.id()), "preference not persisted");
        settle();
        fx(() -> {
            versionList().scrollTo(0); versionList().applyCss(); versionList().layout();
            check(hasDialog("version-preferred-heading"), "preferred version group absent");
            versionList().scrollTo(1); versionList().layout();
            check(hasDialog("version-other-heading"), "other version group absent");
            selectVersion(fragment.id());
            check(versionList().getSelectionModel().getSelectedItem().track().id().equals(fragment.id())
                    && label("version-resource").getText().contains(fragment.title())
                    && label("version-resource").getText().contains("10"), "fragment resource identity/range absent");
            preserved(); screenshot(node("version-dialog").getScene(), "version-identity.png"); closeDialog("version-dialog");
        });
        check(versions.load().versionByResourceId(other.id()).orElseThrow().performer().equals("用户指定演唱者"), "performer identity was inferred or lost");
        System.out.println("PASS preference grouping, explicit user label/performer and fragment identity preserve playing resource/queue");

        phase = "date groups and private note/lyric retry";
        fx(() -> click("nav-diary")); awaitDiary();
        fx(() -> {
            diaryPane().tabs().getSelectionModel().select(1);
            noteFilter().getSelectionModel().select("日期未知");
        }); settle();
        fx(() -> {
            check(node("diary-notes").lookupAll("#diary-note-row").size() == 1, "unknown note date filter invented known dates");
            check(textWithin(node("diary-notes")).contains("日期未知"), "legacy date unknown label missing");
            noteFilter().getSelectionModel().select("全部日期");
            check(node("diary-notes").lookupAll("#diary-date-heading").size() >= 3, "note dates are not grouped");
            diaryPane().editTrackNote(first); preserved();
        });
        String noteDraft = "失败后完整保留的私人长备注\n" + "雨声中的中文与 English 记忆。".repeat(80);
        failAndRetryDiary(noteDraft);
        fx(() -> {
            diaryPane().tabs().getSelectionModel().select(2);
            check(node("diary-lyrics").lookupAll("#diary-date-heading").size() >= 2, "lyrics not date grouped");
            lyricFilter().getSelectionModel().select(lyricFilter().getItems().stream()
                    .filter(value -> value.matches("\\d{4}-\\d{2}-\\d{2}"))
                    .min(String::compareTo).orElseThrow());
            check(node("diary-lyrics").lookupAll("#diary-lyric-row").size() == 1, "lyric date filter did not narrow rows");
            lyricFilter().getSelectionModel().select("全部日期"); preserved();
        });
        System.out.println("PASS unknown/known date groups, date filters, long private note and real HTTP note/lyric failure retry");

        phase = "five theme / two window-size matrix";
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> chooseTheme(mode));
            for (int[] size : new int[][] {{1280, 800}, {860, 600}}) {
                phase = "collection memory " + mode + " " + size[0] + "x" + size[1];
                fx(() -> { stage.setWidth(size[0]); stage.setHeight(size[1]); openPlaylist(); }); settle();
                fx(() -> { playlistBounds(); preserved(); }); paintedShot("playlist-" + mode.name().toLowerCase() + "-" + size[0] + ".png");
                openPresentation(); settle();
                fx(() -> { presentationBounds(); preserved(); }); paintedShot(nodeScene("playlist-presentation-dialog"), "presentation-" + mode.name().toLowerCase() + "-" + size[0] + ".png");
                fx(() -> click("playlist-presentation-cancel"));
                openVersions(); settle();
                fx(() -> { versionBounds(); preserved(); }); paintedShot(nodeScene("version-dialog"), "versions-" + mode.name().toLowerCase() + "-" + size[0] + ".png");
                fx(() -> { closeDialog("version-dialog"); click("nav-diary"); }); awaitDiary();
                fx(() -> { diaryPane().tabs().getSelectionModel().select(1); diaryBounds(); preserved(); });
                paintedShot("notes-" + mode.name().toLowerCase() + "-" + size[0] + ".png");
                fx(() -> { diaryPane().tabs().getSelectionModel().select(2); diaryBounds(); preserved(); });
                paintedShot("lyrics-" + mode.name().toLowerCase() + "-" + size[0] + ".png");
            }
        }
        System.out.println("PASS five themes at 1280x800/860x600; core controls, actual viewport access and retained painted screenshots");

        phase = "HTTP ZIP roundtrip and restart";
        var savedPlaylist = playlist(); var savedDiary = diary.load(); var savedVersions = versions.load();
        Path zip = root.resolve("collection-memory-roundtrip.zip"); BackupClient backups = new BackupClient(service);
        backups.export(zip); check(Files.size(zip) > 100, "HTTP export missing ZIP");
        library.updatePlaylistMetadata(playlistId, "", ""); diary.saveNote(first, "changed for roundtrip");
        backups.restore(zip);
        check(playlist().equals(savedPlaylist), "ZIP lost description or embedded cover");
        check(diary.load().notes().equals(savedDiary.notes()) && diary.load().lyricFavorites().equals(savedDiary.lyricFavorites()), "ZIP lost note/lyric dates or content");
        check(versions.load().equals(savedVersions), "ZIP lost preference/label/resource identity");
        fx(() -> { preserved(); click("play-button"); });
        await(5_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause before restart failed");
        fx(this::stopApp); startApp();
        await(15_000, () -> fxValue(() -> picker().getItems().stream().anyMatch(p -> p.id().equals(playlistId))), "restart did not load playlist");
        fx(this::openPlaylist); settle();
        fx(() -> {
            check(label("playlist-description").getText().equals(savedPlaylist.description()) && has("playlist-custom-cover-image"), "restart lost custom cover/description");
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING, "restart automatically produced sound");
        });
        openPresentation(); fx(() -> { click("playlist-cover-clear"); click("playlist-presentation-save"); });
        await(15_000, () -> playlist().coverDataUri().isEmpty(), "cover clear did not persist");
        await(5_000, () -> fxValue(() -> !hasDialog("playlist-presentation-dialog")), "cover clear save stayed open");
        fx(this::stopApp); startApp();
        await(15_000, () -> fxValue(() -> picker().getItems().stream().anyMatch(p -> p.id().equals(playlistId))), "second restart failed");
        fx(this::openPlaylist); settle();
        fx(() -> check(!has("playlist-custom-cover-image") && artworkImages().size() == 4, "clear/restart did not restore automatic collage"));
        System.out.println("PASS HTTP ZIP exact roundtrip of cover/description/dates/versions; restart and clear/restart persist");

        phase = "explicit resource and lyric timestamp playback";
        openVersions(); fx(() -> { selectVersion(fragment.id()); click("version-play"); });
        awaitPlaying(fragment.id());
        fx(() -> { click("nav-diary"); }); awaitDiary();
        fx(() -> {
            diaryPane().tabs().getSelectionModel().select(2);
            Node row = node("diary-lyrics").lookupAll("#diary-lyric-row").stream()
                    .filter(n -> "memory-lyric-today".equals(n.getProperties().get("lyricId"))).findFirst().orElseThrow();
            ((ButtonBase) row.lookup("#diary-lyric-listen")).fire();
        });
        awaitPlaying(first.id());
        await(6_000, () -> fxValue(() -> audio().position().toSeconds() >= 4.1), "lyric playback did not seek its true timestamp");
        fx(() -> check(queue().current().id().equals(first.id()), "lyric playback substituted preferred resource"));
        System.out.println("PASS only explicit version/lyric play changes resources; fragment and true 4200ms lyric seek use original IDs");
    }

    private void seedArtworkAndVersions() throws Exception {
        images = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        images.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                check(path.matches("/synthetic-cover-[0-3]\\.png"), "unexpected fixture artwork route");
                byte[] png = Files.readAllBytes(root.resolve(path.substring(1)));
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length); exchange.getResponseBody().write(png);
            }
        }); images.start();
        var seed = library.load().favorites();
        playlistId = library.createPlaylist("收藏与记忆 · 中文与 English 的长歌单名称 ".repeat(4)).playlists().get(0).id();
        for (int i = 0; i < 4; i++) {
            final String id = "memory-" + i;
            Track original = seed.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
            Track withCover = new Track(original.id(), original.title(), original.artist(), original.durationSeconds(), original.source(),
                    original.mediaUri(), original.bvid(), original.page(), URI.create("http://127.0.0.1:" + images.getAddress().getPort() + "/synthetic-cover-" + i + ".png"));
            library.addToPlaylist(playlistId, withCover);
        }
        first = playlist().tracks().get(0); other = playlist().tracks().get(1);
        fragment = Track.segment(first, "用户收藏的片段", "用户标记演唱者", 10_000, 25_000);
        library.addToPlaylist(playlistId, fragment);
        versions.create("同一首记忆旋律", "私人歌曲备注", List.of(
                new VersionCatalog.Version("录音室 · 用户标记", "原演唱者", first),
                new VersionCatalog.Version("翻唱 · 用户标记", "另一位演唱者", other),
                new VersionCatalog.Version("片段 · 用户标记", "用户标记演唱者", fragment)));
    }

    private void failAndRetryPresentation(String draft) throws Exception {
        LibraryClient actual = fxValue(() -> field("libraryClient", LibraryClient.class));
        HttpServer failure = failingServer(); Field base = privateField(LibraryClient.class, "base");
        URI before = (URI) base.get(actual);
        try {
            base.set(actual, failureUri(failure)); fx(() -> click("playlist-presentation-save"));
            await(15_000, () -> fxValue(() -> hasDialog("playlist-presentation-feedback")
                    && label("playlist-presentation-feedback").getText().contains("失败")), "playlist HTTP 500 feedback missing");
            fx(() -> { check(descriptionInput().getText().equals(draft), "playlist failure lost draft");
                check(!button("playlist-presentation-save").isDisabled(), "playlist failure cannot retry"); preserved(); });
            check(!playlist().description().equals(draft), "failed playlist write persisted");
            base.set(actual, before); fx(() -> click("playlist-presentation-save"));
            await(15_000, () -> fxValue(() -> !hasDialog("playlist-presentation-dialog")), "playlist retry did not dismiss");
            check(playlist().description().equals(draft), "playlist retry did not persist draft");
        } finally { base.set(actual, before); failure.stop(0); }
    }

    private void failAndRetryDiary(String draft) throws Exception {
        DiaryClient actual = fxValue(() -> paneField("client", DiaryClient.class));
        HttpServer failure = failingServer(); Field base = privateField(DiaryClient.class, "base");
        URI before = (URI) base.get(actual);
        try {
            base.set(actual, failureUri(failure));
            fx(() -> { noteInput().setText(draft); click("diary-note-save"); });
            await(15_000, () -> fxValue(() -> label("diary-feedback").getText().contains("500")), "note HTTP 500 feedback missing");
            fx(() -> {
                check(noteInput().getText().equals(draft) && !button("diary-note-save").isDisabled(), "note failure lost draft/retry");
                diaryPane().editTrackNote(other); diaryPane().editTrackNote(first);
                check(noteInput().getText().equals(draft), "changing track lost failed note draft"); preserved();
            });
            base.set(actual, before); fx(() -> click("diary-note-save"));
            await(15_000, () -> diary.load().notes().stream().anyMatch(n -> n.track().id().equals(first.id()) && n.text().equals(draft)), "note retry did not persist draft");
            base.set(actual, failureUri(failure));
            String text = "失败后仍记住的真实歌词位置 · 私人内容";
            fx(() -> diaryPane().saveLyricFavorite(first, 12_345, text));
            await(15_000, () -> fxValue(() -> has("diary-lyric-retry")), "lyric failure did not offer retry");
            fx(() -> { Node pending = node("diary-lyric-pending-row");
                check(first.id().equals(pending.getProperties().get("trackId")), "lyric failure lost track");
                check(((Number) pending.getProperties().get("positionMillis")).longValue() == 12_345, "lyric failure lost timestamp");
                check(text.equals(pending.getProperties().get("text")), "lyric failure lost content"); preserved();
            });
            base.set(actual, before); fx(() -> { diaryPane().refresh(); diaryPane().tabs().getSelectionModel().select(1); diaryPane().tabs().getSelectionModel().select(2); });
            awaitDiary();
            fx(() -> { check(has("diary-lyric-retry"), "refresh/tab lost failed lyric"); click("diary-lyric-retry"); });
            await(15_000, () -> diary.load().lyricFavorites().stream().anyMatch(l -> l.track().id().equals(first.id()) && l.positionMillis() == 12_345 && l.text().equals(text)), "lyric retry lost real provenance");
            fx(this::preserved);
        } finally { base.set(actual, before); failure.stop(0); }
    }

    private HttpServer failingServer() throws Exception {
        HttpServer failure = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        failure.createContext("/", exchange -> {
            try (exchange) {
                byte[] result; int status;
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().readAllBytes(); status = 500;
                    result = "<error code=\"SMOKE_WRITE_FAILURE\" message=\"合成保存失败\"/>".getBytes(StandardCharsets.UTF_8);
                } else {
                    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(service.resolve(exchange.getRequestURI())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    status = response.statusCode(); result = response.body();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(status, result.length); exchange.getResponseBody().write(result);
            } catch (Exception error) { error.printStackTrace(System.err); }
        }); failure.start(); return failure;
    }
    private static URI failureUri(HttpServer server) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    private void playlistBounds() {
        ScrollPane scroll = field("playlistPageScroll", ScrollPane.class); fits(scroll); noHorizontalScroll(scroll);
        for (String id : List.of("playlist-cover", "playlist-page-name", "playlist-description", "playlist-edit-presentation")) { reveal(scroll, node(id)); fits(node(id)); }
        scroll.setVvalue(0); layout(); fits(node("playback-bar"));
    }
    private void presentationBounds() {
        Node pane = node("playlist-presentation-dialog"); fits(pane);
        ScrollPane scroll = (ScrollPane) node("playlist-presentation-scroll"); noHorizontalScroll(scroll);
        for (String id : List.of("playlist-description-input", "playlist-cover-select", "playlist-cover-clear")) { reveal(scroll, node(id)); fits(node(id)); }
        for (String id : List.of("playlist-presentation-save", "playlist-presentation-cancel")) fits(node(id));
        scroll.setVvalue(0); pane.getScene().getRoot().layout();
    }
    private void versionBounds() {
        ScrollPane scroll = (ScrollPane) node("version-scroll"); fits(node("version-dialog")); noHorizontalScroll(scroll);
        for (String id : List.of("version-list", "version-resource", "version-play", "version-prefer", "version-label", "version-performer", "version-action-help")) { reveal(scroll, node(id)); fits(node(id)); }
        scroll.setVvalue(.25); scroll.getScene().getRoot().layout();
    }
    private void diaryBounds() {
        fits(node("diary-pane")); fits(node("diary-summary")); noHorizontalScroll(node("diary-pane"));
        int tab = diaryPane().tabs().getSelectionModel().getSelectedIndex();
        if (tab == 1) { fits(node("diary-note-date-filter")); fits(node("diary-note-input")); fits(node("diary-note-save")); }
        if (tab == 2) fits(node("diary-lyric-date-filter"));
        Node content = tab == 1 ? node("diary-notes") : node("diary-lyrics");
        ScrollPane scroll = (ScrollPane) diaryPane().tabs().getSelectionModel().getSelectedItem().getContent();
        for (Node body : content.lookupAll(tab == 1 ? "#diary-note-body" : "#diary-lyric-body")) {
            Bounds bounds = body.localToScene(body.getLayoutBounds());
            Node viewport = scroll.lookup(".viewport"); Bounds area = viewport.localToScene(viewport.getLayoutBounds());
            check(bounds.getMinX() >= area.getMinX() - 1 && bounds.getMaxX() <= area.getMaxX() + 1, "private text width escapes viewport");
            check(((Label) body).isWrapText(), "long private content does not wrap");
        }
        scroll.setVvalue(0); layout();
    }
    private void reveal(ScrollPane scroll, Node target) {
        for (int pass = 0; pass < 3; pass++) {
            scroll.getScene().getRoot().applyCss(); scroll.getScene().getRoot().layout();
            Node viewportNode = scroll.lookup(".viewport"); Bounds viewport = viewportNode.localToScene(viewportNode.getLayoutBounds());
            Bounds item = target.localToScene(target.getLayoutBounds());
            double extent = scroll.getContent().getLayoutBounds().getHeight() - viewport.getHeight();
            if (extent <= 0) return;
            double delta = item.getMinY() < viewport.getMinY() ? item.getMinY() - viewport.getMinY() - 8
                    : item.getMaxY() > viewport.getMaxY() ? item.getMaxY() - viewport.getMaxY() + 8 : 0;
            scroll.setVvalue(Math.max(0, Math.min(1, scroll.getVvalue() + delta / extent)));
        }
        scroll.getScene().getRoot().layout();
    }
    private void fits(Node node) {
        check(node.isVisible() && node.isManaged(), "hidden key node " + node.getId());
        Bounds b = node.localToScene(node.getLayoutBounds()); Scene s = node.getScene();
        check(b.getWidth() > 0 && b.getHeight() > 0 && b.getMinX() >= -1 && b.getMaxX() <= s.getWidth() + 1
                && b.getMinY() >= -1 && b.getMaxY() <= s.getHeight() + 1, "key control overflow " + node.getId() + ": " + b);
    }
    private void noHorizontalScroll(Node node) {
        check(node.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast)
                .noneMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible() && bar.getHeight() > 0), "horizontal scroll " + node.getId());
    }
    private void paintedShot(String name) throws Exception { paintedShot(stage.getScene(), name); }
    private void paintedShot(Scene scene, String name) throws Exception { pause(150); fx(() -> screenshot(scene, name)); }
    private Scene nodeScene(String id) throws Exception { return fxValue(() -> node(id).getScene()); }
    private void screenshot(Scene scene, String name) {
        try {
            scene.getRoot().applyCss(); scene.getRoot().layout(); WritableImage shot = scene.snapshot(null);
            BufferedImage png = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Path target = root.resolve(name); check(ImageIO.write(png, "png", target.toFile()) && Files.size(target) > 1000, "missing screenshot");
            System.out.println("SCREENSHOT: " + target);
        } catch (Exception error) { throw new AssertionError("cannot save screenshot " + name, error); }
    }
    private List<ImageView> artworkImages() { return node("playlist-cover").lookupAll("ImageView").stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).toList(); }
    private LibraryClient.Playlist playlist() throws Exception { return library.load().playlists().stream().filter(p -> p.id().equals(playlistId)).findFirst().orElseThrow(); }
    private void openPlaylist() { click("nav-playlists"); picker().setValue(picker().getItems().stream().filter(p -> p.id().equals(playlistId)).findFirst().orElseThrow()); }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return field("playlistPicker", ComboBox.class); }
    @SuppressWarnings("unchecked") private ComboBox<String> noteFilter() { return (ComboBox<String>) node("diary-note-date-filter"); }
    @SuppressWarnings("unchecked") private ComboBox<String> lyricFilter() { return (ComboBox<String>) node("diary-lyric-date-filter"); }
    @SuppressWarnings("unchecked") private ListView<VersionCatalog.Version> versionList() { return (ListView<VersionCatalog.Version>) node("version-list"); }
    private void selectVersion(String resourceId) { versionList().getSelectionModel().select(versionList().getItems().stream().filter(v -> v.track().id().equals(resourceId)).findFirst().orElseThrow()); }
    private TextArea descriptionInput() { return (TextArea) node("playlist-description-input"); }
    private TextArea noteInput() { return (TextArea) node("diary-note-input"); }
    private DiaryPane diaryPane() { return field("diaryPane", DiaryPane.class); }
    private void openPresentation() throws Exception { Platform.runLater(() -> click("playlist-edit-presentation")); await(5_000, () -> fxValue(() -> hasDialog("playlist-presentation-dialog")), "presentation dialog did not open"); settle(); }
    private void openVersions() throws Exception { fx(() -> invoke("showVersions", new Class<?>[]{Track.class}, first)); await(15_000, () -> fxValue(() -> hasDialog("version-dialog")), "versions did not open"); settle(); }
    private void awaitDiary() throws Exception { await(15_000, () -> fxValue(() -> has("diary-feedback") && label("diary-feedback").getText().equals("已更新")), "diary not updated"); settle(); }
    private void playFirst() {
        @SuppressWarnings("unchecked") ListView<Track> list = (ListView<Track>) node("playlist-tracks");
        list.getSelectionModel().clearAndSelect(0);
        list.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, true, false, false, null));
    }
    private void applyLibrary(LibraryClient.Snapshot snapshot) { invoke("applyLibrary", new Class<?>[]{LibraryClient.Snapshot.class}, snapshot); }
    private Object invoke(String name, Class<?>[] parameters, Object... args) {
        try { Method method = LumenFxApp.class.getDeclaredMethod(name, parameters); method.setAccessible(true); return method.invoke(app, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot invoke " + name, error); }
    }
    private void startApp() throws Exception {
        fx(() -> { if (app != null) stage = new Stage(); app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(1280); stage.setHeight(800); });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()) + " x " + fxValue(() -> stage.getOutputScaleY())); settle();
    }
    private void stopApp() { app.stop(); stage.hide(); }
    private void rememberPlayback() throws Exception {
        originalAudio = fxValue(this::audio); originalQueue = fxValue(() -> queue().snapshot());
        originalLoad = fxValue(() -> field("loadVersion", Long.class)); originalPosition = fxValue(() -> audio().position().toSeconds());
    }
    private void preserved() {
        check(audio() == originalAudio && field("loadVersion", Long.class) == originalLoad, "private organization reloaded audio");
        check(queue().snapshot().equals(originalQueue), "private organization changed queue/index/source/repeat");
        check(audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() >= originalPosition - .2, "private organization stopped/reset audio");
    }
    private void chooseTheme(ThemeController.Mode mode) {
        for (int i = 0; i < ThemeController.Mode.values().length && field("themeMode", ThemeController.Mode.class) != mode; i++) click("theme-button");
        check(field("themeMode", ThemeController.Mode.class) == mode, "theme did not change");
    }
    private String textWithin(Node root) { return root.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText).reduce("", (a, b) -> a + "\n" + b); }
    private void closeDialog(String id) { ((ButtonBase) ((DialogPane) node(id)).lookupButton(ButtonType.CLOSE)).fire(); }
    private Label label(String id) { return (Label) node(id); }
    private ButtonBase button(String id) { return (ButtonBase) node(id); }
    private void click(String id) { button(id).fire(); }
    private boolean has(String id) { return stage.getScene().lookup("#" + id) != null; }
    private boolean hasDialog(String id) { return findDialog(id) != null; }
    private Node findDialog(String id) {
        for (Window window : Window.getWindows()) if (window.isShowing() && window != stage && window.getScene() != null) {
            Node found = window.getScene().lookup("#" + id); if (found != null) return found;
        } return null;
    }
    private Node node(String id) { Node found = findDialog(id); if (found == null) found = stage.getScene().lookup("#" + id); check(found != null, "missing UI #" + id); return found; }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private static Field privateField(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private <T> T field(String name, Class<T> type) { try { return type.cast(privateField(LumenFxApp.class, name).get(app)); } catch (Exception error) { throw new AssertionError("cannot inspect " + name, error); } }
    private <T> T paneField(String name, Class<T> type) { try { return type.cast(privateField(DiaryPane.class, name).get(diaryPane())); } catch (Exception error) { throw new AssertionError("cannot inspect diary " + name, error); } }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private void settle() throws Exception { pause(420); fx(this::layout); }
    private void awaitPlaying(String id) throws Exception {
        await(15_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && field("loadedTrack", Track.class) != null
                && field("loadedTrack", Track.class).id().equals(id) && audio().position().toSeconds() > .2), "actual WAV did not play " + id);
    }
    private static String required(String key) { String value = System.getenv(key); check(value != null && !value.isBlank(), "missing " + key); return value; }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private void await(long millis, CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) { if (condition.get()) return; pause(70); } throw new AssertionError(message + "; phase=" + phase);
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
        if (images != null) images.stop(0); System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
