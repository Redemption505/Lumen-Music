package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.OnlineAudioCache;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Isolated application-chain regression; all HTTP traffic stays on loopback. */
public final class Phase4CoreSmoke extends Application {
    private LumenFxApp app;
    private Path root;
    private HttpServer audioServer;
    private final CountDownLatch allowDownload = new CountDownLatch(1);
    private final CountDownLatch allowWriters = new CountDownLatch(1);
    private final CountDownLatch downloadStarted = new CountDownLatch(1);
    private final boolean probe = "1".equals(System.getenv("LUMEN_PHASE4_PROBE"));
    private final boolean prepared = "prepared".equals(System.getenv("LUMEN_PHASE4_SEEK_WINDOW"));
    private final boolean ready = "ready".equals(System.getenv("LUMEN_PHASE4_SEEK_WINDOW"));
    private final CompletableFuture<Long> preparedClose = new CompletableFuture<>();
    private double preparedSaved;

    @Override public void start(Stage initial) {
        Platform.setImplicitExit(false);
        Thread driver = new Thread(() -> {
            int result = 0;
            try { exercise(initial); }
            catch (Throwable error) { result = 1; error.printStackTrace(System.err); }
            finally {
                allowDownload.countDown(); allowWriters.countDown();
                if (audioServer != null) audioServer.stop(0);
            }
            int exitCode = result;
            Platform.runLater(() -> { if (app != null) app.stop(); Platform.exit(); });
            // Smoke owns this JVM; never leave a failed media initialization running.
            try { Thread.sleep(750); } catch (InterruptedException ignored) { }
            System.exit(exitCode);
        }, "phase4-core-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise(Stage stage) throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(root.toString().contains("phase4-core-b"), "fixture must use the B isolated profile");
        for (String variable : List.of("LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                "LUMEN_AI_SETTINGS_PATH", "LOCALAPPDATA", "APPDATA"))
            check(Path.of(required(variable)).toAbsolutePath().normalize().startsWith(root), "profile escape");
        fx(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); });
        byte[] body = prepared || ready ? Files.readAllBytes(Path.of(getParameters().getRaw().get(0))) : new byte[128];
        audioServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        audioServer.createContext("/api/tracks/", exchange -> {
            downloadStarted.countDown();
            try { allowDownload.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                exchange.sendResponseHeaders(200, -1); exchange.close(); return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            catch (java.io.IOException ignored) { }
        });
        audioServer.start();
        int duration = prepared || ready ? 32 : 100;
        double expectedTarget = .6 * duration;
        Track track = new Track("phase4-seek", "Synthetic seek fixture", "Fixture", duration, "bilibili",
                null, "BV1bE41127s6", 1, null, 1, 0);
        FakeAudio fake = new FakeAudio(duration);
        fx(() -> {
            if (prepared) {
                ((JavaFxAudioPlayer) field("localAudio").get(app)).close();
                field("localAudio").set(app, new JavaFxAudioPlayer(state -> {
                    try {
                        invoke("onPlaybackState", new Class<?>[] {JavaFxAudioPlayer.State.class}, state);
                        if (state != JavaFxAudioPlayer.State.LOADING) return;
                        invoke("savePlaybackState", new Class<?>[0]);
                        preparedSaved = new PlaybackStateStore(root.resolve("playback-state.xml"))
                                .read().orElseThrow().positionSeconds();
                        check(Math.abs(preparedSaved - expectedTarget) <= 1, "target lost in completed-file LOADING");
                        // Queued before MediaPlayer construction can enqueue READY: close this exact window.
                        Platform.runLater(() -> {
                            try {
                                check(((AudioPlayer) field("audio").get(app)).state() == JavaFxAudioPlayer.State.LOADING,
                                        "fixture missed completed-file before READY window");
                                long before = System.nanoTime(); app.stop();
                                preparedClose.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
                            } catch (Throwable error) { preparedClose.completeExceptionally(error); }
                        });
                    } catch (Throwable error) { preparedClose.completeExceptionally(error); }
                }, message -> preparedClose.completeExceptionally(new AssertionError(message))));
            }
            ((OnlineAudioCache) field("onlineAudio").get(app)).close();
            field("onlineAudio").set(app, new OnlineAudioCache(new MusicClient(
                    URI.create("http://127.0.0.1:" + audioServer.getAddress().getPort()))));
            PlayerQueue queue = (PlayerQueue) field("queue").get(app);
            queue.select(List.of(track), 0);
            field("loadedTrack").set(app, track);
            field("restoredPositionSeconds").set(app, null);
            field("audio").set(app, fake);
            invoke("fallbackSeekToLocal", new Class<?>[] {double.class, long.class}, .6,
                    field("loadVersion").getLong(app));
        });
        check(downloadStarted.await(3, TimeUnit.SECONDS), "controlled download did not start");
        fx(() -> invoke("savePlaybackState", new Class<?>[0]));
        double saved = new PlaybackStateStore(root.resolve("playback-state.xml")).read().orElseThrow().positionSeconds();
        if (!probe) check(Math.abs(saved - expectedTarget) <= 1, "seek target lost while download was pending: " + saved);

        CountDownLatch busy = new CountDownLatch(4);
        fx(() -> {
            for (String name : List.of("localTasks", "lyricWrites", "onlineDownloads"))
                ((ExecutorService) field(name).get(app)).submit(() -> block(busy));
            Object reporter = field("listening").get(app);
            Field writer = reporter.getClass().getDeclaredField("writer"); writer.setAccessible(true);
            ((ExecutorService) writer.get(reporter)).submit(() -> block(busy));
        });
        check(busy.await(3, TimeUnit.SECONDS), "busy queues did not enter controlled wait");
        Track local = new Track("phase4-final-local", "Queued final fixture", "Synthetic", 1, "local",
                root.resolve("queued-local.wav").toUri());
        var manual = new LyricsSelectionStore.Selection("Final fixture", "Synthetic", "[00:00.00]Final write", 250);
        if (!probe) fx(() -> {
            ((javafx.collections.ObservableList<Track>) field("localTracks").get(app)).add(local);
            invoke("persistLocalCatalogue", new Class<?>[0]);
            field("manualLyricSelection").set(app, manual);
            invoke("saveManualLyrics", new Class<?>[0]);
        });
        long closeMillis;
        if (prepared) {
            allowDownload.countDown(); closeMillis = preparedClose.get(12, TimeUnit.SECONDS);
        } else {
            if (ready) {
                allowDownload.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                boolean positioned = false;
                while (System.nanoTime() < deadline) {
                    CompletableFuture<Boolean> sample = new CompletableFuture<>();
                    Platform.runLater(() -> {
                        try {
                            AudioPlayer actual = (AudioPlayer) field("audio").get(app);
                            sample.complete(actual == field("localAudio").get(app)
                                    && (actual.state() == JavaFxAudioPlayer.State.READY || actual.state() == JavaFxAudioPlayer.State.PAUSED)
                                    && Math.abs(actual.position().toSeconds() - expectedTarget) <= 1);
                        } catch (Throwable error) { sample.completeExceptionally(error); }
                    });
                    if (sample.get(2, TimeUnit.SECONDS)) { positioned = true; break; }
                    Thread.sleep(50);
                }
                check(positioned, "normal fallback never reached its paused target after READY");
                Thread.sleep(300);
                fx(() -> check(((AudioPlayer) field("audio").get(app)).state() != JavaFxAudioPlayer.State.PLAYING,
                        "normal fallback resumed a paused track"));
            }
            long before = System.nanoTime(); fx(() -> app.stop());
            closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        }
        if (!probe) check(closeMillis < 1500, "FX close still waits for background queues: " + closeMillis);
        allowWriters.countDown();
        if (!probe) {
            ((CompletableFuture<?>) field("shutdownComplete").get(app)).get(8, TimeUnit.SECONDS);
            double restored = new PlaybackStateStore(root.resolve("playback-state.xml")).read().orElseThrow().positionSeconds();
            check(Math.abs(restored - expectedTarget) <= 1, "close changed pending target");
            check(new LocalMusicStore(root.resolve("local-music.xml")).read().tracks().contains(local), "queued catalogue write lost");
            check(new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory()).load(track).orElseThrow().equals(manual),
                    "queued lyric write lost");
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(URI.create(required("LUMEN_SERVICE_URL") + "/api/health"))
                            .timeout(java.time.Duration.ofSeconds(2)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            check(response.statusCode() == 200, "closing killed the external fixture service");
            // Recreate the actual app against the saved profile; startup must show a paused restoration.
            fx(() -> {
                stage.hide(); app = new LumenFxApp(); app.startUi(new Stage(), List.of());
                PlayerQueue queue = (PlayerQueue) field("queue").get(app);
                check(queue.current().id().equals(track.id()), "restart restored the wrong track");
                check(Math.abs((Double) field("restoredPositionSeconds").get(app) - expectedTarget) <= 1,
                        "restart restored wrong position");
                check(((AudioPlayer) field("audio").get(app)).state() == JavaFxAudioPlayer.State.IDLE,
                        "restart unexpectedly started audio");
                app.stop();
            });
            ((CompletableFuture<?>) field("shutdownComplete").get(app)).get(8, TimeUnit.SECONDS);
        }
        String evidence = (probe ? "BASELINE" : "PASS") + " pendingDownloadPosition=" + saved
                + "; beforeReadyPosition=" + (prepared ? preparedSaved : "not-this-case")
                + "; pausedFallbackAfterReady=" + ready
                + "; busyFxCloseMillis=" + closeMillis
                + "; queuedWritesAndPausedAppRestart=" + !probe + "\n";
        Files.writeString(root.resolve("phase4-core-evidence.txt"), evidence);
        System.out.print(evidence);
    }

    private void block(CountDownLatch started) {
        started.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (allowWriters.getCount() != 0 && System.nanoTime() < deadline) {
            try { allowWriters.await(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { } // Deliberate bounded, non-cooperative fixture.
        }
    }
    private Field field(String name) throws Exception {
        Field result = LumenFxApp.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, args);
    }
    private void fx(Action action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> { try { action.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); } });
        done.get(16, TimeUnit.SECONDS);
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new AssertionError(name + " missing"); return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static final class FakeAudio implements AudioPlayer {
        private double position = 37;
        private final int duration;
        private FakeAudio(int duration) { this.duration = duration; }
        public JavaFxAudioPlayer.State state() { return JavaFxAudioPlayer.State.PAUSED; }
        public Duration position() { return Duration.seconds(position); }
        public Duration duration() { return Duration.seconds(duration); }
        public void setVolume(double value) { }
        public void seekFraction(double fraction) { position = duration * fraction; }
        public void load(URI uri, boolean autoplay) { }
        public void toggle() { }
        public void stop() { position = 0; }
        public void close() { stop(); }
    }
}
