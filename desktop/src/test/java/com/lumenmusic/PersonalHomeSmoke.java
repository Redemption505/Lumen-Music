package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;

/** Stage 4 / 3.2 acceptance: actual windows and audio, isolated synthetic data. */
public final class PersonalHomeSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private Stage stage;
    private LumenFxApp app;
    private Path root;
    private URI service;
    private LibraryClient library;
    private HomeClient home;
    private Track first;
    private Track reunion;
    private String playlistId;
    private String smartId;
    private PlayerQueue.Snapshot originalQueue;
    private AudioPlayer originalAudio;
    private long originalLoad;
    private double originalPosition;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(285_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL personal home watchdog: " + phase);
                System.exit(1);
            }
        }, "personal-home-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL personal home phase: " + phase);
                error.printStackTrace(System.err);
                try { fx(() -> screenshot(stage.getScene(), "failure.png")); } catch (Exception ignored) { }
                finish(1);
            }
        }, "personal-home-driver");
        driver.setDaemon(true); driver.start();
    }

    private void checkFirstScreenHeading() {
        ScrollPane scroll = (ScrollPane) node("home-scroll");
        check(Math.abs(scroll.getVvalue()) < .0001, "first-screen check requires top scroll position");
        Node heading = stage.getScene().lookup(".recommendation-heading");
        Node viewport = scroll.lookup(".viewport");
        check(heading != null && viewport != null, "home heading/viewport fixture missing");
        Bounds text = heading.localToScene(heading.getLayoutBounds());
        Bounds view = viewport.localToScene(viewport.getLayoutBounds());
        double visible = Math.max(0, Math.min(text.getMaxY(), view.getMaxY()) - Math.max(text.getMinY(), view.getMinY()));
        check(visible < .1 || visible >= text.getHeight() - .5,
                "home first-screen heading partially clipped: visible=" + visible + ", height=" + text.getHeight());
        System.out.println("PASS first-screen heading width=" + stage.getWidth() + " visible=" + visible);
    }

    private void exercise() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(root != null && root.toString().contains("personal-home-smoke"), "disposable home profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(Path.of(required(key)).toAbsolutePath().normalize().startsWith(root), "profile escaped fixture " + key);
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null,
                "acceptance must not use real external AI credentials");
        service = URI.create(required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(service.getHost()), "loopback demo service required");
        library = new LibraryClient(service); home = new HomeClient(service);
        check(library.load().favorites().isEmpty() && library.load().playlists().isEmpty(), "new profile is not empty");
        check(new DiaryClient(service).load().summary().totalPlays() == 0, "new profile already has history");

        phase = "new listener empty homepage";
        startApp();
        await(15_000, () -> fxValue(() -> has("home-feedback") && has("home-session-detail")), "home did not initialize");
        pause(500);
        fx(() -> {
            check(queue().tracks().isEmpty(), "empty profile invented a continuing queue");
            check(button("home-continue-play").isDisabled(), "empty profile offers fictional continue playback");
            check(!has("home-reunion-play"), "empty profile offers fictional reunion playback");
            check(node("home-section-reunion").lookupAll(".label").stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).anyMatch(item -> item.getText().contains("真实")), "no-history explanation is missing");
            check(!button("home-open-local").isDisabled() && !button("home-open-favorites").isDisabled(),
                    "new listener cannot reach import/favorites");
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING, "startup automatically made sound");
            screenshot(stage.getScene(), "new-listener.png");
        });
        System.out.println("PASS new listener has honest empty continue/reunion cards and import/favorites entries");
        fx(this::stopApp);

        phase = "restore synthetic history through HTTP and seed paused session";
        BackupClient backups = new BackupClient(service);
        backups.restore(root.resolve("synthetic-home.zip"));
        LibraryClient.Snapshot state = library.load();
        LibraryClient.Playlist playlist = state.playlists().stream().filter(item -> item.tracks().size() == 200).findFirst().orElseThrow();
        playlistId = playlist.id(); first = playlist.tracks().get(0);
        reunion = state.favorites().stream().filter(item -> item.id().equals("home-reunion")).findFirst().orElseThrow();
        SmartPlaylistClient smart = new SmartPlaylistClient(service);
        smartId = smart.save(null, "写代码 · 智能常驻", null, SmartPlaylistClient.Rules.empty()).smartPlaylists().get(0).id();
        HomeClient.Preferences seeded = new HomeClient.Preferences(HomeClient.DEFAULT_SECTIONS,
                List.of(new HomeClient.Pin("playlist", playlistId), new HomeClient.Pin("smart", smartId)));
        check(home.save(seeded).equals(seeded), "home HTTP preference seed did not persist");
        new PlaybackStateStore(root.resolve("playback-state.xml")).save(new PlaybackStateStore.State(
                List.of(first, playlist.tracks().get(1)), 0, PlayerQueue.RepeatMode.ALL, 18.5));
        startApp();
        await(15_000, () -> fxValue(() -> has(pinId("playlist", playlistId)) && has(pinId("smart", smartId))
                && !button(pinId("playlist", playlistId)).isDisabled() && !button(pinId("smart", smartId)).isDisabled()
                && has("home-reunion-play") && !button("home-reunion-play").isDisabled()), "home fixture did not load");
        HomeReunion.Selection expectedReunion = HomeReunion.select(new DiaryClient(service).load()).orElseThrow();
        fx(() -> {
            check(queue().current().id().equals(first.id()) && queue().tracks().size() == 2, "paused queue was not restored");
            check(Math.abs(field("restoredPositionSeconds", Double.class) - 18.5) < .1, "paused position was not restored");
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING && audio().state() != JavaFxAudioPlayer.State.STALLED,
                    "restored startup automatically plays");
            check(!button("home-continue-play").isDisabled(), "restored homepage cannot continue");
            check(label("home-reunion-title").getText().contains(reunion.title()), "reunion does not match real history track");
            check(label("home-reunion-reason").getText().equals(expectedReunion.reason()), "reunion explanation does not match real diary dates");
            screenshot(stage.getScene(), "paused-restored.png");
            click("home-continue-play");
        });
        awaitPlaying(first.id());
        await(6_000, () -> fxValue(() -> audio().position().toSeconds() >= 18.3), "continue did not resume saved position");
        rememberPlayback();
        System.out.println("PASS restored queue/position are silent until explicit homepage continue resumes real WAV");

        phase = "home navigation and candidate generation preserve playback";
        fx(() -> click(pinId("playlist", playlistId))); settle();
        fx(() -> {
            check(((ListView<?>) node("playlist-tracks")).getItems().size() == 200, "ordinary pin did not open its playlist");
            preserved(); click("nav-home");
            click(pinId("smart", smartId));
        }); settle();
        await(15_000, () -> fxValue(() -> hasDialog("smart-name") && ((TextField) dialogNode("smart-name")).getText().equals("写代码 · 智能常驻")),
                "smart pin did not select its own smart playlist");
        fx(() -> { preserved(); closeDialog("smart-playlist-dialog"); click("nav-home"); });
        settle();
        fx(() -> { ((TextField) node("home-query")).setText("合成首页验收"); click("home-search-button"); });
        await(20_000, () -> fxValue(() -> !button("home-search-button").isDisabled()), "candidate generation did not finish");
        fx(() -> { preserved(); check(!field("recommendations", javafx.collections.ObservableList.class).isEmpty(), "demo generated no candidates"); click("home-radio"); });
        settle();
        fx(() -> { check(hasDialog("radio-dialog"), "home radio entry did not open the radio dialog"); preserved(); closeDialog("radio-dialog"); click("nav-home"); });
        settle();
        System.out.println("PASS ordinary/smart pins, recommendation candidates and radio navigation preserve audio, queue and loadVersion");

        phase = "customization cancel and save";
        HomeClient.Preferences before = home.load();
        openCustomization();
        fx(() -> {
            sectionList().getSelectionModel().select(0); click("home-section-down");
            pinList().getSelectionModel().select(0); click("home-pin-down");
            click("home-customize-cancel");
        });
        await(3_000, () -> fxValue(() -> !hasDialog("home-customize")), "cancel did not dismiss customization");
        check(home.load().equals(before), "cancel wrote layout preferences");
        fx(this::preserved);
        openCustomization();
        fx(() -> {
            sectionList().getSelectionModel().select(0); click("home-section-down");
            pinList().getSelectionModel().select(0); click("home-pin-down");
            selectUnpinnedCandidate(); click("home-pin-add");
            check(pinList().getItems().size() == 3, "candidate pin was not added to draft");
            pinList().getSelectionModel().select(2); click("home-pin-up");
        });
        HomeClient.Preferences edited = fxValue(this::draft);
        fx(() -> click("home-customize-save"));
        await(15_000, () -> fxValue(() -> !hasDialog("home-customize")), "successful save did not dismiss customization");
        check(home.load().equals(edited), "dialog pin/section order did not persist");
        fx(this::preserved);
        System.out.println("PASS dialog pin add/reorder and section reorder persist; cancel does not write or interrupt playback");

        phase = "save error keeps draft and retry is usable";
        failedSaveCheck(edited);

        phase = "five-theme small/home scroll boundary matrix";
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> chooseTheme(mode));
            for (int[] size : new int[][] {{1280, 800}, {860, 600}}) {
                phase = "home " + mode + " " + size[0] + "x" + size[1];
                fx(() -> { stage.setWidth(size[0]); stage.setHeight(size[1]); click("nav-home"); }); settle();
                fx(() -> {
                    checkHomeBounds(); preserved();
                    FrontendStyleAssertions.home(stage.getScene());
                    ((ScrollPane) node("home-scroll")).setVvalue(0); layout();
                    checkFirstScreenHeading();
                    screenshot(stage.getScene(), mode.name().toLowerCase() + "-home-top-" + size[0] + "x" + size[1] + ".png");
                    ((ScrollPane) node("home-scroll")).setVvalue(1); layout();
                    screenshot(stage.getScene(), mode.name().toLowerCase() + "-home-bottom-" + size[0] + "x" + size[1] + ".png");
                });
            }
        }
        fx(() -> { stage.setWidth(860); stage.setHeight(600); });
        for (ThemeController.Mode mode : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT)) {
            fx(() -> chooseTheme(mode)); openCustomization();
            fx(() -> { checkDialogBounds(); screenshot(dialogNode("home-customize").getScene(), mode.name().toLowerCase() + "-customize-small.png"); click("home-customize-cancel"); });
        }
        System.out.println("PASS all five themes at 1280x800/860x600 with bounded cards, scroll access, long pins and small customization dialog");
        System.out.println("PASS actual home auxiliary/pin colors, hover, focus and disabled styles in five themes and both sizes");

        phase = "ZIP preferences round-trip over HTTP";
        HomeClient.Preferences roundTrip = home.load();
        Path exported = root.resolve("home-preferences-roundtrip.zip");
        backups.export(exported);
        check(Files.size(exported) > 100, "HTTP export did not create ZIP");
        home.save(HomeClient.defaults());
        check(home.load().equals(HomeClient.defaults()), "roundtrip reset did not persist");
        backups.restore(exported);
        check(home.load().equals(roundTrip), "HTTP backup restore lost home preferences");
        fx(this::preserved);
        System.out.println("PASS HTTP export/reset/restore returns exact section and pin preferences");

        phase = "explicit reunion playback";
        fx(() -> { click("nav-home"); click("home-reunion-play"); });
        awaitPlaying(reunion.id());
        fx(() -> {
            check(field("loadVersion", Long.class) > originalLoad, "explicit reunion did not load new audio");
            check(!queue().snapshot().equals(originalQueue), "explicit reunion did not create its playback queue");
            click("play-button");
        });
        await(5_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "could not pause reunion before restart");
        PlayerQueue.Snapshot savedQueue = fxValue(() -> queue().snapshot());
        double savedPosition = fxValue(() -> audio().position().toSeconds());
        fx(this::stopApp);
        startApp();
        await(15_000, () -> fxValue(() -> queue().current() != null && queue().current().id().equals(reunion.id())
                && has(pinId("smart", smartId))), "second real App instance did not restore home and queue");
        fx(() -> {
            check(queue().snapshot().equals(savedQueue), "restart lost queue/index/source/repeat state");
            check(Math.abs(field("restoredPositionSeconds", Double.class) - savedPosition) < .4, "restart lost paused position");
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING, "restarted App automatically plays");
        });
        check(home.load().equals(roundTrip), "new App instance lost persisted preferences");
        openCustomization();
        fx(() -> { check(draft().equals(roundTrip), "new App did not load saved layout/pins into dialog"); click("home-customize-cancel"); });
        System.out.println("PASS explicit reunion alone changes queue; actual new App instance restores preferences and paused queue/position silently");
    }

    private void failedSaveCheck(HomeClient.Preferences saved) throws Exception {
        HttpServer failing = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        failing.createContext("/api/library/home", exchange -> {
            try (exchange) {
                byte[] response;
                int status;
                if ("POST".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().readAllBytes(); status = 500;
                    response = "<error code=\"SMOKE_WRITE_FAILURE\" message=\"合成保存失败\"/>".getBytes(StandardCharsets.UTF_8);
                } else {
                    var result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(service.resolve(exchange.getRequestURI())).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    status = result.statusCode(); response = result.body();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response);
            } catch (Exception error) { error.printStackTrace(System.err); }
        });
        failing.start();
        HomeClient appClient = fxValue(() -> field("homeClient", HomeClient.class));
        Field base = HomeClient.class.getDeclaredField("base"); base.setAccessible(true);
        URI actualBase = (URI) base.get(appClient);
        try {
            openCustomization();
            fx(() -> { pinList().getSelectionModel().select(0); click("home-pin-remove"); });
            HomeClient.Preferences wanted = fxValue(this::draft);
            base.set(appClient, URI.create("http://127.0.0.1:" + failing.getAddress().getPort()));
            fx(() -> click("home-customize-save"));
            await(15_000, () -> fxValue(() -> hasDialog("home-customize-feedback")
                    && ((Label) dialogNode("home-customize-feedback")).getText().contains("失败")), "save error was not visible");
            fx(() -> {
                check(hasDialog("home-customize"), "save error closed the dialog and lost draft");
                check(draft().equals(wanted), "save error lost draft modifications");
                check(!button("home-customize-save").isDisabled(), "save error cannot retry"); preserved();
            });
            check(home.load().equals(saved), "failed save changed service preferences");
            base.set(appClient, actualBase);
            fx(() -> click("home-customize-save"));
            await(15_000, () -> fxValue(() -> !hasDialog("home-customize")), "retry did not complete");
            check(home.load().equals(wanted), "retry did not persist preserved draft");
            // Keep three pins for the long-title/card boundary matrix.
            home.save(saved);
            fx(() -> { click("nav-favorites"); click("nav-home"); });
            await(15_000, () -> fxValue(() -> field("personalHome", PersonalHomePane.class).preferences().equals(saved)), "home did not reload restored preferences");
            System.out.println("PASS HTTP save failure is visible, draft remains intact and retry persists it without playback changes");
        } finally { base.set(appClient, actualBase); failing.stop(0); }
    }

    private void startApp() throws Exception {
        fx(() -> {
            if (app != null) stage = new Stage();
            app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(1280); stage.setHeight(800);
        });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()) + " x " + fxValue(() -> stage.getOutputScaleY()));
        settle();
    }
    private void stopApp() { app.stop(); stage.hide(); }
    private void rememberPlayback() throws Exception {
        originalAudio = fxValue(this::audio); originalQueue = fxValue(() -> queue().snapshot());
        originalLoad = fxValue(() -> field("loadVersion", Long.class)); originalPosition = fxValue(() -> audio().position().toSeconds());
    }
    private void preserved() {
        check(audio() == originalAudio, "home action replaced audio object");
        check(field("loadVersion", Long.class) == originalLoad, "home action reloaded audio");
        check(queue().snapshot().equals(originalQueue), "home action changed queue/index/source/repeat");
        check(audio().state() == JavaFxAudioPlayer.State.PLAYING, "home action stopped audio");
        check(audio().position().toSeconds() >= originalPosition - .2, "home action reset audio position");
    }
    private void checkHomeBounds() {
        layout(); ScrollPane scroll = (ScrollPane) node("home-scroll"); fits(scroll); fits(node("playback-bar"));
        fits(node("home-customize-open")); noHorizontalScroll(scroll);
        check(!node("home-page").lookupAll(".list-view").stream().anyMatch(item -> item instanceof ListView<?> list && list.getItems().size() >= 200),
                "home rendered full 200-track library instead of small cards");
        for (String id : List.of("home-section-continue", "home-section-pinned", "home-section-reunion", "home-section-discover")) {
            Bounds card = node(id).localToScene(node(id).getLayoutBounds());
            Bounds viewport = scroll.localToScene(scroll.getLayoutBounds());
            check(card.getWidth() > 100 && card.getMinX() >= viewport.getMinX() - 1 && card.getMaxX() <= viewport.getMaxX() + 1,
                    "home card width escapes viewport " + id);
        }
        for (String id : List.of("home-continue-play", pinId("playlist", playlistId), "home-reunion-play", "home-search-button")) {
            reveal(scroll, node(id)); fits(node(id));
        }
    }
    private void checkDialogBounds() {
        Node dialog = dialogNode("home-customize"); fits(dialog);
        ScrollPane scroll = dialog.lookupAll(".scroll-pane").stream().filter(ScrollPane.class::isInstance)
                .map(ScrollPane.class::cast).findFirst().orElseThrow();
        for (String id : List.of("home-section-list", "home-pin-list", "home-pin-candidate")) { reveal(scroll, dialogNode(id)); fits(dialogNode(id)); }
        ButtonBase add = button("home-pin-add");
        fits(add);
        check(add.getWidth() >= add.prefWidth(-1) - 1, "pin action label was squeezed out");
        for (String id : List.of("home-customize-save", "home-customize-cancel")) fits(dialogNode(id));
        noHorizontalScroll(dialogNode("home-pin-list"));
        Window window = dialog.getScene().getWindow();
        check(window.getWidth() <= stage.getWidth() + 2 && window.getHeight() <= stage.getHeight() + 2, "customization exceeds small main-window dimensions");
    }
    private void chooseTheme(ThemeController.Mode mode) {
        for (int i = 0; i < ThemeController.Mode.values().length && field("themeMode", ThemeController.Mode.class) != mode; i++) click("theme-button");
        check(field("themeMode", ThemeController.Mode.class) == mode, "theme did not change " + mode);
    }
    private HomeClient.Preferences draft() { return new HomeClient.Preferences(List.copyOf(sectionList().getItems()), List.copyOf(pinList().getItems())); }
    @SuppressWarnings("unchecked") private ListView<String> sectionList() { return (ListView<String>) dialogNode("home-section-list"); }
    @SuppressWarnings("unchecked") private ListView<HomeClient.Pin> pinList() { return (ListView<HomeClient.Pin>) dialogNode("home-pin-list"); }
    private ComboBox<?> candidate() { return (ComboBox<?>) dialogNode("home-pin-candidate"); }
    private void selectUnpinnedCandidate() {
        for (int i = 0; i < candidate().getItems().size(); i++) {
            Object option = candidate().getItems().get(i);
            try {
                var accessor = option.getClass().getDeclaredMethod("pin"); accessor.setAccessible(true);
                if (!pinList().getItems().contains((HomeClient.Pin) accessor.invoke(option))) {
                    candidate().getSelectionModel().select(i); return;
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect candidate pin", error); }
        }
        throw new AssertionError("fixture lacks an unpinned candidate");
    }
    private void closeDialog(String id) {
        DialogPane pane = (DialogPane) dialogNode(id);
        ((ButtonBase) pane.lookupButton(ButtonType.CLOSE)).fire();
    }
    private void reveal(ScrollPane scroll, Node target) {
        for (int pass = 0; pass < 3; pass++) {
            scroll.getScene().getRoot().applyCss(); scroll.getScene().getRoot().layout();
            Node viewportNode = scroll.lookup(".viewport");
            Bounds viewport = viewportNode.localToScene(viewportNode.getLayoutBounds());
            Bounds item = target.localToScene(target.getLayoutBounds());
            double extent = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
            if (extent <= 0) return;
            double delta = item.getMinY() < viewport.getMinY() ? item.getMinY() - viewport.getMinY() - 8
                    : item.getMaxY() > viewport.getMaxY() ? item.getMaxY() - viewport.getMaxY() + 8 : 0;
            scroll.setVvalue(Math.max(0, Math.min(1, scroll.getVvalue() + delta / extent)));
        }
        scroll.getScene().getRoot().layout();
    }
    private void openCustomization() throws Exception {
        Platform.runLater(() -> click("home-customize-open"));
        await(5_000, () -> fxValue(() -> hasDialog("home-customize")), "customization did not open"); pause(150);
    }
    private void fits(Node value) {
        check(value.isVisible() && value.isManaged(), "hidden key node " + value.getId());
        Bounds bounds = value.localToScene(value.getLayoutBounds()); Scene scene = value.getScene();
        check(bounds.getWidth() > 0 && bounds.getHeight() > 0 && bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1
                && bounds.getMinY() >= -1 && bounds.getMaxY() <= scene.getHeight() + 1, "key control overflow " + value.getId() + ": " + bounds);
    }
    private void noHorizontalScroll(Node value) {
        check(value.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast)
                .noneMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible() && bar.getHeight() > 0), "horizontal scroll " + value.getId());
    }
    private void screenshot(Scene scene, String name) {
        try {
            scene.getRoot().applyCss(); scene.getRoot().layout(); WritableImage shot = scene.snapshot(null);
            BufferedImage png = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Path target = root.resolve(name); check(ImageIO.write(png, "png", target.toFile()) && Files.size(target) > 1_000, "missing screenshot " + name);
            System.out.println("SCREENSHOT: " + target);
        } catch (Exception error) { throw new AssertionError("cannot retain screenshot " + name, error); }
    }
    private static String pinId(String kind, String id) { return "home-pin-" + kind + "-" + id; }
    private Label label(String id) { return (Label) node(id); }
    private ButtonBase button(String id) { return (ButtonBase) node(id); }
    private void click(String id) { button(id).fire(); }
    private boolean has(String id) { return stage.getScene().lookup("#" + id) != null; }
    private boolean hasDialog(String id) { return findDialog(id) != null; }
    private Node findDialog(String id) {
        for (Window window : Window.getWindows()) if (window.isShowing() && window != stage && window.getScene() != null) {
            Node found = window.getScene().lookup("#" + id); if (found != null) return found;
        }
        return null;
    }
    private Node dialogNode(String id) { Node found = findDialog(id); check(found != null, "missing dialog #" + id); return found; }
    private Node node(String id) { Node found = findDialog(id); if (found == null) found = stage.getScene().lookup("#" + id); check(found != null, "missing UI #" + id); return found; }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private <T> T field(String name, Class<T> type) {
        try { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private void settle() throws Exception { pause(420); fx(this::layout); }
    private void awaitPlaying(String id) throws Exception {
        await(15_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && field("loadedTrack", Track.class) != null
                && field("loadedTrack", Track.class).id().equals(id) && audio().position().toSeconds() > .2), "local WAV did not play " + id);
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
        System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
