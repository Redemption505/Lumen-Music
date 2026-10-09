package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.scene.image.WritableImage;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javax.imageio.ImageIO;

/** The first app downloads AAC; a fresh app must play that disk entry with the audio fixture closed. */
public final class CachePlaybackSmoke extends Application {
    private final Track track = new Track("bilibili:BV1nhMuzaEJr:p1", "Cache playback fixture", "Local fixture",
            32, "bilibili", null, "BV1nhMuzaEJr", 1);
    private final AtomicInteger heads = new AtomicInteger(), gets = new AtomicInteger(), offlineStreams = new AtomicInteger();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final ExecutorService httpWorkers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "cache-playback-fixture"); thread.setDaemon(true); return thread;
    });
    private HttpServer server;
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private byte[] fixture;
    private URI cachedUri;
    private InetSocketAddress address;
    private volatile String phase = "startup";
    private int headsBefore, getsBefore;
    private JavaFxAudioPlayer.State lastObservedState;
    private long lastObservedSecond = -1;
    private int lastObservedEntries = -1;
    private double maxObservedPosition;

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(60_000); }
            catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL cache playback timeout: " + phase);
                System.exit(1);
            }
        }, "cache-playback-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        safely(() -> {
            Path library = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize();
            root = library.getParent();
            check(root != null && !Files.exists(library), "A fresh isolated library is required");
            for (String variable : List.of("LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH"))
                check(Path.of(required(variable)).toAbsolutePath().normalize().getParent().equals(root),
                        variable + " must stay inside the isolated profile");
            check(Path.of(required("LOCALAPPDATA")).toAbsolutePath().normalize().equals(root), "LOCALAPPDATA must be isolated");
            check(!Files.exists(root.resolve("audio-cache")), "Refusing an existing audio cache");
            fixture = Files.readAllBytes(Path.of(getParameters().getRaw().get(0)));
            URI base = URI.create(required("LUMEN_SERVICE_URL"));
            check("http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0,
                    "The fixture service must use a fresh localhost port");
            address = new InetSocketAddress("127.0.0.1", base.getPort());
            new HotkeySettingsStore(root.resolve("hotkeys.properties")).save(Map.of());
            startServer(false);
            app = new LumenFxApp(); stage = initialStage;
            verifyCodeSource();
            app.startUi(stage, List.of());
            PersistentAudioCache disk = field("persistentCache", PersistentAudioCache.class);
            check(!disk.enabled(), "Persistent cache must be disabled by default");
            disk.configure(true, 16L * 1024 * 1024);
            playCurrent();
            phase = "first VLC playback and complete background download";
            waitFor(20_000, () -> audio() instanceof VlcAudioPlayer && audio().state() == JavaFxAudioPlayer.State.PLAYING
                    && audio().position().toSeconds() > 1 && disk.entryCount() == 1, () -> {
                cachedUri = disk.lookup(track).orElseThrow(() -> new AssertionError("No complete disk entry"));
                check(Files.size(Path.of(cachedUri)) == fixture.length, "Cached audio must be the complete AAC file");
                check(heads.get() >= 1 && gets.get() >= 1, "The first playback did not use the fixture HTTP audio");
                TitledPane settings = (TitledPane) stage.getScene().lookup("#sidebar-settings");
                check(settings != null, "Settings sidebar is missing");
                settings.setExpanded(true);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                TitledPane audioSettings = (TitledPane) stage.getScene().lookup("#settings-audio");
                check(audioSettings != null, "Audio settings are missing");
                audioSettings.setExpanded(true);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                Label usage = (Label) stage.getScene().lookup("#audio-cache-usage");
                check(usage != null && usage.getText().contains("1 \u9996"),
                        "Cache usage did not refresh after complete download: " + (usage == null ? "missing" : usage.getText()));
                savePreview("cache-online-playback.png");
                app.stop(); stage.close();
                headsBefore = heads.get(); getsBefore = gets.get();
                server.stop(0);
                // The audio fixture is closed. A separate error-only server counts forbidden network fallbacks.
                startServer(true);
                phase = "fresh app replays persisted AAC while fixture audio is offline";
                app = new LumenFxApp(); stage = new Stage();
                verifyCodeSource(); app.startUi(stage, List.of());
                PersistentAudioCache restored = field("persistentCache", PersistentAudioCache.class);
                check(restored.enabled() && restored.entryCount() == 1, "Disk cache did not survive app restart");
                playCurrent();
                waitFor(10_000, () -> audio() instanceof VlcAudioPlayer && audio().state() == JavaFxAudioPlayer.State.PLAYING
                        && audio().position().toSeconds() > 2.2, () -> {
                    Field uri = VlcAudioPlayer.class.getDeclaredField("currentUri"); uri.setAccessible(true);
                    check(cachedUri.equals(uri.get(audio())), "Replay must pass the cached file URI to real VLC");
                    check(offlineStreams.get() == 0, "Cached replay attempted an audio API preflight or GET");
                    check(heads.get() == headsBefore && gets.get() == getsBefore, "Original HTTP fixture was requested again");
                    savePreview("cache-offline-playback.png");
                    System.out.println("PASS complete cache playback: first real HTTP AAC playback + complete background cache;"
                            + " fresh app offline VLC file playback advanced to " + audio().position().toSeconds()
                            + "s; no audio HEAD/GET on replay; initial requests=" + headsBefore + "/" + getsBefore);
                    succeed();
                });
            });
        });
    }

    private void playCurrent() throws Exception {
        maxObservedPosition = 0; lastObservedState = null; lastObservedSecond = -1; lastObservedEntries = -1;
        PlayerQueue queue = field("queue", PlayerQueue.class);
        queue.select(List.of(track), 0); queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
        Method load = LumenFxApp.class.getDeclaredMethod("loadCurrent", boolean.class); load.setAccessible(true);
        load.invoke(app, true);
    }

    private void savePreview(String name) throws IOException {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        WritableImage image = stage.getScene().snapshot(null);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        var pixels = image.getPixelReader();
        for (int y = 0; y < output.getHeight(); y++)
            for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, pixels.getArgb(x, y));
        Path file = root.resolve(name);
        check(ImageIO.write(output, "png", file.toFile()), "PNG image writer is unavailable");
        check(Files.size(file) > 1_000, "Empty cache playback screenshot: " + file);
        System.out.println("SCREENSHOT: " + file);
    }

    private void verifyCodeSource() throws Exception {
        String expected = System.getProperty("lumen.test.installedJar");
        Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        if (expected != null && !expected.isBlank()) check(actual.equals(Path.of(expected).toAbsolutePath().normalize()),
                "Installed smoke loaded the wrong production code: " + actual);
        System.out.println("Production CodeSource: " + actual);
    }

    private void startServer(boolean offline) throws IOException {
        server = HttpServer.create(address, 0); server.setExecutor(httpWorkers);
        server.createContext("/api/", exchange -> respond(exchange, offline)); server.start();
    }

    private void respond(HttpExchange exchange, boolean offline) {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/stream")) {
                System.out.println("CACHE HTTP " + (offline ? "offline-trap " : "fixture ")
                        + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " Range=" + exchange.getRequestHeaders().getFirst("Range"));
                if (offline) {
                    offlineStreams.incrementAndGet();
                    exchange.getResponseHeaders().set("X-Lumen-Error-Code", "AUDIO_UNAVAILABLE");
                    exchange.sendResponseHeaders(503, -1); return;
                }
                exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                int start = 0, end = fixture.length - 1;
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null && range.startsWith("bytes=")) {
                    String[] values = range.substring(6).split("-", -1);
                    if (values.length != 2) throw new IOException("Unsupported audio range: " + range);
                    if (values[0].isEmpty()) {
                        int suffix = Integer.parseInt(values[1]);
                        start = suffix <= 0 ? fixture.length : Math.max(0, fixture.length - suffix);
                    } else {
                        start = Integer.parseInt(values[0]);
                        if (!values[1].isEmpty()) end = Math.min(end, Integer.parseInt(values[1]));
                    }
                }
                if (exchange.getRequestMethod().equals("HEAD")) heads.incrementAndGet();
                else gets.incrementAndGet();
                if (start > end || start >= fixture.length) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + fixture.length);
                    exchange.sendResponseHeaders(416, -1); return;
                }
                int length = end - start + 1;
                System.out.println("CACHE HTTP response start=" + start + " end=" + end
                        + " length=" + length + " total=" + fixture.length);
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
                if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); }
                else {
                    if (range != null) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fixture.length);
                    exchange.sendResponseHeaders(range == null ? 200 : 206, length);
                    exchange.getResponseBody().write(fixture, start, length);
                }
                return;
            }
            String xml = switch (path) {
                case "/api/library" -> "<library version=\"1\"><favorites/><playlists/></library>";
                case "/api/ai-settings" -> "<ai-settings enabled=\"false\" keyConfigured=\"false\" model=\"fixture\" baseUrl=\"http://127.0.0.1\"/>";
                case "/api/listening" -> "<listening accepted=\"true\"/>";
                case "/api/lyrics" -> "<lyrics/>";
                default -> "<tracks/>";
            };
            exchange.getRequestBody().readAllBytes();
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // VLC may close an obsolete range response during stop or seek.
        } finally { exchange.close(); }
    }

    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private <T> T field(String name, Class<T> type) {
        try { Field value = LumenFxApp.class.getDeclaredField(name); value.setAccessible(true); return type.cast(value.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect " + name, error); }
    }
    private void waitFor(int milliseconds, BooleanSupplier condition, CheckedAction next) {
        poll(System.nanoTime() + milliseconds * 1_000_000L, condition, next);
    }
    private void poll(long deadline, BooleanSupplier condition, CheckedAction next) {
        safely(() -> {
            observePlayback();
            if (condition.getAsBoolean()) { next.run(); return; }
            check(System.nanoTime() < deadline, "Timeout in " + phase + ": " + audio().state()
                    + ", position=" + audio().position() + ", maxPosition=" + maxObservedPosition
                    + ", entries=" + field("persistentCache", PersistentAudioCache.class).entryCount()
                    + ", HEAD/GET=" + heads.get() + "/" + gets.get() + ", offlineRequests=" + offlineStreams.get());
            PauseTransition timer = new PauseTransition(Duration.millis(75));
            timer.setOnFinished(event -> poll(deadline, condition, next)); timer.play();
        });
    }
    private void observePlayback() {
        AudioPlayer player = audio();
        double position = player.position().toSeconds();
        maxObservedPosition = Math.max(maxObservedPosition, position);
        long second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
        int count = field("persistentCache", PersistentAudioCache.class).entryCount();
        if (player.state() == lastObservedState && second == lastObservedSecond && count == lastObservedEntries) return;
        lastObservedState = player.state(); lastObservedSecond = second; lastObservedEntries = count;
        System.out.println("CACHE OBS " + phase + "; engine=" + player.getClass().getSimpleName()
                + "; state=" + player.state() + "; position=" + position + "; maxPosition=" + maxObservedPosition
                + "; duration=" + player.duration().toSeconds() + "; entries=" + count
                + "; enabled=" + field("persistentCache", PersistentAudioCache.class).enabled()
                + "; HEAD/GET=" + heads.get() + "/" + gets.get() + "; offlineRequests=" + offlineStreams.get());
        System.out.flush();
    }
    private void safely(CheckedAction action) {
        if (finished.get()) return;
        try { action.run(); }
        catch (Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            System.err.println("FAIL " + phase); error.printStackTrace(System.err);
            cleanup(); System.exit(1);
        }
    }
    private void succeed() { finished.set(true); cleanup(); Platform.exit(); }
    private void cleanup() {
        try { if (app != null) app.stop(); } catch (Throwable error) { error.printStackTrace(System.err); }
        if (stage != null) stage.close();
        if (server != null) server.stop(0); httpWorkers.shutdownNow();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
