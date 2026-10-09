package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Real batch dialog, real Python transactions, and neutral native WAV previews. */
public final class SegmentBatchSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean(), failNextBatch = new AtomicBoolean();
    private final AtomicInteger batchCalls = new AtomicInteger();
    private final AtomicInteger radioCalls = new AtomicInteger();
    private final Set<String> listened = ConcurrentHashMap.newKeySet();
    private final List<String> observations = new ArrayList<>(List.of("phase,state,sourceSeconds,queueIndex,loadVersion,trackId"));
    private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "batch-smoke-http"); thread.setDaemon(true); return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    private Path root;
    private URI service, fixture;
    private HttpServer proxy;
    private LibraryClient library;
    private LumenFxApp app;
    private Stage stage;
    private Track source, unknown, baseline, following;
    private volatile String phase = "startup";

    @Override public void start(Stage initial) {
        stage = initial; Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(265_000); } catch (InterruptedException ignored) { return; }
            System.err.println("FAIL batch watchdog: " + phase); System.exit(1);
        }, "batch-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int code = 0;
            try { exercise(); }
            catch (Throwable error) { code = 1; System.err.println("FAIL batch phase: " + phase); error.printStackTrace(System.err); }
            finally { finish(code); }
        }, "batch-smoke-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        prepare(); createApp();
        selectBaseline(); awaitPlaying(baseline); pauseAtFive();
        if ("1".equals(System.getenv("LUMEN_SEGMENT_BATCH_RADIO_ONLY"))) {
            radioPreviewChecks(); return;
        }
        visualChecks();
        if (Boolean.getBoolean("lumen.scene.smoke.visualOnly")) {
            System.out.println("PASS batch dialog dark/light 860x600/1280x800 readable and editable at actual DPI"); return;
        }
        entryChecks(); invalidAndUnknownChecks(); previewChecks();
        favoriteAndRetryChecks(); playlistAndAtomicChecks(); identityCheck(); restartAndZipChecks();
        check(listened.stream().noneMatch(id -> id.startsWith("segment:") || id.equals(source.id()) || id.equals(unknown.id())),
                "neutral preview entered real listening history: " + listened);
        System.out.println("PASS batch paste/edit/validation, real neutral previews, foreground-preserving save, atomic transactions,"
                + " duplicate reports, complete-source cache identity, restart/ZIP and two-theme minimum/desktop readability");
    }

    private void prepare() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("segment-batch-smoke"), "isolated batch profile required");
        for (String name : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path value = Path.of(required(name)).toAbsolutePath().normalize();
            check(value.equals(root) || root.equals(value.getParent()), name + " escaped isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "personal API key entered smoke");
        service = URI.create(required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(service.getHost()) && service.getPort() > 0, "loopback service required");
        library = new LibraryClient(service);
        source = new Track("batch-source", "Long synthetic collection", "Synthetic fixture", 32, "local", root.resolve("batch-source.wav").toUri());
        unknown = new Track("batch-unknown-source", "Unknown-length collection", "Synthetic fixture", 0, "local", source.mediaUri());
        baseline = new Track("batch-baseline-first", "Baseline first", "Synthetic fixture", 96, "local", root.resolve("batch-baseline.wav").toUri());
        following = new Track("batch-baseline-second", "Baseline second", "Synthetic fixture", 96, "local", baseline.mediaUri());
        check(Files.size(Path.of(source.mediaUri())) > 700_000 && Files.size(Path.of(baseline.mediaUri())) > 2_000_000, "real WAV fixtures missing");
        new HotkeySettingsStore(root.resolve("hotkeys.properties")).save(Map.of());
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(workers); proxy.createContext("/api/", this::forward); proxy.start();
        fixture = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
    }

    private void createApp() throws Exception {
        fx(() -> {
            if (app != null) { app.stop(); stage.close(); stage = new Stage(); }
            app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(1280); stage.setHeight(800);
            set(app, "libraryClient", new LibraryClient(fixture));
            set(field("listening", ListeningReporter.class), "endpoint", fixture.resolve("/api/listening"));
        });
        await(10_000, () -> fxValue(() -> ((Label) node("library-status")).getText().equals("个人曲库已载入")), "formal library did not load");
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()));
    }

    private void selectBaseline() throws Exception {
        fx(() -> { queue().select(List.of(baseline, following), 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true); });
    }
    private void pauseAtFive() throws Exception {
        fx(() -> button("play-button").fire());
        await(4000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "baseline did not pause");
        fx(() -> invoke("seekToFraction", new Class<?>[] {double.class}, 5.0 / 96));
        await(4000, () -> fxValue(() -> Math.abs(audio().position().toSeconds() - 5) < .3), "paused baseline seek failed");
    }

    private void entryChecks() throws Exception {
        phase = "real source-row and immersive batch entry points";
        fx(() -> {
            ContextMenu menu = new ContextMenu();
            @SuppressWarnings("unchecked") ListView<Track> list = (ListView<Track>) node("favorites-list");
            invoke("populateTrackMenu", new Class<?>[] {ContextMenu.class, ListView.class, Track.class, int.class}, menu, list, source, 0);
            MenuItem batch = menu.getItems().stream().filter(item -> "track-menu-segment-batch".equals(item.getId())).findFirst()
                    .orElseThrow(() -> new AssertionError("source-row batch entry missing"));
            batch.fire(); check(dialog().getId().equals("segment-batch-dialog"), "menu opened wrong dialog"); closeDialog(dialog());
            button("open-immersive").fire();
        });
        waitMillis(250);
        fx(() -> { button("immersive-segment-batch").fire(); closeDialog(dialog()); button("immersive-collapse").fire(); });
        checkBaselinePaused();
        System.out.println("PASS source-row menu and immersive batch controls open the actual dialog without disturbing playback");
    }

    private void visualChecks() throws Exception {
        phase = "batch candidate editor readability";
        for (ThemeController.Mode theme : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT)) {
            for (int[] size : new int[][] {{860, 600}, {1280, 800}}) {
                fx(() -> {
                    for (int count = 0; count < 8 && field("themeMode", ThemeController.Mode.class) != theme; count++) button("theme-button").fire();
                    stage.setWidth(size[0]); stage.setHeight(size[1]); open(source); parse("00:02 First candidate\n00:04 Second candidate\n00:06 - 00:08 Last candidate");
                    Stage window = (Stage) dialog().getScene().getWindow(); window.setWidth(size[0]); window.setHeight(size[1]);
                });
                waitMillis(250);
                fx(() -> {
                    DialogPane pane = dialog();
                    ensureVisible(pane.lookup("#segment-batch-input")); fits(pane.lookup("#segment-batch-parse"));
                    screenshot(pane, theme.name().toLowerCase() + "-batch-" + size[0] + "x" + size[1] + "-top.png");
                    table().getSelectionModel().select(0); edit("title", "Edited visible candidate");
                    check(table().getItems().get(0).title().equals("Edited visible candidate"), "visible editor did not update row");
                    ensureVisible(pane.lookup("#segment-batch-save")); fits(pane.lookup("#segment-batch-preview"));
                    screenshot(pane, theme.name().toLowerCase() + "-batch-" + size[0] + "x" + size[1] + "-bottom.png");
                });
                if (theme == ThemeController.Mode.DARK && size[0] == 860) contextMenuCheck();
                fx(() -> closeDialog(dialog()));
            }
        }
        fx(() -> { stage.setWidth(1280); stage.setHeight(800); });
        checkBaselinePaused();
        System.out.println("PASS batch editor readable/editable at minimum and desktop windows, dark/light and actual output DPI");
    }

    private void contextMenuCheck() throws Exception {
        fx(() -> {
            TextArea input = (TextArea) dialog().lookup("#segment-batch-input"); ensureVisible(input); input.requestFocus();
            var screen = input.localToScreen(20, 20);
            input.fireEvent(new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    20, 20, screen.getX(), screen.getY(), false, new javafx.scene.input.PickResult(input, 20, 20)));
        });
        await(3000, () -> fxValue(() -> contextMenuWindow() != null), "TextArea native context menu did not open");
        waitMillis(100);
        fx(() -> {
            Window menu = contextMenuWindow();
            screenshotScene(menu.getScene(), "dark-batch-textarea-context-menu.png");
            for (Node content : menu.getScene().getRoot().lookupAll(".context-menu"))
                System.out.println("MENU SURFACE " + content.getClass().getName() + " styles=" + content.getStyleClass()
                        + " background=" + (content instanceof javafx.scene.layout.Region region ? region.getBackground() : "not Region"));
            var surface = menu.getScene().getRoot().lookupAll(".context-menu").stream()
                    .filter(javafx.scene.layout.Region.class::isInstance).map(javafx.scene.layout.Region.class::cast)
                    .filter(region -> region.getBackground() != null && !region.getBackground().getFills().isEmpty()).findFirst().orElseThrow();
            var fill = surface.getBackground().getFills().get(0).getFill();
            check(fill instanceof javafx.scene.paint.Color, "context menu computed surface color missing");
            var computed = (javafx.scene.paint.Color) fill;
            check((computed.getRed() + computed.getGreen() + computed.getBlue()) / 3 < .65, "context menu computed surface kept light background");
            WritableImage rendered = menu.getScene().snapshot(null);
            Bounds bounds = surface.localToScene(surface.getLayoutBounds());
            int left = (int) bounds.getMinX() + 8, right = (int) bounds.getMaxX() - 8;
            int top = (int) bounds.getMinY() + 16, bottom = (int) bounds.getMaxY() - 16, dark = 0;
            for (int[] point : new int[][] {{left, top}, {right, top}, {left, bottom}, {right, bottom}}) {
                var color = rendered.getPixelReader().getColor(point[0], point[1]);
                if ((color.getRed() + color.getGreen() + color.getBlue()) / 3 < .65) dark++;
            }
            check(dark >= 3, "dark dialog context menu rendered light background: dark samples=" + dark);
            menu.hide();
        });
        System.out.println("PASS actual TextArea context-menu event produced a dark computed popup surface");
    }
    private static Window contextMenuWindow() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(window -> window instanceof javafx.stage.PopupWindow)
                .filter(window -> window.getScene() != null && window.getScene().lookup(".context-menu") != null).findFirst().orElse(null);
    }

    private void invalidAndUnknownChecks() throws Exception {
        phase = "bad timestamps, inverted order and source boundary feedback";
        int count = library.load().favorites().size(), calls = batchCalls.get();
        fx(() -> open(source));
        String[] invalid = {"00:99 Broken timestamp", "00:05 - 00:06 Later\n00:02 - 00:03 Earlier", "00:30 - 00:33 Beyond source"};
        String[] reason = {"格式", "顺序", "超出"};
        for (int index = 0; index < invalid.length; index++) {
            final int example = index;
            fx(() -> { parse(invalid[example]); button(dialog(), "segment-batch-save").fire();
                check(message().contains(reason[example]), "invalid candidate omitted specific feedback: " + message()); });
            check(batchCalls.get() == calls && library.load().favorites().size() == count, "invalid candidate submitted or partially saved");
            System.out.println("VALIDATION " + fxValue(this::message));
        }
        fx(() -> { closeDialog(dialog()); open(unknown); parse("00:01 Known start\n00:04 Unknown tail");
            check(table().getItems().get(1).end().isBlank(), "unknown final boundary was invented");
            button(dialog(), "segment-batch-save").fire(); check(message().contains("终点") || message().contains("未知"), "unknown tail had no actionable feedback"); });
        check(batchCalls.get() == calls, "unknown final boundary reached batch API");
        fx(() -> { table().getSelectionModel().select(1); edit("end", "00:06"); button(dialog(), "segment-batch-preview").fire(); });
        awaitPreview(4, 6); observe("unknown-preview");
        fx(() -> closeDialog(dialog())); awaitBaselinePaused();
        checkNoPreviewHistory();
        System.out.println("PASS malformed/order/bounds feedback, unknown tail blocked until explicit correction and neutral real preview");
    }

    private void previewChecks() throws Exception {
        phase = "sequential native previews restore paused queue and source position";
        fx(() -> { open(source); parse("00:02 - 00:03 First preview\n00:06 - 00:07 Second preview");
            table().getSelectionModel().select(0); button(dialog(), "segment-batch-preview").fire(); });
        awaitPreview(2, 3); observe("first-preview");
        awaitBaselinePaused(); observe("natural-preview-restored");
        fx(() -> { table().getSelectionModel().select(1); button(dialog(), "segment-batch-preview").fire(); });
        awaitPreview(6, 7); observe("second-preview");
        awaitBaselinePaused(); observe("second-natural-restored");
        long restoredLoad = fxValue(() -> field("loadVersion", Long.class));
        fx(() -> { button(dialog(), "segment-batch-preview-stop").fire(); closeDialog(dialog()); });
        waitMillis(300); checkBaselinePaused();
        check(fxValue(() -> field("loadVersion", Long.class) == restoredLoad), "stop/close restored an already-restored preview twice");
        checkNoPreviewHistory();
        System.out.println("PASS real JavaFX previews reached both source offsets, natural completion/close restored queue/position/pause with no preview listening");
    }

    private void favoriteAndRetryChecks() throws Exception {
        phase = "editable selected candidates, failed save preserves draft, retry favorites";
        int before = library.load().favorites().size();
        fx(() -> { open(source); parse("1. 00:02 First\n2. [00:04] Second\n3. 00:06 - 00:08 Third");
            String id = table().getItems().get(0).id(); table().getSelectionModel().select(0);
            edit("title", "Edited first"); edit("artist", "Edited artist"); edit("end", "00:03.500");
            check(table().getItems().get(0).id().equals(id), "edit regenerated candidate identity");
            table().getSelectionModel().select(1); CheckBox selected = (CheckBox) dialog().lookup("#segment-batch-selected");
            if (selected.isSelected()) selected.fire(); check(!table().getItems().get(1).selected(), "selection checkbox did not update row");
        });
        List<String> ids = fxValue(() -> table().getItems().stream().map(SegmentBatchDraft.Row::id).toList());
        failNextBatch.set(true); fx(() -> button(dialog(), "segment-batch-save").fire());
        await(8000, () -> fxValue(() -> message().contains("503") || message().contains("Synthetic batch failure")), "failed transaction had no retry feedback");
        check(library.load().favorites().size() == before, "failed transaction saved partial favorites");
        check(fxValue(() -> table().getItems().stream().map(SegmentBatchDraft.Row::id).toList().equals(ids)), "failed transaction lost draft identities");
        checkBaselinePaused(); fx(() -> button(dialog(), "segment-batch-save").fire());
        await(10_000, () -> library.load().favorites().size() == before + 2, "retry did not save selected favorites");
        awaitClosed(); checkBaselinePaused();
        List<Track> added = library.load().favorites().stream().filter(Track::isSegment).toList();
        check(added.size() == 2 && added.stream().anyMatch(track -> track.title().equals("Edited first") && track.artist().equals("Edited artist")
                && track.segmentStartMillis() == 2000 && track.segmentEndMillis() == 3500), "edited candidate was not round-tripped");
        check(added.stream().noneMatch(track -> track.segmentStartMillis() == 4000), "unchecked candidate was saved");
        phase = "repeat batch reports duplicate favorites without creating entries";
        fx(() -> { open(source); parse("00:02 - 00:03.500 Edited first\n00:06 - 00:08 Third"); button(dialog(), "segment-batch-save").fire(); });
        awaitClosed();
        check(library.load().favorites().size() == before + 2, "duplicate ranges created new favorites");
        String feedback = fxValue(() -> field("status", Label.class).getText());
        check(feedback.contains("2") && (feedback.contains("跳过") || feedback.contains("重复")), "duplicate save omitted skipped-count feedback: " + feedback);
        System.out.println("BATCH DUPLICATE " + feedback);
        System.out.println("PASS real UI edit/selection, transient failure keeps draft, atomic favorite retry and duplicate reporting");
    }

    private void radioPreviewChecks() throws Exception {
        phase = "active radio preview preserves seen identities and candidates";
        fx(() -> {
            set(app, "radioClient", new RadioClient(fixture));
            invoke("startRadio", new Class<?>[] {List.class, String.class, String.class, boolean.class},
                    List.of(baseline), "月光", "familiar", true);
        });
        await(15_000, () -> fxValue(() -> !field("radioLoading", Boolean.class)
                && !field("radio", RadioSession.class).preview().isEmpty()), "real demo radio did not prepare candidates");
        List<RadioClient.Item> candidates = fxValue(() -> field("radio", RadioSession.class).preview());
        Set<String> seen = fxValue(() -> field("radio", RadioSession.class).seenIds());
        long version = fxValue(() -> field("radioVersion", Long.class));
        int requests = radioCalls.get();
        check(requests == 1 && seen.contains(baseline.id()), "radio start did not establish actual seen/candidate state");
        fx(() -> { open(source); parse("00:02 - 00:03 Radio-neutral preview");
            table().getSelectionModel().select(0); button(dialog(), "segment-batch-preview").fire(); });
        awaitPreview(2, 3); String previewId = fxValue(() -> queue().current().id()); observe("radio-preview");
        awaitBaselinePaused(); observe("radio-natural-restored");
        fx(() -> { screenshot(dialog(), "radio-neutral-preview-restored.png"); closeDialog(dialog()); });
        waitMillis(350); checkBaselinePaused(); checkNoPreviewHistory();
        check(fxValue(() -> {
            RadioSession radio = field("radio", RadioSession.class);
            return radio.active() && radio.autoContinue() && radio.neutral() && radio.mode().equals("familiar")
                    && radio.hint().equals("月光") && radio.seeds().equals(List.of(baseline))
                    && radio.preview().equals(candidates) && radio.seenIds().equals(seen)
                    && !radio.seenIds().contains(previewId) && field("radioVersion", Long.class) == version
                    && !field("radioContinuationPending", Boolean.class) && !field("radioLoading", Boolean.class);
        }) && radioCalls.get() == requests, "neutral preview consumed radio candidates/seen state or triggered continuation");
        Files.writeString(root.resolve("radio-neutral-evidence.txt"), "radioRequests=" + requests + "\npreviewId=" + previewId
                + "\nseenBefore=" + seen + "\nseenAfter=" + fxValue(() -> field("radio", RadioSession.class).seenIds())
                + "\ncandidateIds=" + candidates.stream().map(item -> item.track().id()).toList() + "\n");
        System.out.println("PASS active radio neutral native preview natural EOF restored paused queue/5s, kept "
                + candidates.size() + " candidates and seen set unchanged, no continuation/request/history; preview=" + previewId);
    }

    private void playlistAndAtomicChecks() throws Exception {
        phase = "batch creates ordinary playlist without interrupting current playback";
        fx(() -> button("play-button").fire()); awaitPlaying(baseline);
        long load = fxValue(() -> field("loadVersion", Long.class)); double before = fxValue(() -> audio().position().toSeconds());
        String session = fxValue(this::sessionId);
        fx(() -> { open(source); parse("00:10 - 00:12 Playlist first\n00:12 - 00:14 Playlist second");
            @SuppressWarnings("unchecked") ComboBox<String> destination = (ComboBox<String>) dialog().lookup("#segment-batch-destination");
            destination.setValue("新建歌单"); edit("playlist-name", "Imported batch playlist"); button(dialog(), "segment-batch-save").fire(); });
        awaitClosed();
        LibraryClient.Snapshot snapshot = library.load();
        LibraryClient.Playlist created = snapshot.playlists().stream().filter(list -> list.name().equals("Imported batch playlist")).findFirst().orElseThrow();
        check(created.tracks().size() == 2 && created.tracks().stream().allMatch(Track::isSegment), "batch playlist was empty/partial/not ordinary segments");
        check(fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && field("loadVersion", Long.class) == load
                && queue().tracks().equals(List.of(baseline, following)) && sessionId().equals(session)
                && audio().position().toSeconds() >= before), "saving interrupted foreground queue/load/position/session");
        observe("playlist-save-preserved");
        phase = "formal Python batch validates all rows before atomic mutation";
        Track valid = Track.segment(source, "Must not partially save", "Fixture", 18000, 20000);
        Track bad = Track.segment(source, "Invalid boundary", "Fixture", 22000, 30000);
        String body = "<segments destination=\"playlist\" name=\"Must not create\" batchId=\"" + UUID.randomUUID()
                + "\">" + LibraryClient.trackXml(valid) + LibraryClient.trackXml(bad).replace("segmentEndMillis=\"30000\"", "segmentEndMillis=\"33000\"") + "</segments>";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(service.resolve("/api/library/segments/batch"))
                .header("Content-Type", "application/xml; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() >= 400 && response.statusCode() < 500, "formal batch accepted invalid boundary");
        check(library.load().equals(snapshot), "failed formal batch partially changed favorites/playlist or created empty playlist");
        Files.writeString(root.resolve("atomic-error.xml"), response.body());
        System.out.println("PASS ordinary playlist committed fully, saving preserved playing session, invalid batch left complete formal snapshot unchanged");
    }

    private void identityCheck() throws Exception {
        phase = "saved batch segments share complete-source cache identity";
        List<Track> segments = library.load().favorites().stream().filter(Track::isSegment).toList();
        check(segments.size() == 2 && segments.stream().allMatch(track -> track.audioSource().id().equals(source.id())
                && track.audioSource().mediaUri().equals(source.mediaUri())), "batch changed original media identity");
        PersistentAudioCache cache = fxValue(() -> field("persistentCache", PersistentAudioCache.class));
        cache.configure(true, 16 * 1024 * 1024); AtomicInteger copies = new AtomicInteger();
        PersistentAudioCache.Downloader copier = (original, target, cancellation) -> {
            check(original.id().equals(source.id()), "cache requested a segment rather than full source"); copies.incrementAndGet();
            Files.copy(Path.of(source.mediaUri()), target, StandardCopyOption.REPLACE_EXISTING);
        };
        URI first = cache.store(segments.get(0), new MusicClient.DownloadCancellation(), copier);
        URI second = cache.store(segments.get(1), new MusicClient.DownloadCancellation(), copier);
        check(first.equals(second) && first.equals(cache.lookup(source).orElseThrow()) && copies.get() == 1 && cache.entryCount() == 1,
                "batch segments duplicated full-source cache identity");
        check(Files.size(Path.of(first)) == Files.size(Path.of(source.mediaUri())), "shared source cache became per-segment/partial");
        System.out.println("PASS saved batch cache identity: one complete WAV source, one copy, one cache entry shared by both ranges");
    }

    private void restartAndZipChecks() throws Exception {
        phase = "batch identities/ranges survive app restart and ZIP restore";
        LibraryClient.Snapshot expected = library.load();
        createApp(); check(library.load().equals(expected), "restart changed batch identities/ranges");
        check(fxValue(() -> audio().state() != JavaFxAudioPlayer.State.PLAYING), "restart auto-played batch or baseline");
        BackupClient backup = new BackupClient(service); Path zip = root.resolve("batch-export.zip");
        BackupClient.Preview exported = backup.export(zip), inspected = backup.inspect(zip);
        check(Files.size(zip) > 100 && exported.favorites() == expected.favorites().size() && inspected.playlists() == expected.playlists().size(), "ZIP export/inspect omitted batch data");
        library.unfavorite(expected.favorites().stream().filter(Track::isSegment).findFirst().orElseThrow().id());
        library.deletePlaylist(expected.playlists().stream().filter(list -> list.name().equals("Imported batch playlist")).findFirst().orElseThrow().id());
        backup.restore(zip); check(library.load().equals(expected), "ZIP restore lost batch IDs/ranges/source identity/order");
        createApp(); check(fxValue(() -> field("favorites", List.class).size() == expected.favorites().size()), "restarted UI omitted restored batch favorites");
        fx(() -> { open(source); parse("00:02 - 00:03.500 Edited first\n00:06 - 00:08 Third"); });
        waitMillis(250); fx(() -> { screenshot(dialog(), "batch-after-zip-restart.png"); closeDialog(dialog()); });
        System.out.println("PASS formal ZIP export/inspect/restore and app restart preserve all batch identities, ranges, source metadata and playlist order");
    }

    private void awaitPreview(double start, double end) throws Exception {
        await(10_000, () -> fxValue(() -> audio() instanceof JavaFxAudioPlayer && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && queue().current() != null && queue().current().isSegment() && audio().position().toSeconds() >= start + .08
                && audio().position().toSeconds() < end), "real preview did not reach requested source offset " + start);
    }
    private void awaitPlaying(Track track) throws Exception {
        await(10_000, () -> fxValue(() -> queue().current().equals(track) && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() > .1), "real WAV did not play " + track.id());
    }
    private void awaitBaselinePaused() throws Exception {
        try {
            await(10_000, () -> fxValue(() -> queue().current().equals(baseline) && pausedNative()
                    && Math.abs(audio().position().toSeconds() - 5) < .4), "preview did not restore paused baseline position");
        } catch (AssertionError failure) {
            double before = fxValue(() -> audio().position().toSeconds()); waitMillis(350);
            String evidence = fxValue(() -> "state=" + audio().state() + "; native="
                    + ((javafx.scene.media.MediaPlayer) get(audio(), "player")).getStatus()
                    + "; before=" + before + "; after=" + audio().position().toSeconds() + "; queue=" + queue().tracks());
            System.err.println("RESTORE EVIDENCE " + evidence); observe("restore-state-evidence");
            throw new AssertionError(failure.getMessage() + "; " + evidence, failure);
        }
        checkBaselinePaused();
    }
    private void checkBaselinePaused() throws Exception {
        check(fxValue(() -> queue().tracks().equals(List.of(baseline, following)) && queue().index() == 0
                && queue().repeatMode() == PlayerQueue.RepeatMode.OFF && pausedNative()
                && Math.abs(audio().position().toSeconds() - 5) < .4), "editing/preview changed original queue/mode/position/pause");
        double stoppedPosition = fxValue(() -> audio().position().toSeconds());
        waitMillis(350);
        check(fxValue(() -> pausedNative() && Math.abs(audio().position().toSeconds() - stoppedPosition) < .03),
                "restored baseline auto-played or moved while paused");
    }
    private void checkNoPreviewHistory() throws Exception {
        waitMillis(250);
        check(listened.stream().noneMatch(id -> id.startsWith("segment:") || id.equals(source.id()) || id.equals(unknown.id())), "preview produced listening samples: " + listened);
    }
    private void awaitClosed() throws Exception { await(12_000, () -> fxValue(() -> findDialog() == null), "batch save did not close dialog"); }
    private void open(Track track) { invoke("showSegmentBatch", new Class<?>[] {Track.class}, track); }
    private void parse(String input) { ((TextArea) dialog().lookup("#segment-batch-input")).setText(input); button(dialog(), "segment-batch-parse").fire(); check(!table().getItems().isEmpty(), "paste parse produced no candidates"); }
    private void edit(String name, String value) { ((TextField) dialog().lookup("#segment-batch-" + name)).setText(value); }
    @SuppressWarnings("unchecked") private TableView<SegmentBatchDraft.Row> table() { return (TableView<SegmentBatchDraft.Row>) dialog().lookup("#segment-batch-table"); }
    private String message() { return ((Label) dialog().lookup("#segment-batch-message")).getText(); }
    private static DialogPane findDialog() { return Window.getWindows().stream().filter(Window::isShowing).filter(window -> window.getScene() != null)
            .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
            .filter(pane -> pane.lookup("#segment-batch-input") != null).findFirst().orElse(null); }
    private static DialogPane dialog() { DialogPane pane = findDialog(); if (pane == null) throw new AssertionError("batch dialog missing"); return pane; }
    private static void closeDialog(DialogPane pane) {
        var cancel = pane.getButtonTypes().stream().filter(type -> type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        ((Button) pane.lookupButton(cancel)).fire();
    }
    private static Button button(DialogPane pane, String id) { Node value = pane.lookup("#" + id); if (!(value instanceof Button)) throw new AssertionError("button missing: " + id); return (Button) value; }
    private Button button(String id) { return (Button) node(id); }
    private Node node(String id) { Node value = stage.getScene().lookup("#" + id); if (value == null) throw new AssertionError("UI node missing: " + id); return value; }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private boolean pausedNative() {
        // Non-autoplay JavaFX reloads are READY; verify native non-playing state and a stable clock.
        if (!(audio() instanceof JavaFxAudioPlayer)) return false;
        if (audio().state() != JavaFxAudioPlayer.State.PAUSED && audio().state() != JavaFxAudioPlayer.State.READY) return false;
        try {
            javafx.scene.media.MediaPlayer nativePlayer = (javafx.scene.media.MediaPlayer) get(audio(), "player");
            return nativePlayer != null && (nativePlayer.getStatus() == javafx.scene.media.MediaPlayer.Status.PAUSED
                    || nativePlayer.getStatus() == javafx.scene.media.MediaPlayer.Status.READY);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private String sessionId() { try { Object session = get(field("listening", ListeningReporter.class), "current"); return session == null ? "none" : (String) get(session, "id"); } catch (Exception error) { throw new AssertionError(error); } }

    private static void ensureVisible(Node node) {
        check(node != null, "visible control missing");
        for (Parent parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getContent() != null) {
                double excess = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
                if (excess > 0) {
                    Bounds relative = scroll.getContent().sceneToLocal(node.localToScene(node.getLayoutBounds()));
                    scroll.setVvalue(Math.max(0, Math.min(1, (relative.getMinY() - 12) / excess)));
                }
            }
        }
        node.getScene().getRoot().applyCss(); node.getScene().getRoot().layout(); fits(node);
    }
    private static void fits(Node node) {
        check(node != null && node.isVisible() && node.isManaged(), "control not available");
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        check(bounds.getMinX() >= -1 && bounds.getMaxX() <= node.getScene().getWidth() + 1
                && bounds.getMinY() >= -1 && bounds.getMaxY() <= node.getScene().getHeight() + 1, "control clipped: " + node.getId());
    }
    private void screenshot(DialogPane pane, String name) {
        pane.applyCss(); pane.layout(); screenshotScene(pane.getScene(), name);
    }
    private void screenshotScene(javafx.scene.Scene scene, String name) {
        try {
            scene.getRoot().applyCss(); scene.getRoot().layout(); WritableImage shot = scene.snapshot(null);
            BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Path destination = root.resolve(name); check(ImageIO.write(image, "png", destination.toFile()) && Files.size(destination) > 1000, "empty dialog screenshot");
            System.out.println("SCREENSHOT: " + destination);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private void observe(String label) throws Exception { fx(() -> observations.add(label + "," + audio().state() + "," + audio().position().toSeconds()
            + "," + queue().index() + "," + field("loadVersion", Long.class) + "," + queue().current().id())); }

    private void forward(HttpExchange exchange) {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes(); String path = exchange.getRequestURI().toString();
            if (path.equals("/api/radio")) radioCalls.incrementAndGet();
            if (path.equals("/api/listening")) {
                var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
                listened.add(((org.w3c.dom.Element) xml.getElementsByTagName("track").item(0)).getAttribute("id"));
            }
            if (path.equals("/api/library/segments/batch")) {
                batchCalls.incrementAndGet();
                if (failNextBatch.compareAndSet(true, false)) {
                    byte[] error = "<error message=\"Synthetic batch failure, retry\"/>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8"); exchange.sendResponseHeaders(503, error.length); exchange.getResponseBody().write(error); return;
                }
            }
            var response = http.send(HttpRequest.newBuilder(service.resolve(path)).timeout(java.time.Duration.ofSeconds(15))
                    .header("Content-Type", "application/xml; charset=utf-8").method(exchange.getRequestMethod(),
                            body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8"); exchange.sendResponseHeaders(response.statusCode(), response.body().length); exchange.getResponseBody().write(response.body());
        } catch (Exception error) { if (!finished.get()) error.printStackTrace(System.err); }
        finally { exchange.close(); }
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); });
            if (root != null) { Files.write(root.resolve("native-preview.csv"), observations); Files.write(root.resolve("listened-track-ids.txt"), listened.stream().sorted().toList()); }
        } catch (Exception error) { error.printStackTrace(System.err); code = 1; }
        if (proxy != null) proxy.stop(0); workers.shutdownNow(); Platform.exit(); System.exit(code);
    }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    private static void fx(CheckedRunnable action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private static <T> T fxValue(CheckedSupplier<T> supplier) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>(); Platform.runLater(() -> { try { result.complete(supplier.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void await(long millis, CheckedSupplier<Boolean> condition, String error) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) { if (condition.get()) return; waitMillis(25); }
        throw new AssertionError(error + "; phase=" + phase + "; state=" + fxValue(() -> audio().state()) + "; position=" + fxValue(() -> audio().position().toSeconds()));
    }
    private static void waitMillis(long millis) throws InterruptedException { Thread.sleep(millis); }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new AssertionError("missing " + name); return value; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private <T> T field(String name, Class<T> type) { try { return type.cast(get(app, name)); } catch (Exception error) { throw new AssertionError(error); } }
    private static Object get(Object object, String name) throws Exception { Field value = object.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private void invoke(String name, Class<?>[] types, Object... args) { try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, args); } catch (Exception error) { throw new AssertionError(error); } }
}
