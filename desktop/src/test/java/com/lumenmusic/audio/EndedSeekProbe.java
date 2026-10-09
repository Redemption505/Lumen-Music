package com.lumenmusic.audio;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

/** Checks the actual media engine's seek-then-replay behavior at end of media. */
public final class EndedSeekProbe {
    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            AudioPlayer[] current = new AudioPlayer[1];
            boolean[] restarted = { false };
            try {
                java.util.function.Consumer<JavaFxAudioPlayer.State> listener = state -> {
                    System.out.println("state=" + state + " position=" + current[0].position().toSeconds()
                            + " duration=" + current[0].duration().toSeconds());
                    if (state != JavaFxAudioPlayer.State.ENDED || restarted[0]) return;
                    restarted[0] = true;
                    double target = current[0].duration().toSeconds() / 2;
                    current[0].seekFraction(.5);
                    current[0].toggle();
                    PauseTransition check = new PauseTransition(Duration.millis(500));
                    check.setOnFinished(event -> {
                        try {
                            double actual = current[0].position().toSeconds();
                            if (current[0].state() != JavaFxAudioPlayer.State.PLAYING)
                                throw new AssertionError("replay did not report PLAYING: " + current[0].state());
                            if (!(actual >= target - .15 && actual <= target + .8))
                                throw new AssertionError("replay lost explicit seek: " + actual + " expected near " + target);
                            System.out.println("PASS " + args[0] + " explicit seek after ENDED: " + actual);
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            current[0].close();
                            done.countDown();
                        }
                    });
                    check.play();
                };
                java.util.function.Consumer<String> onError = message -> {
                    failure.set(new AssertionError(message));
                    done.countDown();
                };
                current[0] = args[0].equals("vlc")
                        ? new VlcAudioPlayer(listener, onError) : new JavaFxAudioPlayer(listener, onError);
                current[0].setVolume(0);
                current[0].load(Path.of(args[1]).toAbsolutePath().toUri(), true);
            } catch (Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        boolean completed = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!completed) throw new AssertionError("engine end-of-media check timed out");
        if (failure.get() != null) throw new AssertionError("engine end-of-media check failed", failure.get());
    }
}
