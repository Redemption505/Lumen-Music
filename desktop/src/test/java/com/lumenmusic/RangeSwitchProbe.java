package com.lumenmusic;

import com.lumenmusic.audio.JavaFxAudioPlayer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.net.URI;

/** Actual JavaFX direct proxy switch probe; opt-in because it calls live Bilibili. */
public final class RangeSwitchProbe extends Application {
    private JavaFxAudioPlayer audio;
    private int track;
    private boolean switching;
    private PauseTransition timeout;

    @Override public void start(Stage stage) {
        String first = System.getenv("LUMEN_MEDIA_PRIMARY");
        String second = System.getenv("LUMEN_MEDIA_SECONDARY");
        if (first == null || second == null) fail("缺少两条测试音轨 URL");
        audio = new JavaFxAudioPlayer(state -> {
            if (state != JavaFxAudioPlayer.State.PLAYING) return;
            if (track == 1 && !switching) {
                switching = true;
                delay(700, () -> {
                    track = 2;
                    audio.load(URI.create(second), true);
                });
            } else if (track == 2) {
                delay(900, () -> {
                    if (audio.state() != JavaFxAudioPlayer.State.PLAYING
                            || audio.position().toMillis() <= 0)
                        fail("切换后第二条音轨未推进");
                    System.out.println("PASS: direct proxy playback switched to second real track, position="
                            + audio.position().toMillis() + "ms");
                    audio.close();
                    timeout.stop();
                    Platform.exit();
                });
            }
        }, this::fail);
        timeout = delay(45_000, () -> fail("直连代理切歌超时"));
        track = 1;
        audio.load(URI.create(first), true);
    }

    private PauseTransition delay(int milliseconds, Runnable action) {
        var timer = new PauseTransition(Duration.millis(milliseconds));
        timer.setOnFinished(event -> action.run());
        timer.play();
        return timer;
    }

    private void fail(String message) {
        System.err.println("FAIL: " + message);
        if (audio != null) audio.close();
        System.exit(1);
    }

    public static void main(String[] args) { launch(args); }
}
