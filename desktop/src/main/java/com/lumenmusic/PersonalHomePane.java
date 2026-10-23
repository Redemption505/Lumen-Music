package com.lumenmusic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/** Personal landing page. All playback and persistence belong to the shared application. */
public final class PersonalHomePane extends BorderPane implements AutoCloseable {
    public record Actions(Runnable resume, Consumer<HomeClient.Pin> openPin,
                          Consumer<Track> playReunion, Runnable openLocal, Runnable openFavorites,
                          Runnable openDiary, Runnable openOrganization,
                          Consumer<HomeClient.Preferences> savePreferences) { }

    private record PinOption(HomeClient.Pin pin, String title) {
        @Override public String toString() { return title; }
    }

    private final Actions actions;
    private final GridPane grid = new GridPane();
    private final Label sessionBadge = text("○ 待开启", "home-session-badge");
    private final Button resume;
    private final Button organizeButton;
    private final VBox continuing;
    private final VBox pinned;
    private final VBox reunion;
    private final VBox discover;
    private final Map<String, VBox> cards = new LinkedHashMap<>();
    private final StackPane vinylDisc = new StackPane();
    private final Label sessionTitle = text("让下一首音乐从这里开始", "home-track-title");
    private final Label sessionDetail = text("还没有可继续的播放会话", "muted");
    private final VBox pinItems = new VBox(6);
    private final VBox reunionContent = new VBox(8);
    private final Label feedback = text("", "muted");
    private final Consumer<DialogPane> themeDialog;
    private HomeClient.Preferences preferences = HomeClient.defaults();
    private List<LibraryClient.Playlist> playlists = List.of();
    private List<SmartPlaylistClient.SmartPlaylist> smart = List.of();
    private Dialog<Void> customizeDialog;
    private Label customizeFeedback;
    private VBox customizeForm;
    private Button customizeSave;
    private Button customizeCancel;
    private boolean saving;
    private boolean twoColumns;
    private Function<Track, String> trackTitle = Track::title;
    private Function<Track, String> performer = Track::artist;
    private Track lastSessionTrack;
    private String lastSessionDetail;
    private boolean lastCanResume;
    private boolean lastPlaying;
    private DiaryClient.Diary lastDiary;

