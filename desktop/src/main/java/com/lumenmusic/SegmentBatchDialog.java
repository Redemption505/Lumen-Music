package com.lumenmusic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** Editable, local timestamp draft. Saving never changes the real playback queue. */
final class SegmentBatchDialog {
    private final Dialog<Void> dialog = new Dialog<>();
    private final Track source;
    private final LibraryClient client;
    private final ExecutorService tasks;
    private final Consumer<LibraryClient.BatchResult> saved;
    private final Consumer<Track> playPreview;
    private final Runnable restorePreview;
    private final TableView<SegmentBatchDraft.Row> table = new TableView<>();
    private final TextArea input = new TextArea();
    private final TextField title = field("segment-batch-title");
    private final TextField artist = field("segment-batch-artist");
    private final TextField start = field("segment-batch-start");
    private final TextField end = field("segment-batch-end");
    private final CheckBox selected = new CheckBox("保存此片段");
    private final ComboBox<String> destination = new ComboBox<>();
    private final TextField playlistName = field("segment-batch-playlist-name");
    private final Label message = new Label();
    private final Button preview;
    private final Button save;
    private final Button parse;
    private List<SegmentBatchDraft.Validation> validations = List.of();
    private String batchId = UUID.randomUUID().toString();
    private boolean syncing;
    private boolean saving;

    SegmentBatchDialog(Window owner, Track source, LibraryClient client, ExecutorService tasks,
                       Consumer<LibraryClient.BatchResult> saved, Consumer<Track> playPreview,
                       Runnable restorePreview, Consumer<DialogPane> theme,
                       Function<String, Node> icons) {
        this.source = source.audioSource();
        this.client = client;
        this.tasks = tasks;
        this.saved = saved;
        this.playPreview = playPreview;
        this.restorePreview = restorePreview;
        dialog.initOwner(owner);
        dialog.setTitle("合集批量整理");
        dialog.setResizable(true);
        dialog.getDialogPane().setId("segment-batch-dialog");
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        theme.accept(dialog.getDialogPane());
        dialog.getDialogPane().getStyleClass().add("segment-batch-dialog");

        Label sourceName = new Label(this.source.title());
        sourceName.setWrapText(true);
        sourceName.setMaxHeight(42);
        sourceName.getStyleClass().add("section-title");
        sourceName.setTooltip(new Tooltip((this.source.bvid() == null ? this.source.title()
                : this.source.bvid() + " · P" + this.source.page())
                + (this.source.durationSeconds() > 0 ? " · " + LyricTiming.formatStart(this.source.durationSeconds() * 1000L) : " · 时长未知")));
        input.setId("segment-batch-input");
        input.setPromptText("0:00 第一首\n3:20 第二首");
        input.setPrefRowCount(3);
        input.setMinHeight(65);
        input.setPrefHeight(82);
        input.setMaxHeight(110);
        parse = command("生成候选", "segment-batch-parse", "list", icons);
        parse.setOnAction(event -> parse());
        HBox inputRow = new HBox(10, input, parse);
        inputRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(input, Priority.ALWAYS);

        table.setId("segment-batch-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(40);
        table.setMinHeight(100);
        table.setPrefHeight(180);
        table.setPlaceholder(new Label("暂无片段候选"));
        TableColumn<SegmentBatchDraft.Row, SegmentBatchDraft.Row> inclusion = new TableColumn<>("保存");
        inclusion.setCellValueFactory(value -> new ReadOnlyObjectWrapper<>(value.getValue()));
        inclusion.setMinWidth(48); inclusion.setMaxWidth(52);
        inclusion.setSortable(false);
        inclusion.setCellFactory(ignored -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            { setAlignment(Pos.CENTER); box.setOnAction(event -> {
                SegmentBatchDraft.Row row = getItem();
                if (row == null || saving) return;
                int index = table.getItems().indexOf(row);
                replace(index, new SegmentBatchDraft.Row(row.id(), row.line(), row.title(), row.artist(),
                        row.start(), row.end(), box.isSelected()));
            }); }
            @Override protected void updateItem(SegmentBatchDraft.Row row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) setGraphic(null);
                else { box.setSelected(row.selected()); box.setDisable(saving); setGraphic(box); }
            }
        });
        TableColumn<SegmentBatchDraft.Row, String> names = column("歌曲", row -> row.title());
        names.setMinWidth(120); names.setPrefWidth(235);
        names.getStyleClass().add("segment-batch-song");
        TableColumn<SegmentBatchDraft.Row, String> ranges = column("时间范围", row -> row.start() + " - " + row.end());
        ranges.setMinWidth(155); ranges.setPrefWidth(205);
        TableColumn<SegmentBatchDraft.Row, String> state = column("时长 / 状态", row -> {
            int index = table.getItems().indexOf(row);
            if (index < 0 || index >= validations.size()) return "";
            var checked = validations.get(index);
            if (!row.selected()) return "不保存";
            return checked.track() == null ? checked.message() : checked.track().durationSeconds() + " 秒";
        });
        state.setMinWidth(120); state.setPrefWidth(180);
        table.getColumns().addAll(inclusion, names, ranges, state);
        table.getSelectionModel().selectedItemProperty().addListener((ignored, before, after) -> syncEditor());

