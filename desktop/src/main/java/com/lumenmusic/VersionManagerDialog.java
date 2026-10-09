package com.lumenmusic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** Explicit song grouping; the playing resource remains a separate snapshot. */
final class VersionManagerDialog {
    private final Dialog<Void> dialog = new Dialog<>();
    private final VersionCatalogClient client;
    private final MusicClient music;
    private final ExecutorService executor;
    private final Consumer<VersionCatalog> changed;
    private final Consumer<Track> play;
    private final Consumer<Track> browse;
    private final Track origin;
    private final List<Track> known;
    private final Consumer<DialogPane> theme;
    private VersionCatalog catalog;
    private VersionCatalog.Song song;
    private final TextField title = new TextField();
    private final TextArea note = new TextArea();
    private final TextField label = new TextField();
    private final TextField performer = new TextField();
    private final TextField candidateLabel = new TextField("其他版本");
    private final TextField candidatePerformer = new TextField();
    private final TextField query = new TextField();
    private final Label feedback = new Label();
    private final Label resource = new Label();
    private final ListView<VersionCatalog.Version> versions = new ListView<>();
    private final ComboBox<Track> candidates = new ComboBox<>();
    private final ComboBox<VersionCatalog.Song> groups = new ComboBox<>();
    private final VBox content = new VBox(12);
    private boolean busy;
    private long searchRequest;

