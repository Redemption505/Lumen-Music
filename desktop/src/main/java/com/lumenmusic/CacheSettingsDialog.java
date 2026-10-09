package com.lumenmusic;

import com.lumenmusic.audio.PersistentAudioCache;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Preferences for the optional complete-audio disk cache. */
final class CacheSettingsDialog {
    private CacheSettingsDialog() { }

    static void show(Stage owner, PersistentAudioCache cache) {
        Stage dialog = new Stage();
        dialog.setTitle("音频缓存");
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        VBox content = createPanel(cache);
        content.setPadding(new Insets(22));
        SmoothScrolling.install(content);
        Scene scene = new Scene(content, 450, 270);
        scene.getStylesheets().add(CacheSettingsDialog.class.getResource("/lumen-theme.css").toExternalForm());
        content.getStyleClass().addAll(owner.getScene().getRoot().getStyleClass().stream()
                .filter(style -> style.endsWith("-theme")).toList());
        content.setStyle(owner.getScene().getRoot().getStyle());
        dialog.setScene(scene);
        dialog.show();
    }

    static VBox createPanel(PersistentAudioCache cache) {
        CheckBox enabled = new CheckBox("缓存已播放的在线音频");
        enabled.setId("audio-cache-enabled");
        enabled.setSelected(cache.enabled());
        ComboBox<Integer> limit = new ComboBox<>(FXCollections.observableArrayList(256, 512, 1024, 2048));
        limit.setId("audio-cache-limit");
        limit.setValue((int) Math.max(256, Math.min(2048, cache.maxBytes() / (1024 * 1024))));
        Label usage = new Label();
        usage.setId("audio-cache-usage");
        Runnable refresh = () -> usage.setText("当前占用 " + cache.usedBytes() / (1024 * 1024)
                + " MiB · " + cache.entryCount() + " 首");
        refresh.run();
        Label status = new Label("默认关闭");
        status.setWrapText(true);
        Button save = new Button("保存设置");
        save.getStyleClass().add("accent-button");
        Button clear = new Button("清空缓存");
        clear.setId("audio-cache-clear");
        save.setId("audio-cache-save");
        save.setOnAction(event -> {
            save.setDisable(true);
            boolean selected = enabled.isSelected();
            long bytes = limit.getValue() * 1024L * 1024L;
            CompletableFuture.runAsync(() -> {
                try { cache.configure(selected, bytes); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).whenComplete((ignored, error) -> Platform.runLater(() -> {
                save.setDisable(false);
                if (error == null) {
                    refresh.run();
                    status.setText("缓存设置已保存");
                } else status.setText("保存失败：" + errorMessage(error));
            }));
        });
        clear.setOnAction(event -> {
            int count = cache.entryCount();
            if (count == 0) { status.setText("缓存已经为空"); return; }
            if (!DestructiveConfirmation.confirm(clear.getScene() == null ? null : clear.getScene().getWindow(),
                    "清空音频缓存", "将清空 " + count + " 首曲目的内部音频缓存（"
                    + cache.usedBytes() / (1024 * 1024) + " MiB）。后续播放需要重新获取音频，收藏、歌单和本地原文件会保留。", null)) return;
            clear.setDisable(true);
            status.setText("正在清理缓存…");
            CompletableFuture.runAsync(() -> {
                try { cache.clear(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).whenComplete((ignored, error) -> Platform.runLater(() -> {
                clear.setDisable(false);
                refresh.run();
                status.setText(error == null ? "内部音频缓存已清空"
                        : "清理失败：" + errorMessage(error));
            }));
        });
        VBox content = new VBox(8, enabled, new Label("容量上限（MiB）"), limit,
                usage, status, new HBox(9, save, clear));
        content.setUserData(refresh);
        content.getStyleClass().add("settings-dialog");
        FriendlyTooltips.install(content);
        return content;
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
