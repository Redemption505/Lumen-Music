package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import java.io.BufferedOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Wall-clock endurance against the candidate JAR; uses production import/select/seek paths. */
public final class CandidateEnduranceSmoke extends Application {
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private volatile String phase = "startup";
    private volatile boolean finished;
    private Track first, second;
    private final boolean online = Boolean.getBoolean("lumen.candidate.online");
    private final int seconds = Integer.getInteger("lumen.candidate.seconds", 3600);
    private int switches, seeks;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep((seconds + 600L) * 1000); } catch (InterruptedException ignored) { return; }
            if (!finished) { System.err.println("FAIL candidate watchdog: " + phase); System.exit(1); }
        }, "candidate-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int code = 0;
            try { exercise(); }
            catch (Throwable error) { code = 1; System.err.println("FAIL candidate phase: " + phase); error.printStackTrace(System.err); }
            finally {
                try { fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); }); }
                catch (Throwable error) { code = 1; error.printStackTrace(System.err); }
                finished = true;
                Platform.exit();
                System.out.flush(); System.err.flush();
                if (code != 0) System.exit(code);
            }
        }, "candidate-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        validateIsolation();
        if (!online) createFixtures();
        value(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); return null; });
        await(20000, () -> value(() -> label("library-status").getText().equals("个人曲库已载入")), "isolated service library");
        if (online) { first = search("LUMEN_SMOKE_BV_PRIMARY"); second = search("LUMEN_SMOKE_BV_SECONDARY"); }
        else {
            phase = "production folder import";
            await(10000, () -> value(() -> field("localCatalogueReady", CompletableFuture.class).isDone()), "local catalogue readiness");
            fx(() -> invoke("importLocalPaths", new Class<?>[] { List.class, boolean.class }, List.of(root.resolve("audio")), false));
            await(20000, () -> value(() -> !field("localImportBusy", Boolean.class) && localTracks().size() == 2), "two imported synthetic WAV files");
            first = value(() -> localTracks().stream().filter(t -> t.title().equals("candidate-first")).findFirst().orElseThrow());
            second = value(() -> localTracks().stream().filter(t -> t.title().equals("candidate-second")).findFirst().orElseThrow());
            check(first.durationSeconds() >= 4200 && second.durationSeconds() >= 120, "production WAV metadata did not read source durations");
            log("FOLDER_IMPORT_PASS first=" + first + " duration=" + first.durationSeconds() + " secondDuration=" + second.durationSeconds());
        }
        check(!first.id().equals(second.id()), "samples must differ");
        LyricsSelectionStore lyrics = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory());
        lyrics.save(first, new LyricsSelectionStore.Selection(first.title(), first.artist(), "[00:00.00]candidate-first-only", 0));
        lyrics.save(second, new LyricsSelectionStore.Selection(second.title(), second.artist(), "[00:00.00]candidate-second-only", 0));
        select(first); awaitPlaying(first); verifyIdentity(first);
        if (seconds > 0) continuous();
        rapidSwitches();
        distantSeeks();
        verifyIdentity(first);
        String summary = "{\"mode\":\"" + (online ? "live-online" : "synthetic-local") + "\",\"requiredSeconds\":" + seconds
                + ",\"full60MinuteAcceptance\":" + (seconds >= 3600) + ",\"rapidSwitches\":" + switches + ",\"distantSeeks\":" + seeks
                + ",\"completedUtc\":\"" + Instant.now() + "\",\"scope\":\"candidate production JAR, real production audio engine clock; no acoustic/headphone or independent-machine acceptance\"}";
        Files.writeString(root.resolve("candidate-result.json"), summary);
        log("PASS: candidate runtime " + (online ? "live-online" : "synthetic-local") + " seconds=" + seconds + " switches=" + switches + " seeks=" + seeks);
    }

    private void continuous() throws Exception {
        phase = "actual wall-clock continuous playback";
        check(value(() -> audio().duration().toSeconds()) > seconds + 100, "source shorter than continuous sample");
        Path csv = root.resolve("native-endurance.csv");
        try (var writer = Files.newBufferedWriter(csv)) {
            writer.write("utc,wallSeconds,nativeSeconds,durationSeconds,state,heapBytes,fxRequestMillis\n"); writer.flush();
            long start = System.nanoTime(), lastAdvance = start;
            double initial = value(() -> audio().position().toSeconds()), before = initial;
            long previous = start;
            int count = 0;
            while ((System.nanoTime() - start) / 1e9 < seconds) {
                Thread.sleep(1000);
                long request = System.nanoTime();
                double[] sample = value(() -> {
                    verifyIdentity(first);
                    check(online ? audio() instanceof VlcAudioPlayer : audio() instanceof JavaFxAudioPlayer, "expected real production audio engine required");
                    check(audio().state() == JavaFxAudioPlayer.State.PLAYING, "unexpected native state " + audio().state());
                    return new double[] { audio().position().toSeconds(), audio().duration().toSeconds() };
                });
                long now = System.nanoTime();
                double wall = (now - start) / 1e9, gap = (now - previous) / 1e9;
                check(sample[0] >= before - .3, "clock went backwards without a seek");
                check(sample[0] - before < gap + 2, "clock advance faster than elapsed wall time");
                if (sample[0] > before + .05) lastAdvance = now;
                check(now - lastAdvance < TimeUnit.SECONDS.toNanos(15), "native clock stalled >=15 seconds");
                long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                writer.write(Instant.now() + "," + wall + "," + sample[0] + "," + sample[1] + ",PLAYING," + heap + "," + (now - request) / 1e6 + "\n");
                writer.flush();
                before = sample[0]; previous = now;
                if (++count % 30 == 0) log("LONG_SAMPLE wall=" + wall + " nativeAdvance=" + (before - initial) + " heap=" + heap);
            }
            double wall = (System.nanoTime() - start) / 1e9;
            check(before - initial >= seconds - 15, "insufficient native advancement: " + (before - initial));
            Files.writeString(root.resolve("long-pass.json"), "{\"wallSeconds\":" + wall + ",\"nativeAdvanceSeconds\":" + (before - initial) + ",\"samples\":" + count + "}");
            log("LONG_PASS wallSeconds=" + wall + " nativeAdvanceSeconds=" + (before - initial));
        }
    }

    private void rapidSwitches() throws Exception {
        phase = "20 rapid production selection changes";
        long before = value(() -> field("loadVersion", Long.class));
        for (int i = 0; i < 20; i++) {
            Track expected = i % 2 == 0 ? second : first;
            select(expected);
            check(value(() -> currentIs(expected)), "selection identity differs at switch " + (i + 1));
            switches++;
            log("SWITCH index=" + switches + " expected=" + expected.id() + " loadVersion=" + value(() -> field("loadVersion", Long.class)));
            Thread.sleep(90);
        }
        awaitPlaying(first);
        long after = value(() -> field("loadVersion", Long.class));
        check(after >= before + 20, "not all 20 switches reached production loading");
        for (int i = 0; i < 5; i++) { Thread.sleep(1000); verifyIdentity(first); check(value(() -> field("loadVersion", Long.class)) == after, "late load replaced final selection"); }
        log("RAPID_PASS switches=" + switches + " loadDelta=" + (after - before));
    }

    private void distantSeeks() throws Exception {
        phase = "10 far paused seek and real resume cycles";
        // Alternate between 15% and 80%: a distance of 65% on each source.
        for (int i = 0; i < 10; i++) {
            fx(() -> button("play-button").fire());
            await(5000, () -> value(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause before seek");
            double fraction = i % 2 == 0 ? .80 : .15;
            double target = value(() -> audio().duration().toSeconds()) * fraction;
            fx(() -> {
                Slider slider = (Slider) stage.getScene().lookup("#progress-slider");
                check(slider != null, "progress slider missing");
                slider.setValue(fraction);
                slider.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.RIGHT, false, false, false, false));
            });
            await(60000, () -> value(() -> currentIs(first) && audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(audio().position().toSeconds() - target) < 2), "far seek target " + target);
            double paused = value(() -> audio().position().toSeconds());
            Thread.sleep(500);
            check(value(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(audio().position().toSeconds() - paused) < .5), "seek resumed automatically");
            fx(() -> button("play-button").fire());
            await(10000, () -> value(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > paused + .5), "native advance after far seek");
            verifyIdentity(first); seeks++;
            log("SEEK_PASS index=" + seeks + " fraction=" + fraction + " target=" + target + " actual=" + value(() -> audio().position().toSeconds()) + " nativeUri=" + value(this::nativeUri));
        }
    }

    private void verifyIdentity(Track expected) {
        value(() -> {
            check(currentIs(expected), "queue or loaded track mismatch");
            check(field("immersiveTrackTitle", Label.class).getText().equals(expected.title()), "visible title mismatch");
            check(label("current-lyric").getText().equals(expected.equals(first) ? "candidate-first-only" : "candidate-second-only"), "lyric identity mismatch");
            if (!online) check(nativeUri().equals(expected.mediaUri()), "native source file mismatch");
            return null;
        });
    }

    private void createFixtures() throws Exception {
        phase = "self-generated PCM fixtures";
        Path dir = Files.createDirectory(root.resolve("audio"));
        writeWave(dir.resolve("candidate-first.wav"), Math.max(4320, seconds + 300), 440);
        writeWave(dir.resolve("candidate-second.wav"), 150, 660);
    }
    private static void writeWave(Path path, int seconds, int frequency) throws Exception {
        int rate = 8000, dataBytes = Math.multiplyExact(rate * 2, seconds);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes + 36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        ByteBuffer second = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rate; i++) second.putShort((short) Math.round(500 * Math.sin(2 * Math.PI * frequency * i / rate)));
        try (var out = new BufferedOutputStream(Files.newOutputStream(path))) {
            out.write(header.array()); for (int i = 0; i < seconds; i++) out.write(second.array());
        }
        log("SYNTHETIC_WAV " + path + " durationSeconds=" + seconds + " rate=" + rate + " toneHz=" + frequency);
    }

    private Track search(String variable) throws Exception {
        String bvid = System.getenv(variable);
        check(bvid != null && bvid.matches("BV[0-9A-Za-z]{10}"), "missing public BV");
        phase = "live public search " + bvid;
        fx(() -> { button("nav-search").fire(); ((TextField) stage.getScene().lookup("#search-query")).setText("https://www.bilibili.com/video/" + bvid + "/"); button("search-button").fire(); });
        await(40000, () -> value(() -> searchTracks().stream().anyMatch(t -> bvid.equals(t.bvid()))), "live BV search");
        Track result = value(() -> searchTracks().stream().filter(t -> bvid.equals(t.bvid())).findFirst().orElseThrow());
        check(result.mediaUri() == null && result.durationSeconds() > 50 && "bilibili".equals(result.source()), "invalid real online source");
        log("PUBLIC_TRACK " + result.id() + " duration=" + result.durationSeconds()); return result;
    }
    private void validateIsolation() throws Exception {
        root = Path.of(System.getenv("LUMEN_SMOKE_ROOT")).toRealPath();
        check(Files.isRegularFile(root.resolve("isolated-candidate.marker")), "isolation marker missing");
        for (String name : List.of("LUMEN_LIBRARY_PATH", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(System.getenv(name)).toAbsolutePath().normalize();
            check(path.getParent().equals(root) && !Files.exists(path), "fresh isolated state required: " + name);
        }
        for (String name : List.of("LUMEN_AI_API_KEY", "OPENAI_API_KEY", "LUMEN_AI_CHAT_COMPLETIONS_URL", "LUMEN_AI_MODEL")) check(System.getenv(name) == null || System.getenv(name).isBlank(), "inherited AI environment");
        check(Path.of(System.getenv("LOCALAPPDATA")).toRealPath().equals(root), "LOCALAPPDATA escapes profile");
        Path expected = Path.of(System.getProperty("lumen.smoke.installed.jar")).toRealPath();
        Path actual = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(expected.equals(actual), "wrong production JAR origin " + actual);
        log("PRODUCTION_ORIGIN " + actual + " mode=" + (online ? "live-online" : "synthetic-local") + " realWallSeconds=" + seconds);
    }
    private void select(Track track) { fx(() -> invoke("select", new Class<?>[] { List.class, int.class }, List.of(track), 0)); }
    private void awaitPlaying(Track expected) throws Exception { await(45000, () -> value(() -> currentIs(expected) && (online ? audio() instanceof VlcAudioPlayer : audio() instanceof JavaFxAudioPlayer) && audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > 1 && label("current-lyric").getText().equals(expected.equals(first) ? "candidate-first-only" : "candidate-second-only")), "native play " + expected.id()); }
    private boolean currentIs(Track expected) { Track loaded = field("loadedTrack", Track.class); return loaded != null && loaded.id().equals(expected.id()) && field("queue", PlayerQueue.class).current().id().equals(expected.id()); }
    @SuppressWarnings("unchecked") private List<Track> localTracks() { return field("localTracks", List.class); }
    private List<Track> searchTracks() { return ((ListView<?>) stage.getScene().lookup("#search-list")).getItems().stream().filter(Track.class::isInstance).map(Track.class::cast).toList(); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private URI nativeUri() { try { if (audio() instanceof VlcAudioPlayer) { Field f = VlcAudioPlayer.class.getDeclaredField("currentUri"); f.setAccessible(true); return (URI) f.get(audio()); } Field f = JavaFxAudioPlayer.class.getDeclaredField("player"); f.setAccessible(true); return URI.create(((javafx.scene.media.MediaPlayer) f.get(audio())).getMedia().getSource()); } catch (Exception e) { throw new AssertionError(e); } }
    private <T> T field(String name, Class<T> type) { try { Field f = LumenFxApp.class.getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(app)); } catch (Exception e) { throw new AssertionError(e); } }
    private void invoke(String name, Class<?>[] types, Object... args) { try { Method m = LumenFxApp.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(app, args); } catch (Exception e) { throw new AssertionError(e); } }
    private Button button(String id) { Button b = (Button) stage.getScene().lookup("#" + id); check(b != null, "missing button " + id); return b; }
    private Label label(String id) { Label l = (Label) stage.getScene().lookup("#" + id); check(l != null, "missing label " + id); return l; }
    private void await(int millis, BooleanSupplier condition, String message) throws Exception { long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "timeout " + message); Thread.sleep(100); } }
    private static <T> T value(Supplier<T> supplier) {
        if (Platform.isFxApplicationThread()) return supplier.get();
        CompletableFuture<T> result = new CompletableFuture<>(); Platform.runLater(() -> { try { result.complete(supplier.get()); } catch (Throwable e) { result.completeExceptionally(e); } });
        try { return result.get(30, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError("FX action failed", e); }
    }
    private static void fx(Runnable action) { value(() -> { action.run(); return null; }); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void log(String text) { System.out.println(text); System.out.flush(); }
    public static void main(String[] args) { launch(args); }
}
