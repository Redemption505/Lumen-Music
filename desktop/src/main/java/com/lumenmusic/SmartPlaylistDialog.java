package com.lumenmusic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

/** Organizer for personal smart playlists, tags, folders and related resources. */
final class SmartPlaylistDialog {
    private record PlaylistItem(String id, String name, boolean smart, List<Track> tracks) {
        @Override public String toString() { return (smart ? "◇ " : "♫ ") + name; }
    }
    private record Related(Track first, Track second) {
        @Override public String toString() {
            return first.title() + " · " + first.artist() + "  /  " + second.title() + " · " + second.artist();
        }
    }
    private record FolderOption(String id, String name) {
        @Override public String toString() { return name; }
    }

    private final Dialog<Void> dialog = new Dialog<>();
    private final SmartPlaylistClient client;
    private final ExecutorService executor;
    private final Function<List<Track>, Set<String>> cachedIds;
    private final Supplier<List<LibraryClient.Playlist>> playlists;
    private final BiConsumer<List<Track>, Integer> play;
    private final Consumer<Track> versions;
    private final Runnable changed;
    private final Consumer<DialogPane> theme;
    private final TabPane tabs = new TabPane();
    private final Tab smartTab = new Tab("智能歌单");
    private final Tab tagsTab = new Tab("标签");
    private final Tab foldersTab = new Tab("文件夹");
    private final Tab relatedTab = new Tab("重复候选");
    private final Label feedback = new Label();
    private final ListView<SmartPlaylistClient.SmartPlaylist> smartList = new ListView<>();
    private final ListView<Track> results = new ListView<>();
    private final ListView<SmartPlaylistClient.Entry> entries = new ListView<>();
    private final ListView<PlaylistItem> folderItems = new ListView<>();
    private final ListView<Track> folderTracks = new ListView<>();
    private final ListView<Related> related = new ListView<>();
    private final TextField name = new TextField();
    private final CheckBox favoriteOnly = new CheckBox("仅收藏");
    private final TextField favoriteDays = field("收藏超过天数（可空）");
    private final ComboBox<String> listening = options("不限", "最近听过", "最近未听", "未记录收听");
    private final TextField listeningDays = field("收听天数");
    private final ComboBox<String> cached = options("不限", "已缓存", "未缓存");
    private final TextField minDuration = field("最短秒数");
    private final TextField maxDuration = field("最长秒数");
    private final TextField ruleTags = field("所有标签，逗号分隔");
    private final ComboBox<String> presets = options("自定义", "老收藏且近期未听", "已缓存纯音乐", "八分钟内现场");
    private final TextField tagInput = field("标签，逗号分隔");
    private final Label tagDetail = new Label();
    private final FlowPane suggestions = new FlowPane(6, 6);
    private final ComboBox<FolderOption> smartFolder = new ComboBox<>();
    private final ComboBox<FolderOption> folderFilter = new ComboBox<>();
    private final ComboBox<FolderOption> assignFolder = new ComboBox<>();
    private final TextField folderName = field("文件夹名称");
    private final Button saveFolder = new Button("保存文件夹");
    private final Button deleteFolder = new Button("删除文件夹");
    private final Label folderDetail = new Label();
    private final Label ruleNote = new Label("全部条件需同时满足；较早收藏但无收藏日期的曲目不会匹配天数条件");
    private final PauseTransition previewDelay = new PauseTransition(Duration.millis(400));
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(30), event -> refresh()));
    private SmartPlaylistClient.State state;
    private List<LibraryClient.Playlist> ordinary = List.of();
    private List<Track> resultSnapshot = List.of();
    private List<Track> folderSnapshot = List.of();
    private long loadRequest;
    private long previewRequest;
    private long lifecycle;
    private boolean filling;
    private boolean busy;
    private boolean errorLatched;
    private boolean trackManagementInstalled;

    SmartPlaylistDialog(Window owner, SmartPlaylistClient client, ExecutorService executor,
                        Function<List<Track>, Set<String>> cachedIds,
                        Supplier<List<LibraryClient.Playlist>> playlists,
                        BiConsumer<List<Track>, Integer> play, Consumer<Track> versions,
                        Runnable changed, Consumer<DialogPane> theme) {
        this.client = client;
        this.executor = executor;
        this.cachedIds = cachedIds;
        this.playlists = playlists;
        this.play = play;
        this.versions = versions;
        this.changed = changed;
        this.theme = theme;
        dialog.initOwner(owner);
        dialog.setTitle("整理曲库");
        dialog.setResizable(true);
        dialog.getDialogPane().setId("smart-playlist-dialog");
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        theme.accept(dialog.getDialogPane());
        feedback.setWrapText(true);
        feedback.setMinHeight(22);
        feedback.setId("organization-feedback");
        ruleNote.setWrapText(true);
        for (Tab tab : List.of(smartTab, tagsTab, foldersTab, relatedTab)) tab.setClosable(false);
        tabs.getTabs().addAll(smartTab, tagsTab, foldersTab, relatedTab);
        tabs.setMinWidth(0);
        tabs.setId("organization-tabs");
        setupSmart();
        setupTags();
        setupFolders();
        setupRelated();
        FlowPane navigation = new FlowPane(8, 8);
        navigation.setId("organization-navigation");
        navigation.setMinWidth(0);
        ToggleGroup navigationGroup = new ToggleGroup();
        for (Tab tab : tabs.getTabs()) {
            ToggleButton entry = new ToggleButton(tab.getText());
            entry.setId("organization-nav-" + tabs.getTabs().indexOf(tab));
            entry.getStyleClass().add("organization-nav-button");
            entry.setMinWidth(Region.USE_PREF_SIZE);
            entry.setToggleGroup(navigationGroup);
            entry.setSelected(tab == tabs.getSelectionModel().getSelectedItem());
            entry.setOnAction(event -> {
                tabs.getSelectionModel().select(tab);
                entry.setSelected(true);
            });
            tabs.getSelectionModel().selectedItemProperty().addListener((ignored, before, after) ->
                    entry.setSelected(after == tab));
            navigation.getChildren().add(entry);
        }
        VBox root = new VBox(8, navigation, tabs, feedback);
        root.setPadding(new Insets(8));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.setMinWidth(0);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(Math.max(440, Math.min(740, owner.getWidth() - 60)),
                Math.max(400, Math.min(660, owner.getHeight() - 80)));
        dialog.getDialogPane().setMinWidth(440);
        previewDelay.setOnFinished(event -> preview());
        timer.setCycleCount(Timeline.INDEFINITE);
        dialog.setOnShown(event -> { refresh(); timer.play(); });
        dialog.setOnHidden(event -> {
            timer.stop();
            previewDelay.stop();
            loadRequest++;
            previewRequest++;
            lifecycle++;
            busy = false;
        });
    }

    void show() { dialog.show(); }
    boolean isShowing() { return dialog.isShowing(); }
    void close() { dialog.close(); }
    void setTrackManagement(Function<ListView<Track>, Node> controls) {
        Objects.requireNonNull(controls);
        if (trackManagementInstalled) return;
        Node resultControls = Objects.requireNonNull(controls.apply(results));
        Node folderControls = Objects.requireNonNull(controls.apply(folderTracks));
        VBox resultBody = (VBox) results.getParent();
        VBox folderBody = (VBox) folderTracks.getParent();
        resultBody.getChildren().add(resultBody.getChildren().indexOf(results), resultControls);
        folderBody.getChildren().add(folderBody.getChildren().indexOf(folderTracks), folderControls);
        trackManagementInstalled = true;
    }
    void showForTrack(String id) { show(); selectTrack(id); }
    void selectTrack(String id) {
        if (id == null) return;
        tabs.getSelectionModel().select(tagsTab);
        if (state == null) { pendingTrackId = id; return; }
        for (int i = 0; i < entries.getItems().size(); i++) {
            if (entries.getItems().get(i).track().id().equals(id)) {
                entries.getSelectionModel().clearAndSelect(i);
                entries.scrollTo(i);
                break;
            }
        }
    }
    private String pendingTrackId;
    private String pendingPlaylistId;

    void selectPlaylist(String id) {
        if (id == null) return;
        tabs.getSelectionModel().select(smartTab);
        if (state == null) { pendingPlaylistId = id; return; }
        openSmart(id);
    }

    void refresh() {
        if (!dialog.isShowing() || busy) return;
        long request = ++loadRequest;
        List<LibraryClient.Playlist> ordinarySnapshot = List.copyOf(playlists.get());
        if (!errorLatched) feedback.setText("正在刷新整理结果…");
        executor.submit(() -> {
            try {
                SmartPlaylistClient.State result = client.load();
                Platform.runLater(() -> {
                    if (request != loadRequest || !dialog.isShowing()) return;
                    ordinary = ordinarySnapshot;
                    applyState(result);
                    if (!errorLatched) feedback.setText("已更新 · " + result.entries().size() + " 首资源");
                    preview();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (request == loadRequest && dialog.isShowing()) {
                        errorLatched = true;
                        feedback.setText("刷新失败：" + message(error));
                    }
                });
            }
        });
    }

    private void setupSmart() {
        smartList.setId("smart-playlist-list");
        smartList.setPrefHeight(126);
        SmoothListScroll.install(smartList);
        smartList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null || filling) return;
            fillRule(selected);
            clearPreview();
            preview();
        });
        name.setPromptText("智能歌单名称");
        name.setId("smart-name");
        favoriteOnly.setId("smart-favorite-only");
        favoriteDays.setId("smart-favorite-days");
        listening.setId("smart-listening");
        listeningDays.setId("smart-listening-days");
        cached.setId("smart-cached");
        minDuration.setId("smart-min-duration");
        maxDuration.setId("smart-max-duration");
        ruleTags.setId("smart-tags-all");
        presets.setId("smart-presets");
        presets.valueProperty().addListener((obs, old, value) -> applyPreset(value));
        smartFolder.setMaxWidth(Double.MAX_VALUE);
        smartFolder.setMinWidth(0);
        smartFolder.setId("smart-folder");
        favoriteDays.setDisable(true);
        favoriteOnly.selectedProperty().addListener((obs, old, value) -> favoriteDays.setDisable(!value));
        listening.valueProperty().addListener((obs, old, value) ->
                listeningDays.setDisable(!"最近听过".equals(value) && !"最近未听".equals(value)));
        listeningDays.setDisable(true);
        for (TextField field : List.of(favoriteDays, listeningDays, minDuration, maxDuration, ruleTags))
            field.textProperty().addListener((obs, old, value) -> schedulePreview());
        favoriteOnly.selectedProperty().addListener((obs, old, value) -> schedulePreview());
        listening.valueProperty().addListener((obs, old, value) -> schedulePreview());
        cached.valueProperty().addListener((obs, old, value) -> schedulePreview());
        results.setId("smart-preview-results");
        results.setPrefHeight(190);
        configureTrackList(results, () -> resultSnapshot);
        FlowPane actions = flow(button("新建", "smart-new", this::newSmart),
                button("保存规则", "smart-save", this::saveSmart), button("删除", "smart-delete", this::deleteSmart),
                button("预览", "smart-preview", () -> { errorLatched = false; preview(); }),
                button("另存普通歌单", "smart-snapshot", this::snapshot));
        FlowPane ruleLine = flow(favoriteOnly, favoriteDays, new Label("收听"), listening,
                listeningDays, new Label("缓存"), cached);
        FlowPane durationLine = flow(new Label("时长"), minDuration, maxDuration);
        VBox body = new VBox(8, new Label("智能歌单"), smartList, name,
                new Label("所属文件夹"), smartFolder, new Label("规则预设"), presets,
                ruleNote,
                ruleLine, durationLine, ruleTags, actions, new Label("当前匹配"), results);
        smartTab.setContent(scroll(body));
    }

    private void setupTags() {
        entries.setId("organization-track-list");
        tagInput.setId("organization-tag-input");
        entries.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        entries.setPrefHeight(280);
        SmoothListScroll.install(entries);
        entries.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(SmartPlaylistClient.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.track().title() + " · " + item.track().artist()
                        + (item.tags().isEmpty() ? "" : "  [" + String.join("、", item.tags()) + "]")
                        + " · " + (item.cached() == null ? "缓存未知" : item.cached() ? "已缓存" : "未缓存"));
            }
        });
        entries.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<SmartPlaylistClient.Entry>) change -> updateTagDetail());
        entries.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                SmartPlaylistClient.Entry selected = entries.getSelectionModel().getSelectedItem();
                if (selected != null) versions.accept(selected.track());
            }
        });
        tagDetail.setWrapText(true);
        suggestions.setPrefWrapLength(560);
        FlowPane actions = flow(button("添加", "organization-tag-add", () -> mutateTags("add")),
                button("移除", "organization-tag-remove", () -> mutateTags("remove")),
                button("替换全部标签", "organization-tag-replace", () -> mutateTags("replace")));
        VBox body = new VBox(8, new Label("曲目（可多选）"), entries, tagDetail,
                new Label("建议标签"), suggestions, tagInput, actions);
        tagsTab.setContent(scroll(body));
    }

    private void setupFolders() {
        folderFilter.setMaxWidth(Double.MAX_VALUE);
        folderFilter.setId("organization-folder-filter");
        folderName.setId("organization-folder-name");
        saveFolder.setId("organization-folder-save");
        deleteFolder.setId("organization-folder-delete");
        folderFilter.valueProperty().addListener((obs, old, value) -> {
            if (!filling) {
                folderName.setText(value == null || value.id() == null || value.id().isEmpty() ? "" : value.name());
                updateFolderItems();
            }
        });
        folderItems.setId("organization-folder-playlists");
        folderItems.setPrefHeight(150);
        SmoothListScroll.install(folderItems);
        folderItems.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            folderSnapshot = selected == null ? List.of() : List.copyOf(selected.tracks());
            folderTracks.getItems().setAll(folderSnapshot);
            folderDetail.setText(selected == null ? "" : selected.smart()
                    ? "智能歌单：到“智能歌单”页查看规则和结果" : folderSnapshot.size() + " 首曲目");
        });
        folderItems.setOnMouseClicked(event -> {
            PlaylistItem item = folderItems.getSelectionModel().getSelectedItem();
            if (item != null && item.smart() && event.getClickCount() == 2) openSmart(item.id());
        });
        folderTracks.setId("organization-folder-tracks");
        folderTracks.setPrefHeight(175);
        configureTrackList(folderTracks, () -> folderSnapshot);
        assignFolder.setMaxWidth(Double.MAX_VALUE);
        assignFolder.setId("organization-folder-assignment");
        FlowPane folderActions = flow(saveFolder, deleteFolder);
        saveFolder.setOnAction(event -> saveFolder());
        deleteFolder.setOnAction(event -> deleteFolder());
        FlowPane playlistActions = flow(button("分配到文件夹", "organization-folder-assign", this::assignFolder),
                button("查看智能规则", "organization-folder-open-smart", () -> {
                    PlaylistItem item = folderItems.getSelectionModel().getSelectedItem();
                    if (item != null && item.smart()) openSmart(item.id());
                }));
        folderDetail.setWrapText(true);
        VBox body = new VBox(8, new Label("文件夹"), folderFilter, folderName,
                folderActions, new Label("歌单"), folderItems, assignFolder, playlistActions,
                folderDetail, new Label("曲目"), folderTracks);
        foldersTab.setContent(scroll(body));
    }

    private void setupRelated() {
        related.setId("organization-related-list");
        related.setPrefHeight(430);
        SmoothListScroll.install(related);
        related.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                openRelated();
        });
        VBox body = new VBox(8, new Label("标题相近的资源"), related,
                flow(button("打开版本管理", "organization-open-version", this::openRelated)));
        relatedTab.setContent(scroll(body));
    }

    private void configureTrackList(ListView<Track> list, Supplier<List<Track>> snapshot) {
        list.setMinWidth(0);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        SmoothListScroll.install(list);
        list.setCellFactory(view -> new ListCell<>() {
            private final Button playButton = new Button("▶");
            private final Button versionButton = new Button("⋯");
            private final Label label = new Label();
            private final HBox row = new HBox(7, label, playButton, versionButton);
            {
                label.setMaxWidth(Double.MAX_VALUE);
                label.setMinWidth(0);
                HBox.setHgrow(label, Priority.ALWAYS);
                playButton.setTooltip(new Tooltip("播放曲目"));
                versionButton.setTooltip(new Tooltip("管理版本"));
                playButton.setId("organization-track-play");
                versionButton.setId("organization-track-version");
                playButton.setOnAction(event -> playTrack(list, snapshot.get(), getItem()));
                versionButton.setOnAction(event -> { if (getItem() != null) versions.accept(getItem()); });
                playButton.setOnMouseClicked(event -> event.consume());
                versionButton.setOnMouseClicked(event -> event.consume());
            }
            @Override protected void updateItem(Track item, boolean empty) {
                super.updateItem(item, empty);
                label.setText(empty || item == null ? "" : item.title() + " · " + item.artist());
                setGraphic(empty || item == null ? null : row);
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                playTrack(list, snapshot.get(), list.getSelectionModel().getSelectedItem());
        });
    }

    private void playTrack(ListView<Track> list, List<Track> current, Track track) {
        if (track == null) return;
        List<Track> immutable = List.copyOf(current);
        int index = immutable.indexOf(track);
        if (index >= 0) play.accept(immutable, index);
    }

    private void applyState(SmartPlaylistClient.State next) {
        state = next;
        String selectedSmart = smartList.getSelectionModel().getSelectedItem() == null ? null
                : smartList.getSelectionModel().getSelectedItem().id();
        filling = true;
        smartList.getItems().setAll(next.smartPlaylists());
        if (selectedSmart != null) next.smartPlaylists().stream().filter(item -> item.id().equals(selectedSmart))
                .findFirst().ifPresent(item -> smartList.getSelectionModel().select(item));
        Set<String> selectedEntries = new HashSet<>();
        for (var item : entries.getSelectionModel().getSelectedItems()) selectedEntries.add(item.track().id());
        entries.getItems().setAll(next.entries());
        entries.getSelectionModel().clearSelection();
        for (int i = 0; i < entries.getItems().size(); i++)
            if (selectedEntries.contains(entries.getItems().get(i).track().id()))
                entries.getSelectionModel().select(i);
        fillFolderOptions(next);
        filling = false;
        updateTagDetail();
        updateFolderItems();
        updateRelated(next.entries());
        if (pendingTrackId != null) {
            String id = pendingTrackId;
            pendingTrackId = null;
            selectTrack(id);
        }
        if (pendingPlaylistId != null) {
            String id = pendingPlaylistId;
            pendingPlaylistId = null;
            selectPlaylist(id);
        }
    }

    private void fillFolderOptions(SmartPlaylistClient.State next) {
        String filterId = folderFilter.getValue() == null ? null : folderFilter.getValue().id();
        String smartId = smartFolder.getValue() == null ? null : smartFolder.getValue().id();
        String assignId = assignFolder.getValue() == null ? null : assignFolder.getValue().id();
        List<FolderOption> all = new ArrayList<>();
        all.add(new FolderOption(null, "全部文件夹"));
        all.add(new FolderOption("", "未归类"));
        for (var folder : next.folders()) all.add(new FolderOption(folder.id(), folder.name()));
        folderFilter.getItems().setAll(all);
        smartFolder.getItems().setAll(all.subList(1, all.size()));
        assignFolder.getItems().setAll(all.subList(1, all.size()));
        selectFolder(folderFilter, filterId);
        selectFolder(smartFolder, smartId);
        selectFolder(assignFolder, assignId);
    }
    private static void selectFolder(ComboBox<FolderOption> box, String id) {
        box.getItems().stream().filter(item -> java.util.Objects.equals(item.id(), id)).findFirst()
                .ifPresentOrElse(box::setValue, () -> box.getSelectionModel().selectFirst());
    }

    private void updateFolderItems() {
        if (state == null || filling) return;
        FolderOption filter = folderFilter.getValue();
        String wanted = filter == null ? null : filter.id();
        Map<String, String> assignments = new LinkedHashMap<>();
        for (var assignment : state.assignments()) assignments.put(assignment.playlistId(), assignment.folderId());
        List<PlaylistItem> items = new ArrayList<>();
        for (var playlist : ordinary) {
            if (folderMatches(wanted, assignments.get(playlist.id())))
                items.add(new PlaylistItem(playlist.id(), playlist.name(), false, List.copyOf(playlist.tracks())));
        }
        for (var smart : state.smartPlaylists()) {
            String assigned = assignments.getOrDefault(smart.id(), smart.folderId());
            if (folderMatches(wanted, assigned))
                items.add(new PlaylistItem(smart.id(), smart.name(), true, List.of()));
        }
        String selected = folderItems.getSelectionModel().getSelectedItem() == null ? null
                : folderItems.getSelectionModel().getSelectedItem().id();
        folderItems.getItems().setAll(items);
        for (PlaylistItem item : items) if (item.id().equals(selected)) { folderItems.getSelectionModel().select(item); break; }
    }
    private static boolean folderMatches(String wanted, String actual) {
        if (wanted == null) return true;
        return wanted.isEmpty() ? actual == null || actual.isEmpty() : wanted.equals(actual);
    }

    private void updateRelated(List<SmartPlaylistClient.Entry> source) {
        List<Related> pairs = new ArrayList<>();
        Map<String, List<Track>> groups = new LinkedHashMap<>();
        for (var entry : source) {
            String title = VersionCatalog.normalizedTitle(entry.track().title());
            if (!title.isEmpty()) groups.computeIfAbsent(title, ignored -> new ArrayList<>()).add(entry.track());
        }
        outer: for (List<Track> group : groups.values()) {
            for (int i = 0; i < group.size(); i++) for (int j = i + 1; j < group.size(); j++) {
                Track first = group.get(i), second = group.get(j);
                if (VersionCatalog.related(first, second)) pairs.add(new Related(first, second));
                if (pairs.size() >= 150) break outer;
            }
        }
        related.getItems().setAll(pairs);
    }

    private void updateTagDetail() {
        List<SmartPlaylistClient.Entry> selected = List.copyOf(entries.getSelectionModel().getSelectedItems());
        tagDetail.setText(selected.isEmpty() ? "请选择曲目" : selected.size() == 1
                ? "已有标签：" + (selected.get(0).tags().isEmpty() ? "无" : String.join("、", selected.get(0).tags()))
                : "已选 " + selected.size() + " 首曲目");
        suggestions.getChildren().clear();
        if (selected.size() != 1) return;
        for (String suggestion : selected.get(0).suggestions()) {
            Button adopt = new Button("+ " + suggestion);
            adopt.setTooltip(new Tooltip("采用建议标签"));
            adopt.setOnAction(event -> {
                tagInput.setText(suggestion);
                mutateTags("add");
            });
            suggestions.getChildren().add(adopt);
        }
    }

    private void fillRule(SmartPlaylistClient.SmartPlaylist selected) {
        filling = true;
        name.setText(selected.name());
        SmartPlaylistClient.Rules rules = selected.rules();
        favoriteOnly.setSelected(rules.favoriteOnly());
        favoriteDays.setText(string(rules.favoriteOlderDays()));
        listening.setValue(switch (rules.listening()) {
            case "recent" -> "最近听过";
            case "notRecent" -> "最近未听";
            case "unrecorded" -> "未记录收听";
            default -> "不限";
        });
        listeningDays.setText(string(rules.listeningDays()));
        cached.setValue(switch (rules.cached()) { case "yes" -> "已缓存"; case "no" -> "未缓存"; default -> "不限"; });
        minDuration.setText(string(rules.minDuration()));
        maxDuration.setText(string(rules.maxDuration()));
        ruleTags.setText(String.join(", ", rules.tagsAll()));
        presets.setValue("自定义");
        selectFolder(smartFolder, selected.folderId() == null ? "" : selected.folderId());
        filling = false;
    }
    private static String string(Integer value) { return value == null ? "" : value.toString(); }

    private void applyPreset(String preset) {
        if (filling || preset == null || "自定义".equals(preset)) return;
        filling = true;
        favoriteOnly.setSelected("老收藏且近期未听".equals(preset));
        favoriteDays.setText("老收藏且近期未听".equals(preset) ? "30" : "");
        listening.setValue("老收藏且近期未听".equals(preset) ? "最近未听" : "不限");
        listeningDays.setText("老收藏且近期未听".equals(preset) ? "14" : "");
        cached.setValue("已缓存纯音乐".equals(preset) ? "已缓存" : "不限");
        minDuration.clear();
        maxDuration.setText("八分钟内现场".equals(preset) ? "480" : "");
        ruleTags.setText("已缓存纯音乐".equals(preset) ? "纯音乐"
                : "八分钟内现场".equals(preset) ? "现场" : "");
        filling = false;
        schedulePreview();
    }

    private SmartPlaylistClient.Rules readRules() {
        Integer favoriteAge = favoriteOnly.isSelected() ? number(favoriteDays) : null;
        String listeningMode = switch (listening.getValue()) {
            case "最近听过" -> "recent";
            case "最近未听" -> "notRecent";
            case "未记录收听" -> "unrecorded";
            default -> "any";
        };
        Integer days = "recent".equals(listeningMode) || "notRecent".equals(listeningMode)
                ? number(listeningDays) : null;
        if (("recent".equals(listeningMode) || "notRecent".equals(listeningMode)) && days == null)
            throw new IllegalArgumentException("请填写收听天数");
        Integer min = number(minDuration), max = number(maxDuration);
        if (min != null && max != null && min > max) throw new IllegalArgumentException("最短时长不能大于最长时长");
        return new SmartPlaylistClient.Rules(favoriteOnly.isSelected(), favoriteAge, listeningMode,
                days, "已缓存".equals(cached.getValue()) ? "yes" : "未缓存".equals(cached.getValue()) ? "no" : "any",
                min, max, splitTags(ruleTags.getText()));
    }
    private static Integer number(TextField input) {
        String text = input.getText().strip();
        if (text.isEmpty()) return null;
        try {
            int value = Integer.parseInt(text);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) { throw new IllegalArgumentException("请输入非负整数：" + input.getPromptText()); }
    }
    private static List<String> splitTags(String text) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : text.split("[,，]")) if (!raw.isBlank()) values.add(raw.strip());
        return List.copyOf(values);
    }

    private void newSmart() {
        smartList.getSelectionModel().clearSelection();
        filling = true;
        name.clear();
        favoriteOnly.setSelected(false);
        favoriteDays.clear();
        listening.setValue("不限");
        listeningDays.clear();
        cached.setValue("不限");
        minDuration.clear();
        maxDuration.clear();
        ruleTags.clear();
        presets.setValue("自定义");
        selectFolder(smartFolder, "");
        filling = false;
        schedulePreview();
    }
    private void saveSmart() {
        String title = name.getText().strip();
        if (title.isEmpty()) { feedback.setText("请填写智能歌单名称"); return; }
        try {
            SmartPlaylistClient.Rules rules = readRules();
            var selected = smartList.getSelectionModel().getSelectedItem();
            String folder = folderId(smartFolder);
            Set<String> previousIds = new HashSet<>();
            if (state != null) for (var item : state.smartPlaylists()) previousIds.add(item.id());
            mutate(() -> client.save(selected == null ? null : selected.id(), title, folder, rules),
                    "规则已保存", true, next -> {
                        if (selected == null) for (var item : next.smartPlaylists())
                            if (!previousIds.contains(item.id())) {
                                filling = true;
                                smartList.getSelectionModel().select(item);
                                filling = false;
                                break;
                            }
                    });
        } catch (Exception error) { feedback.setText(message(error)); }
    }
    private void deleteSmart() {
        var selected = smartList.getSelectionModel().getSelectedItem();
        if (selected == null) { feedback.setText("请选择智能歌单"); return; }
        if (!confirm("删除智能歌单", "将删除 1 个智能歌单：“" + selected.name()
                + "”。规则无法撤回；曲目与普通歌单会保留。")) return;
        mutate(() -> client.delete(selected.id()), "智能歌单已删除", true, next -> newSmart());
    }
    private void snapshot() {
        try {
            SmartPlaylistClient.Rules rules = readRules();
            String title = name.getText().strip();
            if (title.isEmpty()) title = "智能歌单快照";
            String snapshotName = title + "（快照）";
            List<Track> tracks = state == null ? List.of()
                    : state.entries().stream().map(SmartPlaylistClient.Entry::track).toList();
            String folder = folderId(smartFolder);
            mutate(() -> client.snapshot(null, snapshotName, folder, rules, cacheContext(rules, tracks)),
                    "普通歌单已创建", true);
        } catch (Exception error) { feedback.setText(message(error)); }
    }
    private void schedulePreview() {
        if (filling || !dialog.isShowing()) return;
        errorLatched = false;
        ++previewRequest;
        clearPreview();
        feedback.setText("规则已更改，正在重新预览…");
        previewDelay.playFromStart();
    }
    private void clearPreview() {
        resultSnapshot = List.of();
        results.getItems().clear();
    }
    private void preview() {
        if (state == null || busy || !dialog.isShowing()) return;
        SmartPlaylistClient.Rules rules;
        try { rules = readRules(); }
        catch (Exception error) { feedback.setText(message(error)); return; }
        long request = ++previewRequest;
        List<Track> tracks = state.entries().stream().map(SmartPlaylistClient.Entry::track).toList();
        if (!errorLatched) feedback.setText("正在计算匹配曲目…");
        executor.submit(() -> {
            try {
                SmartPlaylistClient.State next = client.preview(null, rules, cacheContext(rules, tracks));
                Platform.runLater(() -> {
                    if (request != previewRequest || !dialog.isShowing()) return;
                    resultSnapshot = List.copyOf(next.results());
                    results.getItems().setAll(resultSnapshot);
                    if (!errorLatched) feedback.setText("匹配 " + resultSnapshot.size() + " 首曲目");
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (request == previewRequest && dialog.isShowing() && !errorLatched)
                        feedback.setText("预览失败：" + message(error));
                });
            }
        });
    }
    private Set<String> checkedCacheIds(List<Track> tracks) {
        Set<String> ids = cachedIds.apply(tracks);
        if (ids == null) throw new IllegalStateException("无法读取缓存状态");
        return ids;
    }
    private Set<String> cacheContext(SmartPlaylistClient.Rules rules, List<Track> tracks) {
        return "any".equals(rules.cached()) ? null : checkedCacheIds(tracks);
    }

    private void mutateTags(String mode) {
        List<String> ids = entries.getSelectionModel().getSelectedItems().stream()
                .map(item -> item.track().id()).toList();
        if (ids.isEmpty()) { feedback.setText("请选择至少一首曲目"); return; }
        List<String> tags = splitTags(tagInput.getText());
        if (tags.isEmpty() && !"replace".equals(mode)) { feedback.setText("请填写标签"); return; }
        if ("replace".equals(mode) && !confirm("替换全部标签", "将替换 " + ids.size()
                + " 首曲目的全部已有标签。新标签：" + (tags.isEmpty() ? "无（清空全部标签）" : String.join("、", tags))
                + "。原标签无法撤回。")) return;
        if ("remove".equals(mode) && !confirm("移除标签", "将从 " + ids.size() + " 首曲目中移除标签："
                + String.join("、", tags) + "。此操作无法撤回。")) return;
        mutate(() -> client.tags(mode, ids, tags), "标签已更新", false);
    }
    private void saveFolder() {
        String title = folderName.getText().strip();
        if (title.isEmpty()) { feedback.setText("请填写文件夹名称"); return; }
        FolderOption selected = folderFilter.getValue();
        String id = selected == null || selected.id() == null || selected.id().isEmpty() ? null : selected.id();
        mutate(() -> client.folderSave(id, title), "文件夹已保存", false);
    }
    private void deleteFolder() {
        FolderOption selected = folderFilter.getValue();
        if (selected == null || selected.id() == null || selected.id().isEmpty()) {
            feedback.setText("请选择文件夹"); return;
        }
        if (!confirm("删除文件夹", "将删除 1 个文件夹：“" + selected.name()
                + "”。其中的歌单将变为未归类，歌单及曲目会保留。此操作无法撤回。")) return;
        mutate(() -> client.folderDelete(selected.id()), "文件夹已删除", false);
    }
    private void assignFolder() {
        PlaylistItem item = folderItems.getSelectionModel().getSelectedItem();
        if (item == null) { feedback.setText("请选择歌单"); return; }
        String id = folderId(assignFolder);
        mutate(() -> client.folderAssign(item.id(), id), "歌单已分配", false);
    }
    private static String folderId(ComboBox<FolderOption> box) {
        return box.getValue() == null ? "" : box.getValue().id();
    }
    private void openSmart(String id) {
        tabs.getSelectionModel().select(smartTab);
        for (var item : smartList.getItems()) if (item.id().equals(id)) {
            smartList.getSelectionModel().select(item);
            smartList.scrollTo(item);
            break;
        }
    }
    private void openRelated() {
        Related item = related.getSelectionModel().getSelectedItem();
        if (item == null) { feedback.setText("请选择重复候选"); return; }
        versions.accept(item.first());
    }

    private void mutate(Callable<SmartPlaylistClient.State> operation, String success, boolean ordinaryChanged) {
        mutate(operation, success, ordinaryChanged, ignored -> { });
    }
    private void mutate(Callable<SmartPlaylistClient.State> operation, String success,
                        boolean ordinaryChanged, Consumer<SmartPlaylistClient.State> afterApply) {
        if (busy) { feedback.setText("正在保存，请稍候"); return; }
        busy = true;
        errorLatched = false;
        long generation = lifecycle;
        ++loadRequest;
        ++previewRequest;
        feedback.setText("正在保存…");
        executor.submit(() -> {
            try {
                SmartPlaylistClient.State next = operation.call();
                Platform.runLater(() -> {
                    if (!dialog.isShowing() || generation != lifecycle) return;
                    busy = false;
                    errorLatched = false;
                    applyState(next);
                    afterApply.accept(next);
                    if (ordinaryChanged) changed.run();
                    feedback.setText(success);
                    preview();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!dialog.isShowing() || generation != lifecycle) return;
                    busy = false;
                    ++previewRequest;
                    previewDelay.stop();
                    errorLatched = true;
                    feedback.setText("保存失败：" + message(error));
                });
            }
        });
    }

    private boolean confirm(String title, String detail) {
        return DestructiveConfirmation.confirm(dialog.getDialogPane().getScene().getWindow(), title, detail, theme);
    }
    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private static TextField field(String prompt) {
        TextField value = new TextField();
        value.setPromptText(prompt);
        value.setPrefColumnCount(13);
        return value;
    }
    private static ComboBox<String> options(String... values) {
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(values));
        box.getSelectionModel().selectFirst();
        return box;
    }
    private static Button button(String label, Runnable action) {
        Button value = new Button(label);
        value.setOnAction(event -> action.run());
        return value;
    }
    private static Button button(String label, String id, Runnable action) {
        Button value = button(label, action);
        value.setId(id);
        return value;
    }
    private static FlowPane flow(javafx.scene.Node... nodes) {
        FlowPane value = new FlowPane(7, 7, nodes);
        value.setPrefWrapLength(560);
        return value;
    }
    private static ScrollPane scroll(VBox body) {
        body.setPadding(new Insets(8));
        body.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        SmoothScrollPane.install(scroll);
        return scroll;
    }
}
