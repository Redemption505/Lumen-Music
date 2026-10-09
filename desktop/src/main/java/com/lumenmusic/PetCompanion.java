package com.lumenmusic;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Path;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.Node;
import javafx.util.Duration;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small desktop companion. Reactions are driven only by observed player/library events. */
public final class PetCompanion extends VBox implements AutoCloseable {
    public enum Species {
        BLACK_CAT("小黑咪"), RICE_GHOST("糯米飘"), ORANGE_DOG("橘子汪"), HIDDEN("不显示");
        private final String label;
        Species(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public enum Event {
        PLAYING, PAUSED, TRACK_CHANGED, FAVORITED, UNFAVORITED,
        NEW_FAVORITE, REPLAYED_OLD_FAVORITE, FOCUS_COMPLETED;

        boolean isMilestone() {
            return this == NEW_FAVORITE || this == REPLAYED_OLD_FAVORITE || this == FOCUS_COMPLETED;
        }
    }

    private final StackPane stage = new StackPane();
    private final Label name = new Label();
    private final Label bubble = new Label();
    private final ScaleTransition rhythm = new ScaleTransition(Duration.millis(650), stage);
    private final PauseTransition hideBubble = new PauseTransition(Duration.seconds(2.6));
    private final TranslateTransition reaction = new TranslateTransition(Duration.millis(210), stage);
    private Timeline idle;
    private Timeline blink;
    private Timeline detailMotion;
    private PetArt currentArt;
    private Species species = Species.BLACK_CAT;
    private boolean playing;
    private boolean compact;
    private boolean quiet;
    private boolean nightMode;
    private boolean reducedMotion;
    private final PetPhrases phrases = new PetPhrases();
    private final PetEventPolicy eventPolicy;
    private Consumer<String> eventNotice;
    private long lastInteractionPhrase;
    private boolean closed;

    public PetCompanion() {
        this(Clock.systemDefaultZone());
    }

    PetCompanion(Clock clock) {
        eventPolicy = new PetEventPolicy(clock);
        setId("pet-companion");
        getStyleClass().add("pet-companion");
        setAlignment(Pos.CENTER);
        setSpacing(8);
        setPadding(new Insets(12));
        stage.setMinSize(132, 122);
        stage.setPrefSize(132, 122);
        name.getStyleClass().add("pet-name");
        bubble.getStyleClass().add("pet-bubble");
        bubble.setWrapText(true);
        bubble.setMaxWidth(175);
        bubble.setVisible(false);
        bubble.setManaged(false);
        getChildren().addAll(stage, name, bubble);
        rhythm.setFromY(1);
        rhythm.setToY(1.055);
        rhythm.setFromX(1);
        rhythm.setToX(1.025);
        rhythm.setAutoReverse(true);
        rhythm.setCycleCount(Animation.INDEFINITE);
        rhythm.setInterpolator(Interpolator.EASE_BOTH);
        reaction.setFromY(0);
        reaction.setToY(-9);
        reaction.setAutoReverse(true);
        reaction.setCycleCount(2);
        hideBubble.setOnFinished(event -> {
            bubble.setVisible(false);
            bubble.setManaged(false);
        });
        setSpecies(species);
    }

    public Species species() { return species; }
    public boolean isPlaying() { return playing; }
    public boolean isQuiet() { return quiet; }
    public boolean isNightMode() { return nightMode; }
    public boolean isReducedMotion() { return reducedMotion; }

    /** Used by the compact dock to display accepted milestone messages nearby. */
    public void setEventNotice(Consumer<String> eventNotice) {
        if (closed) return;
        this.eventNotice = eventNotice;
    }

    public void setNightMode(boolean nightMode) {
        if (this.nightMode == nightMode || closed) return;
        this.nightMode = nightMode;
        if (nightMode) {
            stopMotion();
            hideBubble.stop();
            bubble.setVisible(false);
            bubble.setManaged(false);
        } else {
            resumeMotion();
        }
    }

    public void setQuiet(boolean quiet) {
        if (this.quiet == quiet || closed) return;
        this.quiet = quiet;
        if (quiet) {
            stopMotion();
            hideBubble.stop();
            bubble.setVisible(false);
            bubble.setManaged(false);
        } else {
            resumeMotion();
        }
    }

    public void setReducedMotion(boolean reducedMotion) {
        if (this.reducedMotion == reducedMotion || closed) return;
        this.reducedMotion = reducedMotion;
        if (reducedMotion) stopMotion();
        else resumeMotion();
    }

    public void setCompact(boolean compact) {
        if (closed) return;
        this.compact = compact;
        if (compact) {
            hideBubble.stop();
            bubble.setVisible(false);
            bubble.setManaged(false);
        }
        double size = compact ? 70 : 132;
        stage.setMinSize(size, compact ? 70 : 122);
        stage.setPrefSize(size, compact ? 70 : 122);
        stage.setMaxSize(size, compact ? 70 : 122);
        name.setVisible(!compact);
        name.setManaged(!compact);
        for (var child : stage.getChildren()) {
            child.setScaleX(compact ? .56 : 1);
            child.setScaleY(compact ? .56 : 1);
        }
        setPadding(compact ? Insets.EMPTY : new Insets(12));
    }

    public void setSpecies(Species species) {
        if (closed) return;
        this.species = java.util.Objects.requireNonNull(species);
        boolean hidden = species == Species.HIDDEN;
        setVisible(!hidden);
        setManaged(!hidden);
        if (hidden) {
            stopMotion();
            hideBubble.stop();
            bubble.setVisible(false);
            bubble.setManaged(false);
            currentArt = null;
            return;
        }
        name.setText(species.label());
        stopMotion();
        PetArt art = switch (species) {
            case BLACK_CAT -> cat();
            case RICE_GHOST -> ghost();
            case ORANGE_DOG -> dog();
            case HIDDEN -> throw new IllegalStateException("Hidden species has no graphic");
        };
        currentArt = art;
        stage.getChildren().setAll(art.root());
        for (var child : stage.getChildren()) {
            child.setScaleX(compact ? .56 : 1);
            child.setScaleY(compact ? .56 : 1);
        }
        resumeMotion();
    }

    public void onPlaybackState(boolean playing) {
        if (closed || this.playing == playing) return;
        this.playing = playing;
        if (species == Species.HIDDEN || closed) return;
        if (playing) {
            if (motionAllowed()) rhythm.play();
            sayAuto(Event.PLAYING, phrases.playing::next);
        } else {
            rhythm.stop();
            stage.setScaleX(1);
            stage.setScaleY(1);
            sayAuto(Event.PAUSED, phrases.paused::next);
        }
    }

    public void onTrackChanged(String title) {
        if (title == null || title.isBlank()) return;
        String concise = title.strip();
        if (concise.length() > 14) concise = concise.substring(0, 13) + "…";
        String titleText = concise;
        sayAuto(Event.TRACK_CHANGED, () -> phrases.trackChanged.next().formatted(titleText));
        react();
    }

    public void onFavoriteChanged(boolean favorite) {
        sayAuto(favorite ? Event.FAVORITED : Event.UNFAVORITED,
                favorite ? phrases.favorited::next : phrases.unfavorited::next);
        react();
    }

    /** The caller must confirm the real event before invoking this method. */
    public boolean onMusicEvent(Event event, String detail) {
        Objects.requireNonNull(event);
        if (!event.isMilestone()) throw new IllegalArgumentException("Expected a music milestone event");
        boolean accepted = sayAuto(event, () -> switch (event) {
            case NEW_FAVORITE -> phrases.newFavorite.next().formatted(describeTrack(detail));
            case REPLAYED_OLD_FAVORITE -> phrases.replayedOldFavorite.next().formatted(describeTrack(detail));
            case FOCUS_COMPLETED -> phrases.focusCompleted.next();
            default -> throw new IllegalStateException("Not a music milestone event");
        });
        if (accepted && !quiet) react();
        return accepted;
    }

    private static String describeTrack(String detail) {
        if (detail == null || detail.isBlank()) return "这首歌";
        String title = detail.strip();
        if (title.length() > 14) title = title.substring(0, 13) + "…";
        return "「" + title + "」";
    }

    public void onInteraction(String source) {
        if (closed || species == Species.HIDDEN) return;
        if (quiet || nightMode) {
            showBubble("陪着你。", true);
            return;
        }
        react();
        long now = System.nanoTime();
        if (lastInteractionPhrase == 0 || now - lastInteractionPhrase >= 5_000_000_000L) {
            lastInteractionPhrase = now;
            showBubble(switch (species) {
                case BLACK_CAT -> "喵！";
                case RICE_GHOST -> "飘过来陪你啦";
                case ORANGE_DOG -> "汪！";
                case HIDDEN -> "";
            }, false);
        }
    }

    private void startIdle(PetArt art) {
        Node root = art.root();
        double drift = species == Species.RICE_GHOST ? -5 : -2;
        idle = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(root.translateYProperty(), 0),
                        new KeyValue(root.rotateProperty(), species == Species.RICE_GHOST ? -3 : -2)),
                new KeyFrame(Duration.seconds(1.3), new KeyValue(root.translateYProperty(), drift),
                        new KeyValue(root.rotateProperty(), species == Species.RICE_GHOST ? 3 : 2)),
                new KeyFrame(Duration.seconds(2.6), new KeyValue(root.translateYProperty(), 0),
                        new KeyValue(root.rotateProperty(), species == Species.RICE_GHOST ? -3 : -2)));
        idle.setCycleCount(Animation.INDEFINITE);
        idle.play();
        blink = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(art.eyes().scaleYProperty(), 1)),
                new KeyFrame(Duration.seconds(2.3), new KeyValue(art.eyes().scaleYProperty(), 1)),
                new KeyFrame(Duration.seconds(2.4), new KeyValue(art.eyes().scaleYProperty(), .12)),
                new KeyFrame(Duration.seconds(2.5), new KeyValue(art.eyes().scaleYProperty(), 1)),
                new KeyFrame(Duration.seconds(4.2), new KeyValue(art.eyes().scaleYProperty(), 1)));
        blink.setCycleCount(Animation.INDEFINITE);
        blink.play();
        if (art.detail() != null) {
            detailMotion = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(art.detail().rotateProperty(), -14)),
                    new KeyFrame(Duration.millis(320), new KeyValue(art.detail().rotateProperty(), 15)),
                    new KeyFrame(Duration.millis(640), new KeyValue(art.detail().rotateProperty(), -14)));
            detailMotion.setCycleCount(Animation.INDEFINITE);
            detailMotion.play();
        }
    }

    private void stopIdle() {
        if (idle != null) idle.stop();
        if (blink != null) blink.stop();
        if (detailMotion != null) detailMotion.stop();
        idle = null;
        blink = null;
        detailMotion = null;
    }

    private boolean motionAllowed() {
        return !closed && !quiet && !nightMode && !reducedMotion && species != Species.HIDDEN;
    }

    private void resumeMotion() {
        if (!motionAllowed() || currentArt == null) return;
        if (playing) rhythm.play();
        if (idle == null) startIdle(currentArt);
    }

    private void stopMotion() {
        stopIdle();
        rhythm.stop();
        reaction.stop();
        stage.setTranslateX(0);
        stage.setTranslateY(0);
        stage.setRotate(0);
        stage.setScaleX(1);
        stage.setScaleY(1);
        if (currentArt != null) {
            currentArt.root().setTranslateX(0);
            currentArt.root().setTranslateY(0);
            currentArt.root().setRotate(0);
            currentArt.eyes().setScaleY(1);
            if (currentArt.detail() != null) currentArt.detail().setRotate(0);
        }
    }

    private boolean sayAuto(Event event, Supplier<String> message) {
        if (closed) return false;
        boolean compactNotice = compact && event.isMilestone() && eventNotice != null;
        if (!eventPolicy.accept(event, species == Species.HIDDEN || (compact && !compactNotice),
                quiet, nightMode)) return false;
        String text = message.get();
        if (compactNotice) eventNotice.accept(text);
        else showBubble(text, event == Event.FOCUS_COMPLETED);
        return true;
    }

    private void showBubble(String message, boolean manual) {
        if (species == Species.HIDDEN || compact || closed || (quiet && !manual)) return;
        bubble.setText(message);
        bubble.setManaged(true);
        bubble.setVisible(true);
        hideBubble.playFromStart();
    }

    private void react() {
        if (!motionAllowed()) return;
        reaction.playFromStart();
    }

    private static Circle circle(double x, double y, double radius, Color fill) {
        Circle shape = new Circle(x, y, radius, fill);
        return shape;
    }

    private record PetArt(Group root, Group eyes, Node detail) { }

    private static Group eyes(double left, double right, double y, double radius, Color fill) {
        return new Group(circle(left, y, radius, fill), circle(right, y, radius, fill));
    }

    private static PetArt cat() {
        Color fur = Color.web("#33364a"), inner = Color.web("#b97ca5");
        Polygon leftEar = new Polygon(26, 46, 35, 10, 56, 36);
        Polygon rightEar = new Polygon(76, 36, 97, 10, 106, 46);
        leftEar.setFill(fur); rightEar.setFill(fur);
        Polygon leftInner = new Polygon(34, 35, 38, 22, 46, 36);
        Polygon rightInner = new Polygon(86, 36, 94, 22, 98, 35);
        leftInner.setFill(inner); rightInner.setFill(inner);
        Ellipse face = new Ellipse(66, 64, 43, 38);
        face.setFill(fur);
        Polygon nose = new Polygon(62, 68, 70, 68, 66, 73);
        nose.setFill(Color.web("#e694aa"));
        Path tail = tail(Color.web("#33364a"), 99, 86, 124, 71, 110, 54, 9);
        tail.setRotate(0);
        Group pupils = eyes(52, 82, 57, 2, Color.web("#202032"));
        return new PetArt(new Group(tail, leftEar, rightEar, face, leftInner, rightInner,
                eyes(51, 81, 57, 5, Color.web("#e9dfac")), pupils, nose), pupils, tail);
    }

    private static PetArt ghost() {
        Color rice = Color.web("#f3e9e3");
        Ellipse body = new Ellipse(66, 59, 40, 45);
        body.setFill(rice);
        Polygon hem = new Polygon(27, 76, 32, 104, 47, 95, 61, 105,
                76, 95, 91, 105, 101, 78);
        hem.setFill(rice);
        Group eyes = eyes(52, 80, 55, 4, Color.web("#413b51"));
        return new PetArt(new Group(body, hem, eyes,
                circle(43, 67, 6, Color.web("#efb5b2")),
                circle(89, 67, 6, Color.web("#efb5b2")),
                circle(66, 70, 3, Color.web("#ad7b82"))), eyes, null);
    }

    private static PetArt dog() {
        Color fur = Color.web("#dd9254");
        Ellipse leftEar = new Ellipse(30, 63, 16, 34);
        Ellipse rightEar = new Ellipse(102, 63, 16, 34);
        leftEar.setFill(Color.web("#a96a3f")); rightEar.setFill(Color.web("#a96a3f"));
        Ellipse face = new Ellipse(66, 62, 40, 38);
        face.setFill(fur);
        Ellipse muzzle = new Ellipse(66, 77, 22, 14);
        muzzle.setFill(Color.web("#f5d4a8"));
        Path tail = tail(Color.web("#a96a3f"), 95, 83, 120, 82, 116, 62, 12);
        Group eyes = eyes(51, 81, 58, 4, Color.web("#3b2c2b"));
        return new PetArt(new Group(tail, leftEar, rightEar, face, muzzle, eyes,
                circle(66, 72, 6, Color.web("#443031"))), eyes, tail);
    }

    private static Path tail(Color color, double x1, double y1, double cx, double cy,
                             double x2, double y2, double width) {
        Path path = new Path(new MoveTo(x1, y1), new QuadCurveTo(cx, cy, x2, y2));
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setFill(Color.TRANSPARENT);
        return path;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        eventNotice = null;
        eventPolicy.close();
        stopMotion();
        hideBubble.stop();
        hideBubble.setOnFinished(null);
        bubble.setVisible(false);
        bubble.setManaged(false);
    }

    public void dispose() { close(); }
}

