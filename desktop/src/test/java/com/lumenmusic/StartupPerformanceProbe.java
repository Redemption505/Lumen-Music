package com.lumenmusic;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/** Real launcher/service lifecycle in a synthetic profile; never sends native input. */
public final class StartupPerformanceProbe {
    private static final Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
    private static final CompletableFuture<Void> observed = new CompletableFuture<>();
    private static long started;
    private static volatile double windowMs = -1, uiMs = -1, serviceMs = -1, proxyMs = -1, closeAtMs = -1;
    private static volatile double maximumUiRoundTripMs;
    private static volatile int uiSamples;
    private static volatile Stage stage;
    private static volatile Throwable failure;

    private StartupPerformanceProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Provide report path under the test profile");
        Path root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent().normalize();
        Path build = Path.of("build").toAbsolutePath().normalize();
        Path report = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.startsWith(build) || root.equals(build) || !report.startsWith(root))
            throw new IllegalArgumentException("Probe requires a dedicated build profile and report");
        for (String key : List.of("LOCALAPPDATA", "APPDATA", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH", "TEMP", "TMP"))
            if (!Path.of(required(key)).toAbsolutePath().normalize().startsWith(root))
                throw new IllegalArgumentException("Profile escaped: " + key);
        for (String key : List.of("LUMEN_AI_API_KEY", "OPENAI_API_KEY", "LUMEN_AI_ENABLED"))
            if (System.getenv(key) != null) throw new IllegalArgumentException("AI environment entered probe");
        if (!"demo".equals(required("LUMEN_PROVIDER"))) throw new IllegalArgumentException("Probe uses only demo data");
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(build.toRealPath())) throw new IllegalArgumentException("Profile escaped build");
        seedProfile(root);
        var playbackStore = new PlaybackStateStore(root.resolve("playback-state.xml"));
        var playbackBefore = playbackStore.read().orElseThrow();
        var localStore = new LocalMusicStore(root.resolve("local-music.xml"));
        var localBefore = localStore.read();
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        URI proxy = URI.create(required("LUMEN_STREAM_URL"));
        checkPortFree(service.getPort());
        checkPortFree(proxy.getPort());
        // startup-probe.ps1 owns the timeout and terminates this exact live process tree.
        // Exiting the JVM from a competing watchdog could orphan its child services.
        started = System.nanoTime();
        Thread observer = new Thread(() -> observe(service, proxy), "startup-probe-observer");
        observer.setDaemon(true);
        observer.start();
        try {
            LumenLauncher.main(new String[0]);
            observed.get(10, TimeUnit.SECONDS);
            if (failure != null) throw new AssertionError("Startup observation failed", failure);
            if (closeAtMs < 0) throw new AssertionError("Launcher returned without normal UI close");
            double exitMs = elapsed() - closeAtMs;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (children.stream().anyMatch(child -> child.isAlive() && !ownConsoleHost(child)) && System.nanoTime() < deadline) Thread.sleep(50);
            if (children.stream().anyMatch(child -> child.isAlive() && !ownConsoleHost(child))) {
                for (ProcessHandle child : children) System.err.println("ObservedChild pid=" + child.pid()
                        + " name=" + child.info().command().map(value -> Path.of(value).getFileName().toString()).orElse("unknown")
                        + " parent=" + child.parent().map(ProcessHandle::pid).orElse(-1L) + " alive=" + child.isAlive());
                throw new AssertionError("Owned child survived shutdown");
            }
            checkPortFree(service.getPort());
            checkPortFree(proxy.getPort());
            if (!playbackBefore.equals(playbackStore.read().orElseThrow()) || !localBefore.equals(localStore.read()))
                throw new AssertionError("Paused queue or local catalogue changed");
            String childPids = children.stream().map(child -> Long.toString(child.pid())).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            String evidence = "{\"passed\":true,\"windowObservedMs\":" + windowMs + ",\"localUiReadyMs\":" + uiMs
                    + ",\"serviceHealthyMs\":" + serviceMs + ",\"proxyHealthyMs\":" + proxyMs
                    + ",\"launcherReturnAfterCloseMs\":" + exitMs + ",\"maxUiRoundTripMs\":" + maximumUiRoundTripMs
                    + ",\"uiSamples\":" + uiSamples + ",\"ownedChildrenObserved\":" + children.size()
                    + ",\"applicationChildrenStoppedBeforeJvmExit\":true,\"portsReleased\":true,\"pausedQueuePreserved\":true"
                    + ",\"localCataloguePreserved\":true,\"nativeExecutableLaunch\":false,\"osRebootTrial\":false"
                    + ",\"observedChildPids\":[" + childPids
                    + "],\"buildId\":\"" + BuildInfo.current().buildId() + "\"}\n";
            Files.createDirectories(report.getParent());
            Files.writeString(report, evidence);
            System.out.println("LauncherClassOrigin=" + LumenLauncher.class.getProtectionDomain().getCodeSource().getLocation());
            System.out.print(evidence);
        } catch (Throwable error) {
            // The application installs a redacted uncaught handler; retain full test-only failures here.
            error.printStackTrace(System.err);
            terminateObservedChildren();
            throw error;
        }
    }

    private static void observe(URI service, URI proxy) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (System.nanoTime() < deadline) {
                ProcessHandle.current().descendants().forEach(children::add);
                long posted = System.nanoTime();
                var response = new CompletableFuture<Boolean>();
                try {
                    Platform.runLater(() -> {
                        try {
                            if (stage == null) stage = Window.getWindows().stream().filter(window -> window instanceof Stage
                                    && window.isShowing() && window.getScene() != null && window.getScene().lookup("#nav-local") != null)
                                    .map(window -> (Stage) window).findFirst().orElse(null);
                            if (stage == null) { response.complete(false); return; }
                            if (windowMs < 0) windowMs = elapsed();
                            boolean alreadyReady = uiMs >= 0;
                            if (uiMs < 0) {
                                ((ButtonBase) stage.getScene().lookup("#nav-local")).fire();
                                TabPane tabs = (TabPane) stage.getScene().lookup("#main-tabs");
                                ListView<?> list = (ListView<?>) tabs.getSelectionModel().getSelectedItem().getContent().lookup("#local-list");
                                if (list == null) throw new AssertionError("Local navigation did not select its content");
                                Label now = (Label) stage.getScene().lookup("#now-playing");
                                // A restored queue must remain paused; catalogue loading is asynchronous.
                                if (now == null || !now.getText().contains("已暂停")) throw new AssertionError("Startup unexpectedly changed playback state");
                                if (list.getItems().size() == 1) uiMs = elapsed();
                            }
                            if (alreadyReady) {
                                maximumUiRoundTripMs = Math.max(maximumUiRoundTripMs, (System.nanoTime() - posted) / 1_000_000.0);
                                ++uiSamples;
                            }
                            response.complete(true);
                        } catch (Throwable error) { response.completeExceptionally(error); }
                    });
                } catch (IllegalStateException toolkitNotReady) { Thread.sleep(30); continue; }
                if (response.get(15, TimeUnit.SECONDS)) {
                    if (serviceMs < 0 && healthy(client, service.resolve("/api/health"), true)) serviceMs = elapsed();
                    if (proxyMs < 0 && healthy(client, proxy.resolve("/health"), false)) proxyMs = elapsed();
                    if (uiMs >= 0 && serviceMs >= 0 && proxyMs >= 0 && uiSamples >= 15) {
                        var closed = new CompletableFuture<Void>();
                        Platform.runLater(() -> {
                            closeAtMs = elapsed();
                            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                            closed.complete(null);
                        });
                        closed.get(10, TimeUnit.SECONDS);
                        observed.complete(null);
                        return;
                    }
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Startup observation timed out");
        } catch (Throwable error) {
            failure = error;
            observed.completeExceptionally(error);
            try { Platform.runLater(() -> { if (stage != null) stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)); }); }
            catch (IllegalStateException ignored) { }
        }
    }

    private static boolean healthy(HttpClient client, URI uri, boolean python) {
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(300)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && (python
                    ? response.body().contains("status=\"ok\"") && response.body().contains("provider=\"demo\"")
                    : response.body().equals("ok"));
        } catch (Exception ignored) { return false; }
    }

    private static boolean ownConsoleHost(ProcessHandle child) {
        // A console allocated for this test JVM lives until this JVM exits. The parent script
        // verifies it along with every captured child after WaitForExit; do not kill it here.
        return child.parent().map(ProcessHandle::pid).orElse(-1L) == ProcessHandle.current().pid()
                && child.info().command().map(value -> Path.of(value).getFileName().toString().equalsIgnoreCase("conhost.exe")).orElse(false);
    }

    private static void terminateObservedChildren() {
        // These handles belong to descendants observed during this probe, never a name/port search.
        ProcessHandle.current().descendants().forEach(children::add);
        children.stream().filter(child -> child.isAlive() && !ownConsoleHost(child)).forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (children.stream().anyMatch(child -> child.isAlive() && !ownConsoleHost(child)) && System.nanoTime() < deadline) {
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        children.stream().filter(child -> child.isAlive() && !ownConsoleHost(child)).forEach(ProcessHandle::destroyForcibly);
    }

    private static void checkPortFree(int port) throws Exception {
        try (var socket = new ServerSocket()) { socket.bind(new java.net.InetSocketAddress("127.0.0.1", port)); }
    }

    private static void seedProfile(Path root) throws Exception {
        Path media = root.resolve("synthetic-startup.wav");
        if (!Files.exists(media)) {
            int bytes = 8 * 8000 * 2;
            ByteBuffer wave = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
            wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
            wave.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
            wave.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
            wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
            Files.write(media, wave.array());
            Track track = new Track("startup-probe-local", "Synthetic startup", "Fixture", 8, "local", media.toUri());
            new LocalMusicStore(root.resolve("local-music.xml")).save(new LocalMusicStore.State(List.of(track), Map.of()));
            new PlaybackStateStore(root.resolve("playback-state.xml")).save(
                    new PlaybackStateStore.State(List.of(track), 0, PlayerQueue.RepeatMode.ONE, 2.5));
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing isolated environment: " + name);
        return value;
    }
    private static double elapsed() { return (System.nanoTime() - started) / 1_000_000.0; }
}
