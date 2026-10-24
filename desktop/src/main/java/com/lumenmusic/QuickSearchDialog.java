package com.lumenmusic;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Reusable command palette backed by a fresh local snapshot on each opening. */
public final class QuickSearchDialog {
    private final Dialog<Void> dialog = new Dialog<>();
    private final Window owner;
    private final TextField query = new TextField();
    private final ListView<QuickSearchIndex.Result> results = new ListView<>();
    private final Supplier<QuickSearchIndex> indexSupplier;
    private final Consumer<Track> playTrack;
    private final Consumer<LibraryClient.Playlist> openPlaylist;
    private final Consumer<DialogPane> applyTheme;
    private QuickSearchIndex index;
    private boolean ownerInitialized;

    public QuickSearchDialog(Window owner, Supplier<QuickSearchIndex> indexSupplier,
                             Consumer<Track> playTrack,
                             Consumer<LibraryClient.Playlist> openPlaylist,
                             Consumer<DialogPane> applyTheme) {
        this.owner = Objects.requireNonNull(owner);
        this.indexSupplier = Objects.requireNonNull(indexSupplier);
        this.playTrack = Objects.requireNonNull(playTrack);
        this.openPlaylist = Objects.requireNonNull(openPlaylist);
        this.applyTheme = Objects.requireNonNull(applyTheme);
        dialog.setTitle("快捷搜索");
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));

        query.setId("quick-search-query");
        query.setPromptText("搜索歌曲、歌单或操作");
        query.textProperty().addListener((observable, oldValue, newValue) -> refreshResults());

        results.setId("quick-search-results");
        results.setPrefHeight(390);
        results.setMinHeight(240);
        results.setCellFactory(ignored -> new ResultCell());
        VBox emptyBox = new VBox(6);
        emptyBox.setAlignment(Pos.CENTER);
        Label emptyTitle = new Label("无匹配结果");
        emptyTitle.getStyleClass().add("empty-state-title");
        Label emptyHint = new Label("尝试输入歌名、歌手、歌单名称或快捷命令");
        emptyHint.getStyleClass().add("muted");
        emptyBox.getChildren().addAll(emptyTitle, emptyHint);
        results.setPlaceholder(emptyBox);
        results.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) activateSelection();
        });

        HBox hints = new HBox(14);
        hints.getStyleClass().add("quick-search-hints");
        hints.setAlignment(Pos.CENTER_RIGHT);
        Label navHint = new Label("↑↓ 导航");
        Label enterHint = new Label("↵ 执行");
        Label escHint = new Label("ESC 关闭");
        navHint.getStyleClass().add("quick-search-hint-key");
        enterHint.getStyleClass().add("quick-search-hint-key");
        escHint.getStyleClass().add("quick-search-hint-key");
        hints.getChildren().addAll(navHint, enterHint, escHint);

        VBox content = new VBox(10, query, results, hints);
        content.setPadding(new Insets(6, 0, 0, 0));
        content.setPrefWidth(520);
        content.setMinWidth(0);
        VBox.setVgrow(results, Priority.ALWAYS);
        DialogPane pane = dialog.getDialogPane();
        pane.setId("quick-search-dialog");
        SmoothScrolling.install(pane);
        FriendlyTooltips.install(pane);
        pane.setContent(content);
        pane.setMinWidth(440);
        pane.setPrefWidth(540);
        pane.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        dialog.setOnShown(event -> query.requestFocus());
    }

    public void show() {
        if (dialog.isShowing()) {
            query.requestFocus();
            return;
        }
        if (!ownerInitialized) {
            if (owner.getScene() == null) throw new IllegalStateException("主窗口尚未就绪");
            dialog.initOwner(owner);
            ownerInitialized = true;
        }
        index = Objects.requireNonNull(indexSupplier.get(), "搜索索引不能为空");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().clear();
        pane.getStyleClass().removeAll("dark-theme", "light-theme", "background-theme",
                "dynamic-theme", "lumen-theme");
        applyTheme.accept(pane);
        query.clear();
        refreshResults();
        dialog.show();
    }

    public void close() { dialog.close(); }

    public boolean isShowing() { return dialog.isShowing(); }

    private void refreshResults() {
        if (index == null) return;
        List<QuickSearchIndex.Result> found = index.search(query.getText());
        results.getItems().setAll(found);
        if (!found.isEmpty()) results.getSelectionModel().selectFirst();
    }

    private void handleKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            dialog.close();
            event.consume();
        } else if (event.getCode() == KeyCode.ENTER) {
            activateSelection();
            event.consume();
        } else if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
            int count = results.getItems().size();
            if (count > 0) {
                int current = results.getSelectionModel().getSelectedIndex();
                int next = event.getCode() == KeyCode.DOWN
                        ? Math.min(count - 1, current + 1) : Math.max(0, current - 1);
                results.getSelectionModel().select(next);
                results.scrollTo(next);
            }
            event.consume();
        }
    }

    private void activateSelection() {
        QuickSearchIndex.Result selected = results.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        dialog.close();
        switch (selected.type()) {
            case TRACK -> playTrack.accept(selected.track());
            case PLAYLIST -> openPlaylist.accept(selected.playlist());
            case COMMAND -> selected.command().action().run();
        }
    }

    private final class ResultCell extends ListCell<QuickSearchIndex.Result> {
        @Override protected void updateItem(QuickSearchIndex.Result item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label kind = new Label(item.type().label());
            kind.getStyleClass().addAll("eyebrow", "quick-search-badge", "badge-" + item.type().name().toLowerCase(java.util.Locale.ROOT));
            kind.setMinWidth(44);
            kind.setAlignment(Pos.CENTER);
            Label title = new Label(item.title());
            title.setStyle("-fx-font-weight: bold;");
            title.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            Label subtitle = new Label(item.subtitle());
            subtitle.getStyleClass().add("muted");
            subtitle.setMinWidth(0);
            subtitle.setMaxWidth(Double.MAX_VALUE);
            subtitle.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox names = new VBox(2, title, subtitle);
            names.setMinWidth(0);
            HBox.setHgrow(names, Priority.ALWAYS);
            Label actionHint = new Label("↵");
            actionHint.getStyleClass().add("quick-search-cell-action");
            HBox row = new HBox(12, kind, names, actionHint);
            row.setAlignment(Pos.CENTER_LEFT);
            row.prefWidthProperty().bind(results.widthProperty().subtract(44));
            setGraphic(row);
        }
    }
}
