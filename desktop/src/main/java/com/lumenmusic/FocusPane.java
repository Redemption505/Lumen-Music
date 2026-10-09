package com.lumenmusic;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Compact focus scene sharing the owner's playback controls and audio state. */
public final class FocusPane extends BorderPane implements AutoCloseable {
    private final ImageView cover = new ImageView();
    private final Label title = new Label("尚未播放");
    private final Label artist = new Label();
    private final Label lyric = new Label();
    private final Label playbackTime = new Label("00:00 / 00:00");
    private final Slider progress = new Slider(0, 1, 0);
    private final Button play = new Button("播放");
    private final Button previous = new Button("上一首");
    private final Button next = new Button("下一首");
    private final Label focusTime = new Label();
    private final Label sleepTime = new Label();
    private final Button focusStart = new Button("开始专注");
    private final Button sleepStart = new Button("开始睡眠定时");
    private final ChoiceBox<Integer> focusMinutes = new ChoiceBox<>();
    private final ChoiceBox<Integer> sleepMinutes = new ChoiceBox<>();
    private final FocusSession focusSession;
    private final FocusSession sleepSession;
    private final Timeline refresh;

    public FocusPane(Runnable onBack, Runnable onPrevious, Runnable onPlayPause,
                     Runnable onNext, Runnable onSleepComplete, Consumer<String> notice) {
        this(onBack, onPrevious, onPlayPause, onNext, onSleepComplete, notice, ignored -> { });
    }