        selected.setId("segment-batch-selected");
        selected.setOnAction(event -> edit());
        for (TextField field : List.of(title, artist, start, end))
            field.textProperty().addListener((ignored, before, after) -> edit());
        GridPane editor = new GridPane();
        editor.setHgap(10); editor.setVgap(8);
        ColumnConstraints label = new ColumnConstraints(); label.setMinWidth(40);
        ColumnConstraints flexible = new ColumnConstraints(); flexible.setHgrow(Priority.ALWAYS);
        editor.getColumnConstraints().addAll(label, flexible, new ColumnConstraints(40), new ColumnConstraints());
        editor.getColumnConstraints().get(3).setHgrow(Priority.ALWAYS);
        editor.addRow(0, new Label("歌名"), title, new Label("歌手"), artist);
        editor.addRow(1, new Label("起点"), start, new Label("终点"), end);
        title.setMaxWidth(Double.MAX_VALUE); artist.setMaxWidth(Double.MAX_VALUE);
        start.setMaxWidth(Double.MAX_VALUE); end.setMaxWidth(Double.MAX_VALUE);
        preview = command("试听", "segment-batch-preview", "play", icons);
        preview.setOnAction(event -> {
            Track chosen = previewTrack();
            if (chosen == null) return;
            try { playPreview.accept(chosen); message.setText("正在试听：" + chosen.title()); }
            catch (IllegalArgumentException error) { message.setText(error.getMessage()); }
        });
        Button stopPreview = command("结束试听", "segment-batch-preview-stop", "stop", icons);
        stopPreview.setOnAction(event -> restorePreview.run());
        HBox previewRow = new HBox(10, selected, preview, stopPreview);
        previewRow.setAlignment(Pos.CENTER_LEFT);