/** Independent phrase cycles keep each observed event varied without random repetition. */
final class PetPhrases {
    final PhraseCycle playing = new PhraseCycle(List.of(
            "开始播放啦", "音乐继续流淌", "一起听这一首", "把这一刻留给音乐",
            "继续听这段旋律", "我在这里陪你听", "歌声又响起来了", "今天也有音乐作伴"));
    final PhraseCycle paused = new PhraseCycle(List.of(
            "先歇一会儿", "音乐暂时停下了", "等你回来继续听", "这首歌先按下暂停",
            "不用急，我在这里", "旋律暂时休息一下", "留一点安静给自己", "下一段旋律，等你准备好"));
    final PhraseCycle trackChanged = new PhraseCycle(List.of(
            "切换到「%s」", "下一首是「%s」", "这次选了「%s」", "换成「%s」啦",
            "这次选中「%s」", "播放列表来到「%s」", "准备听「%s」", "新曲目是「%s」"));
    final PhraseCycle favorited = new PhraseCycle(List.of(
            "把这首歌留下啦", "这首歌已收藏", "喜欢的旋律，多留一首", "已标记为喜欢",
            "下次还能在收藏里找到它", "这首歌进入收藏了", "记下这一首", "又添了一首喜欢的歌"));
    final PhraseCycle unfavorited = new PhraseCycle(List.of(
            "已从收藏中移除", "给收藏腾点位置", "这一首先放下", "已取消喜欢标记",
            "已从喜欢列表移除", "这首歌已取消收藏", "喜欢的歌，也可以重新选择", "收藏里不再保留这一首"));
    final PhraseCycle newFavorite = new PhraseCycle(List.of(
            "刚收藏了%s", "新收藏%s已记下", "%s进了收藏", "收藏里添了%s",
            "把%s留在收藏里", "已收藏%s", "新喜欢的%s已记下", "%s加入收藏啦"));
    final PhraseCycle replayedOldFavorite = new PhraseCycle(List.of(
            "又听了一遍老收藏%s", "老收藏%s再次响起", "重听了收藏已久的%s", "又回到老收藏%s",
            "今天重听了%s", "老收藏%s又陪你听了一次", "再次播放了老收藏%s", "重温了老收藏%s"));
    final PhraseCycle focusCompleted = new PhraseCycle(List.of(
            "专注完成，辛苦啦。", "这段专注结束了。", "专注完成，可以歇一歇。", "完成这次专注了。",
            "这次专注已完成。", "专注时间到了，辛苦啦。", "刚完成一段专注。", "专注结束，慢慢来。"));

    static final class PhraseCycle {
        private final List<String> phrases;
        private int next;

        PhraseCycle(List<String> phrases) {
            if (phrases == null || phrases.isEmpty()) throw new IllegalArgumentException("phrases must not be empty");
            this.phrases = List.copyOf(phrases);
        }

        String next() {
            String phrase = phrases.get(next);
            next = (next + 1) % phrases.size();
            return phrase;
        }

        int size() { return phrases.size(); }
    }
}