    public FocusPane(Runnable onBack, Runnable onPrevious, Runnable onPlayPause,
                     Runnable onNext, Runnable onSleepComplete, Consumer<String> notice,
                     DoubleConsumer onSeek) {
        Objects.requireNonNull(onBack);
        Objects.requireNonNull(onPrevious);
        Objects.requireNonNull(onPlayPause);
        Objects.requireNonNull(onNext);
        Objects.requireNonNull(onSleepComplete);
        Objects.requireNonNull(notice);
        Objects.requireNonNull(onSeek);
        setId("focus-pane");
        SmoothScrolling.install(this);
        getStyleClass().add("focus-pane");
        setPadding(new Insets(16, 24, 20, 24));

        Button back = new Button("返回");
        back.setId("focus-back");
        back.setOnAction(event -> onBack.run());
        setTop(back);

        cover.setFitWidth(210);
        cover.setFitHeight(131.25);
        cover.setPreserveRatio(false);
        cover.setSmooth(true);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(210, 131.25);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        cover.setClip(clip);
        cover.imageProperty().addListener((ignored, before, image) -> {
            if (image == null) { cover.setViewport(null); return; }
            double width = Math.min(image.getWidth(), image.getHeight() * 1.6);
            double height = width / 1.6;
            if (width > 0) cover.setViewport(new javafx.geometry.Rectangle2D(
                    (image.getWidth() - width) / 2, (image.getHeight() - height) / 2, width, height));
        });
        Label emptyCover = new Label("♫");
        emptyCover.setStyle("-fx-font-size: 42px; -fx-opacity: 0.55;");
        emptyCover.visibleProperty().bind(cover.imageProperty().isNull());
        StackPane artwork = new StackPane(cover, emptyCover);
        artwork.getStyleClass().add("focus-cover");
        artwork.setMinSize(210, 132);
        artwork.setPrefSize(210, 132);
        artwork.setMaxSize(210, 132);
        title.setId("focus-title");
        title.getStyleClass().add("track-title");
        title.setWrapText(true);
        title.setAlignment(Pos.CENTER);
        title.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxSize(520, 56);
        artist.setId("focus-artist");
        artist.setTextOverrun(OverrunStyle.ELLIPSIS);
        artist.setMaxWidth(520);
        artist.setAlignment(Pos.CENTER);
        lyric.setId("focus-lyric");
        lyric.setTextOverrun(OverrunStyle.ELLIPSIS);
        lyric.setMaxWidth(520);
        lyric.setAlignment(Pos.CENTER);

        previous.setId("focus-previous");
        play.setId("focus-play");
        next.setId("focus-next");
        previous.setOnAction(event -> onPrevious.run());
        play.setOnAction(event -> onPlayPause.run());
        next.setOnAction(event -> onNext.run());
        HBox playback = new HBox(10, previous, play, next);
        playback.setAlignment(Pos.CENTER);
        progress.setId("focus-seek");
        progress.setPrefWidth(320);
        progress.setMaxWidth(520);
        progress.setOnMouseReleased(event -> onSeek.accept(progress.getValue()));
        progress.setOnKeyReleased(event -> onSeek.accept(progress.getValue()));
        HBox playbackProgress = new HBox(10, progress, playbackTime);
        playbackProgress.setAlignment(Pos.CENTER);

        focusSession = new FocusSession(java.time.Duration.ofMinutes(25), () -> {
            focusTime.setText("专注完成");
            focusStart.setText("重新开始");
            notice.accept("专注完成");
        });
        sleepSession = new FocusSession(java.time.Duration.ofMinutes(30), onSleepComplete);
        focusMinutes.getItems().addAll(25, 45, 60);
        focusMinutes.setId("focus-minutes");
        focusMinutes.setValue(25);
        focusMinutes.valueProperty().addListener((ignored, before, minutes) -> {
            if (minutes != null) {
                focusSession.setDuration(java.time.Duration.ofMinutes(minutes));
                updateTimers();
            }
        });
        sleepMinutes.getItems().addAll(15, 30, 45, 60, 90);
        sleepMinutes.setId("focus-sleep-minutes");
        sleepMinutes.setValue(30);
        sleepMinutes.valueProperty().addListener((ignored, before, minutes) -> {
            if (minutes != null) {
                sleepSession.setDuration(java.time.Duration.ofMinutes(minutes));
                updateTimers();
            }
        });
        focusStart.setOnAction(event -> {
            if (focusSession.state() == FocusSession.State.RUNNING) focusSession.pause();
            else focusSession.start();
            updateTimers();
        });
        Button focusEnd = new Button("结束专注");
        focusEnd.setId("focus-end");
        focusStart.setId("focus-start");
        focusTime.setId("focus-focus-time");
        sleepTime.setId("focus-sleep-time");
        sleepStart.setId("focus-sleep-toggle");
        focusEnd.setOnAction(event -> {
            focusSession.cancel();
            updateTimers();
        });
        sleepStart.setOnAction(event -> {
            if (sleepSession.state() == FocusSession.State.RUNNING) sleepSession.cancel();
            else sleepSession.start();
            updateTimers();
        });
        FlowPane focusControls = new FlowPane(8, 6, new Label("专注"), focusMinutes,
                new Label("分钟"), focusTime, focusStart, focusEnd);
        FlowPane sleepControls = new FlowPane(8, 6, new Label("睡眠定时"), sleepMinutes,
                new Label("分钟"), sleepTime, sleepStart);
        focusControls.setAlignment(Pos.CENTER);
        sleepControls.setAlignment(Pos.CENTER);
        VBox timers = new VBox(8, focusControls, sleepControls);
        timers.setAlignment(Pos.CENTER);
        timers.setMaxWidth(700);

        VBox content = new VBox(10, artwork, title, artist, lyric, playback,
                playbackProgress, timers);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(700);
        content.setPadding(new Insets(8, 4, 8, 4));
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        setCenter(scroller);
        updateTimers();
        refresh = new Timeline(new KeyFrame(Duration.millis(250), event -> updateTimers()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
    }

    public ImageView coverView() { return cover; }
    public Button previousButton() { return previous; }
    public Button playButton() { return play; }
    public Button nextButton() { return next; }
    public Slider seekSlider() { return progress; }
    public ObjectProperty<Image> coverImageProperty() { return cover.imageProperty(); }
    public StringProperty titleProperty() { return title.textProperty(); }
    public StringProperty artistProperty() { return artist.textProperty(); }
    public StringProperty lyricProperty() { return lyric.textProperty(); }
    public StringProperty timeProperty() { return playbackTime.textProperty(); }
    public DoubleProperty progressProperty() { return progress.valueProperty(); }
    public FocusSession focusSession() { return focusSession; }
    public FocusSession sleepSession() { return sleepSession; }
    public void setPlaying(boolean playing) { play.setText(playing ? "暂停" : "播放"); }

    public void updateTrack(Image image, String trackTitle, String trackArtist, String currentLyric) {
        cover.setImage(image);
        title.setText(trackTitle == null || trackTitle.isBlank() ? "尚未播放" : trackTitle);
        artist.setText(trackArtist == null ? "" : trackArtist);
        lyric.setText(currentLyric == null ? "" : currentLyric);
    }

    public void setLyric(String currentLyric) {
        lyric.setText(currentLyric == null ? "" : currentLyric);
    }

    private void updateTimers() {
        focusSession.tick();
        sleepSession.tick();
        if (focusSession.state() != FocusSession.State.COMPLETED)
            focusTime.setText(format(focusSession.remaining()));
        focusStart.setText(switch (focusSession.state()) {
            case RUNNING -> "暂停专注";
            case PAUSED -> "继续专注";
            case COMPLETED -> "重新开始";
            case IDLE -> "开始专注";
        });
        sleepTime.setText(sleepSession.state() == FocusSession.State.COMPLETED
                ? "已结束" : format(sleepSession.remaining()));
        sleepStart.setText(sleepSession.state() == FocusSession.State.RUNNING
                ? "取消睡眠定时" : "开始睡眠定时");
    }

    private static String format(java.time.Duration duration) {
        long seconds = (duration.toMillis() + 999) / 1000;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    @Override public void close() {
        refresh.stop();
        focusSession.cancel();
        sleepSession.cancel();
    }
}
