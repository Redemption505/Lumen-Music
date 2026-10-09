package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** End-to-end lyric timing checks with native page playback and a controlled long-video clock. */
public final class LyricPlaybackSmoke extends Application {
    private static final String LRC = "[00:00.00]first\n[00:01.00]next\n[00:05.00]last";
    private static final Track P1 = page(1);
    private static final Track P2 = page(2);
    private final DemoAudioLibrary demo = new DemoAudioLibrary();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger heads = new AtomicInteger(), gets = new AtomicInteger();
    private final CountDownLatch releaseLateLyrics = new CountDownLatch(1);
    private final CountDownLatch lateLyricsReceived = new CountDownLatch(1);
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "lyrics-smoke-http"); thread.setDaemon(true); return thread;
    });
    private HttpServer server;
    private LumenFxApp app;
    private Stage stage;
    private byte[] wave;
    private FakeClock fake;
    private LyricsSelectionStore store;
    private String phase = "startup";

    private static Track page(int page) {
        return new Track("bilibili:BV1nhMuzaEJr:p" + page, "Fixture P" + page,
                "Fixture", 8, "bilibili", null, "BV1nhMuzaEJr", page, null, 2);
    }

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(40_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL lyrics timeout: " + phase);
                cleanup(); System.exit(1);
            }
        }, "lyrics-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        safely(() -> {
            stage = initialStage;
            Path root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
            check(root.toString().contains("build"), "Isolated build profile required");
            String installed = System.getProperty("lumen.test.installedJar", "");
            if (!installed.isBlank()) {
                Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize();
                check(actual.equals(Path.of(installed).toAbsolutePath().normalize()),
                        "Installed production CodeSource mismatch: " + actual);
                System.out.println("INSTALLED CODE SOURCE: " + actual);
            }
            wave = Files.readAllBytes(Path.of(demo.resolve("demo-1")));
            store = new LyricsSelectionStore(root.resolve("lyrics-selections"));
            for (Track page : List.of(P1, P2))
                store.save(page, new LyricsSelectionStore.Selection(page.title(), page.artist(), LRC, 0));
            URI base = URI.create(required("LUMEN_SERVICE_URL"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", base.getPort()), 0);
            server.setExecutor(workers);
            server.createContext("/api/", this::respond);
            server.start();
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            PlayerQueue queue = field("queue", PlayerQueue.class);
            queue.select(List.of(P1, P2), 0);
            queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true);
            phase = "native P1 reaches its final lyric";
            waitFor(11_000, () -> audio() instanceof VlcAudioPlayer
                    && audio().state() == JavaFxAudioPlayer.State.PLAYING
                    && audio().position().toSeconds() > 5.2 && lyricIndex() == 2, this::advancePage);
        });
    }

    private void advancePage() throws Exception {
        check(heads.get() > 0 && gets.get() > 0, "P1 did not use HTTP HEAD/GET with real VLC");
        ((Button) node("next-button")).fire();
        check(field("queue", PlayerQueue.class).current().page() == 2, "Next button did not select P2");
        check(lyricIndex() == 0, "P2 cached first lyric read the old native position: " + lyricIndex());
        phase = "native P2 starts at zero and advances its lyric";
        waitFor(10_000, () -> audio() instanceof VlcAudioPlayer
                && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() > .1, () -> {
            check(audio().position().toSeconds() < 3, "P2 native position did not reset");
            waitFor(4_000, () -> lyricIndex() == 1, this::longVideo);
        });
    }

    private void longVideo() throws Exception {
        System.out.println("PASS native two-page VLC lyric timing; HEAD=" + heads + " GET=" + gets);
        audio().stop();
        fake = new FakeClock();
        set("audio", fake);
        Track longVideo = new Track("long-video", "Long medley", "Fixture", 3600, "demo");
        field("queue", PlayerQueue.class).select(List.of(longVideo), 0);
        long version = fieldLong("loadVersion") + 1;
        set("loadVersion", version);
        set("lyricMediaVersion", version);
        fake.millis = 18 * 60_000L;
        invoke("loadLyrics", new Class<?>[] {Track.class, long.class}, longVideo, version);
        LyricsClient.Candidate chosen = candidate(2, "EnhancedLRC", LRC);
        invoke("applyOnlineLyrics", new Class<?>[] {LyricsClient.Candidate.class, boolean.class}, chosen, false);
        DialogPane dialog = matchDialog();
        check(lyricIndex() == -1 && field("manualLyricSelection", LyricsSelectionStore.Selection.class) == null,
                "Unconfirmed long-video lyrics were applied at the final line");
        applyLongVideoCandidate(dialog, longVideo, chosen, version);
    }

    private void applyLongVideoCandidate(DialogPane dialog, Track longVideo,
                                         LyricsClient.Candidate chosen, long version) throws Exception {
        @SuppressWarnings("unchecked") ComboBox<LyricsClient.Candidate> candidates =
                (ComboBox<LyricsClient.Candidate>) dialog.lookup("#lyric-match-candidates");
        check(candidates.getItems().size() == 1 && chosen.equals(candidates.getValue()),
                "Long-video match dialog did not preserve the prefilled lyric candidate");
        check("EnhancedLRC".equals(((TextField) dialog.lookup("#lyric-match-title")).getText()),
                "Long-video match dialog did not prefill the candidate title");
        ((TextField) dialog.lookup("#lyric-match-start")).setText("18:00");
        ((Button) dialog.lookup("#lyric-match-apply")).fire();
        check(lyricIndex() == 0, "18:00 anchor did not show first lyric");
        fake.millis = 18 * 60_000L + 1_100;
        invoke("updateLyrics", new Class<?>[0]);
        check(lyricIndex() == 1, "18:01.1 did not advance to next lyric");
        phase = "long-video timing persistence";
        waitFor(2_000, () -> {
            try { return store.load(longVideo).map(s -> s.startMillis() == 18 * 60_000L).orElse(false); }
            catch (IOException error) { return false; }
        }, () -> {
            LyricsSelectionStore.Selection saved = store.load(longVideo).orElseThrow();
            check(saved.offsetMillis() == 0 && saved.lrc().equals(LRC), "Anchor/offset/LRC were not persisted");
            fake.millis = 0; invoke("updateLyrics", new Class<?>[0]);
            check(lyricIndex() == -1, "Seek before segment must show no lyric");
            invoke("loadLyrics", new Class<?>[] {Track.class, long.class}, longVideo, version);
            fake.millis = 18 * 60_000L + 1_100; invoke("updateLyrics", new Class<?>[0]);
            check(lyricIndex() == 1, "Restored anchor did not advance after seek");
            System.out.println("PASS controlled 18-minute long-video clock, anchor persistence and restore");
            lateSearch();
        });
    }

    private void lateSearch() throws Exception {
        phase = "late automatic lyric request cannot replace manual lyrics";
        ((TextField) node("lyrics-query")).setText("late");
        ((Button) node("search-lyrics")).fire();
        waitFor(3_000, () -> lateLyricsReceived.getCount() == 0, () -> {
            LyricsClient.Candidate manual = candidate(3, "Manual winner", LRC);
            invoke("applyOnlineLyrics", new Class<?>[] {LyricsClient.Candidate.class, boolean.class}, manual, false);
            releaseLateLyrics.countDown();
            waitFor(3_000, () -> lateDelivered.get(), () -> after(400, () -> {
                check(((Label) node("lyrics-status")).getText().contains("Manual winner"),
                        "Late automatic search replaced the manual choice");
                staleDialog();
            }));
        });
    }

    private final AtomicBoolean lateDelivered = new AtomicBoolean();

    private void staleDialog() throws Exception {
        phase = "old match dialog cannot write into a new track";
        ((Button) node("immersive-match-lyrics")).fire();
        DialogPane dialog = matchDialog();
        waitFor(3_000, () -> !((Button) dialog.lookup("#lyric-match-search")).isDisabled(),
                () -> applyStaleDialog(dialog));
    }

    private void applyStaleDialog(DialogPane dialog) throws Exception {
        @SuppressWarnings("unchecked") ComboBox<LyricsClient.Candidate> candidates =
                (ComboBox<LyricsClient.Candidate>) dialog.lookup("#lyric-match-candidates");
        candidates.getItems().setAll(candidate(4, "Stale", LRC)); candidates.setValue(candidates.getItems().get(0));
        Track next = new Track("next-demo", "Next demo", "Fixture", 8, "demo");
        field("queue", PlayerQueue.class).select(List.of(next), 0);
        long version = fieldLong("loadVersion") + 1;
        set("loadVersion", version); set("lyricMediaVersion", version);
        invoke("loadLyrics", new Class<?>[] {Track.class, long.class}, next, version);
        ((Button) dialog.lookup("#lyric-match-apply")).fire();
        check(field("manualLyricSelection", LyricsSelectionStore.Selection.class) == null,
                "Old dialog wrote a lyric choice into the new track");
        check(store.load(next).isEmpty(), "Old dialog persisted lyrics against the new track");
        System.out.println("PASS delayed search and stale-dialog guards (controlled clock)");
        finished.set(true); cleanup(); Platform.exit();
    }

    private static LyricsClient.Candidate candidate(int id, String title, String lrc) {
        return new LyricsClient.Candidate(id, title, "Fixture", 8, 100, "smoke", lrc);
    }

    private DialogPane matchDialog() {
        return Window.getWindows().stream().filter(window -> window instanceof Stage candidate
                        && "手动匹配歌词".equals(candidate.getTitle()))
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElseThrow();
    }

    private void respond(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/stream")) { respondAudio(exchange); return; }
            String xml;
            if (path.equals("/api/lyrics")) {
                String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                if (query.contains("late")) {
                    lateLyricsReceived.countDown();
                    releaseLateLyrics.await(5, TimeUnit.SECONDS);
                    xml = "<lyrics autoId=\"7\"><candidate id=\"7\" title=\"Late auto\" artist=\"Fixture\" duration=\"8\" score=\"100\" reason=\"smoke\"><lrc>"
                            + LRC + "</lrc></candidate></lyrics>";
                    lateDelivered.set(true);
                } else xml = "<lyrics/>";
            } else xml = switch (path) {
                case "/api/library" -> "<library version=\"1\"><favorites/><playlists/></library>";
                case "/api/ai-settings" -> "<ai-settings enabled=\"false\" keyConfigured=\"false\" model=\"fixture\" baseUrl=\"http://127.0.0.1\"/>";
                case "/api/listening" -> "<listening accepted=\"true\"/>";
                default -> "<tracks/>";
            };
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } catch (Exception ignored) { }
        finally { exchange.close(); }
    }

    private void respondAudio(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        int start = 0, end = wave.length - 1;
        if (range != null && range.startsWith("bytes=")) {
            String[] values = range.substring(6).split("-", -1);
            if (!values[0].isEmpty()) start = Integer.parseInt(values[0]);
            if (!values[1].isEmpty()) end = Math.min(end, Integer.parseInt(values[1]));
        }
        exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (start > end || start >= wave.length) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + wave.length);
            exchange.sendResponseHeaders(416, -1); return;
        }
        int length = end - start + 1;
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
        if (exchange.getRequestMethod().equals("HEAD")) { heads.incrementAndGet(); exchange.sendResponseHeaders(200, -1); }
        else {
            gets.incrementAndGet();
            if (range != null) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + wave.length);
            exchange.sendResponseHeaders(range == null ? 200 : 206, length);
            exchange.getResponseBody().write(wave, start, length);
        }
    }

    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private int lyricIndex() { return field("activeLyricIndex", Integer.class); }
    private Object node(String id) { Object found = stage.getScene().lookup("#" + id); check(found != null, "Missing #" + id); return found; }
    private <T> T field(String name, Class<T> type) {
        try { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private long fieldLong(String name) { return field(name, Long.class); }
    private void set(String name, Object value) throws Exception {
        Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); field.set(app, value);
    }
    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, arguments);
    }
    private void waitFor(int milliseconds, BooleanSupplier condition, Checked next) {
        poll(System.nanoTime() + milliseconds * 1_000_000L, condition, next);
    }
    private void after(int milliseconds, Checked next) {
        PauseTransition timer = new PauseTransition(Duration.millis(milliseconds));
        timer.setOnFinished(event -> safely(next)); timer.play();
    }
    private void poll(long deadline, BooleanSupplier condition, Checked next) {
        safely(() -> {
            if (condition.getAsBoolean()) { next.run(); return; }
            check(System.nanoTime() < deadline, "Timeout during " + phase + "; state=" + audio().state()
                    + "; position=" + audio().position() + "; lyric=" + lyricIndex());
            PauseTransition timer = new PauseTransition(Duration.millis(50));
            timer.setOnFinished(event -> poll(deadline, condition, next)); timer.play();
        });
    }
    private void safely(Checked action) {
        if (finished.get()) return;
        try { action.run(); }
        catch (Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            System.err.println("FAIL lyrics phase: " + phase); error.printStackTrace();
            cleanup(); System.exit(1);
        }
    }
    private void cleanup() {
        releaseLateLyrics.countDown();
        try { if (app != null) app.stop(); } catch (Exception ignored) { }
        try { if (stage != null) stage.close(); } catch (Exception ignored) { }
        if (server != null) server.stop(0);
        workers.shutdownNow(); demo.close();
    }
    private static String required(String key) {
        String value = System.getenv(key); check(value != null && !value.isBlank(), "Missing " + key); return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static final class FakeClock implements AudioPlayer {
        long millis;
        public JavaFxAudioPlayer.State state() { return JavaFxAudioPlayer.State.PAUSED; }
        public Duration position() { return Duration.millis(millis); }
        public Duration duration() { return Duration.hours(1); }
        public void setVolume(double value) { }
        public void seekFraction(double fraction) { millis = Math.round(fraction * 3_600_000); }
        public void load(URI uri, boolean autoplay) { }
        public void toggle() { }
        public void stop() { }
        public void close() { }
    }
}
