package com.lumenmusic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;

/** A read-only playback view over personal listening history, with explicit private edits. */
public final class DiaryPane extends BorderPane implements AutoCloseable {
    private final DiaryClient client;
    private final Consumer<Track> play;
    private final BiConsumer<Track, Long> playLyric;
    private final Consumer<String> navigatePlaylist;
    private final Consumer<String> status;
    private Consumer<String> recoverReadStatus = ignored -> { };
    private String reportedReadFailure;
    private final Consumer<ContextMenu> themeMenu;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lumen-diary-io");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private int requestVersion;
    private int noteWriteVersion;
    private boolean editorUpdating;
    private boolean editorDirty;
    private boolean editorBusy;
    private DiaryClient.Diary diary;
    private Track selectedTrack;
    private Predicate<Track> isFavorite = track -> false;
    private Consumer<Track> toggleFavorite;
    private BiFunction<Track, List<Track>, ContextMenu> trackMenu;
    private Function<List<Track>, ContextMenu> batchMenu;
    private final Set<String> selectedTrackIds = new LinkedHashSet<>();
    private final List<CheckBox> trackChecks = new ArrayList<>();
    private final Map<Button, Track> favoriteButtons = new LinkedHashMap<>();
    private final Map<String, String> noteDrafts = new LinkedHashMap<>();
    private final List<PendingLyric> pendingLyrics = new ArrayList<>();
    private boolean filtersUpdating;
    private final ComboBox<String> noteDateFilter = new ComboBox<>();
    private final ComboBox<String> lyricDateFilter = new ComboBox<>();

    private static final class PendingLyric {
        final Track track;
        final long position;
        final String text;
        boolean saving;
        PendingLyric(Track track, long position, String text) {
            this.track = track;
            this.position = position;
            this.text = text;
        }
    }

    private final Label summary = new Label("正在读取听歌记录...");
    private final Label feedback = new Label();
    private final Button refresh = iconButton("刷新", "refresh");
    private final Button export = iconButton("导出", "export");
    private final ToggleButton batchMode = new ToggleButton();
    private final CheckBox selectAll = new CheckBox("全选");
    private final Label selectionCount = new Label("已选 0 首");
    private final Button batchMore = iconButton("批量操作", "more");
    private final HBox batchBar = new HBox(12, selectAll, selectionCount, batchMore);
    private final TabPane tabs = new TabPane();
    private final Tab overviewTab = new Tab("回顾");
    private final Tab notesTab = new Tab("备注");
    private final Tab lyricsTab = new Tab("歌词");
    private final VBox overview = new VBox(0);
    private final VBox notes = new VBox(0);
    private final VBox lyrics = new VBox(0);
    private final VBox editor = new VBox(9);
    private final Label editorTrack = new Label("选择一首曲目写下私人备注");
    private final TextArea noteText = new TextArea();
    private final Button saveNote = new Button("保存备注");
    private final Button deleteNote = new Button("删除备注");

    public DiaryPane(DiaryClient client, Consumer<Track> play, Consumer<String> navigatePlaylist,
                     Consumer<String> status, Consumer<ContextMenu> themeMenu) {
        this(client, play, (track, position) -> play.accept(track), navigatePlaylist, status, themeMenu);
    }

