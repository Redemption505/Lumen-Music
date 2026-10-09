package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.Equalizer;

/** Real public audio, native five-minute clock, obsolete loads, and offline persisted cache. */
public final class ReleaseOnlinePlaybackSmoke extends Application {
    private record Clock(double position, double duration, String state, boolean normalizationEnabled,
                         String normalizationStatus, double preamp) { }
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private volatile String phase = "startup";
    private volatile boolean finished;
    private HttpServer offlineTrap;
    private MediaPlayer observedNativePlayer;
    private final AtomicInteger offlineRequests = new AtomicInteger();
    private final AtomicInteger nativeEnds = new AtomicInteger();
    private final List<String> samples = new ArrayList<>(List.of("elapsedSeconds,positionSeconds,durationSeconds,state,heapBytes"));

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep((seconds() + 180L) * 1000); }
            catch (InterruptedException ignored) { return; }
            if (!finished) { System.err.println("FAIL release online watchdog: " + phase); System.exit(1); }
        }, "release-online-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int result = 0;
            try { exercise(); }
            catch (Throwable error) { result = 1; System.err.println("FAIL release online phase: " + phase); error.printStackTrace(System.err); }
            finally {
                try { if (root != null) Files.write(root.resolve("native-long-playback.csv"), samples); }
                catch (Throwable error) { result = 1; error.printStackTrace(System.err); }
                try { fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); }); }
                catch (Throwable error) { result = 1; error.printStackTrace(System.err); }
                if (offlineTrap != null) offlineTrap.stop(0);
                finished = true;
                System.out.flush(); System.err.flush();
                Platform.exit();
                if (result != 0) System.exit(result);
            }
        }, "release-online-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        validateIsolation();
        value(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); return null; }, 30);
        await(10_000, () -> value(() -> label("library-status").getText().equals("\u4e2a\u4eba\u66f2\u5e93\u5df2\u8f7d\u5165")), "isolated library readiness");
        Track first = search("LUMEN_SMOKE_BV_PRIMARY");
        Track second = search("LUMEN_SMOKE_BV_SECONDARY");
        check(!first.id().equals(second.id()), "public sources must differ");
        fx(() -> {
            try { disk().configure(true, 64L * 1024 * 1024); }
            catch (java.io.IOException error) { throw new AssertionError("cannot enable isolated cache", error); }
        });
        select(first);
        awaitPlaying(first);
        if (normalization()) {
            check(value(() -> field("normalizationEnabled", Boolean.class)), "isolated profile did not enable normalization");
            check(value(this::clock).normalizationEnabled, "actual VLC normalization is disabled");
            samples.set(0, "elapsedSeconds,positionSeconds,durationSeconds,state,heapBytes,normalizationStatus,normalizationGainDb,observedNoAdvanceMillis,samplingGapMillis");
        }
        fx(this::observeNativeEnds);
        phase = "native continuous public audio sample";
        fx(() -> queue().setRepeatMode(PlayerQueue.RepeatMode.ONE));
        long start = System.nanoTime(), lastAdvance = start;
        double before = value(() -> audio().position().toSeconds()), advanced = 0;
        long previousSample = start;
        double measuredAt = Double.NaN, maxPendingGap = 0, maxMeasuredGap = 0;
        int loops = 0, index = 0;
        while ((System.nanoTime() - start) / 1_000_000_000.0 < seconds()) {
            double elapsedBefore = (System.nanoTime() - start) / 1_000_000_000.0;
            boolean dense = normalization() && (elapsedBefore < 25 || Double.isNaN(measuredAt) || elapsedBefore < measuredAt + 2);
            Thread.sleep(dense ? 50 : 1000);
            Clock clock = value(() -> {
                check(audio() instanceof VlcAudioPlayer, "sample left real VLC");
                check(currentIs(first), "sample changed public source");
                check(audio().state() != JavaFxAudioPlayer.State.FAILED, "native playback failed");
                observeNativeEnds();
                return clock();
            });
            long now = System.nanoTime();
            double elapsed = (now - start) / 1_000_000_000.0;
            double advanceGap = (now - lastAdvance) / 1_000_000.0;
            double samplingGap = (now - previousSample) / 1_000_000.0;
            previousSample = now;
            if (normalization()) {
                check(clock.normalizationEnabled, "VLC normalization was disabled during continuous acceptance");
                check(!clock.normalizationStatus.startsWith("\u6d4b\u91cf\u5931\u8d25"), "VLC normalization measurement failed: " + clock.normalizationStatus);
                if (clock.normalizationStatus.startsWith("\u5df2\u6d4b") && Double.isNaN(measuredAt)) {
                    measuredAt = elapsed;
                    log("NORMALIZATION_RESULT elapsed=" + elapsed + " position=" + clock.position
                            + " precedingAdvanceGapMillis=" + advanceGap + " precedingSamplingGapMillis=" + samplingGap
                            + " status=" + clock.normalizationStatus);
                }
                if (Double.isNaN(measuredAt)) maxPendingGap = Math.max(maxPendingGap, advanceGap);
                else maxMeasuredGap = Math.max(maxMeasuredGap, advanceGap);
                boolean strict = elapsedBefore < 25 || (!Double.isNaN(measuredAt) && elapsedBefore < measuredAt + 2);
                if (strict) {
                    check(samplingGap < 2000, "JavaFX clock sampling was blocked for >=2 seconds near initial normalization; gapMillis=" + samplingGap);
                    check(advanceGap < 2000, "native clock stalled for >=2 seconds near initial normalization; gapMillis=" + advanceGap);
                }
            }
            double delta = clock.position - before;
            if (delta < -1) {
                check(nativeEnds.get() > loops && clock.position < 4, "clock reset without an observed native EOF: " + before + " -> " + clock.position);
                // Container duration can include a trailing gap. Count only observed forward progress.
                delta = 0; loops++;
            }
            check(delta < 4, "native sample advanced faster than real time: " + delta);
            if (delta > (dense ? .001 : .05)) { advanced += delta; lastAdvance = now; }
            check(System.nanoTime() - lastAdvance < TimeUnit.SECONDS.toNanos(15), "native playback stalled for 15 seconds");
            before = clock.position;
            long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            String row = elapsed + "," + clock.position + "," + clock.duration + "," + clock.state + "," + heap;
            if (normalization()) row += ",\"" + clock.normalizationStatus.replace("\"", "\"\"") + "\","
                    + (clock.preamp - VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB) + "," + advanceGap + "," + samplingGap;
            samples.add(row);
            if (++index % (dense ? 200 : 10) == 0) log("LONG_SAMPLE elapsed=" + elapsed + " nativePosition=" + clock.position + " advanced=" + advanced + " loops=" + loops + " heap=" + heap);
        }
        if (normalization()) {
            check(!Double.isNaN(measuredAt), "actual VLC normalization did not finish its measurement");
            Clock finalClock = value(this::clock);
            check(finalClock.normalizationEnabled && finalClock.normalizationStatus.startsWith("\u5df2\u6d4b"),
                    "actual VLC normalization completion state was not retained");
            log("NORMALIZATION_PASS measuredAtSeconds=" + measuredAt + " maxPendingAdvanceGapMillis=" + maxPendingGap
                    + " maxMeasuredAdvanceGapMillis=" + maxMeasuredGap + " finalGainDb="
                    + (finalClock.preamp - VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB));
        }
        check(advanced >= seconds() - 15, "insufficient native advancement during actual wall-clock sample: " + advanced);
        Files.write(root.resolve("native-long-playback.csv"), samples);
        log("LONG_PASS wallSeconds=" + (System.nanoTime() - start) / 1_000_000_000.0 + " nativeAdvanceSeconds=" + advanced + " loops=" + loops);
        pauseResume(first);
        phase = "rapid obsolete public loads cannot replace final selection";
        long version = value(() -> field("loadVersion", Long.class));
        for (int i = 0; i < 8; i++) { select(i % 2 == 0 ? second : first); Thread.sleep(90); }
        awaitPlaying(first);
        long settled = value(() -> field("loadVersion", Long.class));
        check(settled >= version + 8, "rapid switches did not execute load/cancellation path");
        for (int i = 0; i < 5; i++) {
            Thread.sleep(1000);
            check(value(() -> currentIs(first) && field("loadVersion", Long.class) == settled
                    && audio().state() == JavaFxAudioPlayer.State.PLAYING), "obsolete switch replaced final playback");
        }
        log("RAPID_PASS loads=" + (settled - version) + " final=" + first.id());
        phase = "favorite persistence and complete public cache";
        fx(() -> button("favorite-current").fire());
        await(10_000, () -> value(() -> field("favorites", List.class).stream().anyMatch(item -> item instanceof Track track && track.id().equals(first.id()))), "real favorite persistence");
        await(45_000, () -> value(() -> { try { return disk().lookup(first).isPresent(); } catch (Exception e) { throw new AssertionError(e); } }), "complete public audio cache");
        URI cached = value(() -> { try { return disk().lookup(first).orElseThrow(); } catch (Exception e) { throw new AssertionError(e); } });
        check(Files.size(Path.of(cached)) > 1000, "public cache file is empty");
        fx(() -> { app.stop(); stage.close(); });
        offlineTrap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        offlineTrap.createContext("/", exchange -> {
            offlineRequests.incrementAndGet();
            System.err.println("OFFLINE_FORBIDDEN " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        offlineTrap.start();
        System.setProperty("lumen.stream.base", "http://127.0.0.1:" + offlineTrap.getAddress().getPort());
        phase = "fresh app plays favorite cache with every stream endpoint unavailable";
        value(() -> { app = new LumenFxApp(); stage = new Stage(); app.startUi(stage, List.of()); return null; }, 30);
        await(10_000, () -> value(() -> field("favorites", List.class).size() == 1), "fresh favorite reload");
        check(value(() -> audio().state() != JavaFxAudioPlayer.State.PLAYING), "fresh app auto-played");
        fx(() -> {
            button("nav-favorites").fire();
            ListView<?> list = (ListView<?>) stage.getScene().lookup("#favorites-list");
            check(list != null && list.getItems().size() == 1, "fresh favorite row missing");
            list.getSelectionModel().select(0);
            javafx.event.Event.fireEvent(list, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    10, 10, 10, 10, javafx.scene.input.MouseButton.PRIMARY, 2, false, false, false, false,
                    false, false, false, false, false, true, null));
        });
        awaitPlaying(first);
        double offlineBefore = value(() -> audio().position().toSeconds());
        await(8000, () -> value(() -> currentIs(first) && audio().position().toSeconds() > offlineBefore + 2), "offline native advancement");
        URI nativeUri = value(() -> {
            try { Field uri = VlcAudioPlayer.class.getDeclaredField("currentUri"); uri.setAccessible(true); return (URI) uri.get(audio()); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        check(cached.equals(nativeUri), "offline VLC did not use persisted public cache: " + nativeUri);
        check(offlineRequests.get() == 0, "offline replay performed forbidden stream preflight or transfer");
        log("OFFLINE_PASS cached=" + cached + " position=" + value(() -> audio().position().toSeconds()) + " streamRequests=" + offlineRequests.get());
        System.out.println("PASS: release online JavaFX native >=300s sample, pause/resume, rapid cancellation, favorite persistence, fresh app public cache offline playback");
    }

    private void pauseResume(Track expected) throws Exception {
        phase = "real VLC pause and resume";
        fx(() -> button("play-button").fire());
        await(3000, () -> value(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "native pause");
        double before = value(() -> audio().position().toSeconds());
        Thread.sleep(1200);
        check(value(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(audio().position().toSeconds() - before) < .5), "paused native clock moved");
        fx(() -> button("play-button").fire());
        await(8000, () -> value(() -> currentIs(expected) && audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > before + 2), "resume native advancement");
        log("PAUSE_RESUME_PASS position=" + value(() -> audio().position().toSeconds()));
    }

    private void observeNativeEnds() {
        try {
            Field nativeField = VlcAudioPlayer.class.getDeclaredField("player");
            nativeField.setAccessible(true);
            MediaPlayer nativePlayer = (MediaPlayer) nativeField.get(audio());
            if (nativePlayer == null || nativePlayer == observedNativePlayer) return;
            observedNativePlayer = nativePlayer;
            nativePlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void finished(MediaPlayer player) {
                    nativeEnds.incrementAndGet();
                    log("NATIVE_EOF observedAtNanos=" + System.nanoTime());
                }
            });
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private Clock clock() {
        if (!normalization()) return new Clock(audio().position().toSeconds(), audio().duration().toSeconds(),
                audio().state().name(), false, "", Double.NaN);
        try {
            Field enabled = VlcAudioPlayer.class.getDeclaredField("normalizationEnabled");
            enabled.setAccessible(true);
            Field equalizer = VlcAudioPlayer.class.getDeclaredField("normalizationEqualizer");
            equalizer.setAccessible(true);
            Equalizer eq = (Equalizer) equalizer.get(audio());
            check(eq != null, "normalization player has no preinstalled equalizer");
            return new Clock(audio().position().toSeconds(), audio().duration().toSeconds(), audio().state().name(),
                    enabled.getBoolean(audio()), audio().normalizationStatus(), eq.preamp());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private Track search(String variable) throws Exception {
        String bvid = System.getenv(variable);
        check(bvid != null && bvid.matches("BV[0-9A-Za-z]{10}"), "missing public BV: " + variable);
        phase = "public search " + bvid;
        fx(() -> {
            button("nav-search").fire();
            ((TextField) stage.getScene().lookup("#search-query")).setText("https://www.bilibili.com/video/" + bvid + "/");
            button("search-button").fire();
        });
        await(25000, () -> value(() -> searchTracks().stream().anyMatch(track -> bvid.equals(track.bvid()))), "public link search " + bvid);
        Track track = value(() -> searchTracks().stream().filter(item -> bvid.equals(item.bvid())).findFirst().orElseThrow());
        check("bilibili".equals(track.source()) && track.mediaUri() == null && track.page() > 0, "search did not return public online identity");
        log("PUBLIC_TRACK " + track.id() + " title=" + track.title());
        return track;
    }

    private void validateIsolation() throws Exception {
        root = Path.of(System.getenv("LUMEN_SMOKE_ROOT")).toRealPath();
        check(Files.isRegularFile(root.resolve("isolated-online-smoke.marker")), "missing isolation marker");
        for (String name : List.of("LUMEN_LIBRARY_PATH", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(Path.of(System.getenv(name)).toAbsolutePath().normalize().getParent().equals(root), "nonisolated " + name);
        for (String name : List.of("LUMEN_AI_API_KEY", "OPENAI_API_KEY", "LUMEN_AI_CHAT_COMPLETIONS_URL", "LUMEN_AI_MODEL"))
            check(System.getenv(name) == null || System.getenv(name).isBlank(), "personal AI environment present: " + name);
        check(Path.of(System.getenv("LOCALAPPDATA")).toRealPath().equals(root), "LOCALAPPDATA is not isolated");
        Path expected = Path.of(System.getProperty("lumen.smoke.installed.jar")).toRealPath();
        Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(expected.equals(actual), "wrong production origin: " + actual);
        check(seconds() >= 300, "actual wall-clock sample must be >=300 seconds");
        log("PRODUCTION_ORIGIN " + actual);
    }

    private void select(Track track) throws Exception {
        fx(() -> {
            queue().select(List.of(track), 0);
            queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            try { Method method = LumenFxApp.class.getDeclaredMethod("loadCurrent", boolean.class); method.setAccessible(true); method.invoke(app, true); }
            catch (Exception error) { throw new AssertionError(error); }
        });
    }
    private void awaitPlaying(Track expected) throws Exception {
        await(35000, () -> value(() -> currentIs(expected) && audio() instanceof VlcAudioPlayer
                && audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > 1), "real VLC playback " + expected.id());
    }
    private List<Track> searchTracks() { return ((ListView<?>) stage.getScene().lookup("#search-list")).getItems().stream().filter(Track.class::isInstance).map(Track.class::cast).toList(); }
    private boolean currentIs(Track track) { return queue().current() != null && queue().current().id().equals(track.id()); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private PersistentAudioCache disk() { return field("persistentCache", PersistentAudioCache.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private Button button(String id) { Button node = (Button) stage.getScene().lookup("#" + id); check(node != null, "missing " + id); return node; }
    private Label label(String id) { Label node = (Label) stage.getScene().lookup("#" + id); check(node != null, "missing " + id); return node; }
    private <T> T field(String name, Class<T> type) {
        try { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app)); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void await(int millis, BooleanSupplier condition, String context) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < deadline, "timeout " + context + "; status=" + value(() -> label("status").getText()) + "; search=" + value(() -> label("search-status").getText()));
            Thread.sleep(100);
        }
    }
    private static <T> T value(Supplier<T> supplier) {
        return value(supplier, 10);
    }
    private static <T> T value(Supplier<T> supplier, int timeoutSeconds) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> { try { future.complete(supplier.get()); } catch (Throwable error) { future.completeExceptionally(error); } });
        try { return future.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError("JavaFX action failed", error); }
    }
    private static void fx(Runnable action) { value(() -> { action.run(); return null; }); }
    private static int seconds() { return Integer.getInteger("lumen.smoke.longPlaybackSeconds", 300); }
    private static boolean normalization() { return Boolean.getBoolean("lumen.smoke.normalization"); }
    private static void log(String message) { System.out.println(message); System.out.flush(); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) { launch(args); }
}
