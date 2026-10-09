package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Runs only in a dedicated profile with an external demo service. No Python process is spawned. */
public final class StartupUiSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger pulses = new AtomicInteger();
    private final AtomicInteger pendingAudioPulses = new AtomicInteger();
    private final CountDownLatch allowStartup = new CountDownLatch(1);
    private Stage stage;
    private LumenFxApp app;
    private AnimationTimer heartbeat;
    private HttpServer health;
    private StartupCoordinator<AutoCloseable> injectedRuntime;
    private Path root;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(60_000); }
            catch (InterruptedException interrupted) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL startup UI watchdog");
                System.exit(1);
            }
        }, "startup-ui-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) { error.printStackTrace(System.err); finish(1); }
        }, "startup-ui-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent().normalize();
        check(root.toString().contains("startup-ui-smoke"), "profile must be under startup-ui-smoke");
        for (String name : List.of("LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                "LUMEN_AI_SETTINGS_PATH", "LOCALAPPDATA", "APPDATA"))
            check(Path.of(required(name)).toAbsolutePath().normalize().startsWith(root), name + " escapes profile");
        check(runtimeField().get(null) == null, "direct UI fixture unexpectedly owns runtime");
        check(LumenLauncher.runtimeReady().get(1, TimeUnit.SECONDS).serviceAvailable(),
                "direct launcher should preserve its externally managed service");
        Files.createDirectories(root);
        Path wave = root.resolve("startup-fixture.wav");
        writeWave(wave);
        Track local = new Track("startup-local", "Startup fixture", "Fixture", 8, "local", wave.toUri());
        LocalMusicStore localStore = new LocalMusicStore(root.resolve("local-music.xml"));
        LocalMusicStore.State catalogue = new LocalMusicStore.State(List.of(local), Map.of());
        localStore.save(catalogue);
        byte[] originalCatalogue = Files.readAllBytes(root.resolve("local-music.xml"));
        PlaybackStateStore.State playback = new PlaybackStateStore.State(List.of(local), 0,
                PlayerQueue.RepeatMode.ONE, 2.5);
        new PlaybackStateStore(root.resolve("playback-state.xml")).save(playback);
        fx(() -> {
            app = new LumenFxApp();
            heartbeat = new AnimationTimer() {
                @Override public void handle(long now) {
                    pulses.incrementAndGet();
                    try {
                        CompletableFuture<?> ready = (CompletableFuture<?>) field("vlcReady").get(app);
                        if (ready != null && !ready.isDone()) pendingAudioPulses.incrementAndGet();
                    } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                }
            };
            heartbeat.start();
            app.startUi(stage, List.of());
            check(stage.isShowing(), "startup did not show a window");
            ((ButtonBase) stage.getScene().lookup("#nav-local")).fire();
        });
        await(() -> fxValue(() -> ((javafx.collections.ObservableList<?>) field("localTracks").get(app)).size() == 1),
                "local catalogue did not restore");
        await(() -> fxValue(() -> field("serviceInitialized").getBoolean(app)), "external fixture did not initialize");
        check(Arrays.equals(originalCatalogue, Files.readAllBytes(root.resolve("local-music.xml"))),
                "startup rewrote existing local catalogue");

        AtomicBoolean healthy = new AtomicBoolean();
        AtomicInteger healthCalls = new AtomicInteger();
        health = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        health.createContext("/api/health", exchange -> {
            healthCalls.incrementAndGet();
            byte[] body = "<health status=\"ok\" provider=\"demo\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(healthy.get() ? 200 : 503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        health.start();
        URI service = URI.create("http://127.0.0.1:" + health.getAddress().getPort());
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        Constructor<?> servicesConstructor = Class.forName("com.lumenmusic.LumenLauncher$RuntimeServices")
                .getDeclaredConstructor(ManagedPythonService.class, ManagedStreamProxy.class);
        servicesConstructor.setAccessible(true);
        injectedRuntime = new StartupCoordinator<>(() -> {
            if (attempts.incrementAndGet() == 1) {
                started.countDown();
                if (!allowStartup.await(10, TimeUnit.SECONDS)) throw new IOException("fixture startup timed out");
            }
            // First attempt: HTTP 503 plus absent source fails before spawning. Retry: health reuses service.
            ManagedPythonService managed = ManagedPythonService.start(service, root.resolve("absent-source"),
                    "absent-python", "demo", root.resolve("fixture-service.log"));
            return (AutoCloseable) servicesConstructor.newInstance(managed, null);
        });
        runtimeField().set(null, injectedRuntime);
        fx(() -> {
            field("serviceInitialized").setBoolean(app, false);
            Method begin = LumenFxApp.class.getDeclaredMethod("beginRuntimeStartup", boolean.class);
            begin.setAccessible(true);
            begin.invoke(app, false);
        });
        check(started.await(3, TimeUnit.SECONDS), "background startup did not begin");
        int beforePulses = pulses.get();
        fx(() -> {
            ((ButtonBase) stage.getScene().lookup("#nav-local")).fire();
            ((ButtonBase) stage.getScene().lookup("#sidebar-settings")).fire();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            ((ButtonBase) stage.getScene().lookup("#theme-button")).fire();
            ((ButtonBase) stage.getScene().lookup("#nav-local")).fire();
            check(((Button) field("retryStartupButton").get(app)).isDisabled(), "startup retry was not busy");
        });
        await(() -> pulses.get() >= beforePulses + 8, "JavaFX froze while runtime was waiting");
        allowStartup.countDown();
        await(() -> fxValue(() -> {
            Button retry = (Button) field("retryStartupButton").get(app);
            return retry.isVisible() && !retry.isDisabled();
        }), "failed startup did not expose retry");
        healthy.set(true);
        fx(() -> ((Button) field("retryStartupButton").get(app)).fire());
        await(() -> fxValue(() -> field("serviceInitialized").getBoolean(app)
                && !((Button) field("retryStartupButton").get(app)).isVisible()), "retry did not recover service");
        check(attempts.get() == 2 && healthCalls.get() == 2, "retry did not perform two real health probes");
        fx(() -> app.stop());
        check(new PlaybackStateStore(root.resolve("playback-state.xml")).read().orElseThrow().equals(playback),
                "startup/close changed restored queue or position");
        check(localStore.read().equals(catalogue), "startup/close changed local catalogue");
        String evidence = "PASS: direct UI retains external service; FX navigation/theme/heartbeat during runtime wait; "
                + "HTTP startup failure, retry recovery, saved queue/position/catalogue preserved\n"
                + "PulsesWhileNativeAudioPending=" + pendingAudioPulses.get() + "\n";
        Files.writeString(root.resolve("startup-ui-evidence.txt"), evidence);
        System.out.print(evidence);
    }

    private static void writeWave(Path path) throws Exception {
        int bytes = 8 * 8000 * 2;
        ByteBuffer wave = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        wave.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        wave.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        Files.write(path, wave.array());
    }

    private Field field(String name) throws ReflectiveOperationException {
        Field field = LumenFxApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Field runtimeField() throws ReflectiveOperationException {
        Field field = LumenLauncher.class.getDeclaredField("runtime");
        field.setAccessible(true);
        return field;
    }
    private void await(CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(50); }
        throw new AssertionError(message);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(3, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        allowStartup.countDown();
        try {
            fx(() -> {
                if (heartbeat != null) heartbeat.stop();
                if (app != null) app.stop();
                for (Window window : List.copyOf(Window.getWindows())) window.hide();
            });
        } catch (Exception error) { error.printStackTrace(System.err); }
        if (injectedRuntime != null) injectedRuntime.close();
        if (health != null) health.stop(0);
        System.exit(code);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        check(value != null && !value.isBlank(), "missing " + name);
        return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
}