    VersionManagerDialog(Window owner, Track origin, List<Track> known, VersionCatalog catalog,
                         VersionCatalogClient client, MusicClient music, ExecutorService executor,
                         Consumer<VersionCatalog> changed, Consumer<Track> play,
                         Consumer<Track> browse, Consumer<DialogPane> theme) {
        this.origin = origin;
        this.known = List.copyOf(known);
        this.theme = theme;
        this.catalog = catalog;
        this.client = client;
        this.music = music;
        this.executor = executor;
        this.changed = changed;
        this.play = play;
        this.browse = browse;
        dialog.initOwner(owner);
        dialog.setTitle("歌曲版本");
        dialog.setResizable(true);
        dialog.getDialogPane().setId("version-dialog");
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        theme.accept(dialog.getDialogPane());
        title.setId("version-song-title");
        note.setId("version-song-note");
        note.setPrefRowCount(2);
        note.setWrapText(true);
        label.setId("version-label");
        label.setPromptText("录音室 / 现场 / 翻唱 / 纯音乐");
        performer.setId("version-performer");
        performer.setPromptText("演唱者");
        candidateLabel.setId("version-candidate-label");
        candidatePerformer.setId("version-candidate-performer");
        candidatePerformer.setPromptText("演唱者");
        query.setId("version-query");
        query.setText(origin.title());
        versions.setId("version-list");
        versions.setMinWidth(0);
        versions.setPrefHeight(164);
        versions.setPlaceholder(new Label("保存歌曲后，在这里查看和整理版本"));
        SmoothListScroll.install(versions);
        versions.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(VersionCatalog.Version value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                setGraphic(null);
                if (empty || value == null) return;
                boolean preferred = VersionReading.preferred(song, value);
                VBox card = new VBox(5);
                card.getStyleClass().add("version-memory-card");
                boolean firstInGroup = getIndex() <= 0 || getIndex() > versions.getItems().size()
                        || preferred != VersionReading.preferred(song,
                        versions.getItems().get(getIndex() - 1));
                if (firstInGroup) {
                    Label heading = cellLabel(preferred ? "我常听的版本（偏好）" : "其他版本", "version-group-heading");
                    heading.setId(preferred ? "version-preferred-heading" : "version-other-heading");
                    card.getChildren().add(heading);
                }
                Label identity = cellLabel(VersionReading.label(value) + " · " + value.performer(), "version-identity");
                Label track = cellLabel(value.track().title(), "version-resource-title");
                Label source = cellLabel(describe(value.track()), "version-resource-caption");
                card.getChildren().addAll(identity, track, source);
                card.prefWidthProperty().bind(widthProperty().subtract(28));
                setGraphic(card);
            }
        });
        versions.getSelectionModel().selectedItemProperty().addListener((ignored, before, value) -> {
            resource.setText(value == null ? origin.toString() : describe(value.track()));
            if (value != null) {
                label.setText(value.label());
                performer.setText(value.performer());
            }
        });
        candidates.setId("version-candidate");
        candidates.setMinWidth(0);
        candidates.setMaxWidth(Double.MAX_VALUE);
        candidates.setCellFactory(ignored -> candidateCell());
        candidates.setButtonCell(candidateCell());
        candidates.valueProperty().addListener((ignored, before, value) -> {
            if (value != null) candidatePerformer.setText(value.artist());
        });
        feedback.setId("version-feedback");
        feedback.setWrapText(true);
        resource.setWrapText(true);
        resource.setId("version-resource");
        resource.setMinHeight(Region.USE_PREF_SIZE);
        resource.getStyleClass().add("muted");
        groups.setId("version-group-target");
        groups.setMinWidth(0);
        groups.setMaxWidth(Double.MAX_VALUE);
        groups.setCellFactory(ignored -> songCell());
        groups.setButtonCell(songCell());
        FlowPane groupActions = new FlowPane(8, 8,
                button("查看歌曲", "version-browse", () -> {
                    var target = groups.getValue();
                    if (target == null) { feedback.setText("请选择歌曲"); return; }
                    target.preferredTrack().ifPresent(track -> { dialog.close(); browse.accept(track); });
                }), button("归入所选歌曲", "version-merge", this::merge));
        HBox grouping = new HBox(8, groups);
        HBox.setHgrow(groups, Priority.ALWAYS);

        FlowPane songActions = new FlowPane(8, 8,
                button("保存歌曲", "version-save", this::saveSong),
                button("撤销上次整理", "version-undo", () -> mutate(client::undo, "已撤销")));
        FlowPane versionActions = new FlowPane(8, 8,
                button("保存版本信息", "version-save-info", () -> {
                    var value = selected();
                    if (value != null) mutate(() -> client.updateVersion(song.id(), value.id(),
                            nonblank(label.getText(), "原始版本"), nonblank(performer.getText(), value.track().artist())), "版本信息已保存");
                }),
                button("播放此版本", "version-play", () -> {
                    var value = selected();
                    if (value != null) { play.accept(value.track()); dialog.close(); }
                }),
                button("设为偏好", "version-prefer", () -> {
                    var value = selected();
                    if (value != null) mutate(() -> client.update(song.id(), title.getText(), note.getText(), value.id()), "已设置偏好版本");
                }),
                button("独立收藏", "version-favorite", () -> {
                    var value = selected();
                    if (value != null) {
                        busy = true;
                        content.setDisable(true);
                        executor.submit(() -> {
                            try {
                                new LibraryClient(serviceBase()).favorite(value.track());
                                Platform.runLater(() -> { changed.accept(this.catalog); done("已独立收藏此版本"); });
                            } catch (Exception error) { Platform.runLater(() -> done(error.getMessage())); }
                        });
                    }
                }),
                button("拆分版本", "version-detach", () -> {
                    var value = selected();
                    if (value != null && confirm("将此版本从歌曲中独立出来？", "原有收藏和歌单条目会保留。"))
                        mutate(() -> client.detach(song.id(), value.id()), "版本已独立");
                }));
        HBox search = new HBox(8, query, button("搜索资源", "version-search", this::search));
        query.setMinWidth(0);
        HBox.setHgrow(query, Priority.ALWAYS);
        HBox details = new HBox(8, label, performer);
        HBox.setHgrow(label, Priority.ALWAYS);
        HBox.setHgrow(performer, Priority.ALWAYS);
        label.setMinWidth(0);
        performer.setMinWidth(0);
        HBox candidateDetails = new HBox(8, candidateLabel, candidatePerformer);
        HBox.setHgrow(candidateLabel, Priority.ALWAYS);
        HBox.setHgrow(candidatePerformer, Priority.ALWAYS);
        candidateLabel.setMinWidth(0);
        candidatePerformer.setMinWidth(0);
        FlowPane candidateActions = new FlowPane(8, 8,
                button("加入版本", "version-add", this::add),
                button("替换所选资源", "version-replace", this::replace));
        Label actionHelp = new Label("播放此版本：使用所选版本的具体资源。设为偏好：仅记录个人偏好，供歌曲入口选择版本，不开始或切换当前播放。");
        actionHelp.setId("version-action-help");
        actionHelp.setWrapText(true);
        actionHelp.setMinHeight(Region.USE_PREF_SIZE);
        actionHelp.getStyleClass().add("version-resource-caption");
        content.getChildren().addAll(new Label("歌曲名称"), title, new Label("个人备注"), note,
                songActions, new Label("其他歌曲"), grouping, groupActions,
                new Label("歌曲版本"), versions, resource, details, versionActions, actionHelp,
                new Separator(), new Label("资源候选"), search, candidates, candidateDetails, candidateActions, feedback);
        content.setPadding(new Insets(8));
        content.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setId("version-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        SmoothScrollPane.install(scroll);
        scroll.setPrefViewportHeight(460);
        scroll.setPrefViewportWidth(580);
        dialog.getDialogPane().setContent(scroll);
        refresh(catalog);
        dialog.setOnHidden(event -> searchRequest++);
    }

    void show() { dialog.show(); }

    private static Label cellLabel(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(style);
        return label;
    }

    private static java.net.URI serviceBase() {
        return java.net.URI.create(System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765"));
    }

    private ListCell<Track> candidateCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Track track, boolean empty) {
                super.updateItem(track, empty);
                setText(empty || track == null ? null : (VersionCatalog.related(origin, track) ? "可能相关 · " : "")
                        + track.title() + " · " + track.artist());
            }
        };
    }

    private static ListCell<VersionCatalog.Song> songCell() {
        return new ListCell<>() {
            @Override protected void updateItem(VersionCatalog.Song song, boolean empty) {
                super.updateItem(song, empty);
                setText(empty || song == null ? null : song.title() + " · " + song.versions().size() + " 个版本");
            }
        };
    }

    private Button button(String text, String id, Runnable action) {
        Button value = new Button(text);
        value.setId(id);
        value.setOnAction(event -> { if (!busy) action.run(); });
        return value;
    }

    private VersionCatalog.Version selected() {
        var value = versions.getSelectionModel().getSelectedItem();
        if (value == null) feedback.setText("请先选择版本");
        return value;
    }

    private void refresh(VersionCatalog value) {
        catalog = value;
        String previous = song == null ? null : song.id();
        song = value.songs().stream().filter(item -> item.id().equals(previous)).findFirst()
                .orElseGet(() -> value.findByResourceId(origin.id()).orElse(null));
        title.setText(song == null ? origin.title() : song.title());
        note.setText(song == null ? "" : song.note());
        versions.setItems(FXCollections.observableArrayList(VersionReading.ordered(song)));
        if (song != null) versions.getSelectionModel().select(song.versions().stream()
                .filter(item -> item.id().equals(song.preferredVersionId())).findFirst().orElse(song.versions().get(0)));
        else { label.setText("原始版本"); performer.setText(origin.artist()); resource.setText(describe(origin)); }
        versions.refresh();
        groups.getItems().setAll(catalog.songs().stream()
                .filter(item -> song == null || !item.id().equals(song.id())).toList());
        if (!groups.getItems().isEmpty()) groups.getSelectionModel().selectFirst();
        List<Track> available = new ArrayList<>();
        for (Track track : known) {
            if (track.id().equals(origin.id()) || value.findByResourceId(track.id()).isPresent()) continue;
            if (available.stream().noneMatch(item -> item.id().equals(track.id()))) available.add(track);
        }
        available.sort(java.util.Comparator.comparing(track ->
                !VersionCatalog.related(origin, track)));
        candidates.getItems().setAll(available);
        if (!available.isEmpty()) candidates.getSelectionModel().selectFirst();
    }

    private void saveSong() {
        if (title.getText().isBlank()) { feedback.setText("歌曲名称不能为空"); return; }
        if (song == null) mutate(() -> client.create(title.getText(), note.getText(),
                List.of(new VersionCatalog.Version("", nonblank(label.getText(), "原始版本"),
                        nonblank(performer.getText(), origin.artist()), origin))), "歌曲已建立");
        else mutate(() -> client.update(song.id(), title.getText(), note.getText(), song.preferredVersionId()), "歌曲信息已保存");
    }

    private void add() {
        if (song == null) { feedback.setText("请先保存歌曲"); return; }
        Track candidate = candidates.getValue();
        if (candidate == null) { feedback.setText("请选择真实资源"); return; }
        if (!confirm("将所选资源加入这首歌曲？", describe(candidate))) return;
        mutate(() -> client.add(song.id(), nonblank(candidateLabel.getText(), "其他版本"),
                nonblank(candidatePerformer.getText(), candidate.artist()), candidate), "版本已加入");
    }

    private void merge() {
        var target = groups.getValue();
        if (target == null) { feedback.setText("请选择已有歌曲"); return; }
        if (!confirm("归入“" + target.title() + "”？", "保留所选歌曲的名称和偏好版本；现有备注、收藏与歌单条目保留。")) return;
        if (song == null) mutate(() -> client.add(target.id(), nonblank(label.getText(), "原始版本"),
                nonblank(performer.getText(), origin.artist()), origin), "已归入歌曲");
        else mutate(() -> client.merge(target.id(), song.id()), "歌曲已合并");
    }

    private void replace() {
        var version = selected();
        Track candidate = candidates.getValue();
        if (version == null || candidate == null) { feedback.setText("请选择版本和替换资源"); return; }
        if (!confirm("替换此版本的播放资源？", describe(candidate) + "\n歌单位置和个人备注保留，歌词与缓存使用新资源。")) return;
        mutate(() -> client.replace(song.id(), version.id(), candidate), "资源已替换；当前播放不受影响");
    }

    private void mutate(Callable<VersionCatalog> operation, String success) {
        busy = true;
        content.setDisable(true);
        feedback.setText("正在保存…");
        executor.submit(() -> {
            try {
                VersionCatalog result = operation.call();
                Platform.runLater(() -> { changed.accept(result); refresh(result); done(success); });
            } catch (Exception error) { Platform.runLater(() -> done(error.getMessage())); }
        });
    }

    private void done(String text) {
        busy = false;
        content.setDisable(false);
        feedback.setText(text == null ? "操作失败" : text);
    }

    private void search() {
        if (query.getText().isBlank()) return;
        String text = query.getText().strip();
        long request = ++searchRequest;
        feedback.setText("正在搜索…");
        executor.submit(() -> {
            try {
                List<Track> result = music.search(text);
                Platform.runLater(() -> {
                    if (request != searchRequest || !dialog.isShowing()) return;
                    candidates.getItems().setAll(result.stream()
                            .filter(track -> catalog.findByResourceId(track.id()).isEmpty()).toList());
                    if (!candidates.getItems().isEmpty()) candidates.getSelectionModel().selectFirst();
                    feedback.setText(candidates.getItems().size() + " 个可选资源");
                });
            } catch (Exception error) {
                Platform.runLater(() -> { if (request == searchRequest) feedback.setText("搜索失败：" + error.getMessage()); });
            }
        });
    }

    private boolean confirm(String heading, String detail) {
        return DestructiveConfirmation.confirm(dialog.getDialogPane().getScene().getWindow(), heading, detail, theme);
    }

    private static String nonblank(String value, String fallback) { return value.isBlank() ? fallback : value.strip(); }

    private static String describe(Track track) {
        String source = track.bvid() == null ? track.source() + " · " + track.title()
                : track.bvid() + " / P" + track.page();
        return source + (track.isSegment() ? " · " + LyricTiming.formatStart(track.segmentStartMillis())
                + "–" + LyricTiming.formatStart(track.segmentEndMillis()) : "");
    }
}
