package com.lumenmusic;

import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Local AI intent settings; the key is never shown again or included in backups. */
final class AiSettingsDialog {
    private AiSettingsDialog() { }

    static void show(Stage owner, AiSettingsClient client, Runnable saved) {
        Stage dialog = new Stage();
        dialog.setTitle("AI 推荐设置");
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        CheckBox enabled = new CheckBox("启用 AI 检索意图");
        TextField endpoint = new TextField();
        endpoint.setPromptText("https://…/v1/chat/completions");
        TextField model = new TextField();
        model.setPromptText("模型名称");
        PasswordField key = new PasswordField();
        key.setPromptText("留空则保留已保存的密钥");
        Label keyHint = new Label();
        boolean[] keyConfigured = {false};
        keyHint.getStyleClass().add("muted");
        Label status = new Label("正在读取配置…");
        status.setWrapText(true);
        Button save = new Button("保存设置");
        save.getStyleClass().add("accent-button");
        save.setDisable(true);
        Button close = new Button("关闭");
        close.setOnAction(event -> dialog.close());
        save.setOnAction(event -> {
            if (keyConfigured[0] && !key.getText().isBlank()
                    && !DestructiveConfirmation.confirm(dialog, "替换 API 密钥",
                    "将替换 1 个已保存的 API 密钥。旧密钥无法从应用中找回；保存后会使用新密钥。", null)) return;
            save.setDisable(true);
            status.setText("正在保存…");
            String submittedKey = key.getText().isBlank() ? null : key.getText();
            boolean submittedEnabled = enabled.isSelected();
            String submittedModel = model.getText();
            String submittedEndpoint = endpoint.getText();
            CompletableFuture.supplyAsync(() -> {
                try { return client.save(submittedEnabled, submittedModel,
                        submittedEndpoint, submittedKey); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).whenComplete((result, error) -> Platform.runLater(() -> {
                save.setDisable(false);
                if (error != null) {
                    status.setText("保存失败：" + errorMessage(error));
                    return;
                }
                key.clear();
                keyConfigured[0] = result.keyConfigured();
                keyHint.setText(result.keyConfigured() ? "密钥已保存，本次未显示" : "尚未设置密钥");
                status.setText("已保存；刷新首页推荐后生效");
                saved.run();
            }));
        });
        VBox content = new VBox(9, enabled, new Label("Chat Completions 地址"), endpoint,
                new Label("模型"), model, new Label("API 密钥"), key, keyHint, status,
                new javafx.scene.layout.HBox(9, save, close));
        content.setPadding(new Insets(22));
        content.getStyleClass().add("settings-dialog");
        SmoothScrolling.install(content);
        FriendlyTooltips.install(content);
        Scene scene = new Scene(content, 440, 340);
        scene.getStylesheets().add(AiSettingsDialog.class.getResource("/lumen-theme.css").toExternalForm());
        content.getStyleClass().addAll(owner.getScene().getRoot().getStyleClass().stream()
                .filter(style -> style.endsWith("-theme")).toList());
        content.setStyle(owner.getScene().getRoot().getStyle());
        dialog.setScene(scene);
        dialog.show();
        CompletableFuture.supplyAsync(() -> {
            try { return client.read(); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText("读取失败：" + errorMessage(error));
                return;
            }
            enabled.setSelected(result.enabled());
            endpoint.setText(result.baseUrl());
            model.setText(result.model());
            keyConfigured[0] = result.keyConfigured();
            keyHint.setText(result.keyConfigured() ? "密钥已保存，本次未显示" : "尚未设置密钥");
            status.setText("AI 只生成检索词，曲目来自真实搜索并在本地重排");
            save.setDisable(false);
        }));
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
