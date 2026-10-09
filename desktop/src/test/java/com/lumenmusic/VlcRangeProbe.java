package com.lumenmusic;

import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import java.net.URI;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Opt-in real-media probe; requires a VLC runtime and two Axum stream URLs. */
public final class VlcRangeProbe extends Application {
    private VlcAudioPlayer audio;
    private long startedNanos;
    private int track = 1;
    private int firstPlayingEvents;
    private boolean switching;
    private PauseTransition timeout;

    @Override public void start(Stage stage) {
        String first = System.getenv("LUMEN_MEDIA_PRIMARY");
        String second = System.getenv("LUMEN_MEDIA_SECONDARY");
        if (first == null || second == null) fail("缺少两条 Axum 测试音轨 URL");
        audio = new VlcAudioPlayer(state -> {
            System.out.printf("STATE track=%d state=%s time=%.3f duration=%.3f%n",
                    track, state, audio == null ? 0 : audio.position().toSeconds(),
                    audio == null ? 0 : audio.duration().toSeconds());
            if (state == JavaFxAudioPlayer.State.PLAYING && track == 1 && firstPlayingEvents++ == 0)
                after(1000, this::seekAndCheck);
            if (state == JavaFxAudioPlayer.State.PLAYING && track == 2 && !switching) {
                switching = true;
                after(1200, () -> {
                    double position = audio.position().toSeconds();
                    if (audio.state() != JavaFxAudioPlayer.State.PLAYING || position <= 0)
                        fail("切歌后第二条音轨未播放");
                    System.out.printf("PASS switch position=%.3f%n", position);
                    audio.close();
                    timeout.stop();
                    Platform.exit();
                });
            }
        }, this::fail);
        timeout = after(90_000, () -> fail("VLC 真实音轨实验超时"));
        startedNanos = System.nanoTime();
        audio.load(URI.create(first), true);
    }

    private void seekAndCheck() {
        double length = audio.duration().toSeconds();
        if (!Double.isFinite(length) || length < 40) fail("真实音轨时长无效：" + length);
        double before = audio.position().toSeconds();
        double target = length * 0.3;
        System.out.printf("SEEK target=%.3f before=%.3f startDelayMs=%d%n", target, before,
                (System.nanoTime() - startedNanos) / 1_000_000);
        audio.seekFraction(0.3);
        after(5000, () -> {
            double actual = audio.position().toSeconds();
            System.out.printf("SEEK after5s=%.3f delta=%.3f%n", actual, actual - target);
            if (Math.abs(actual - target) > 12) fail("远距离 seek 未生效");
            audio.toggle();
            double pausedAt = audio.position().toSeconds();
            after(1500, () -> {
                double pausedNow = audio.position().toSeconds();
                System.out.printf("PAUSE before=%.3f after1.5s=%.3f%n", pausedAt, pausedNow);
                if (Math.abs(pausedNow - pausedAt) > 0.8) fail("暂停后位置仍推进");
                audio.toggle();
                after(2000, () -> {
                    double resumed = audio.position().toSeconds();
                    System.out.printf("RESUME after2s=%.3f%n", resumed);
                    if (resumed < pausedNow + 0.5) fail("恢复后位置未推进");
                    track = 2;
                    audio.load(URI.create(System.getenv("LUMEN_MEDIA_SECONDARY")), true);
                });
            });
        });
    }

    private PauseTransition after(int milliseconds, Runnable action) {
        var delay = new PauseTransition(Duration.millis(milliseconds));
        delay.setOnFinished(event -> action.run());
        delay.play();
        return delay;
    }

    private void fail(String message) {
        System.err.println("FAIL: " + message);
        if (audio != null) audio.close();
        System.exit(1);
    }

    public static void main(String[] args) { launch(args); }
}
