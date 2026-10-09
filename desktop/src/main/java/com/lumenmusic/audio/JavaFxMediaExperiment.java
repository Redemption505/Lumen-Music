package com.lumenmusic.audio;

import java.nio.file.Path;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Temporary compressed-audio experiment; the production player follows this proof. */
public final class JavaFxMediaExperiment extends Application {
    private MediaPlayer player;
    private int starts;
    private PauseTransition timeout;

    @Override public void start(Stage stage) {
        String primary = System.getenv().getOrDefault("LUMEN_MEDIA_PRIMARY",
                "../build/audio-experiment/mdn-t-rex-roar.mp3");
        String primaryUri = primary.startsWith("http://") || primary.startsWith("https://")
                ? primary : Path.of(primary).toAbsolutePath().normalize().toUri().toString();
        Path wav = Path.of("../build/audio-experiment/tone-440.wav").toAbsolutePath().normalize();
        timeout = delay(40_000, () -> fail("播放实验超时"));
        try {
            var media = new Media(primaryUri);
            player = new MediaPlayer(media);
            player.setOnError(() -> fail("音频来源: " + player.getError()));
            player.setOnReady(() -> {
                System.out.println(primary + " READY duration=" + player.getTotalDuration());
                player.play();
            });
            player.setOnPlaying(() -> {
                starts++;
                if (starts == 1 && "1".equals(System.getenv("LUMEN_MEDIA_SEEK_TEST"))) {
                    delay(500, () -> {
                        var target = player.getTotalDuration().multiply(0.3);
                        System.out.println("SEEK target=" + target + " before=" + player.getCurrentTime());
                        player.seek(target);
                        delay(5_000, () -> {
                            System.out.println("SEEK after=" + player.getCurrentTime());
                            check(player.getCurrentTime().toSeconds() > target.toSeconds() * 0.5,
                                    "网络媒体拖动进度未生效");
                            player.dispose();
                            timeout.stop();
                            Platform.exit();
                        });
                    });
                } else if (starts == 1) delay(250, () -> {
                    check(player.getCurrentTime().toMillis() > 0, "播放位置未推进");
                    player.pause();
                });
                else delay(200, () -> {
                    check(player.getCurrentTime().toMillis() > 0, "恢复后位置未推进");
                    player.stop();
                    player.dispose();
                    playWav(wav);
                });
            });
            player.setOnPaused(() -> {
                System.out.println("PAUSED at=" + player.getCurrentTime());
                delay(150, player::play);
            });
        } catch (RuntimeException e) { fail("压缩音频初始化: " + e); }
    }

    private void playWav(Path wav) {
        try {
            player = new MediaPlayer(new Media(wav.toUri().toString()));
            player.setOnError(() -> fail("WAV: " + player.getError()));
            player.setOnReady(player::play);
            player.setOnPlaying(() -> delay(200, () -> {
                check(player.getCurrentTime().toMillis() > 0, "切换到 WAV 后未播放");
                player.stop();
                player.dispose();
                timeout.stop();
                System.out.println("PASS: JavaFX Media load/play/pause/resume, switch to WAV, dispose");
                Platform.exit();
            }));
        } catch (RuntimeException e) { fail("WAV 初始化: " + e); }
    }

    private PauseTransition delay(int milliseconds, Runnable action) {
        var timer = new PauseTransition(Duration.millis(milliseconds));
        timer.setOnFinished(event -> action.run());
        timer.play();
        return timer;
    }

    private void check(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private void fail(String message) {
        System.err.println("FAIL: " + message);
        if (player != null) player.dispose();
        System.exit(1);
    }

    public static void main(String[] args) { launch(args); }
}