    public PersonalHomePane(Node discoveryControls, Node recommendations, Actions actions,
                            Consumer<ContextMenu> themeMenu, Consumer<DialogPane> themeDialog) {
        this.actions = Objects.requireNonNull(actions);
        Objects.requireNonNull(themeMenu);
        this.themeDialog = Objects.requireNonNull(themeDialog);
        setId("home-page");
        SmoothScrolling.install(this);
        FriendlyTooltips.install(this);
        getStyleClass().addAll("content-page", "personal-home");
        setMinSize(0, 0);
        Label title = text("我的音乐空间", "page-title");
        Label subtitle = text("继续熟悉的旋律，也留一点空间给新的发现", "page-subtitle");
        Button customize = button("调整首页", "home-customize-open", this::customize);
        HBox heading = new HBox(12, title, customize);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        feedback.setId("home-feedback");
        feedback.setWrapText(true);
        feedback.setVisible(false);
        feedback.setManaged(false);
        VBox header = new VBox(5, heading, subtitle, feedback);
        header.getStyleClass().add("home-personal-heading");
        setTop(header);

        organizeButton = button("整理我的歌单", "home-open-organization", actions.openOrganization());
        organizeButton.getStyleClass().add("home-card-action");
        continuing = card("continue", "继续听", sessionBadge);
        pinned = card("pinned", "我的常驻", organizeButton);
        reunion = card("reunion", "今日重逢", text("听歌记忆", "home-card-caption"));
        discover = card("discover", "想发现一点", text("灵感探索", "home-card-caption"));

        resume = button("继续播放", "home-continue-play", actions.resume());
        resume.getStyleClass().add("accent-button");
        resume.setDisable(true);
        sessionTitle.setId("home-session-title");
        Tooltip sessionTooltip = new Tooltip();
        sessionTooltip.textProperty().bind(sessionTitle.textProperty());
        sessionTitle.setTooltip(sessionTooltip);
        sessionDetail.setId("home-session-detail");
        sessionDetail.setWrapText(true);
        VBox sessionTextBox = new VBox(4, sessionTitle, sessionDetail);
        sessionTextBox.setMinWidth(0);
        VBox.setVgrow(sessionTextBox, Priority.ALWAYS);

        vinylDisc.getStyleClass().add("home-vinyl-disc");
        vinylDisc.setMinSize(56, 56);
        vinylDisc.setPrefSize(56, 56);
        vinylDisc.setMaxSize(56, 56);
        Region vinylLabel = new Region();
        vinylLabel.getStyleClass().add("home-vinyl-label");
        vinylLabel.setMinSize(22, 22);
        vinylLabel.setPrefSize(22, 22);
        vinylLabel.setMaxSize(22, 22);
        Region vinylHole = new Region();
        vinylHole.getStyleClass().add("home-vinyl-hole");
        vinylHole.setMinSize(6, 6);
        vinylHole.setPrefSize(6, 6);
        vinylHole.setMaxSize(6, 6);
        vinylDisc.getChildren().addAll(vinylLabel, vinylHole);
        StackPane.setAlignment(vinylLabel, Pos.CENTER);
        StackPane.setAlignment(vinylHole, Pos.CENTER);
        vinylDisc.setTranslateX(4);

        StackPane vinylSleeve = new StackPane();
        vinylSleeve.getStyleClass().add("home-vinyl-sleeve");
        vinylSleeve.setMinSize(56, 56);
        vinylSleeve.setPrefSize(56, 56);
        vinylSleeve.setMaxSize(56, 56);
        Label sleeveIcon = new Label("♫");
        sleeveIcon.getStyleClass().add("home-sleeve-icon");
        vinylSleeve.getChildren().add(sleeveIcon);
        StackPane.setAlignment(sleeveIcon, Pos.CENTER);

        StackPane vinylStage = new StackPane(vinylDisc, vinylSleeve);
        vinylStage.getStyleClass().add("home-vinyl-stage");
        vinylStage.setMinSize(72, 56);
        vinylStage.setPrefSize(72, 56);
        vinylStage.setMaxSize(72, 56);
        StackPane.setAlignment(vinylDisc, Pos.CENTER_LEFT);
        StackPane.setAlignment(vinylSleeve, Pos.CENTER_LEFT);
        vinylStage.setMouseTransparent(true);

        HBox sessionTopRow = new HBox(12, vinylStage, sessionTextBox);
        sessionTopRow.setAlignment(Pos.CENTER_LEFT);
        sessionTopRow.setMinWidth(0);
        HBox.setHgrow(sessionTextBox, Priority.ALWAYS);

        continuing.getChildren().addAll(sessionTopRow, resume);
        pinItems.setId("home-pins");
        VBox.setVgrow(pinItems, Priority.ALWAYS);
        pinned.getChildren().add(pinItems);
        VBox.setVgrow(reunionContent, Priority.ALWAYS);
        reunion.getChildren().add(reunionContent);
        Label discoverHint = text("从私人电台开始，或写下这次想听什么", "muted");
        discoverHint.setWrapText(true);
        VBox.setVgrow(discoveryControls, Priority.ALWAYS);
        discover.getChildren().addAll(discoverHint, discoveryControls);
        cards.put("continue", continuing);
        cards.put("pinned", pinned);
        cards.put("reunion", reunion);
        cards.put("discover", discover);
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setId("home-sections");
        VBox body = new VBox(18, grid, recommendations);
        if (recommendations instanceof javafx.scene.layout.Region region) {
            region.setMinHeight(240);
            region.setPrefHeight(360);
        }
        body.setMinWidth(0);
        body.getStyleClass().add("home-personal-body");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setId("home-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("playlist-scroll");
        scroll.viewportBoundsProperty().addListener((ignored, before, after) -> {
            boolean wide = after.getWidth() >= 760;
            if (wide != twoColumns) { twoColumns = wide; layoutSections(); }
        });
        setCenter(scroll);
        layoutSections();
        renderPins();
        setDiary(null);
    }

    public void setPreferences(HomeClient.Preferences value) {
        preferences = Objects.requireNonNull(value);
        layoutSections();
        renderPins();
    }

    public HomeClient.Preferences preferences() { return preferences; }

    public void setIdentityFunctions(Function<Track, String> title, Function<Track, String> performer) {
        this.trackTitle = Objects.requireNonNull(title);
        this.performer = Objects.requireNonNull(performer);
        setSession(lastSessionTrack, lastSessionDetail, lastCanResume, lastPlaying);
        setDiary(lastDiary);
    }

    public void setPlaylists(List<LibraryClient.Playlist> ordinary, SmartPlaylistClient.State state) {
        playlists = List.copyOf(ordinary == null ? List.of() : ordinary);
        smart = state == null ? List.of() : state.smartPlaylists();
        renderPins();
    }

    public void setSession(Track track, String detail, boolean canResume, boolean playing) {
        lastSessionTrack = track;
        lastSessionDetail = detail;
        lastCanResume = canResume;
        lastPlaying = playing;
        sessionTitle.setText(track == null ? "让下一首音乐从这里开始" : trackTitle.apply(track));
        sessionDetail.setText(track == null ? "还没有可继续的播放会话" : detail);
        resume.setText(playing ? "正在播放" : "继续播放");
        resume.setDisable(track == null || !canResume || playing);
        if (playing) {
            sessionBadge.setText("● 正在播放");
            sessionBadge.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), true);
            vinylDisc.setTranslateX(16);
            vinylDisc.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), true);
        } else if (track != null && canResume) {
            sessionBadge.setText("⏸ 上次停在");
            sessionBadge.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), false);
            vinylDisc.setTranslateX(10);
            vinylDisc.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), false);
        } else {
            sessionBadge.setText("○ 待开启");
            sessionBadge.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), false);
            vinylDisc.setTranslateX(4);
            vinylDisc.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), false);
        }
    }

    public void setDiary(DiaryClient.Diary diary) {
        lastDiary = diary;
        reunionContent.getChildren().clear();
        HomeReunion.select(diary).ifPresentOrElse(selection -> {
            Label track = text(trackTitle.apply(selection.track()), "home-track-title");
            track.setId("home-reunion-title");
            Label reason = text(selection.reason(), "muted");
            reason.setId("home-reunion-reason");
            reason.getStyleClass().add("home-reunion-reason-box");
            reason.setWrapText(true);
            Button playBtn = button("回听这首歌", "home-reunion-play", () -> actions.playReunion().accept(selection.track()));
            playBtn.getStyleClass().add("home-reunion-play-button");
            Button diaryBtn = button("查看最近播放", "home-open-diary", actions.openDiary());
            diaryBtn.getStyleClass().add("home-reunion-diary-button");
            HBox actionRow = new HBox(8, playBtn, diaryBtn);
            actionRow.setAlignment(Pos.CENTER_LEFT);
            reunionContent.getChildren().addAll(track, text(performer.apply(selection.track()), "muted"), reason, actionRow);
        }, () -> {
            Label empty = text("听歌后，真实的重听与初遇会在这里留下记录", "muted");
            empty.setWrapText(true);
            javafx.scene.layout.FlowPane entries = new javafx.scene.layout.FlowPane(8, 8,
                    button("导入本地音乐", "home-open-local", actions.openLocal()),
                    button("打开收藏", "home-open-favorites", actions.openFavorites()),
                    button("最近播放", "home-open-diary", actions.openDiary()));
            reunionContent.getChildren().addAll(empty, entries);
        });
    }

    public void setFeedback(String value) {
        feedback.setText(value == null ? "" : value);
        boolean visible = !feedback.getText().isBlank();
        feedback.setVisible(visible);
        feedback.setManaged(visible);
    }

    private void layoutSections() {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        int columns = twoColumns ? 2 : 1;
        for (int index = 0; index < columns; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / columns);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        int index = 0;
        for (String id : preferences.sections()) {
            VBox card = cards.get(id);
            if (card != null) grid.add(card, index % columns, index++ / columns);
        }
    }

    private void renderPins() {
        pinItems.getChildren().clear();
        for (HomeClient.Pin pin : preferences.pinned()) {
            PinOption option = option(pin);
            Button open = button(option.title(), "home-pin-" + pin.kind() + "-" + pin.id(),
                    () -> actions.openPin().accept(pin));
            open.setMaxWidth(Double.MAX_VALUE);
            open.setAlignment(Pos.CENTER_LEFT);
            open.setTooltip(new Tooltip(option.title()));
            open.getStyleClass().add("home-pin-button");
            if ("smart".equals(pin.kind())) {
                open.getStyleClass().add("home-pin-smart");
            }
            open.setDisable(!pinExists(pin));
            pinItems.getChildren().add(open);
        }
        if (pinItems.getChildren().isEmpty()) {
            Label empty = text("把常听的普通或智能歌单放在这里", "muted");
            empty.setWrapText(true);
            pinItems.getChildren().addAll(empty, button("选择常驻歌单", "home-pin-choose", this::customize));
        }
    }

    private boolean pinExists(HomeClient.Pin pin) {
        return "smart".equals(pin.kind()) ? smart.stream().anyMatch(item -> item.id().equals(pin.id()))
                : playlists.stream().anyMatch(item -> item.id().equals(pin.id()));
    }

    private PinOption option(HomeClient.Pin pin) {
        String name = "smart".equals(pin.kind())
                ? smart.stream().filter(item -> item.id().equals(pin.id())).map(SmartPlaylistClient.SmartPlaylist::name).findFirst().orElse("已移除的智能歌单")
                : playlists.stream().filter(item -> item.id().equals(pin.id())).map(LibraryClient.Playlist::name).findFirst().orElse("已移除的歌单");
        return new PinOption(pin, name + ("smart".equals(pin.kind()) ? " · 智能歌单" : ""));
    }

    /** Draft edits remain inside this dialog until a successful explicit save. */
    public void customize() {
        if (customizeDialog != null) {
            customizeDialog.getDialogPane().getScene().getWindow().requestFocus();
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        customizeDialog = dialog;
        dialog.setTitle("调整首页");
        dialog.setResizable(true);
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.initModality(Modality.NONE);
        var pane = dialog.getDialogPane();
        SmoothScrolling.install(pane);
        pane.setId("home-customize");
        themeDialog.accept(pane);
        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(saveType, cancelType);
        customizeSave = (Button) pane.lookupButton(saveType);
        customizeCancel = (Button) pane.lookupButton(cancelType);
        customizeSave.setId("home-customize-save");
        customizeSave.getStyleClass().add("accent-button");
        customizeCancel.setId("home-customize-cancel");
        var sections = FXCollections.observableArrayList(preferences.sections());
        var pins = FXCollections.observableArrayList(preferences.pinned());
        ListView<String> sectionList = new ListView<>(sections);
        sectionList.setId("home-section-list");
        sectionList.setPrefHeight(152);
        sectionList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : sectionTitle(id));
            }
        });
        sectionList.getSelectionModel().selectFirst();
        ListView<HomeClient.Pin> pinList = new ListView<>(pins);
        pinList.setId("home-pin-list");
        pinList.setPrefHeight(168);
        pinList.getSelectionModel().selectFirst();
        pinList.setCellFactory(view -> new ListCell<>() {
            private final Tooltip titleTooltip = FriendlyTooltips.configure(new Tooltip());
            { setPrefWidth(0); } // Fit the viewport; ellipsis and tooltip retain long names.
            @Override protected void updateItem(HomeClient.Pin pin, boolean empty) {
                super.updateItem(pin, empty);
                String title = empty || pin == null ? null : option(pin).title();
                setText(title);
                titleTooltip.setText(title);
                setTooltip(title == null ? null : titleTooltip);
            }
        });
        List<PinOption> choices = new ArrayList<>();
        playlists.forEach(item -> choices.add(new PinOption(new HomeClient.Pin("playlist", item.id()), item.name())));
        smart.forEach(item -> choices.add(new PinOption(new HomeClient.Pin("smart", item.id()), item.name() + " · 智能歌单")));
        ComboBox<PinOption> candidates = new ComboBox<>(FXCollections.observableArrayList(choices));
        candidates.setId("home-pin-candidate");
        candidates.setMinWidth(0);
        candidates.setMaxWidth(Double.MAX_VALUE);
        candidates.setPromptText(choices.isEmpty() ? "先创建一个歌单" : "选择普通或智能歌单");
        if (!choices.isEmpty()) candidates.getSelectionModel().selectFirst();
        Button add = button("置顶", "home-pin-add", () -> {
            PinOption item = candidates.getValue();
            if (item == null) return;
            if (pins.contains(item.pin())) { customizeFeedback.setText("这个歌单已在常驻中"); return; }
            if (pins.size() >= 6) { customizeFeedback.setText("最多放置 6 个常驻歌单"); return; }
            pins.add(item.pin());
            pinList.getSelectionModel().select(item.pin());
            customizeFeedback.setText("");
        });
        add.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        add.setDisable(choices.isEmpty());
        HBox addRow = new HBox(8, candidates, add);
        HBox.setHgrow(candidates, Priority.ALWAYS);
        Button remove = button("移除", "home-pin-remove", () -> {
            int index = pinList.getSelectionModel().getSelectedIndex();
            if (index >= 0) pins.remove(index);
        });
        remove.disableProperty().bind(pinList.getSelectionModel().selectedIndexProperty().lessThan(0));
        HBox sectionMoves = new HBox(8, moveButton(sectionList, -1, "home-section-up"), moveButton(sectionList, 1, "home-section-down"));
        HBox pinMoves = new HBox(8, moveButton(pinList, -1, "home-pin-up"), moveButton(pinList, 1, "home-pin-down"), remove);
        customizeFeedback = text("", "home-customize-feedback");
        customizeFeedback.setId("home-customize-feedback");
        customizeFeedback.setWrapText(true);
        customizeForm = new VBox(10, text("首页区域顺序", "section-title"), sectionList, sectionMoves,
                text("我的常驻 · 最多 6 个", "section-title"), pinList, pinMoves, addRow);
        ScrollPane formScroll = new ScrollPane(customizeForm);
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.setPrefViewportHeight(getScene() == null ? 480
                : Math.max(260, Math.min(480, getScene().getWindow().getHeight() - 210)));
        VBox content = new VBox(12, formScroll, customizeFeedback);
        VBox.setVgrow(formScroll, Priority.ALWAYS);
        content.setPrefWidth(450);
        pane.setContent(content);
        customizeSave.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (saving) return;
            setSaving(true);
            customizeFeedback.setText("正在保存...");
            try { actions.savePreferences().accept(new HomeClient.Preferences(List.copyOf(sections), List.copyOf(pins))); }
            catch (RuntimeException ex) { preferenceSaveFailed(ex.getMessage()); }
        });
        dialog.setOnCloseRequest(event -> { if (saving) event.consume(); });
        dialog.setOnHidden(event -> {
            customizeDialog = null;
            customizeFeedback = null;
            customizeForm = null;
            customizeSave = null;
            customizeCancel = null;
        });
        dialog.show();
    }

    public void preferenceSaveSucceeded(HomeClient.Preferences value) {
        setPreferences(value);
        setSaving(false);
        if (customizeDialog != null) customizeDialog.close();
        setFeedback("首页已保存");
    }

    public void preferenceSaveFailed(String message) {
        setSaving(false);
        String detail = message == null || message.isBlank() ? "请重试" : message;
        if (customizeFeedback != null) customizeFeedback.setText("保存失败，调整已保留：" + detail);
        else setFeedback("首页保存失败：" + detail);
    }

    @Override public void close() {
        setSaving(false);
        if (customizeDialog != null) customizeDialog.close();
    }

    private void setSaving(boolean value) {
        saving = value;
        if (customizeForm != null) customizeForm.setDisable(value);
        if (customizeSave != null) customizeSave.setDisable(value);
        if (customizeCancel != null) customizeCancel.setDisable(value);
    }

    private static <T> Button moveButton(ListView<T> list, int step, String id) {
        Button move = button(step < 0 ? "上移" : "下移", id, () -> {
            int from = list.getSelectionModel().getSelectedIndex();
            int to = from + step;
            if (from < 0 || to < 0 || to >= list.getItems().size()) return;
            T item = list.getItems().remove(from);
            list.getItems().add(to, item);
            list.getSelectionModel().select(to);
        });
        Runnable refresh = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            move.setDisable(index < 0 || index + step < 0 || index + step >= list.getItems().size());
        };
        list.getSelectionModel().selectedIndexProperty().addListener((ignored, before, after) -> refresh.run());
        list.getItems().addListener((javafx.collections.ListChangeListener<T>) change -> refresh.run());
        refresh.run();
        return move;
    }

    private static String sectionTitle(String id) {
        return switch (id) {
            case "continue" -> "继续听";
            case "pinned" -> "我的常驻";
            case "reunion" -> "今日重逢";
            case "discover" -> "想发现一点";
            default -> id;
        };
    }

    private static VBox card(String id, String title, Node headerRight) {
        Label titleLabel = text(title, "section-title");
        Node headerNode;
        if (headerRight != null) {
            javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox headerBox = new HBox(8, titleLabel, spacer, headerRight);
            headerBox.setAlignment(Pos.CENTER_LEFT);
            headerNode = headerBox;
        } else {
            headerNode = titleLabel;
        }
        VBox card = new VBox(10, headerNode);
        card.setId("home-section-" + id);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.getStyleClass().add("home-personal-card");
        GridPane.setFillHeight(card, true);
        return card;
    }

    private static Label text(String value, String style) {
        Label label = new Label(value == null ? "" : value);
        label.getStyleClass().add(style);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        if (value != null && !value.isBlank()) label.setTooltip(new Tooltip(value));
        return label;
    }

    private static Button button(String title, String id, Runnable action) {
        Button button = new Button(title);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }
}