        destination.setId("segment-batch-destination");
        destination.getItems().addAll("我的收藏", "新建歌单");
        destination.getSelectionModel().selectFirst();
        playlistName.setText(source.title());
        playlistName.setPromptText("歌单名称");
        playlistName.setVisible(false); playlistName.setManaged(false);
        destination.valueProperty().addListener((ignored, before, after) -> {
            playlistName.setVisible("新建歌单".equals(after));
            playlistName.setManaged(playlistName.isVisible()); refresh();
        });
        playlistName.textProperty().addListener((ignored, before, after) -> refresh());
        save = command("保存片段", "segment-batch-save", "heart", icons);
        save.getStyleClass().add("accent-button");
        save.setOnAction(event -> save());
        HBox actions = new HBox(10, destination, playlistName, save);
        actions.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(playlistName, Priority.ALWAYS);
        message.setId("segment-batch-message");
        message.getStyleClass().add("muted"); message.setWrapText(true); message.setMinHeight(30);
        VBox content = new VBox(10, sourceName, inputRow, table, editor, previewRow, actions, message);
        content.getStyleClass().add("segment-batch-content");
        content.setMinWidth(0); VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(560);
        dialog.getDialogPane().setPrefWidth(Math.min(800, Math.max(560, owner.getWidth() - 48)));
        dialog.getDialogPane().setPrefHeight(560);
        dialog.setOnHidden(event -> restorePreview.run());
        dialog.setOnShown(event -> dialog.getDialogPane().getScene().getWindow().addEventFilter(
                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST, close -> { if (saving) close.consume(); }));
        syncEditor(); refresh();
    }

    void show() { dialog.show(); }
    void close() { dialog.close(); }
    boolean isShowing() { return dialog.isShowing(); }

    private void parse() {
        try {
            var rows = SegmentBatchDraft.parse(input.getText(), source);
            batchId = UUID.randomUUID().toString();
            syncing = true; table.getItems().setAll(rows); syncing = false;
            table.getSelectionModel().selectFirst(); syncEditor(); refresh();
        } catch (IllegalArgumentException error) {
            save.setDisable(true); preview.setDisable(true);
            message.setText(error.getMessage());
        }
    }

    private void syncEditor() {
        if (syncing) return;
        var row = table.getSelectionModel().getSelectedItem();
        syncing = true;
        for (TextField field : List.of(title, artist, start, end)) field.setDisable(row == null || saving);
        selected.setDisable(row == null || saving);
        title.setText(row == null ? "" : row.title()); artist.setText(row == null ? "" : row.artist());
        start.setText(row == null ? "" : row.start()); end.setText(row == null ? "" : row.end());
        selected.setSelected(row != null && row.selected()); syncing = false;
        refresh();
    }

    private void edit() {
        if (syncing || saving) return;
        int index = table.getSelectionModel().getSelectedIndex();
        if (index < 0) return;
        var row = table.getItems().get(index);
        replace(index, new SegmentBatchDraft.Row(row.id(), row.line(), title.getText(), artist.getText(),
                start.getText(), end.getText(), selected.isSelected()));
    }

    private void replace(int index, SegmentBatchDraft.Row row) {
        if (index < 0) return;
        syncing = true; table.getItems().set(index, row); table.getSelectionModel().select(index); syncing = false;
        selected.setSelected(row.selected()); refresh();
    }

    private void refresh() {
        validations = table.getItems().isEmpty() ? List.of() : SegmentBatchDraft.validate(source, List.copyOf(table.getItems()));
        int count = 0, errors = 0;
        String firstError = "";
        for (int index = 0; index < validations.size(); index++) if (table.getItems().get(index).selected()) {
            count++;
            if (validations.get(index).track() == null) {
                errors++; if (firstError.isEmpty()) firstError = "第 " + table.getItems().get(index).line() + " 行：" + validations.get(index).message();
            }
        }
        if (save != null) save.setDisable(saving || count == 0 || errors > 0
                || (playlistName.isManaged() && playlistName.getText().isBlank()));
        if (preview != null) preview.setDisable(saving || previewTrack() == null);
        if (!saving) message.setText(errors > 0 ? firstError : count == 0 ? "暂无待保存片段" : "待保存 " + count + " 个片段");
        table.refresh();
    }

    private Track previewTrack() {
        int index = table.getSelectionModel().getSelectedIndex();
        if (index < 0) return null;
        var rows = new java.util.ArrayList<SegmentBatchDraft.Row>();
        for (int at = 0; at < table.getItems().size(); at++) {
            var row = table.getItems().get(at);
            rows.add(new SegmentBatchDraft.Row(row.id(), row.line(), row.title(), row.artist(), row.start(), row.end(), at == index));
        }
        return SegmentBatchDraft.validate(source, rows).get(index).track();
    }

    private void save() {
        refresh(); if (save.isDisabled()) return;
        var tracks = validations.stream().filter(item -> item.track() != null).map(SegmentBatchDraft.Validation::track).toList();
        String name = playlistName.isManaged() ? playlistName.getText() : null;
        saving = true;
        parse.setDisable(true); input.setDisable(true); table.setDisable(true);
        destination.setDisable(true); playlistName.setDisable(true);
        dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().get(0)).setDisable(true);
        syncEditor(); refresh(); message.setText("正在保存 " + tracks.size() + " 个片段…");
        tasks.submit(() -> {
            try {
                var result = client.saveSegments(tracks, name, batchId);
                Platform.runLater(() -> { saved.accept(result); dialog.close(); });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    saving = false; parse.setDisable(false); input.setDisable(false); table.setDisable(false);
                    destination.setDisable(false); playlistName.setDisable(false);
                    dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().get(0)).setDisable(false);
                    syncEditor(); refresh(); message.setText("保存失败：" + (error.getMessage() == null ? "曲库暂不可用" : error.getMessage()));
                });
            }
        });
    }

    private static TextField field(String id) { TextField field = new TextField(); field.setId(id); field.setMinWidth(0); return field; }

    private static Button command(String text, String id, String icon, Function<String, Node> icons) {
        Button button = new Button(text, icons.apply(icon)); button.setId(id); button.setTooltip(new Tooltip(text)); return button;
    }

    private static TableColumn<SegmentBatchDraft.Row, String> column(String label, Function<SegmentBatchDraft.Row, String> text) {
        TableColumn<SegmentBatchDraft.Row, String> column = new TableColumn<>(label);
        column.setCellValueFactory(value -> new ReadOnlyStringWrapper(text.apply(value.getValue())));
        column.setSortable(false);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Tooltip valueTooltip = FriendlyTooltips.configure(new Tooltip());
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(empty ? null : value);
                valueTooltip.setText(value);
                setTooltip(empty || value == null || value.isBlank() ? null : valueTooltip);
            }
        });
        return column;
    }
}