    public DiaryPane(DiaryClient client, Consumer<Track> play, BiConsumer<Track, Long> playLyric,
                     Consumer<String> navigatePlaylist, Consumer<String> status, Consumer<ContextMenu> themeMenu) {
        this.client = Objects.requireNonNull(client);
        this.play = Objects.requireNonNull(play);
        this.playLyric = Objects.requireNonNull(playLyric);
        this.navigatePlaylist = Objects.requireNonNull(navigatePlaylist);
        this.status = Objects.requireNonNull(status);
        this.themeMenu = Objects.requireNonNull(themeMenu);
        setId("diary-pane");
        SmoothScrolling.install(this);
        FriendlyTooltips.install(this);
        getStyleClass().add("diary-pane");

        Label title = new Label("最近播放");
        title.getStyleClass().add("diary-title");
        batchMode.setGraphic(iconButton("批量管理", "list").getGraphic());
        batchMode.setTooltip(new Tooltip("批量管理"));
        batchMode.setAccessibleText("批量管理");
        batchMode.getStyleClass().add("diary-icon-button");
        batchMode.setId("diary-batch-mode");
        batchMode.setDisable(true);
        batchMode.setOnAction(event -> {
            if (!batchMode.isSelected()) selectedTrackIds.clear();
            updateSelection();
        });
        HBox commands = new HBox(6, batchMode, refresh, export);
        refresh.setId("diary-refresh");
        export.setId("diary-export");
        commands.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(commands, Priority.ALWAYS);
        HBox heading = new HBox(12, title, commands);
        heading.setAlignment(Pos.CENTER_LEFT);
        summary.getStyleClass().add("diary-summary");
        summary.setId("diary-summary");
        summary.setWrapText(true);
        feedback.getStyleClass().add("diary-feedback");
        feedback.setId("diary-feedback");
        feedback.setMinHeight(18);
        feedback.setWrapText(true);
        batchBar.setId("diary-batch-bar");
        batchBar.setAlignment(Pos.CENTER_LEFT);
        batchBar.setVisible(false);
        batchBar.setManaged(false);
        selectAll.setId("diary-select-all");
        selectionCount.setId("diary-selection-count");
        batchMore.setId("diary-batch-more");
        batchMore.setOnAction(event -> {
            if (batchMenu == null || selectedTracks().isEmpty()) return;
            ContextMenu menu = batchMenu.apply(selectedTracks());
            int tabIndex = tabs.getSelectionModel().getSelectedIndex();
            if (tabIndex == 1 || tabIndex == 2) {
                MenuItem delete = new MenuItem(tabIndex == 1 ? "删除所选备注" : "删除所选歌曲的歌词片段");
                delete.setId(tabIndex == 1 ? "diary-batch-delete-notes" : "diary-batch-delete-lyrics");
                List<Track> selected = selectedTracks();
                delete.setOnAction(action -> deleteSelectedRecords(tabIndex, selected));
                menu.getItems().add(delete);
            }
            themeMenu.accept(menu);
            menu.show(batchMore, javafx.geometry.Side.BOTTOM, 0, 3);
        });
        selectAll.setOnAction(event -> {
            selectedTrackIds.clear();
            if (selectAll.isSelected()) visibleTracks().forEach(track -> selectedTrackIds.add(track.id()));
            updateSelection();
        });
        VBox header = new VBox(7, heading, summary, feedback, batchBar);
        header.getStyleClass().add("diary-header");
        setTop(header);

        overview.setId("diary-overview");
        notes.setId("diary-notes");
        lyrics.setId("diary-lyrics");
        overviewTab.setContent(scroll(overview));
        notesTab.setContent(scroll(notes));
        lyricsTab.setContent(scroll(lyrics));
        overviewTab.setId("diary-overview-tab");
        notesTab.setId("diary-notes-tab");
        lyricsTab.setId("diary-lyrics-tab");
        for (Tab tab : List.of(overviewTab, notesTab, lyricsTab)) tab.setClosable(false);
        tabs.getTabs().addAll(overviewTab, notesTab, lyricsTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((ignored, before, after) -> {
            selectedTrackIds.clear();
            updateSelection();
        });
        tabs.setId("diary-tabs");
        setCenter(tabs);

        configureDateFilter(noteDateFilter, "diary-note-date-filter");
        configureDateFilter(lyricDateFilter, "diary-lyric-date-filter");

        editor.getStyleClass().add("diary-editor");
        editorTrack.getStyleClass().add("diary-section-title");
        editorTrack.setWrapText(true);
        editorTrack.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        noteText.setPromptText("写下你与这首歌的回忆");
        noteText.setId("diary-note-input");
        noteText.setWrapText(true);
        noteText.setPrefRowCount(3);
        noteText.setMaxHeight(112);
        noteText.textProperty().addListener((ignored, before, after) -> {
            if (!editorUpdating) {
                editorDirty = true;
                if (selectedTrack != null) noteDrafts.put(selectedTrack.id(), after);
                deleteNote.setDisable(editorBusy || selectedTrack == null || after.isBlank());
            }
        });
        HBox editActions = new HBox(8, saveNote, deleteNote);
        saveNote.setId("diary-note-save");
        deleteNote.setId("diary-note-delete");
        editor.getChildren().addAll(editorTrack, noteText, editActions);
        saveNote.setOnAction(event -> saveSelectedNote(noteText.getText()));
        deleteNote.setOnAction(event -> saveSelectedNote(""));
        refresh.setOnAction(event -> refresh());
        export.setOnAction(event -> export());
        export.setDisable(true);
        renderEmpty();
    }

    /** Lets the owner clear only its still-current status for a resolved read failure. */
    public void setReadFailureRecovery(Consumer<String> recovery) {
        recoverReadStatus = Objects.requireNonNull(recovery);
    }

    public void refresh() {
        if (closed) return;
        feedback.setText("正在更新...");
        run(client::load, state -> {
            diary = state;
            render();
            feedback.setText("已更新");
            if (reportedReadFailure != null) {
                recoverReadStatus.accept(reportedReadFailure);
                reportedReadFailure = null;
            }
        }, true);
    }

    public void selectTrack(Track track) {
        if (closed || track == null) return;
        selectedTrack = track;
        editorTrack.setText(track.title() + " · " + track.artist());
        editorUpdating = true;
        String saved = diary == null ? "" : diary.notes().stream()
                .filter(note -> note.track() != null && note.track().id().equals(track.id()))
                .map(DiaryClient.Note::text).findFirst().orElse("");
        noteText.setText(noteDrafts.getOrDefault(track.id(), saved));
        editorUpdating = false;
        editorDirty = noteDrafts.containsKey(track.id());
        saveNote.setDisable(editorBusy);
        deleteNote.setDisable(editorBusy || noteText.getText().isBlank());
    }

    public void editTrackNote(Track track) {
        selectTrack(track);
        tabs.getSelectionModel().select(notesTab);
        noteText.requestFocus();
    }

    public void showLyricsTab() {
        if (closed) return;
        tabs.getSelectionModel().select(lyricsTab);
        refresh();
    }

    public void saveLyricFavorite(Track track, long positionMillis, String text) {
        if (closed) return;
        PendingLyric pending = new PendingLyric(track, positionMillis, text);
        pendingLyrics.add(pending);
        tabs.getSelectionModel().select(lyricsTab);
        savePendingLyric(pending);
    }

    private void savePendingLyric(PendingLyric pending) {
        if (pending.saving || closed) return;
        pending.saving = true;
        render();
        run(() -> client.saveLyric(pending.track, pending.position, pending.text), state -> {
            pendingLyrics.remove(pending);
            diary = state;
            render();
            tabs.getSelectionModel().select(lyricsTab);
            feedback.setText("已收藏歌词片段");
            status.accept("已收藏歌词片段");
        }, false, () -> {
            pending.saving = false;
            render();
        });
    }

    public TabPane tabs() { return tabs; }

    public void setTrackActions(Predicate<Track> isFavorite, Consumer<Track> toggleFavorite,
                                BiFunction<Track, List<Track>, ContextMenu> trackMenu,
                                Function<List<Track>, ContextMenu> batchMenu) {
        this.isFavorite = Objects.requireNonNull(isFavorite);
        this.toggleFavorite = Objects.requireNonNull(toggleFavorite);
        this.trackMenu = Objects.requireNonNull(trackMenu);
        this.batchMenu = Objects.requireNonNull(batchMenu);
        refreshTrackActions();
        updateSelection();
    }

    public void refreshTrackActions() {
        favoriteButtons.forEach((button, track) -> {
            boolean saved = track != null && isFavorite.test(track);
            button.setDisable(track == null || toggleFavorite == null);
            String hint = saved ? "取消收藏" : "收藏";
            if (button.getTooltip() == null) button.setTooltip(new Tooltip(hint));
            else if (!hint.equals(button.getTooltip().getText())) button.getTooltip().setText(hint);
            button.setAccessibleText(saved ? "取消收藏" : "收藏");
            button.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), saved);
        });
    }

    public List<Track> selectedTracks() {
        return visibleTracks().stream().filter(track -> selectedTrackIds.contains(track.id())).toList();
    }

    private List<Track> visibleTracks() {
        return tracksForTab(diary, tabs.getSelectionModel().getSelectedIndex(),
                noteDateFilter.getValue(), lyricDateFilter.getValue());
    }

    static List<Track> tracksForTab(DiaryClient.Diary diary, int tabIndex) {
        return tracksForTab(diary, tabIndex, DiaryReading.ALL, DiaryReading.ALL);
    }

    static List<Track> tracksForTab(DiaryClient.Diary diary, int tabIndex,
                                    String noteFilter, String lyricFilter) {
        if (diary == null) return List.of();
        Map<String, Track> tracks = new LinkedHashMap<>();
        Consumer<Track> collect = track -> {
            if (track != null && track.id() != null && !track.id().isBlank()) tracks.putIfAbsent(track.id(), track);
        };
        switch (tabIndex) {
            case 0 -> {
                for (String section : List.of("weekly-first", "reheard", "recent"))
                    diary.section(section).entries().forEach(entry -> collect.accept(entry.track()));
            }
            case 1 -> diary.notes().stream().filter(note -> DiaryReading.matches(
                    DiaryReading.noteTimestamp(note), diary.zone(), noteFilter))
                    .forEach(note -> collect.accept(note.track()));
            case 2 -> diary.lyricFavorites().stream().filter(lyric -> DiaryReading.matches(
                    lyric.createdAt(), diary.zone(), lyricFilter)).forEach(lyric -> collect.accept(lyric.track()));
            default -> { }
        }
        return List.copyOf(tracks.values());
    }

    private void updateSelection() {
        List<Track> visible = visibleTracks();
        Set<String> visibleIds = new LinkedHashSet<>();
        visible.forEach(track -> visibleIds.add(track.id()));
        selectedTrackIds.retainAll(visibleIds);
        boolean selecting = batchMode.isSelected();
        batchMode.setDisable(batchMenu == null || visible.isEmpty());
        batchBar.setVisible(selecting);
        batchBar.setManaged(selecting);
        for (CheckBox check : trackChecks) {
            check.setVisible(selecting);
            check.setManaged(selecting);
            check.setSelected(selectedTrackIds.contains(check.getProperties().get("trackId")));
        }
        int count = selectedTracks().size();
        selectionCount.setText("已选 " + count + " 首");
        selectAll.setDisable(visible.isEmpty());
        selectAll.setSelected(count > 0 && count == visible.size());
        selectAll.setIndeterminate(count > 0 && count < visible.size());
        batchMore.setDisable(batchMenu == null || count == 0);
    }

    private void saveSelectedNote(String text) {
        if (selectedTrack == null) return;
        Track track = selectedTrack;
        String beforeSave = noteText.getText();
        boolean hasSavedNote = diary != null && diary.notes().stream().anyMatch(note ->
                note.track() != null && track.id().equals(note.track().id()) && !note.text().isBlank());
        if (text.isBlank() && (hasSavedNote || !beforeSave.isBlank())
                && !confirmDelete("删除私人备注", "将删除“" + track.title()
                + "”的 1 条私人备注及当前草稿。删除后无法恢复，播放记录和曲目会保留。")) return;
        noteDrafts.putIfAbsent(track.id(), beforeSave);
        int writeVersion = ++noteWriteVersion;
        editorBusy = true;
        saveNote.setDisable(true);
        deleteNote.setDisable(true);
        run(() -> client.saveNote(track, text), state -> {
            diary = state;
            if (Objects.equals(noteDrafts.get(track.id()), beforeSave)) noteDrafts.remove(track.id());
            if (selectedTrack != null && selectedTrack.id().equals(track.id()))
                editorDirty = noteDrafts.containsKey(track.id());
            render();
            if (writeVersion == noteWriteVersion) {
                editorBusy = false;
                if (!editorDirty && selectedTrack != null && selectedTrack.id().equals(track.id()))
                    selectTrack(track);
                saveNote.setDisable(selectedTrack == null);
                deleteNote.setDisable(selectedTrack == null || noteText.getText().isBlank());
            }
            feedback.setText(text.isBlank() ? "已删除备注" : "已保存备注");
            status.accept(feedback.getText());
        }, false, () -> {
            if (writeVersion == noteWriteVersion) {
                editorBusy = false;
                saveNote.setDisable(selectedTrack == null);
                deleteNote.setDisable(selectedTrack == null || noteText.getText().isBlank());
            }
        });
    }

    private void removeLyric(String id) {
        DiaryClient.LyricFavorite lyric = diary == null ? null : diary.lyricFavorites().stream()
                .filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (lyric == null) return;
        String trackName = lyric.track() == null ? "未知曲目" : lyric.track().title();
        if (!confirmDelete("删除歌词片段", "将删除“" + trackName + "”的 1 条歌词片段：\n“"
                + lyric.text() + "”\n删除后无法恢复，曲目会保留。")) return;
        run(() -> client.deleteLyric(id), state -> {
            diary = state;
            render();
            feedback.setText("已删除歌词片段");
            status.accept("已删除歌词片段");
        }, false);
    }

    private void deleteSelectedRecords(int tabIndex, List<Track> tracks) {
        Set<String> ids = new LinkedHashSet<>();
        tracks.forEach(track -> ids.add(track.id()));
        List<DiaryClient.Note> notesToDelete = diary.notes().stream()
                .filter(note -> note.track() != null && ids.contains(note.track().id())).toList();
        List<DiaryClient.LyricFavorite> lyricsToDelete = diary.lyricFavorites().stream()
                .filter(lyric -> lyric.track() != null && ids.contains(lyric.track().id())
                        && DiaryReading.matches(lyric.createdAt(), diary.zone(), lyricDateFilter.getValue())).toList();
        int count = tabIndex == 1 ? notesToDelete.size() : lyricsToDelete.size();
        if (count == 0) return;
        String objects = tabIndex == 1 ? "私人备注" : "歌词片段";
        String names = tracks.stream().limit(3).map(Track::title).collect(java.util.stream.Collectors.joining("、"));
        if (tracks.size() > 3) names += "等 " + tracks.size() + " 首曲目";
        if (!confirmDelete("删除所选" + objects, "将删除“" + names + "”的 " + count + " 条" + objects
                + "。删除后无法恢复，播放记录和曲目会保留。")) return;
        run(() -> {
            DiaryClient.Diary state = diary;
            if (tabIndex == 1) {
                for (DiaryClient.Note note : notesToDelete) state = client.saveNote(note.track(), "");
            } else {
                for (DiaryClient.LyricFavorite lyric : lyricsToDelete) state = client.deleteLyric(lyric.id());
            }
            return state;
        }, state -> {
            diary = state;
            selectedTrackIds.clear();
            render();
            feedback.setText(tabIndex == 1 ? "已删除所选备注" : "已删除所选歌词片段");
            status.accept(feedback.getText());
        }, false);
    }

    private boolean confirmDelete(String title, String detail) {
        return DestructiveConfirmation.confirm(getScene() == null ? null : getScene().getWindow(), title, detail, null);
    }

    private void renderEmpty() {
        overview.getChildren().setAll(empty("还没有可回看的记录"));
        notes.getChildren().setAll(editor, dateFilterRow(noteDateFilter), empty("还没有私人备注"));
        lyrics.getChildren().setAll(dateFilterRow(lyricDateFilter));
        addPendingLyrics();
        lyrics.getChildren().add(empty("还没有收藏歌词片段"));
        saveNote.setDisable(selectedTrack == null || editorBusy);
        deleteNote.setDisable(selectedTrack == null || editorBusy || noteText.getText().isBlank());
    }

    private void render() {
        if (diary == null) { renderEmpty(); return; }
        trackChecks.clear();
        favoriteButtons.clear();
        var s = diary.summary();
        summary.setText("本月有效播放 " + s.thisMonthPlays() + " 次  ·  "
                + "详细记录累计：音源 " + s.sourceCount() + "  ·  曲目 " + s.trackCount()
                + "  ·  歌曲 " + s.songCount()
                + (s.unknownFirstCount() > 0 ? "  ·  首听时间未知 " + s.unknownFirstCount() : ""));
        export.setDisable(false);
        overview.getChildren().clear();
        addSection("上周第一次听到", diary.section("weekly-first"), "上周没有可确认的首次收听");
        addSection("最近重听的老收藏", diary.section("reheard"), "近期没有符合条件的重听记录");
        addSection("本月常听歌单", diary.section("monthly-playlists"), "本月还没有从歌单播放的记录");
        addSection("最近听过", diary.section("recent"), "还没有有效播放记录");

        var noteGroups = DiaryReading.groups(diary.notes(), DiaryReading::noteTimestamp, diary.zone());
        updateDateFilter(noteDateFilter, noteGroups.keySet());
        notes.getChildren().setAll(editor, dateFilterRow(noteDateFilter));
        noteGroups.forEach((date, values) -> {
            if (!DiaryReading.ALL.equals(noteDateFilter.getValue()) && !date.equals(noteDateFilter.getValue())) return;
            notes.getChildren().add(dateHeading(date, values.size()));
            values.forEach(note -> notes.getChildren().add(noteRow(note)));
        });
        if (notes.getChildren().size() == 2) notes.getChildren().add(empty("这个日期还没有私人备注"));
        if (selectedTrack != null && !editorDirty && !editorBusy) selectTrack(selectedTrack);

        var lyricGroups = DiaryReading.groups(diary.lyricFavorites(), DiaryClient.LyricFavorite::createdAt, diary.zone());
        updateDateFilter(lyricDateFilter, lyricGroups.keySet());
        lyrics.getChildren().setAll(dateFilterRow(lyricDateFilter));
        addPendingLyrics();
        lyricGroups.forEach((date, values) -> {
            if (!DiaryReading.ALL.equals(lyricDateFilter.getValue()) && !date.equals(lyricDateFilter.getValue())) return;
            lyrics.getChildren().add(dateHeading(date, values.size()));
            values.forEach(lyric -> lyrics.getChildren().add(lyricRow(lyric)));
        });
        if (lyricGroups.isEmpty()) lyrics.getChildren().add(empty("还没有收藏歌词片段"));
        refreshTrackActions();
        updateSelection();
    }

    private void configureDateFilter(ComboBox<String> filter, String id) {
        filter.setId(id);
        filter.getItems().setAll(DiaryReading.ALL);
        filter.setValue(DiaryReading.ALL);
        filter.setMaxWidth(Double.MAX_VALUE);
        filter.setMinWidth(0);
        filter.setOnAction(event -> {
            if (filtersUpdating) return;
            selectedTrackIds.clear();
            render();
        });
    }

    private void updateDateFilter(ComboBox<String> filter, Set<String> dates) {
        String selected = filter.getValue();
        List<String> choices = new ArrayList<>();
        choices.add(DiaryReading.ALL);
        choices.addAll(dates);
        // Replacing items during its selection event can clear the ComboBox's value.
        // Ordinary date navigation should not rebuild an unchanged choice list.
        if (selected != null && !choices.contains(selected)) choices.add(selected);
        if (filter.getItems().equals(choices)) return;
        filtersUpdating = true;
        filter.getItems().setAll(choices);
        filter.setValue(selected == null ? DiaryReading.ALL : selected);
        filtersUpdating = false;
    }

    private HBox dateFilterRow(ComboBox<String> filter) {
        Label description = new Label("按日期浏览");
        description.getStyleClass().add("diary-row-detail");
        String zone = diary == null ? "" : DiaryReading.zone(diary.zone()).getId();
        filter.setTooltip(new Tooltip("记录日期使用听歌记录时区" + (zone.isBlank() ? "" : "：" + zone)
                + "；创建日期未知的备注可按真实更新时间浏览"));
        HBox row = new HBox(10, description, filter);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("diary-date-filter");
        HBox.setHgrow(filter, Priority.ALWAYS);
        return row;
    }

    private static Label dateHeading(String date, int count) {
        Label heading = new Label(date + "  ·  " + count + " 条");
        heading.setId("diary-date-heading");
        heading.getStyleClass().addAll("diary-section-title", "diary-date-heading");
        heading.getProperties().put("date", date);
        return heading;
    }

    private void addPendingLyrics() {
        for (PendingLyric pending : pendingLyrics) {
            VBox text = rowText(pending.text, (pending.track == null ? "未知曲目" : pending.track.title())
                    + "  ·  " + formatPosition(pending.position)
                    + (pending.saving ? "  ·  正在保存…" : "  ·  保存失败，原文与位置已保留"));
            Button retry = new Button("重试保存");
            retry.setId("diary-lyric-retry");
            retry.setDisable(pending.saving);
            retry.setOnAction(event -> savePendingLyric(pending));
            HBox card = row(text, retry);
            card.setId("diary-lyric-pending-row");
            card.getStyleClass().add("diary-memory-card");
            card.getProperties().put("trackId", pending.track == null ? "" : pending.track.id());
            card.getProperties().put("positionMillis", pending.position);
            card.getProperties().put("text", pending.text);
            lyrics.getChildren().add(card);
        }
    }

    private void addSection(String title, DiaryClient.Section section, String emptyText) {
        Label heading = new Label(title + "  " + section.count());
        heading.getStyleClass().add("diary-section-title");
        overview.getChildren().add(heading);
        if (section.entries().isEmpty()) {
            overview.getChildren().add(empty(emptyText));
            return;
        }
        for (DiaryClient.Entry entry : section.entries())
            overview.getChildren().add("monthly-playlists".equals(section.id())
                    ? playlistRow(entry) : trackRow(entry, section.id()));
    }

    private Node trackRow(DiaryClient.Entry entry, String sectionId) {
        Track track = entry.track();
        String name = track == null ? "未知曲目" : track.title();
        String detail = track == null ? "曲目信息不可用" : track.artist() + "  ·  " + source(track);
        String date = DiaryReading.display("weekly-first".equals(sectionId)
                ? entry.firstPlayedAt() : entry.playedAt(), diary.zone());
        if (!date.isBlank()) detail += "  ·  " + date;
        if (entry.legacy()) detail += "  ·  旧记录·最后收听";
        VBox text = rowText(name, detail);
        HBox row = songRow(text, track, () -> play.accept(track), null);
        row.setId("diary-track-row");
        if (track != null) row.getProperties().put("trackId", track.id());
        row.getProperties().put("sectionId", sectionId);
        return row;
    }

    private Node playlistRow(DiaryClient.Entry entry) {
        String name = entry.playlistName().isBlank() ? "未命名歌单" : entry.playlistName();
        VBox text = rowText(name, "本月播放 " + entry.playCount() + " 次");
        Button open = iconButton("打开歌单", "list");
        open.setDisable(entry.playlistId().isBlank());
        open.setOnAction(event -> navigatePlaylist.accept(entry.playlistId()));
        HBox row = row(text, open);
        row.setId("diary-playlist-row");
        row.getProperties().put("playlistId", entry.playlistId());
        return row;
    }

    private Node noteRow(DiaryClient.Note note) {
        Track track = note.track();
        VBox text = rowText(track == null ? "未知曲目" : track.title(),
                track == null ? "曲目信息不可用" : track.artist() + "  ·  " + source(track));
        Label body = memoryText(note.text(), "diary-note-body", "diary-memory-body");
        Label date = memoryText(DiaryReading.noteDates(note, diary.zone()), "diary-note-date", "diary-row-detail");
        text.getChildren().addAll(body, date);
        HBox row = songRow(text, track, () -> play.accept(track), null);
        row.setId("diary-note-row");
        row.getStyleClass().add("diary-memory-card");
        if (track != null) row.getProperties().put("trackId", track.id());
        return row;
    }

    private Node lyricRow(DiaryClient.LyricFavorite lyric) {
        Track track = lyric.track();
        String trackName = track == null ? "未知曲目" : track.title();
        VBox text = rowText(trackName,
                (track == null ? "曲目信息不可用" : track.artist() + "  ·  " + source(track))
                        + "  ·  可回听位置 " + formatPosition(lyric.positionMillis()));
        text.getChildren().addAll(memoryText("“" + lyric.text() + "”", "diary-lyric-body", "diary-memory-body"),
                memoryText("收藏于 " + DiaryReading.display(lyric.createdAt(), diary.zone()),
                        "diary-lyric-date", "diary-row-detail"));
        HBox row = songRow(text, track, () -> playLyric.accept(track, lyric.positionMillis()), menu -> {
            MenuItem fromLine = new MenuItem("从这句播放");
            fromLine.setId("diary-lyric-play");
            fromLine.setDisable(track == null);
            fromLine.setOnAction(event -> playLyric.accept(track, lyric.positionMillis()));
            menu.getItems().add(0, fromLine);
            MenuItem delete = new MenuItem("删除歌词片段");
            delete.setId("diary-lyric-delete");
            delete.setOnAction(event -> removeLyric(lyric.id()));
            menu.getItems().add(delete);
        });
        row.setId("diary-lyric-row");
        row.getStyleClass().add("diary-memory-card");
        row.getProperties().put("lyricId", lyric.id());
        Button listen = iconButton("从这句播放 · " + formatPosition(lyric.positionMillis()), "play");
        listen.setId("diary-lyric-listen");
        listen.setDisable(track == null);
        listen.setOnAction(event -> playLyric.accept(track, lyric.positionMillis()));
        row.getChildren().add(row.getChildren().size() - 2, listen);
        return row;
    }

    private static Label memoryText(String value, String id, String style) {
        Label label = new Label(value);
        label.setId(id);
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(style);
        return label;
    }

    private HBox songRow(VBox text, Track track, Runnable playAction, Consumer<ContextMenu> extendMenu) {
        Button heart = iconButton("收藏", "heart");
        heart.setId("diary-track-favorite");
        heart.getStyleClass().addAll("track-action", "track-favorite");
        heart.getGraphic().getStyleClass().add("row-icon");
        favoriteButtons.put(heart, track);
        heart.setOnAction(event -> {
            if (track != null && toggleFavorite != null) toggleFavorite.accept(track);
        });
        Button more = iconButton("更多操作", "more");
        more.setId("diary-track-more");
        more.getStyleClass().addAll("track-action", "track-more");
        more.setDisable(track == null && extendMenu == null);
        more.setOnAction(event -> {
            List<Track> selection = track != null && selectedTrackIds.contains(track.id())
                    ? selectedTracks() : track == null ? List.of() : List.of(track);
            ContextMenu actions = track != null && trackMenu != null
                    ? trackMenu.apply(track, selection) : new ContextMenu();
            if (track != null) {
                if (actions.getItems().stream().noneMatch(item -> "track-menu-play".equals(item.getId()))) {
                    MenuItem playItem = new MenuItem("播放");
                    playItem.setId("track-menu-play");
                    playItem.setOnAction(action -> play.accept(track));
                    actions.getItems().add(0, playItem);
                }
                if (actions.getItems().stream().noneMatch(item -> "track-menu-diary-note".equals(item.getId()))) {
                    MenuItem edit = new MenuItem("编辑私人备注");
                    edit.setId("track-menu-diary-note");
                    edit.setOnAction(action -> editTrackNote(track));
                    actions.getItems().add(edit);
                }
            }
            if (extendMenu != null) extendMenu.accept(actions);
            themeMenu.accept(actions);
            actions.show(more, javafx.geometry.Side.BOTTOM, 0, 3);
        });
        HBox row = row(text, heart, more);
        if (track != null) {
            CheckBox check = new CheckBox();
            check.setId("diary-track-select");
            check.setAccessibleText("选择 " + track.title());
            check.getProperties().put("trackId", track.id());
            check.setOnAction(event -> {
                if (check.isSelected()) selectedTrackIds.add(track.id());
                else selectedTrackIds.remove(track.id());
                updateSelection();
            });
            trackChecks.add(check);
            row.getChildren().add(0, check);
            row.getProperties().put("trackId", track.id());
            text.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 2)
                    playAction.run();
            });
        }
        return row;
    }

    private void export() {
        if (diary == null || getScene() == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出最近播放");
        chooser.setInitialFileName("最近播放-" + LocalDate.now() + ".md");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md"),
                new FileChooser.ExtensionFilter("纯文本", "*.txt"));
        var target = chooser.showSaveDialog(getScene().getWindow());
        if (target == null) return;
        boolean markdown = !target.getName().toLowerCase().endsWith(".txt");
        run(() -> {
            DiaryClient.Diary current = client.load();
            Files.writeString(target.toPath(), exportText(current, markdown), StandardCharsets.UTF_8);
            return current;
        }, state -> {
            diary = state;
            render();
            feedback.setText("已导出到 " + target.getName());
            status.accept(feedback.getText());
        }, false);
    }

    static String exportText(DiaryClient.Diary diary, boolean markdown) {
        StringBuilder out = new StringBuilder(markdown ? "# 最近播放\n\n" : "最近播放\n\n");
        var s = diary.summary();
        out.append("本月有效播放：").append(s.thisMonthPlays()).append(" 次；详细记录累计音源：")
                .append(s.sourceCount()).append("；曲目：").append(s.trackCount())
                .append("；歌曲：").append(s.songCount()).append("\n\n");
        for (DiaryClient.Section section : diary.sections()) {
            out.append(markdown ? "## " : "").append(section.title()).append("（")
                    .append(section.count()).append("）\n");
            if (section.entries().isEmpty()) out.append("暂无记录\n");
            for (DiaryClient.Entry entry : section.entries()) {
                String name = "monthly-playlists".equals(section.id()) ? entry.playlistName()
                        : entry.track() == null ? "未知曲目" : entry.track().title()
                        + " · " + entry.track().artist() + " · " + source(entry.track());
                out.append("- ").append(name).append("monthly-playlists".equals(section.id())
                        ? " · " + entry.playCount() + " 次" : "")
                        .append(("weekly-first".equals(section.id()) ? entry.firstPlayedAt()
                                : entry.playedAt()).isBlank() ? "" : " · "
                                + DiaryReading.display("weekly-first".equals(section.id())
                                ? entry.firstPlayedAt() : entry.playedAt(), diary.zone()))
                        .append(entry.legacy() ? " · 旧记录·最后收听" : "")
                        .append('\n');
            }
            out.append('\n');
        }
        out.append(markdown ? "## 私人备注\n" : "私人备注\n");
        if (diary.notes().isEmpty()) out.append("暂无备注\n");
        for (DiaryClient.Note note : diary.notes())
            out.append("- ").append(note.track() == null ? "未知曲目" : note.track().title())
                    .append("：").append(note.text()).append('\n')
                    .append("  ").append(DiaryReading.noteDates(note, diary.zone())).append('\n');
        out.append(markdown ? "\n## 歌词片段\n" : "\n歌词片段\n");
        if (diary.lyricFavorites().isEmpty()) out.append("暂无收藏\n");
        for (DiaryClient.LyricFavorite lyric : diary.lyricFavorites())
            out.append("- ").append(lyric.track() == null ? "未知曲目" : lyric.track().title())
                    .append(" @ ").append(formatPosition(lyric.positionMillis()))
                    .append("：").append(lyric.text()).append('\n')
                    .append("  收藏于 ").append(DiaryReading.display(lyric.createdAt(), diary.zone())).append('\n');
        return out.toString();
    }

    private <T> void run(Callable<T> operation, Consumer<T> completed, boolean read) {
        run(operation, completed, read, null);
    }

    private <T> void run(Callable<T> operation, Consumer<T> completed, boolean read, Runnable onError) {
        if (closed) return;
        int version = ++requestVersion;
        io.submit(() -> {
            try {
                T result = operation.call();
                Platform.runLater(() -> {
                    if (!closed && (!read || version == requestVersion)) completed.accept(result);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!closed && (!read || version == requestVersion)) {
                        feedback.setText("操作失败：" + errorMessage(ex));
                        reportedReadFailure = read ? feedback.getText() : null;
                        status.accept(feedback.getText());
                        if (onError != null) onError.run();
                    }
                });
            }
        });
    }

    static String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof RuntimeException
                || cause instanceof java.util.concurrent.ExecutionException)) cause = cause.getCause();
        if (cause instanceof java.net.ConnectException)
            return "听歌记录服务未连接，请稍后刷新或重试连接";
        if (cause instanceof java.net.http.HttpTimeoutException)
            return "听歌记录服务响应超时，请重试";
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override public void close() {
        closed = true;
        requestVersion++;
        io.shutdown();
        try {
            if (!io.awaitTermination(2, TimeUnit.SECONDS)) io.shutdownNow();
        } catch (InterruptedException interrupted) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ScrollPane scroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.getStyleClass().add("diary-content");
        return scroll;
    }

    private static Label empty(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("diary-empty");
        return label;
    }

    private static VBox rowText(String title, String detail) {
        Label name = new Label(title);
        name.getStyleClass().add("diary-row-title");
        name.setWrapText(true);
        name.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label caption = new Label(detail);
        caption.getStyleClass().add("diary-row-detail");
        caption.setWrapText(true);
        caption.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox box = new VBox(3, name, caption);
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static HBox row(VBox content, Button... actions) {
        HBox row = new HBox(8);
        row.setMinWidth(0);
        row.getStyleClass().add("diary-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(content);
        HBox.setHgrow(content, Priority.ALWAYS);
        row.getChildren().addAll(actions);
        return row;
    }

    private static Button iconButton(String label, String icon) {
        Button button = new Button();
        button.getStyleClass().add("diary-icon-button");
        button.setTooltip(new Tooltip(label));
        button.setAccessibleText(label);
        SVGPath shape = new SVGPath();
        shape.setContent(switch (icon) {
            case "play" -> "M8 5V19L19 12Z";
            case "heart" -> "M20.8 4.6A5.5 5.5 0 0 0 13 4.6L12 5.6L11 4.6A5.5 5.5 0 0 0 3.2 12.4L12 21.2L20.8 12.4A5.5 5.5 0 0 0 20.8 4.6Z";
            case "more" -> "M4 10A2 2 0 1 0 4 14A2 2 0 0 0 4 10Z M12 10A2 2 0 1 0 12 14A2 2 0 0 0 12 10Z M20 10A2 2 0 1 0 20 14A2 2 0 0 0 20 10Z";
            case "list" -> "M3 5H18V7H3Z M3 10H18V12H3Z M3 15H13V17H3Z M16 14L22 18L16 22Z";
            case "edit" -> "M3 17L16 4L20 8L7 21H3Z M15 5L17 3L21 7L19 9Z";
            case "delete" -> "M4 6H20V8H4Z M7 9H17L16 21H8Z M9 3H15L16 5H8Z";
            case "refresh" -> "M12 3A9 9 0 1 0 21 12H18A6 6 0 1 1 12 6V9L19 5L12 1Z";
            case "export" -> "M11 2H13V13L17 9L19 11L12 18L5 11L7 9L11 13Z M3 18H5V20H19V18H21V22H3Z";
            default -> "M8 5V19L19 12Z";
        });
        shape.getStyleClass().addAll("ui-icon", "action-icon");
        button.setGraphic(shape);
        return button;
    }

    private static String source(Track track) {
        String source = track.source() == null ? "" : track.source();
        String name = switch (source) {
            case "bilibili" -> "B 站";
            case "netease" -> "网易云";
            case "local" -> "本地";
            default -> source.isBlank() ? "来源未知" : source;
        };
        return track.isSegment() ? name + " · 片段" : name;
    }

    private static String formatPosition(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
