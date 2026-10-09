package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import uk.co.caprica.vlcj.player.base.Equalizer;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

/** Records actual engine gains on JavaFX, including the first 20-second loudness measurement. */
public final class V021AudioSmoke extends Application {
    static volatile Throwable failure;
    private record Sample(long nanos, String engine, String phase, double volume, double preamp,
                          JavaFxAudioPlayer.State state, double position, int equalizerIdentity) { }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private final List<Sample> samples = new ArrayList<>();
    private final List<PauseTransition> pending = new ArrayList<>();
    private final List<AnimationTimer> nativeConfirmations = new ArrayList<>();
    private Timeline clock;
    private AudioPlayer audio;
    private Path root;
    private String phase = "startup";
    private long began, lastPulse, lastAdvance;
    private double lastPosition = -1;
    private volatile boolean finished;
    private Equalizer originalEqualizer;

    @Override public void start(Stage unused) {
        Platform.setImplicitExit(false);
        began = System.nanoTime();
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(65_000); }
            catch (InterruptedException stopped) { return; }
            if (!finished) {
                failure = new AssertionError("Audio smoke timed out: " + phase);
                System.err.println(failure);
                System.exit(1);
            }
        }, "v021-audio-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        safely(() -> {
            root = Path.of(getParameters().getRaw().get(0));
            clock = new Timeline(new KeyFrame(Duration.millis(25), event -> safely(this::sample)));
            clock.setCycleCount(Animation.INDEFINITE);
            audio = new VlcAudioPlayer(state -> { }, message -> fail(new AssertionError(message)));
            audio.setVolume(.8);
            audio.setNormalizationEnabled(true);
            audio.load(root.resolve("loud-tone.wav").toUri(), true);
            originalEqualizer = equalizer();
            check(originalEqualizer != null, "Normalization equalizer was not installed before playback");
            check(Math.abs(originalEqualizer.preamp() - VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB) < .001,
                    "A new measurement must begin at unity gain");
            clock.play();
            waitFor(5_000, () -> audio.state() == JavaFxAudioPlayer.State.PLAYING,
                    () -> volumeSuite(this::awaitNormalization));
        });
    }

    private void sample() throws Exception {
        if (finished || audio == null) return;
        long now = System.nanoTime();
        double position = audio.position().toSeconds();
        var state = audio.state();
        if (lastPulse != 0) check(now - lastPulse < 2_000_000_000L, "JavaFX sampling was blocked for at least 2s");
        lastPulse = now;
        if (state != JavaFxAudioPlayer.State.PLAYING || position < lastPosition || position > lastPosition + .01)
            lastAdvance = now;
        else if (lastAdvance != 0) check(now - lastAdvance < 2_000_000_000L,
                "The playing media clock stalled for at least 2s in " + phase);
        lastPosition = position;
        Equalizer eq = equalizer();
        samples.add(new Sample(now, engine(), phase, nativeVolume(), eq == null ? Double.NaN : eq.preamp(),
                state, position, eq == null ? 0 : System.identityHashCode(eq)));
    }

    private void volumeSuite(Checked continuation) throws Exception {
        phase = "volume-baseline";
        long command = System.nanoTime();
        audio.setVolume(.8);
        expectNativeWithinPulse(.8, command, () -> startVolumeRamp(continuation));
    }

    private void startVolumeRamp(Checked continuation) throws Exception {
        phase = "volume-ramp";
        long start = System.nanoTime();
        audio.setOutputGain(.15, 1);
        check(Math.abs(nativeVolume() - .8) < .012, "Slider change applied an immediate volume jump");
        after(190, () -> {
            check(Math.abs(nativeVolume() - .15) < .012, "150ms volume ramp did not settle within pulse tolerance");
            long intermediates = samples.stream().filter(sample -> sample.nanos >= start
                    && sample.engine.equals(engine()) && sample.volume > .17 && sample.volume < .78)
                    .map(sample -> Math.round(sample.volume * 1000)).distinct().count();
            check(intermediates >= 2, "Native volume did not expose at least two intermediate gains");
            rapidRetarget(0, continuation);
        });
    }

    private void rapidRetarget(int index, Checked continuation) throws Exception {
        double[] targets = {.65, .2, .7, .4};
        String[] phases = {"retarget-up", "retarget-down", "retarget-up-again", "retarget-final"};
        retarget(targets[index], phases[index], () -> {
            if (index + 1 < targets.length) after(10, () -> rapidRetarget(index + 1, continuation));
            else after(190, () -> {
                check(Math.abs(nativeVolume() - .4) < .012, "Rapid retarget did not settle to the latest value");
                immediateMuteSuite(continuation);
            });
        });
    }

    private void retarget(double target, String nextPhase, Checked continuation) throws Exception {
        double previous = nativeVolume();
        phase = nextPhase;
        long start = System.nanoTime();
        audio.setOutputGain(target, 1);
        double current = nativeVolume();
        double reference = requestedVolume();
        check(Math.abs(current - previous) < .16, "Retarget created an audible volume discontinuity");
        boolean increasing = target >= reference;
        System.out.printf(Locale.ROOT, "RETARGET %s %s nativeBefore=%.3f nativeAfterSet=%.3f requestedStart=%.3f target=%.3f%n",
                engine(), nextPhase, previous, current, reference, target);
        after(35, () -> {
            double next = nativeVolume();
            check(increasing ? next >= reference - .025 : next <= reference + .025,
                    "Retarget kept moving toward the obsolete target");
            check(next >= Math.min(reference, target) - .025 && next <= Math.max(reference, target) + .025,
                    "Native retarget escaped the active ramp range");
            List<Sample> segment = samples.stream().filter(sample -> sample.nanos >= start
                    && sample.engine.equals(engine()) && sample.phase.equals(nextPhase)).toList();
            for (int i = 1; i < segment.size(); i++) {
                double delta = segment.get(i).volume - segment.get(i - 1).volume;
                check(increasing ? delta >= -.011 : delta <= .011, "Native volume bounced during rapid retarget");
            }
            continuation.run();
        });
    }

    private void immediateMuteSuite(Checked continuation) throws Exception {
        phase = "user-mute";
        long command = System.nanoTime();
        audio.setOutputGain(0, 1);
        expectNativeWithinPulse(0, command, () -> unmuteUser(continuation));
    }

    private void unmuteUser(Checked continuation) throws Exception {
        phase = "user-unmute";
        audio.setOutputGain(.4, 1);
        after(190, () -> {
            check(Math.abs(nativeVolume() - .4) < .012, "Unmute did not restore user volume");
            phase = "envelope-mute";
            long command = System.nanoTime();
            audio.setOutputGain(.4, 0);
            expectNativeWithinPulse(0, command, () -> changeVolumeWhileMuted(continuation));
        });
    }

    private void changeVolumeWhileMuted(Checked continuation) throws Exception {
        phase = "volume-change-muted";
        long start = System.nanoTime();
        audio.setOutputGain(.7, 0);
        after(190, () -> {
            List<Sample> muted = samples.stream().filter(sample -> sample.nanos >= start
                    && sample.engine.equals(engine()) && sample.phase.equals("volume-change-muted")).toList();
            check(!muted.isEmpty() && muted.stream().allMatch(sample -> sample.volume == 0),
                    "Changing user volume leaked through a muted envelope");
            check(nativeVolume() == 0, "Changing user volume leaked through a muted envelope");
            phase = "envelope-restore";
            long command = System.nanoTime();
            audio.setOutputGain(.7, 1);
            expectNativeWithinPulse(.7, command, () -> {
                phase = "immediate-override";
                long override = System.nanoTime();
                audio.setVolume(.35);
                expectNativeWithinPulse(.35, override, () -> {
                    System.out.println("PASS " + engine() + " native volume: ramp intermediates, retarget, mute and envelope restore");
                    continuation.run();
                });
            });
        });
    }

    private void expectNativeWithinPulse(double expected, long command, Checked next) {
        AnimationTimer confirmation = new AnimationTimer() {
            @Override public void handle(long ignored) {
                safely(() -> {
                    sample();
                    Sample observation = samples.get(samples.size() - 1);
                    double delayMillis = (observation.nanos - command) / 1_000_000.0;
                    boolean matched = expected == 0 ? observation.volume == 0
                            : Math.abs(observation.volume - expected) < .012;
                    if (matched) {
                        stop();
                        nativeConfirmations.remove(this);
                        check(delayMillis <= 50, "Native output reached " + expected + " after more than one pulse: " + delayMillis + "ms");
                        System.out.printf(Locale.ROOT, "NATIVE_APPLIED %s %s expected=%.3f actual=%.3f delayMillis=%.3f%n",
                                engine(), phase, expected, observation.volume, delayMillis);
                        next.run();
                    } else check(delayMillis < 50, "Native output did not reach " + expected + " within 50ms; actual=" + observation.volume);
                });
            }
        };
        nativeConfirmations.add(confirmation);
        confirmation.start();
    }

    private void awaitNormalization() {
        phase = "normalization-measurement";
        waitFor(27_000, () -> audio.normalizationStatus().startsWith("\u5df2\u6d4b"), () -> {
            phase = "normalization-ramp";
            after(1700, () -> {
                check(equalizer() == originalEqualizer, "Measurement completion replaced the live equalizer");
                double unity = VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB;
                double target = equalizer().preamp();
                check(target < unity - 2, "Loud fixture did not produce a negative normalization gain");
                long intermediates = samples.stream().filter(sample -> sample.engine.equals("vlc")
                        && sample.preamp < unity - .05 && sample.preamp > target + .05)
                        .map(sample -> Math.round(sample.preamp * 100)).distinct().count();
                check(intermediates >= 2, "Normalization did not expose intermediate native preamps");
                List<Sample> measured = samples.stream().filter(sample -> sample.engine.equals("vlc")).toList();
                checkGainSteps(measured);
                System.out.printf(Locale.ROOT, "PASS VLC measurement ramp: unity=%.3f final=%.3f intermediateValues=%d sameEqualizer=true%n",
                        unity, target, intermediates);
                pauseAndDisable();
            });
        });
    }

    private void pauseAndDisable() throws Exception {
        phase = "pause";
        audio.toggle();
        waitFor(2000, () -> audio.state() == JavaFxAudioPlayer.State.PAUSED, () -> {
            double pausedPosition = audio.position().toSeconds();
            double previous = equalizer().preamp();
            phase = "disable-normalization-paused";
            long start = System.nanoTime();
            audio.setNormalizationEnabled(false);
            check(Math.abs(equalizer().preamp() - previous) < .01, "Disabling normalization jumped gain");
            after(1700, () -> {
                check(audio.state() == JavaFxAudioPlayer.State.PAUSED, "Gain changes resumed paused playback");
                check(Math.abs(audio.position().toSeconds() - pausedPosition) < .15, "Paused media clock advanced");
                check(Math.abs(equalizer().preamp() - VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB) < .01,
                        "Disabling normalization did not restore unity");
                List<Sample> disabled = samples.stream().filter(sample -> sample.nanos >= start
                        && sample.phase.equals("disable-normalization-paused")).toList();
                checkGainSteps(disabled);
                check(disabled.stream().filter(sample -> sample.preamp > previous + .05
                        && sample.preamp < VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB - .05)
                        .map(sample -> Math.round(sample.preamp * 100)).distinct().count() >= 2,
                        "Disabling normalization did not smooth native gain");
                audio.toggle();
                waitFor(2000, () -> audio.state() == JavaFxAudioPlayer.State.PLAYING, this::cancelAndSwitch);
            });
        });
    }

    private void cancelAndSwitch() throws Exception {
        phase = "cancel-analysis";
        audio.setNormalizationEnabled(true);
        audio.load(root.resolve("quiet-tone.wav").toUri(), true);
        after(250, () -> {
            CompletableFuture<?> old = (CompletableFuture<?>) field(audio, "analysis");
            check(old != null && !old.isDone(), "Quiet analysis was not active before switching");
            audio.load(root.resolve("loud-tone.wav").toUri(), true);
            check(old.isCancelled(), "Switching tracks did not cancel the old analysis");
            check(equalizer() != originalEqualizer, "Switching tracks retained the disposed player's equalizer");
            check(equalizer().preamp() < VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB - 2,
                    "Cached destination measurement was not initialized before playback");
            audio.setNormalizationEnabled(false);
            phase = "after-cancel-switch";
            after(1800, () -> {
                check(Math.abs(equalizer().preamp() - VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB) < .01,
                        "Canceled track analysis contaminated the replacement player");
                check(audio.state() == JavaFxAudioPlayer.State.PLAYING, "Switching or disabling normalization stopped playback");
                System.out.println("PASS VLC pause preservation, smooth disable, canceled analysis and cached track switch");
                beginJavaFx();
            });
        });
    }

    private void beginJavaFx() throws Exception {
        audio.close();
        audio = new JavaFxAudioPlayer(state -> { }, message -> fail(new AssertionError(message)));
        lastPosition = -1;
        lastAdvance = 0;
        phase = "javafx-loading";
        audio.setVolume(.8);
        audio.load(root.resolve("loud-tone.wav").toUri(), true);
        waitFor(5000, () -> audio.state() == JavaFxAudioPlayer.State.PLAYING, () -> volumeSuite(this::succeed));
    }

    private static void checkGainSteps(List<Sample> values) {
        for (int i = 1; i < values.size(); i++) {
            Sample before = values.get(i - 1), after = values.get(i);
            if (before.equalizerIdentity != after.equalizerIdentity) continue;
            double seconds = (after.nanos - before.nanos) / 1e9;
            check(Math.abs(after.preamp - before.preamp) <= 16 * seconds + .06,
                    "Native normalization preamp jumped between samples");
        }
    }

    private double nativeVolume() throws Exception {
        Object nativePlayer = field(audio, "player");
        if (nativePlayer instanceof MediaPlayer vlc) return vlc.audio().volume() / 100.0;
        return ((javafx.scene.media.MediaPlayer) nativePlayer).getVolume();
    }

    private double requestedVolume() throws Exception {
        Object smoother = field(audio, "volume");
        Method current = smoother.getClass().getDeclaredMethod("current");
        current.setAccessible(true);
        return (Double) current.invoke(smoother);
    }

    private Equalizer equalizer() throws Exception {
        return audio instanceof VlcAudioPlayer ? (Equalizer) field(audio, "normalizationEqualizer") : null;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private String engine() { return audio instanceof VlcAudioPlayer ? "vlc" : "javafx"; }

    private void waitFor(long timeoutMillis, BooleanSupplier condition, Checked next) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        poll(deadline, condition, next);
    }

    private void poll(long deadline, BooleanSupplier condition, Checked next) {
        safely(() -> {
            if (condition.getAsBoolean()) next.run();
            else {
                check(System.nanoTime() < deadline, "Timed out in " + phase);
                after(40, () -> poll(deadline, condition, next));
            }
        });
    }

    private void after(long millis, Checked action) {
        PauseTransition pause = new PauseTransition(Duration.millis(millis));
        pending.add(pause);
        pause.setOnFinished(event -> {
            pending.remove(pause);
            safely(action);
        });
        pause.play();
    }

    private void safely(Checked action) {
        if (finished) return;
        try { action.run(); }
        catch (Throwable error) { fail(error); }
    }

    private void succeed() {
        System.out.println("PASS v0.2.1 audio native acceptance");
        finish(null);
    }

    private void fail(Throwable error) {
        if (finished) return;
        System.err.println("FAIL " + phase + ": " + error);
        finish(error);
    }

    private void finish(Throwable error) {
        if (finished) return;
        finished = true;
        failure = error;
        if (clock != null) clock.stop();
        for (var pause : pending) pause.stop();
        pending.clear();
        for (var confirmation : nativeConfirmations) confirmation.stop();
        nativeConfirmations.clear();
        try {
            if (audio != null) audio.close();
            if (root != null) {
                StringBuilder csv = new StringBuilder("elapsed_ms,engine,phase,volume,equalizer_preamp_db,state,position_seconds,equalizer_identity\n");
                for (Sample sample : samples) csv.append(String.format(Locale.ROOT,
                        "%.3f,%s,%s,%.6f,%.6f,%s,%.6f,%d%n", (sample.nanos - began) / 1e6,
                        sample.engine, sample.phase, sample.volume, sample.preamp, sample.state,
                        sample.position, sample.equalizerIdentity));
                Path report = root.resolve("audio-samples.csv");
                Files.writeString(report, csv, StandardCharsets.UTF_8);
                System.out.println("CSV: " + report + " rows=" + samples.size());
            }
        } catch (Throwable cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        } finally { Platform.exit(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
