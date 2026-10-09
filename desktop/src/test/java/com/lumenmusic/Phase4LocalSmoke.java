package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Real FX/VLC local chain; a gated runtime and loopback XML service supply controlled failures. */
public final class Phase4LocalSmoke extends Application {
    private static final String AUTOMATIC = "[00:00.000]Synthetic automatic first\n[00:10.000]Synthetic automatic second";
    private static final String MANUAL = "[00:00.000]Synthetic manual first\n[00:10.000]Synthetic manual second";
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger pulses = new AtomicInteger(), lyricsRequests = new AtomicInteger();
    private final CountDownLatch allowFailure = new CountDownLatch(1), startupEntered = new CountDownLatch(1);
    private final StringBuilder evidence = new StringBuilder();
    private Stage stage;
    private LumenFxApp app;
    private Path root, preferences, automaticFile;
    private Track first, second;
    private HttpServer service;
    private StartupCoordinator<AutoCloseable> runtime;
    private AnimationTimer heartbeat;
    private volatile String phase = "prepare";
    private String selectedTheme;
    private byte[] damagedPreferences, damagedLyrics;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(150_000); } catch (InterruptedException error) { return; }
            if (finished.compareAndSet(false, true)) { System.err.println("FAIL phase4 local watchdog: " + phase); System.exit(1); }
        }, "phase4-local-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) { System.err.println("FAIL phase4 local phase: " + phase); error.printStackTrace(System.err); finish(1); }
        }, "phase4-local-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        root = Phase4LocalSmokeLauncher.profile();
        preferences = Path.of(Phase4LocalSmokeLauncher.required("LUMEN_PREFERENCES_PATH")).toAbsolutePath().normalize();
        check(getParameters().getRaw().size() == 2, "Two synthetic FLAC inputs required");
        Path firstFile = Path.of(getParameters().getRaw().get(0)).toAbsolutePath().normalize();
        Path secondFile = Path.of(getParameters().getRaw().get(1)).toAbsolutePath().normalize();
        check(firstFile.startsWith(root) && secondFile.startsWith(root), "Audio fixture escapes profile");
        first = new Track("phase4-local-first", "Phase4 first synthetic", "Synthetic fixture", 24, "local", firstFile.toUri());
        second = new Track("phase4-local-second", "Phase4 second synthetic", "Synthetic fixture", 24, "local", secondFile.toUri());
        var catalogue = new LocalMusicStore.State(List.of(first, second), Map.of());
        new LocalMusicStore(root.resolve("local-music.xml")).save(catalogue);
        damagedPreferences = ("theme=light\nbroken=" + "\\" + "u12XZ\n").getBytes(StandardCharsets.ISO_8859_1);
        Files.write(preferences, damagedPreferences);
        LyricsSelectionStore lyricStore = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory());
        lyricStore.saveAutomatic(first, new LyricsSelectionStore.Selection(first.title(), first.artist(), AUTOMATIC, 0));
        try (var paths = Files.list(LyricsSelectionStore.defaultDirectory())) {
            automaticFile = paths.filter(path -> path.getFileName().toString().endsWith(".automatic.properties")).findFirst().orElseThrow();
        }
        damagedLyrics = ("PRIVATE_PHASE4_LYRIC_CANARY=" + "\\" + "uBADX\n").getBytes(StandardCharsets.ISO_8859_1);
        Files.write(automaticFile, damagedLyrics);
        prepareRuntime();

        phase = "damaged preferences window and settings notice";
        fx(() -> {
            app = new LumenFxApp();
            heartbeat = new AnimationTimer() { @Override public void handle(long now) { pulses.incrementAndGet(); } };
            heartbeat.start();
            app.startUi(stage, List.of());
            check(stage.isShowing(), "Corrupted Unicode prevented visible startup");
            click("sidebar-settings");
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Label notice = (Label) stage.getScene().lookup("#appearance-recovery-notice");
            check(notice != null && notice.isVisible() && !notice.getText().isBlank(), "Settings omit appearance recovery notice");
            Object themeBefore = field("themeMode").get(app);
            click("theme-button");
            check(!themeBefore.equals(field("themeMode").get(app)), "Theme button did not change actual app theme");
            selectedTheme = field("themeMode").get(app).toString().toLowerCase(java.util.Locale.ROOT);
            click("nav-local");
        });
        check(startupEntered.await(3, TimeUnit.SECONDS), "Runtime gate was not reached");
        await(() -> fxValue(() -> ((List<?>) field("localTracks").get(app)).size() == 2), "Catalogue did not restore");
        mark("Damaged Unicode preferences showed window/settings recovery notice; theme button changed mode");

        phase = "FLAC while service waits";
        check(fxValue(() -> !field("serviceInitialized").getBoolean(app)), "Wait fixture unexpectedly initialized service");
        exerciseLocal("SERVICE_WAITING");
        check(allowFailure.getCount() == 1 && !runtime.start().isDone(), "Playback accidentally released runtime wait");

        phase = "FLAC after service failure";
        allowFailure.countDown();
        await(() -> fxValue(() -> {
            Button retry = (Button) field("retryStartupButton").get(app);
            return retry.isVisible() && !retry.isDisabled();
        }), "Runtime failure was not visible/retryable");
        check(fxValue(() -> !field("serviceInitialized").getBoolean(app)), "Failed service marked available");
        exerciseLocal("SERVICE_FAILED");

        phase = "damaged automatic lyrics loopback recovery";
        fx(() -> ((Button) field("retryStartupButton").get(app)).fire());
        await(() -> fxValue(() -> field("serviceInitialized").getBoolean(app)), "Retry did not reuse loopback fixture service");
        play(0);
        await(() -> fxValue(() -> ((List<?>) field("activeLyrics").get(app)).equals(Lyrics.parse(AUTOMATIC))), "Automatic loopback lyrics were not applied");
        await(() -> {
            try { return lyricStore.load(first).map(selection -> selection.lrc().equals(AUTOMATIC)).orElse(false); }
            catch (LyricsSelectionStore.CorruptRecordException pendingWrite) { return false; }
        }, "Automatic choice was not saved");
        Path recovery = automaticFile.resolveSibling(automaticFile.getFileName() + ".corrupt");
        check(Arrays.equals(damagedLyrics, Files.readAllBytes(recovery)), "Automatic lyric recovery bytes differ");
        check(fxValue(() -> ((Label) field("lyricsStatus").get(app)).getText().contains("原件已保留")), "Successful automatic recovery hid warning");
        var diagnostics = new DiagnosticLog(root.resolve("diagnostics.xml"));
        check(diagnostics.recent().stream().anyMatch(event -> event.code().equals("LYRICS_ERROR")), "LYRICS_ERROR diagnostic missing");
        String diagnosticBytes = Files.readString(root.resolve("diagnostics.xml"));
        for (String canary : List.of("PRIVATE_PHASE4_LYRIC_CANARY", first.title(), root.toString()))
            check(!diagnosticBytes.contains(canary), "Diagnostics contain lyric/track/path canary");
        check(lyricsRequests.get() > 0, "Automatic lyric fixture did not receive a real loopback request");
        mark("Damaged automatic lyrics used actual loopback search/apply/save; original bytes recovered; persistent warning and fixed LYRICS_ERROR verified");

        phase = "manual lyric priority";
        var manual = new LyricsSelectionStore.Selection("Manual fixture", "Synthetic", MANUAL, 350,
                LyricsSelectionStore.Origin.MANUAL, 1500);
        lyricStore.save(first, manual);
        int beforeRequests = lyricsRequests.get();
        play(0);
        await(() -> fxValue(() -> ((List<?>) field("activeLyrics").get(app)).equals(Lyrics.parse(MANUAL))), "Manual lyrics were not restored first");
        check(fxValue(() -> field("lyricOffsetMillis").getLong(app) == 350 && field("lyricStartMillis").getLong(app) == 1500), "Manual timing lost");
        Thread.sleep(300);
        check(lyricsRequests.get() == beforeRequests, "Manual selection unexpectedly searched automatic candidates");
        mark("Manual lyric selection retained priority and restored offset/start without another candidate request");

        phase = "pause, close, persisted preferences and playback";
        await(() -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING), "Final manual lyric track not playing");
        fx(() -> click("play-button"));
        await(() -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "Final pause failed");
        fx(() -> invoke("seekToFraction", new Class<?>[] { double.class }, .25));
        await(() -> fxValue(() -> Math.abs(audio().position().toSeconds() - 6) <= 1), "Final paused position incorrect");
        double finalPosition = fxValue(() -> audio().position().toSeconds());
        AudioPlayer finalAudio = fxValue(this::audio);
        long closeStart = System.nanoTime();
        fx(() -> { heartbeat.stop(); app.stop(); });
        long closeReturnMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStart);
        ((CompletableFuture<?>) field("shutdownComplete").get(app)).get(12, TimeUnit.SECONDS);
        Field closed = VlcAudioPlayer.class.getDeclaredField("closed");
        closed.setAccessible(true);
        check(closed.getBoolean(finalAudio), "VLC player not released on exit");
        var playback = new PlaybackStateStore(root.resolve("playback-state.xml")).read().orElseThrow();
        check(playback.tracks().get(playback.index()).id().equals(first.id())
                && Math.abs(playback.positionSeconds() - finalPosition) <= 1, "Exit did not persist paused track/position");
        check(new LocalMusicStore(root.resolve("local-music.xml")).read().equals(catalogue), "Local catalogue changed during smoke");
        check(new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory()).load(first).orElseThrow().equals(manual), "Manual lyric restart persistence changed");
        var appearance = new AppearancePreferencesStore(preferences).load();
        check(appearance.warning().isEmpty() && selectedTheme.equals(appearance.properties().getProperty("theme")), "Selected theme did not reload");
        List<Path> recoveries;
        try (var paths = Files.list(preferences.getParent())) {
            recoveries = paths.filter(path -> path.getFileName().toString().startsWith(preferences.getFileName() + ".damaged-")).toList();
        }
        check(recoveries.size() == 1 && Arrays.equals(damagedPreferences, Files.readAllBytes(recoveries.get(0))), "Exit lost original damaged preference bytes");
        mark("Exit released VLC and completed final writes; paused playback, theme, manual lyrics and catalogue reload; original damaged preferences retained. Close return ms=" + closeReturnMillis);
        evidence.append("Scope: development classes; real native VLC states/time; synthetic data; no acoustic listening assessment; no installed-version acceptance.\n");
        Files.writeString(root.resolve("phase4-local-evidence.txt"), evidence.toString());
        System.out.print(evidence);
    }

    private void exerciseLocal(String label) throws Exception {
        int beforePulses = pulses.get();
        for (int selected : List.of(0, 1)) {
            play(selected);
            await(() -> fxValue(() -> audio() instanceof VlcAudioPlayer && audio().state() == JavaFxAudioPlayer.State.PLAYING
                    && audio().position().toSeconds() > .4), label + " FLAC did not reach real VLC PLAYING");
            fx(() -> {
                Field uri = VlcAudioPlayer.class.getDeclaredField("currentUri");
                uri.setAccessible(true);
                check(uri.get(audio()).equals((selected == 0 ? first : second).mediaUri()), label + " loaded the wrong FLAC file");
            });
            double before = fxValue(() -> audio().position().toSeconds());
            await(() -> fxValue(() -> audio().position().toSeconds() >= before + .5), label + " position did not advance");
            fx(() -> click("play-button"));
            await(() -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), label + " pause failed");
            fx(() -> traceAudio(label + " before paused seek"));
            fx(() -> invoke("seekToFraction", new Class<?>[] { double.class }, .5));
            fx(() -> traceAudio(label + " immediately after paused seek"));
            try {
                await(() -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(audio().position().toSeconds() - 12) <= 1), label + " paused seek failed");
            } catch (AssertionError error) {
                fx(() -> traceAudio(label + " failed paused seek"));
                throw error;
            }
            double paused = fxValue(() -> audio().position().toSeconds());
            Thread.sleep(300);
            check(Math.abs(fxValue(() -> audio().position().toSeconds()) - paused) < .4, label + " paused clock advanced");
            fx(() -> click("play-button"));
            await(() -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > paused + .4), label + " resume failed");
        }
        check(pulses.get() > beforePulses + 8, label + " FX heartbeat stalled");
        mark(label + ": two FLAC tracks through production select/load; VLC PLAYING/time advances; pause/seek/resume/switch; FX heartbeat advanced " + (pulses.get() - beforePulses));
    }

    private void traceAudio(String label) throws Exception {
        VlcAudioPlayer audio = (VlcAudioPlayer) audio();
        Field playerField = VlcAudioPlayer.class.getDeclaredField("player");
        playerField.setAccessible(true);
        var nativePlayer = (uk.co.caprica.vlcj.player.base.MediaPlayer) playerField.get(audio);
        System.out.println("AUDIO_TRACE " + label + " state=" + audio.state() + " position=" + audio.position().toSeconds()
                + " duration=" + audio.duration().toSeconds() + " nativeState=" + nativePlayer.status().state()
                + " nativeTime=" + nativePlayer.status().time() + " nativeLength=" + nativePlayer.status().length()
                + " seekable=" + nativePlayer.status().isSeekable() + " nativePosition=" + nativePlayer.status().position()
                + " pendingResume=" + field("pendingResumeSeconds").get(app)
                + " pendingLocalSeek=" + field("pendingLocalSeek").get(app) + " seekVersion=" + field("seekVersion").get(app));
    }

    private void prepareRuntime() throws Exception {
        URI base = URI.create(Phase4LocalSmokeLauncher.required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(base.getHost()) && base.getPort() > 0, "Isolated loopback port required");
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", base.getPort()), 0);
        service.createContext("/api/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/api/health" -> "<health status=\"ok\" provider=\"demo\"/>";
                case "/api/library" -> "<library version=\"1\"><favorites/><playlists/></library>";
                case "/api/ai-settings" -> "<ai-settings enabled=\"false\" keyConfigured=\"false\"/>";
                case "/api/lyrics" -> "<lyrics autoId=\"1\"><candidate id=\"1\" title=\"Automatic fixture\" artist=\"Synthetic\" duration=\"24\" score=\"100\" reason=\"fixture\"><lrc>" + AUTOMATIC + "</lrc></candidate></lyrics>";
                default -> "<tracks/>";
            };
            if (path.equals("/api/lyrics")) lyricsRequests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        service.start();
        check(runtimeField().get(null) == null, "Fixture must use direct launcher without existing runtime");
        Constructor<?> constructor = Class.forName("com.lumenmusic.LumenLauncher$RuntimeServices")
                .getDeclaredConstructor(ManagedPythonService.class, ManagedStreamProxy.class);
        constructor.setAccessible(true);
        AtomicInteger attempts = new AtomicInteger();
        runtime = new StartupCoordinator<>(() -> {
            if (attempts.incrementAndGet() == 1) {
                startupEntered.countDown();
                if (!allowFailure.await(60, TimeUnit.SECONDS)) throw new IOException("Controlled startup gate timed out");
                throw new IOException("Controlled service startup failure");
            }
            ManagedPythonService external = ManagedPythonService.start(base, root.resolve("absent-source"),
                    "absent-python", "demo", root.resolve("loopback-service.log"));
            return (AutoCloseable) constructor.newInstance(external, null);
        });
        runtimeField().set(null, runtime);
    }

    private void play(int index) throws Exception {
        fx(() -> { click("nav-local"); invoke("select", new Class<?>[] { List.class, int.class }, List.of(first, second), index); });
    }
    private AudioPlayer audio() throws Exception { return (AudioPlayer) field("audio").get(app); }
    private void click(String id) { ((ButtonBase) stage.getScene().lookup("#" + id)).fire(); }
    private Field field(String name) throws Exception { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Field runtimeField() throws Exception { Field field = LumenLauncher.class.getDeclaredField("runtime"); field.setAccessible(true); return field; }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, args);
    }
    private void await(CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(50); }
        throw new AssertionError(message);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(8, TimeUnit.SECONDS);
    }
    private void mark(String text) { evidence.append("PASS ").append(text).append('\n'); System.out.println("CHECK " + text); }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        allowFailure.countDown();
        try { fx(() -> { if (heartbeat != null) heartbeat.stop(); if (app != null) app.stop(); for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        if (runtime != null) runtime.close();
        if (service != null) service.stop(0);
        System.exit(code);
    }
    private static void check(boolean condition, String message) { Phase4LocalSmokeLauncher.check(condition, message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
}
