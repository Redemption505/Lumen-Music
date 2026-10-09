package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.NextTrackPreloader;
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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Real native clocks, delayed loopback audio, queue edits, and opt-in volume envelopes. */
public final class ContinuousPlaybackSmoke extends Application {
    private static final long HEAD_DELAY_MILLIS = 1200;
    private static final class Requests {
        final AtomicInteger heads = new AtomicInteger(), completedHeads = new AtomicInteger(), gets = new AtomicInteger();
        volatile boolean unavailable;
    }
    private record Sample(double elapsed, String state, double position, double output, double engineVolume,
                          long load, int index, String session, int nativeEnds) { }
    private final Map<String, Requests> requests = new ConcurrentHashMap<>();
    private final Set<String> listened = ConcurrentHashMap.newKeySet();
    private final List<String> metrics = new ArrayList<>(List.of("engine,coldSeconds,preparedSeconds,improvementSeconds"));
    private final List<String> network = Collections.synchronizedList(new ArrayList<>(List.of("nanos,method,path,event")));
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger identity = new AtomicInteger();
    private final AtomicInteger observedNativeEnds = new AtomicInteger();
    private final AtomicLong observedNativeEndNanos = new AtomicLong();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "continuous-http-fixture"); thread.setDaemon(true); return thread;
    });
    private final HttpClient proxy = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
    private HttpServer fixtureServer;
    private URI fixtureBase, serviceBase;
    private byte[] aac;
    private Path root;
    private Track local;
    private LumenFxApp app;
    private Stage stage;
    private volatile List<Track> radioTargets = List.of();
    private volatile boolean offline;
    private volatile String phase = "startup";

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(265_000); } catch (InterruptedException ignored) { return; }
            System.err.println("FAIL continuous watchdog: " + phase); System.exit(1);
        }, "continuous-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int result = 0;
            try { exercise(); }
            catch (Throwable error) { result = 1; System.err.println("FAIL continuous phase: " + phase); error.printStackTrace(System.err); }
            finally { finish(result); }
        }, "continuous-smoke-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        prepare();
        createApp(false);
        fx(() -> {
            check(field("preloadEnabled", Boolean.class), "preparation must default on");
            check(!field("shortFadeEnabled", Boolean.class), "short fades must default off");
            check(!disk().enabled(), "persistent caching must remain opt-in");
        });
        settingsVisuals();
        if (Boolean.getBoolean("lumen.scene.smoke.visualOnly")) {
            select(List.of(local)); awaitPlaying(local);
            settingsPersistence();
            System.out.println("PASS continuous settings defaults, dark/light minimum/desktop readability and restart persistence without autoplay or AB");
            return;
        }
        for (boolean javaFxOnly : new boolean[] {false, true}) {
            if (javaFxOnly) createApp(true);
            String engine = javaFxOnly ? "JavaFX" : "VLC";
            configureCache(false);
            toggle("audio-short-fade-enabled", false);
            toggle("audio-normalization-enabled", false);
            timingChecks(engine, !javaFxOnly);
            if (!javaFxOnly) { queueCancellationChecks(); speculativeFailureCheck(); }
            completeCacheChecks(javaFxOnly);
            fadeAndLoopChecks(engine, !javaFxOnly);
        }
        settingsPersistence();
        System.out.println("PASS continuous playback: real cold/prepared timing, stale-request cancellation, speculative isolation,"
                + " complete offline/shared-source caching, native volume fades, pause/rate/AB, settings persistence and readability");
    }

    private void prepare() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("continuous-playback-smoke"), "isolated continuous profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), key + " escaped isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "personal API keys entered smoke");
        serviceBase = URI.create(required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(serviceBase.getHost()) && serviceBase.getPort() > 0, "loopback service required");
        aac = Files.readAllBytes(root.resolve("continuous-fixture.m4a"));
        local = new Track("continuous-local-first", "Continuous local first", "Synthetic fixture", 32, "local",
                root.resolve("continuous-fixture.wav").toUri());
        new HotkeySettingsStore(root.resolve("hotkeys.properties")).save(Map.of());
        fixtureServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureServer.setExecutor(workers);
        fixtureServer.createContext("/api/", this::respond);
        fixtureServer.start();
        fixtureBase = URI.create("http://127.0.0.1:" + fixtureServer.getAddress().getPort());
        System.setProperty("lumen.stream.base", fixtureBase.toString());
    }

    private void createApp(boolean javaFxOnly) throws Exception {
        createApp(javaFxOnly, true);
    }

    private void createApp(boolean javaFxOnly, boolean clearQueue) throws Exception {
        phase = "create " + (javaFxOnly ? "JavaFX fallback" : "VLC") + " app";
        fx(() -> {
            if (app != null) { app.stop(); stage.close(); stage = new Stage(); }
            app = new LumenFxApp(); app.startUi(stage, List.of());
            stage.setWidth(1280); stage.setHeight(800);
            if (javaFxOnly) {
                VlcAudioPlayer existing = field("streamingAudio", VlcAudioPlayer.class);
                if (existing != null) existing.close();
                set(app, "streamingAudio", null);
            }
            ListeningReporter reporter = field("listening", ListeningReporter.class);
            set(reporter, "endpoint", fixtureBase.resolve("/api/listening"));
            set(app, "radioClient", new RadioClient(fixtureBase));
            if (clearQueue) invoke("clearQueue", new Class<?>[0]);
        });
        await(10_000, () -> fxValue(() -> "个人曲库已载入".equals(((javafx.scene.control.Label) node("library-status")).getText())),
                "isolated library did not load");
        System.out.println("ENGINE configuration=" + (javaFxOnly ? "actual JavaFX with VLC fallback disabled in test" : "actual VLC available")
                + "; scale=" + fxValue(() -> stage.getOutputScaleX()));
    }

    private void timingChecks(String engine, boolean streaming) throws Exception {
        phase = engine + " cold vs prepared switch";
        toggle("audio-preload-enabled", false);
        Track cold = online(engine + " cold");
        select(List.of(local, cold));
        awaitPlaying(local);
        long coldStart = System.nanoTime(); fx(() -> button("next-button").fire());
        awaitPlaying(cold);
        double coldSeconds = elapsed(coldStart);
        check(fxValue(() -> streaming ? audio() instanceof VlcAudioPlayer : audio() instanceof JavaFxAudioPlayer), "wrong native engine");
        toggle("audio-preload-enabled", true);
        Track prepared = online(engine + " prepared");
        select(List.of(local, prepared)); awaitPlaying(local);
        awaitReady(prepared, streaming);
        assertUnplayed(prepared);
        int heads = stats(prepared).heads.get(), gets = stats(prepared).gets.get();
        long warmStart = System.nanoTime(); fx(() -> button("next-button").fire());
        awaitPlaying(prepared);
        double warmSeconds = elapsed(warmStart);
        check(stats(prepared).heads.get() == heads, "prepared foreground repeated HEAD");
        if (!streaming) check(stats(prepared).gets.get() == gets, "prepared complete JavaFX file downloaded again");
        check(coldSeconds - warmSeconds >= .6, "preparation did not improve actual waiting: cold=" + coldSeconds + ", prepared=" + warmSeconds);
        metrics.add(String.format(Locale.ROOT, "%s,%.3f,%.3f,%.3f", engine, coldSeconds, warmSeconds, coldSeconds - warmSeconds));
        System.out.println("WAIT " + engine + " cold=" + coldSeconds + "s prepared=" + warmSeconds + "s improvement=" + (coldSeconds - warmSeconds));
        check(fxValue(() -> queue().tracks().size() == 2 && queue().index() == 1), "switch added speculative queue entries");
    }

    private void queueCancellationChecks() throws Exception {
        phase = "actual manual insertion cancels delayed preparation";
        Track obsolete = online("obsolete insertion"), replacement = online("manual priority");
        select(List.of(local, obsolete)); awaitPlaying(local); awaitHead(obsolete);
        long load = fxValue(() -> field("loadVersion", Long.class));
        String session = fxValue(this::sessionId);
        fx(() -> invoke("playNextTrack", new Class<?>[] {Track.class}, replacement));
        awaitReady(replacement, true); waitMillis(HEAD_DELAY_MILLIS + 100);
        assertCancelled(obsolete, load, session); assertUnplayed(replacement);
        check(fxValue(() -> queue().tracks().get(1).equals(replacement)), "manual track did not win next position");

        phase = "actual reorder invalidates old prepared source";
        Track oldOrder = online("obsolete order"), newOrder = online("new order");
        select(List.of(local, oldOrder, newOrder)); awaitPlaying(local); awaitHead(oldOrder);
        load = fxValue(() -> field("loadVersion", Long.class)); session = fxValue(this::sessionId);
        fx(() -> invoke("editQueue", new Class<?>[] {Runnable.class, String.class}, (Runnable) () -> queue().reorder(2, 1), "fixture reorder"));
        awaitReady(newOrder, true); waitMillis(HEAD_DELAY_MILLIS + 100);
        assertCancelled(oldOrder, load, session); assertUnplayed(newOrder);

        phase = "repeat-one cancels speculative next without moving current";
        Track modeOld = online("obsolete mode");
        select(List.of(local, modeOld)); awaitPlaying(local); awaitHead(modeOld);
        load = fxValue(() -> field("loadVersion", Long.class)); session = fxValue(this::sessionId);
        fx(() -> { button("repeat-button").fire(); button("repeat-button").fire(); });
        check(fxValue(() -> queue().repeatMode() == PlayerQueue.RepeatMode.ONE), "actual repeat button did not select ONE");
        waitMillis(HEAD_DELAY_MILLIS + 150); assertCancelled(modeOld, load, session);

        phase = "radio start/switch/stop cancels obsolete future";
        Track firstRadio = online("first radio preview"), secondRadio = online("replacement radio preview");
        select(List.of(local)); awaitPlaying(local);
        radioTargets = List.of(firstRadio);
        fx(() -> invoke("startRadio", new Class<?>[] {List.class, String.class, String.class, boolean.class}, List.of(local), "first", "familiar", true));
        awaitHead(firstRadio);
        radioTargets = List.of(secondRadio);
        fx(() -> invoke("startRadio", new Class<?>[] {List.class, String.class, String.class, boolean.class}, List.of(local), "second", "familiar", true));
        awaitReady(secondRadio, true); waitMillis(HEAD_DELAY_MILLIS + 100);
        check(!fxValue(() -> preloader().isReady(firstRadio, true)), "old radio preparation survived switch");
        assertUnplayed(firstRadio); assertUnplayed(secondRadio);
        fx(() -> invoke("stopRadio", new Class<?>[0])); waitMillis(150);
        check(!fxValue(() -> preloader().isReady(secondRadio, true)), "radio stop retained ready preparation");
        System.out.println("PASS real insertion/reorder/repeat/radio edits cancel old HEAD and preserve foreground identity");
    }

    private void speculativeFailureCheck() throws Exception {
        phase = "unplayed unavailable source never changes persistent/foreground state";
        Track failed = online("speculative permanent failure"); stats(failed).unavailable = true;
        select(List.of(local, failed)); awaitPlaying(local); awaitHead(failed);
        await(4000, () -> stats(failed).completedHeads.get() > 0, "negative HEAD did not finish");
        waitMillis(200); assertUnplayed(failed);
        check(!fxValue(() -> field("trackPreferences", TrackPreferencesStore.class).isUnavailable(failed.id())), "speculation marked an unplayed source unavailable");
        fx(() -> button("next-button").fire());
        await(5000, () -> fxValue(() -> field("trackPreferences", TrackPreferencesStore.class).isUnavailable(failed.id())),
                "foreground permanent failure was not reported");
        System.out.println("PASS failure isolation: speculative 404 is quiet; actual selection reports the same permanent failure");
    }

    private void completeCacheChecks(boolean javaFxOnly) throws Exception {
        phase = "complete prepared cache publishes only after actual playback";
        offline = false;
        configureCache(true);
        Track source = online("cached source " + (javaFxOnly ? "JavaFX" : "VLC"));
        Track first = Track.segment(source, "Shared source first segment", "Fixture", 1000, 9000);
        Track second = Track.segment(source, "Shared source second segment", "Fixture", 10000, 18000);
        select(List.of(local, first)); awaitPlaying(local); awaitReady(first, !javaFxOnly);
        check(fxValue(() -> disk().entryCount() == 0), "preparing an unplayed source published persistent entry");
        assertUnplayed(first);
        int preparedHeads = stats(source).heads.get();
        fx(() -> invoke("editQueue", new Class<?>[] {Runnable.class, String.class}, (Runnable) () -> queue().playNext(second), "shared-source swap"));
        awaitReady(second, !javaFxOnly);
        check(stats(source).heads.get() == preparedHeads, "shared-source segment triggered another preparation HEAD");

        fx(() -> button("audio-cache-clear").fire());
        await(4000, () -> fxValue(() -> !button("audio-cache-clear").isDisabled()), "cache clear did not finish");
        check(!fxValue(() -> preloader().isReady(second, !javaFxOnly)), "clear retained stale ready generation");
        awaitReady(second, !javaFxOnly);
        check(fxValue(() -> disk().entryCount() == 0), "cache clear/speculation refilled persistent storage");

        fx(() -> button("next-button").fire()); awaitPlaying(second);
        await(6000, () -> fxValue(() -> disk().entryCount() == 1), "actual prepared playback never retained complete source");
        URI saved = fxValue(() -> disk().lookup(source).orElseThrow());
        check(Files.size(Path.of(saved)) == aac.length, "persistent source is partial or per-segment rather than complete");
        int heads = stats(source).heads.get(), gets = stats(source).gets.get();
        offline = true;
        createApp(javaFxOnly);
        select(List.of(first, second)); awaitPlaying(first);
        check(fxValue(() -> "file".equals(engineUri().getScheme())), "offline cache did not reach real native file playback");
        fx(() -> button("next-button").fire()); awaitPlaying(second);
        check(stats(source).heads.get() == heads && stats(source).gets.get() == gets, "offline/shared segment issued stream HTTP");
        check(fxValue(() -> disk().entryCount() == 1), "shared segments duplicated persisted source");
        screenshot("offline-shared-" + (javaFxOnly ? "javafx" : "vlc") + ".png");
        offline = false;
        toggle("audio-preload-enabled", false);
        configureCache(false);
        System.out.println("PASS complete cache: source deduplication, clear generation, post-PLAYING publication, restart/offline playback and settings");
    }

    private void fadeAndLoopChecks(String engine, boolean vlc) throws Exception {
        phase = engine + " optional gain envelope";
        toggle("audio-preload-enabled", true);
        toggle("audio-normalization-enabled", vlc);
        toggle("audio-short-fade-enabled", true);
        fx(() -> ((Slider) node("volume-slider")).setValue(.2));
        long start = System.nanoTime(); select(List.of(local));
        List<Sample> fade = sampleFor(650, start);
        awaitPlaying(local);
        fade.addAll(sampleFor(300, start));
        saveSamples(engine.toLowerCase(Locale.ROOT) + "-fade-in.csv", fade);
        check(fxValue(() -> vlc ? audio() instanceof VlcAudioPlayer : audio() instanceof JavaFxAudioPlayer), "fade used wrong engine");
        check(fade.stream().anyMatch(value -> value.state().equals("PLAYING") && value.output() > .005 && value.output() < .19), "native fade had no intermediate gain");
        check(fxValue(() -> Math.abs(field("outputVolume", Double.class) - .2) < .005), "fade did not restore user's volume");
        check(fade.stream().allMatch(value -> value.engineVolume() <= .211), "native gain exceeded user volume");
        pausePlayback(); double paused = fxValue(() -> audio().position().toSeconds()); waitMillis(250);
        check(Math.abs(fxValue(() -> audio().position().toSeconds()) - paused) < .06, "fade changed paused position");
        start = System.nanoTime(); fx(() -> button("play-button").fire());
        List<Sample> resume = sampleFor(500, start); saveSamples(engine.toLowerCase(Locale.ROOT) + "-resume-fade.csv", resume);
        check(resume.stream().anyMatch(value -> value.state().equals("PLAYING") && value.output() > .005 && value.output() < .19), "resume did not fade in");
        if (vlc) {
            try {
                await(30_000, () -> fxValue(() -> audio().normalizationStatus().contains("LUFS")), "normalization analysis did not complete");
            } catch (AssertionError failure) {
                throw new AssertionError(failure.getMessage() + "; normalization="
                        + fxValue(() -> audio().normalizationStatus()), failure);
            }
        }
        String normalization = fxValue(() -> audio().normalizationStatus());
        System.out.println("NORMALIZATION " + engine + ": " + normalization);

        phase = engine + " 1.5x AB keeps full user volume and one session";
        fx(() -> {
            button("open-practice").fire(); DialogPane pane = dialog();
            @SuppressWarnings("unchecked") ComboBox<Double> rate = (ComboBox<Double>) pane.lookup("#practice-rate"); rate.setValue(1.5);
            TextField a = (TextField) pane.lookup("#practice-start"), b = (TextField) pane.lookup("#practice-end");
            a.setText("0:30.000"); b.setText("0:32.000"); a.fireEvent(new ActionEvent()); b.fireEvent(new ActionEvent());
            CheckBox enabled = (CheckBox) pane.lookup("#practice-enabled"); if (!enabled.isSelected()) enabled.fire(); closeDialog(pane);
        });
        check(fxValue(() -> Math.abs(audio().playbackRate() - 1.5) < .01), "speed changed when fade enabled");
        String session = fxValue(this::sessionId); long load = fxValue(() -> field("loadVersion", Long.class));
        long begun = System.nanoTime(); List<Sample> loop = sampleFor(4600, begun); saveSamples(engine.toLowerCase(Locale.ROOT) + "-ab-envelope.csv", loop);
        int wraps = 0; double prior = -1;
        for (Sample value : loop) {
            if (!value.state().equals("PLAYING") || value.position() < 29.8) continue;
            if (prior - value.position() > .7) wraps++;
            prior = value.position();
            check(Math.abs(value.output() - .2) < .005, "AB tail was faded or muted before B");
            check(value.load() == load && value.session().equals(session), "AB changed load/listening session");
        }
        check(wraps >= 2, "actual speed/AB did not wrap twice: " + wraps);
        check(normalization.equals(fxValue(() -> audio().normalizationStatus())), "fade/AB changed measured normalization");
        pausePlayback();
        Track next = online(engine + " natural next");
        fx(() -> invoke("editQueue", new Class<?>[] {Runnable.class, String.class}, (Runnable) () -> queue().enqueue(next), "natural future"));
        check(!fxValue(() -> preloader().isReady(next, vlc)), "AB should suppress next-track speculation");
        fx(() -> invoke("clearPracticeLoop", new Class<?>[0]));
        awaitReady(next, vlc);
        fx(() -> invoke("seekToFraction", new Class<?>[] {double.class}, 30.8 / 32.0));
        await(5000, () -> fxValue(() -> Math.abs(audio().position().toSeconds() - 30.8) < .4), "tail seek failed");
        fx(this::observeNativeEnd);
        int priorEnds = observedNativeEnds.get();
        double expectedTailSeconds = fxValue(() -> (audio().duration().toSeconds() - audio().position().toSeconds()) / audio().playbackRate());
        long tailStart = System.nanoTime(); fx(() -> button("play-button").fire());
        List<Sample> tail = sampleFor(2500, tailStart); saveSamples(engine.toLowerCase(Locale.ROOT) + "-natural-tail.csv", tail);
        awaitPlaying(next);
        check(tail.stream().anyMatch(value -> value.load() == load && value.state().equals("PLAYING")
                && value.elapsed() > .35 && value.output() < .19 && value.engineVolume() < .19), "natural tail did not fade");
        check(observedNativeEnds.get() == priorEnds + 1, "natural queue advancement was not driven by one real native EOF");
        double nativeTailSeconds = (observedNativeEndNanos.get() - tailStart) / 1e9;
        check(nativeTailSeconds >= expectedTailSeconds - .12, "fade prematurely ended the old track: native="
                + nativeTailSeconds + ", expected=" + expectedTailSeconds);
        check(fxValue(() -> queue().index() == 1 && queue().tracks().size() == 2 && field("loadVersion", Long.class) == load + 1), "natural EOF advanced more than once or inserted tracks");
        toggle("audio-short-fade-enabled", false);
        check(fxValue(() -> Math.abs(field("outputVolume", Double.class) - .2) < .005), "disabling fade did not restore user volume");
        System.out.println("PASS " + engine + " native gain in/out, pause/resume, unchanged normalization state, 1.5x AB and exact natural queue advancement");
        System.out.println("NATIVE EOF " + engine + " tail=" + nativeTailSeconds + "s expected=" + expectedTailSeconds + "s");
    }

    private void settingsVisuals() throws Exception {
        phase = "new settings at minimum/desktop window";
        for (ThemeController.Mode theme : new ThemeController.Mode[] {ThemeController.Mode.DARK, ThemeController.Mode.LIGHT}) {
            for (int[] size : new int[][] {{860, 600}, {1280, 800}}) {
                fx(() -> {
                    for (int step = 0; step < 8 && field("themeMode", ThemeController.Mode.class) != theme; step++) button("theme-button").fire();
                    stage.setWidth(size[0]); stage.setHeight(size[1]); settings();
                });
                waitMillis(200);
                fx(() -> { settings(); fits(node("audio-preload-enabled")); fits(node("audio-short-fade-enabled")); });
                screenshot(theme.name().toLowerCase(Locale.ROOT) + "-settings-" + size[0] + "x" + size[1] + ".png");
            }
        }
        fx(() -> { stage.setWidth(1280); stage.setHeight(800); button("settings-back").fire(); });
        System.out.println("PASS audio settings readable at 860x600/1280x800, dark/light and actual output DPI");
    }

    private void settingsPersistence() throws Exception {
        phase = "continuous settings survive restart";
        toggle("audio-preload-enabled", false); toggle("audio-short-fade-enabled", true);
        fx(() -> {
            button("open-practice").fire(); DialogPane pane = dialog();
            TextField a = (TextField) pane.lookup("#practice-start"), b = (TextField) pane.lookup("#practice-end");
            a.setText("0:01.000"); b.setText("0:03.000"); a.fireEvent(new ActionEvent()); b.fireEvent(new ActionEvent());
            CheckBox enabled = (CheckBox) pane.lookup("#practice-enabled"); if (!enabled.isSelected()) enabled.fire(); closeDialog(pane);
        });
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(required("LUMEN_PREFERENCES_PATH")))) { values.load(reader); }
        check("false".equals(values.getProperty("audio.preload")) && "true".equals(values.getProperty("audio.shortFade")), "settings were not saved");
        createApp(false, false);
        check(fxValue(() -> !field("preloadEnabled", Boolean.class) && field("shortFadeEnabled", Boolean.class)), "restart lost audio settings");
        check(fxValue(() -> audio().state() != JavaFxAudioPlayer.State.PLAYING), "settings restart played automatically");
        check(fxValue(() -> !field("practiceLoop", PracticeLoop.class).enabled()
                && field("practiceLoop", PracticeLoop.class).startMillis() == -1
                && field("practiceLoop", PracticeLoop.class).endMillis() == -1), "restart restored temporary AB marks");
        fx(this::settings);
        waitMillis(250);
        fx(() -> {
            settings(); fits(node("audio-preload-enabled")); fits(node("audio-short-fade-enabled"));
            check(!((CheckBox) node("audio-preload-enabled")).isSelected()
                    && ((CheckBox) node("audio-short-fade-enabled")).isSelected(), "restored toggles do not match preferences");
        });
        screenshot("settings-restored.png");
    }

    private void assertUnplayed(Track track) throws Exception {
        check(!listened.contains(track.id()) && !listened.contains(track.audioSource().id()), "speculation sent listening samples: " + track.id());
        check(!fxValue(() -> field("trackPreferences", TrackPreferencesStore.class).isUnavailable(track.id())), "speculation marked unavailable");
    }

    private void assertCancelled(Track obsolete, long load, String session) throws Exception {
        check(!fxValue(() -> preloader().isReady(obsolete, true)), "old source remained ready after mutation");
        check(fxValue(() -> field("loadVersion", Long.class) == load && queue().current().equals(local) && sessionId().equals(session)),
                "obsolete request changed foreground track/load/listening session");
        assertUnplayed(obsolete);
    }

    private void select(List<Track> tracks) throws Exception {
        fx(() -> { invoke("stopRadio", new Class<?>[0]); queue().select(tracks, 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true); });
    }
    private void pausePlayback() throws Exception {
        fx(() -> { if (audio().state() == JavaFxAudioPlayer.State.PLAYING) button("play-button").fire(); });
        await(4000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause failed");
    }
    private void awaitPlaying(Track track) throws Exception {
        await(15_000, () -> fxValue(() -> track.equals(field("loadedTrack", Track.class))
                && audio().state() == JavaFxAudioPlayer.State.PLAYING && playbackPosition() > .1), "media did not play " + track.title());
    }
    private void awaitReady(Track track, boolean streaming) throws Exception {
        await(10_000, () -> fxValue(() -> preloader().isReady(track, streaming)), "future did not prepare " + track.title());
    }
    private void awaitHead(Track track) throws Exception {
        await(4000, () -> stats(track).heads.get() > 0, "delayed HEAD never began " + track.title());
    }
    private void configureCache(boolean enabled) throws Exception {
        toggle("audio-preload-enabled", false);
        fx(() -> { settings(); ((CheckBox) node("audio-cache-enabled")).setSelected(enabled); button("audio-cache-save").fire(); });
        await(5000, () -> fxValue(() -> !button("audio-cache-save").isDisabled() && disk().enabled() == enabled), "cache configuration did not apply");
        fx(() -> button("audio-cache-clear").fire());
        try {
            await(5000, () -> fxValue(() -> !button("audio-cache-clear").isDisabled() && disk().entryCount() == 0), "cache clear did not apply");
        } catch (AssertionError failure) {
            String diagnostics = fxValue(() -> "entries=" + disk().entryCount() + "; cache UI="
                    + button("audio-cache-clear").getParent().getParent().lookupAll(".label").stream()
                        .map(value -> ((javafx.scene.control.Label) value).getText()).toList());
            throw new AssertionError(failure.getMessage() + "; " + diagnostics, failure);
        }
        toggle("audio-preload-enabled", true);
    }
    private void toggle(String id, boolean selected) throws Exception {
        fx(() -> { settings(); CheckBox value = (CheckBox) node(id); if (value.isSelected() != selected) value.fire(); });
    }
    private void settings() {
        button("sidebar-settings").fire();
        ((TitledPane) node("settings-audio")).setExpanded(true);
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        ScrollPane scroll = (ScrollPane) node("settings-scroll");
        Node section = node("settings-audio");
        double excess = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
        if (excess > 0) scroll.setVvalue(Math.max(0, Math.min(1, (section.getBoundsInParent().getMinY() - 10) / excess)));
        stage.getScene().getRoot().layout();
    }
    private static void fits(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds()); Scene scene = node.getScene();
        check(node.isVisible() && node.isManaged() && bounds.getMinX() >= 0 && bounds.getMaxX() <= scene.getWidth() + 1
                && bounds.getMinY() >= 0 && bounds.getMaxY() <= scene.getHeight() + 1, "clipped settings control: " + node.getId());
        Node text = node.lookup(".text");
        if (text != null) check(text.getLayoutBounds().getWidth() < node.getLayoutBounds().getWidth() - 8, "settings label does not fit");
    }
    private Track online(String title) {
        String bvid = "BV" + String.format(Locale.ROOT, "%010d", identity.incrementAndGet());
        return new Track("bilibili:" + bvid + ":p1", title, "Loopback fixture", 32, "bilibili", null, bvid, 1);
    }
    private Requests stats(Track track) { return requests.computeIfAbsent(track.audioSource().id(), ignored -> new Requests()); }
    private List<Sample> sampleFor(long millis, long started) throws Exception {
        List<Sample> values = new ArrayList<>(); long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            values.add(fxValue(() -> new Sample(elapsed(started), audio().state().name(), audio().position().toSeconds(),
                    field("outputVolume", Double.class), engineVolume(), field("loadVersion", Long.class), queue().index(), sessionId(), observedNativeEnds.get())));
            waitMillis(15);
        }
        return values;
    }
    private void observeNativeEnd() {
        Runnable ended = () -> { observedNativeEndNanos.set(System.nanoTime()); observedNativeEnds.incrementAndGet(); };
        try {
            if (audio() instanceof VlcAudioPlayer) {
                uk.co.caprica.vlcj.player.base.MediaPlayer nativePlayer = (uk.co.caprica.vlcj.player.base.MediaPlayer) get(audio(), "player");
                nativePlayer.events().addMediaPlayerEventListener(new uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
                    @Override public void finished(uk.co.caprica.vlcj.player.base.MediaPlayer player) { ended.run(); }
                });
            } else {
                javafx.scene.media.MediaPlayer nativePlayer = (javafx.scene.media.MediaPlayer) get(audio(), "player");
                Runnable existing = nativePlayer.getOnEndOfMedia();
                nativePlayer.setOnEndOfMedia(() -> { ended.run(); if (existing != null) existing.run(); });
            }
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private double engineVolume() {
        try {
            if (audio() instanceof VlcAudioPlayer) {
                uk.co.caprica.vlcj.player.base.MediaPlayer nativePlayer = (uk.co.caprica.vlcj.player.base.MediaPlayer) get(audio(), "player");
                return nativePlayer == null ? 0 : nativePlayer.audio().volume() / 100.0;
            }
            javafx.scene.media.MediaPlayer nativePlayer = (javafx.scene.media.MediaPlayer) get(audio(), "player");
            return nativePlayer == null ? 0 : nativePlayer.getVolume();
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private URI engineUri() {
        try {
            if (audio() instanceof VlcAudioPlayer) return (URI) get(audio(), "currentUri");
            return URI.create(((javafx.scene.media.MediaPlayer) get(audio(), "player")).getMedia().getSource());
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private String sessionId() {
        try {
            Object session = get(field("listening", ListeningReporter.class), "current");
            return session == null ? "none" : (String) get(session, "id");
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private void saveSamples(String name, List<Sample> samples) throws Exception {
        List<String> rows = new ArrayList<>(List.of("elapsedSeconds,state,sourceSeconds,outputVolume,nativeVolume,loadVersion,queueIndex,sessionId,nativeEnds"));
        for (Sample value : samples) rows.add(String.format(Locale.ROOT, "%.4f,%s,%.4f,%.5f,%.5f,%d,%d,%s,%d", value.elapsed(), value.state(),
                value.position(), value.output(), value.engineVolume(), value.load(), value.index(), value.session(), value.nativeEnds()));
        Files.write(root.resolve(name), rows);
    }
    private void screenshot(String name) throws Exception {
        fx(() -> {
            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            WritableImage shot = stage.getScene().snapshot(null);
            BufferedImage bitmap = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) bitmap.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Path output = root.resolve(name); check(ImageIO.write(bitmap, "png", output.toFile()) && Files.size(output) > 1000, "empty screenshot");
            System.out.println("SCREENSHOT: " + output);
        });
    }
    private void respond(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        network.add(System.nanoTime() + "," + method + "," + path + ",start");
        try {
            if (path.equals("/api/listening")) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
                var track = (org.w3c.dom.Element) xml.getElementsByTagName("track").item(0); listened.add(track.getAttribute("id"));
                var response = proxy.send(HttpRequest.newBuilder(serviceBase.resolve("/api/listening")).timeout(java.time.Duration.ofSeconds(5))
                        .header("Content-Type", "application/xml; charset=utf-8").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                send(exchange, response.statusCode(), "application/xml; charset=utf-8", response.body()); return;
            }
            if (path.equals("/api/radio")) {
                exchange.getRequestBody().readAllBytes();
                StringBuilder xml = new StringBuilder("<radio status=\"ready\" message=\"fixture\" intents=\"fixture\">");
                for (Track track : radioTargets) xml.append("<item reason=\"Synthetic radio future\">").append(LibraryClient.trackXml(track)).append("</item>");
                send(exchange, 200, "application/xml; charset=utf-8", xml.append("</radio>").toString().getBytes(StandardCharsets.UTF_8)); return;
            }
            if (!path.endsWith("/stream")) { send(exchange, 404, "text/plain", new byte[0]); return; }
            String id = URLDecoder.decode(path.substring("/api/tracks/".length(), path.length() - "/stream".length()), StandardCharsets.UTF_8);
            Requests count = requests.computeIfAbsent(id, ignored -> new Requests());
            if (method.equals("HEAD")) { count.heads.incrementAndGet(); Thread.sleep(HEAD_DELAY_MILLIS); }
            else count.gets.incrementAndGet();
            if (offline || count.unavailable) {
                exchange.getResponseHeaders().set("X-Lumen-Error-Code", count.unavailable ? "VIDEO_NOT_FOUND" : "UPSTREAM_UNAVAILABLE");
                exchange.sendResponseHeaders(count.unavailable ? 404 : 503, -1); count.completedHeads.incrementAndGet(); return;
            }
            int start = 0, end = aac.length - 1;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", -1);
                if (parts.length != 2) throw new IllegalArgumentException("unsupported fixture range");
                if (parts[0].isEmpty()) start = Math.max(0, aac.length - Integer.parseInt(parts[1]));
                else { start = Integer.parseInt(parts[0]); if (!parts[1].isEmpty()) end = Math.min(end, Integer.parseInt(parts[1])); }
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4"); exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (start >= aac.length || start > end) { exchange.getResponseHeaders().set("Content-Range", "bytes */" + aac.length); exchange.sendResponseHeaders(416, -1); return; }
            int length = end - start + 1; exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
            if (method.equals("HEAD")) { exchange.sendResponseHeaders(200, -1); count.completedHeads.incrementAndGet(); }
            else {
                if (range != null) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + aac.length);
                exchange.sendResponseHeaders(range == null ? 200 : 206, length); exchange.getResponseBody().write(aac, start, length);
            }
            network.add(System.nanoTime() + "," + method + "," + path + ",complete");
        } catch (Exception error) {
            network.add(System.nanoTime() + "," + method + "," + path + ",closed-" + error.getClass().getSimpleName());
        } finally { exchange.close(); }
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws Exception {
        exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
    }
    private void finish(int result) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); });
            if (root != null) {
                Files.write(root.resolve("waiting-metrics.csv"), metrics);
                synchronized (network) { Files.write(root.resolve("audio-http.csv"), List.copyOf(network)); }
            }
        } catch (Exception error) { error.printStackTrace(System.err); result = 1; }
        if (fixtureServer != null) fixtureServer.stop(0); workers.shutdownNow(); Platform.exit(); System.exit(result);
    }
    private static DialogPane dialog() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                .filter(pane -> pane.lookup("#practice-start") != null).findFirst().orElseThrow();
    }
    private static void closeDialog(DialogPane pane) { ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire(); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private NextTrackPreloader preloader() { return field("nextPreloader", NextTrackPreloader.class); }
    private PersistentAudioCache disk() { return field("persistentCache", PersistentAudioCache.class); }
    private double playbackPosition() { return ((javafx.util.Duration) invoke("playbackPosition", new Class<?>[0])).toSeconds(); }
    private Node node(String id) { Node result = stage.getScene().getRoot().lookup("#" + id); check(result != null, "missing UI #" + id); return result; }
    private Button button(String id) { return (Button) node(id); }
    private <T> T field(String name, Class<T> type) { try { return type.cast(get(app, name)); } catch (Exception error) { throw new AssertionError(name, error); } }
    private static Object get(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private Object invoke(String name, Class<?>[] types, Object... args) { try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(app, args); } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); } }
    private void await(long millis, CheckedBoolean condition, String error) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) { if (condition.get()) return; waitMillis(20); }
        throw new AssertionError(error + "; phase=" + phase + "; state=" + fxValue(() -> audio().state())
                + "; position=" + fxValue(() -> audio().position().toSeconds()));
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> value = new CompletableFuture<>(); Platform.runLater(() -> { try { value.complete(action.get()); } catch (Throwable error) { value.completeExceptionally(error); } });
        return value.get(10, TimeUnit.SECONDS);
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000_000.0; }
    private static void waitMillis(long millis) throws InterruptedException { Thread.sleep(millis); }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("missing " + name); return value; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
