package com.lumenmusic;

import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Interpolator;
import javafx.geometry.Rectangle2D;
import javafx.beans.value.ChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.util.Objects;

/** Moves the existing companion between its dock and a small transparent desktop window. */
public final class DesktopPetWindow implements AutoCloseable {
    private static final double SIZE = 86;
    private static final double EVENT_NOTICE_WIDTH = 220;
    private static final double LYRIC_NOTICE_WIDTH = 260;
    private static final long DRAG_DELAY_NANOS = 250_000_000L;

    private final PetCompanion pet;
    private final VBox dock;
    private final Runnable restoreMain;
    private final String dockStyle;
    private final StackPane root = new StackPane();
    private Stage window;
    private int dockIndex;
    private double savedX = Double.NaN;
    private double savedY = Double.NaN;
    private double pressX;
    private double pressY;
    private double windowPressX;
    private double windowPressY;
    private long pressTime;
    private boolean dragging;
    private boolean closed;
    private Timeline placementMotion;
    private boolean returning;
    private final Popup noticePopup = new Popup();
    private final Label notice = new Label();
    private final StackPane noticeRoot = new StackPane(notice);
    private final PauseTransition hideNotice = new PauseTransition(Duration.seconds(4));
    private String lyric = "";
    private boolean lyricPlaying;
    private String eventNotice = "";
    private final ChangeListener<Number> followWindow = (ignored, before, after) -> positionNotice();
    private final ChangeListener<Boolean> petVisibility = (ignored, before, visible) -> {
        if (visible) refreshNotice();
        else dismissNotice();
    };

    public DesktopPetWindow(PetCompanion pet, VBox dock, Runnable restoreMain) {
        this.pet = Objects.requireNonNull(pet);
        this.dock = Objects.requireNonNull(dock);
        this.restoreMain = Objects.requireNonNull(restoreMain);
        this.dockStyle = pet.getStyle();
        if (pet.getParent() != dock) throw new IllegalArgumentException("pet must be in dock");
        dockIndex = dock.getChildren().indexOf(pet);
        root.setPrefSize(SIZE, SIZE);
        root.setMinSize(SIZE, SIZE);
        root.setMaxSize(SIZE, SIZE);
        root.setBackground(null);
        root.setStyle("-fx-background-color: transparent;");
        root.getStyleClass().add("lumen-theme");
        root.setOnMousePressed(this::pressed);
        root.setOnMouseDragged(this::moved);
        root.setOnMouseReleased(this::released);
        root.setOnMouseClicked(this::clicked);
        notice.setId("desktop-pet-event-notice");
        notice.getStyleClass().add("pet-bubble");
        notice.setWrapText(true);
        // The displayed text is measured/truncated below. Skin ellipsis can add
        // another wrapping line after its own clipping calculation.
        notice.setTextOverrun(OverrunStyle.CLIP);
        notice.setMaxWidth(EVENT_NOTICE_WIDTH);
        // Keep unusually long lyric lines within a two-line bubble.
        notice.fontProperty().addListener((ignored, before, after) -> limitNoticeHeight());
        noticeRoot.getStyleClass().addAll("root", "lumen-theme");
        noticeRoot.setPrefWidth(EVENT_NOTICE_WIDTH);
        noticeRoot.setMaxWidth(EVENT_NOTICE_WIDTH);
        noticeRoot.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        noticeRoot.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/lumen-theme.css")).toExternalForm());
        noticePopup.getContent().add(noticeRoot);
        noticePopup.getScene().getRoot().setStyle("-fx-background-color: transparent; -fx-padding: 0;"
                + " -fx-border-width: 0; -fx-effect: null;");
        noticePopup.setAutoHide(false);
        noticePopup.setAutoFix(false);
        noticePopup.widthProperty().addListener(followWindow);
        noticePopup.heightProperty().addListener(followWindow);
        hideNotice.setOnFinished(event -> {
            eventNotice = "";
            refreshNotice();
        });
        pet.visibleProperty().addListener(petVisibility);
        limitNoticeHeight();
    }

    public void show() {
        if (closed) return;
        if (window == null) {
            window = new Stage(StageStyle.TRANSPARENT);
            window.setAlwaysOnTop(true);
            Scene scene = new Scene(root, SIZE, SIZE, Color.TRANSPARENT);
            scene.setFill(Color.TRANSPARENT);
            scene.getStylesheets().add(Objects.requireNonNull(
                    getClass().getResource("/lumen-theme.css")).toExternalForm());
            window.setScene(scene);
            window.xProperty().addListener(followWindow);
            window.yProperty().addListener(followWindow);
            window.setOnCloseRequest(event -> {
                event.consume();
                hideToDock();
            });
        }
        if (window.isShowing()) return;
        Parent parent = pet.getParent();
        if (parent == dock) dockIndex = dock.getChildren().indexOf(pet);
        detach(parent);
        pet.setCompact(true);
        pet.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0;");
        root.getChildren().setAll(pet);
        if (!Double.isFinite(savedX) || !Double.isFinite(savedY)) {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            savedX = bounds.getMaxX() - SIZE - 32;
            savedY = bounds.getMaxY() - SIZE - 32;
        }
        position(savedX, savedY);
        window.show();
        refreshNotice();
    }

    public void showAnimated() {
        if (closed) return;
        if (isShowing()) {
            if (returning) {
                cancelPlacement();
                refreshNotice();
                if (MotionPreferences.reduced()) position(savedX, savedY);
                else {
                    placementMotion = placementTimeline(savedX, savedY, 300);
                    placementMotion.setOnFinished(event -> placementMotion = null);
                    placementMotion.play();
                }
            }
            return;
        }
        var source = pet.localToScreen(pet.getBoundsInLocal());
        show();
        if (source == null || MotionPreferences.reduced() || !isShowing()) return;
        double x = savedX, y = savedY;
        window.setX(source.getMinX()); window.setY(source.getMinY());
        placementMotion = placementTimeline(x, y, 300);
        placementMotion.setOnFinished(event -> placementMotion = null);
        placementMotion.play();
    }

    public void hideAnimated() {
        if (closed || !isShowing() || returning) return;
        var destination = dock.localToScreen(dock.getBoundsInLocal());
        if (destination == null || MotionPreferences.reduced()) { hideToDock(); return; }
        boolean leaving = placementMotion != null && placementMotion.getStatus() == javafx.animation.Animation.Status.RUNNING;
        double x = leaving ? savedX : window.getX(), y = leaving ? savedY : window.getY();
        cancelPlacement();
        savedX = x; savedY = y;
        returning = true;
        dismissNotice();
        placementMotion = placementTimeline(destination.getMinX() + (destination.getWidth() - SIZE) / 2,
                destination.getMinY(), 260);
        placementMotion.setOnFinished(event -> {
            hideToDock(); savedX = x; savedY = y;
        });
        placementMotion.play();
    }

    private void cancelPlacement() {
        if (placementMotion != null) {
            placementMotion.setOnFinished(null);
            placementMotion.stop();
        }
        placementMotion = null;
        returning = false;
    }

    private Timeline placementTimeline(double x, double y, double millis) {
        var animatedX = new javafx.beans.property.SimpleDoubleProperty(window.getX());
        var animatedY = new javafx.beans.property.SimpleDoubleProperty(window.getY());
        animatedX.addListener((ignored, before, after) -> window.setX(after.doubleValue()));
        animatedY.addListener((ignored, before, after) -> window.setY(after.doubleValue()));
        return new Timeline(new KeyFrame(Duration.millis(millis),
                new KeyValue(animatedX, x, Interpolator.EASE_BOTH),
                new KeyValue(animatedY, y, Interpolator.EASE_BOTH)));
    }

    public void hideToDock() {
        cancelPlacement();
        dismissNotice();
        if (window != null) {
            savedX = window.getX();
            savedY = window.getY();
            window.hide();
        }
        if (pet.getParent() == root) root.getChildren().remove(pet);
        if (pet.getParent() != dock) {
            detach(pet.getParent());
            dock.getChildren().add(Math.min(Math.max(0, dockIndex), dock.getChildren().size()), pet);
        }
        pet.setStyle(dockStyle);
        pet.setCompact(true);
    }

    public boolean isShowing() { return window != null && window.isShowing(); }

    public void showNotice(String message) {
        if (closed || returning || !isShowing() || !pet.isVisible() || message == null || message.isBlank()) return;
        eventNotice = message.strip();
        refreshNotice();
        hideNotice.playFromStart();
    }

    /** Current matched lyric line. Empty text clears it; paused playback hides it immediately. */
    public void setLyric(String text, boolean playing) {
        if (closed) return;
        String next = text == null ? "" : text.strip();
        if (lyric.equals(next) && lyricPlaying == playing) return;
        lyric = next;
        lyricPlaying = playing;
        refreshNotice();
    }

    private void refreshNotice() {
        if (closed || returning || !isShowing() || !pet.isVisible()) {
            noticePopup.hide();
            return;
        }
        boolean lyricNotice = eventNotice.isEmpty();
        String text = lyricNotice ? (lyricPlaying ? lyric : "") : eventNotice;
        if (text.isEmpty()) {
            noticePopup.hide();
            return;
        }
        notice.setId(lyricNotice ? "desktop-pet-current-lyric" : "desktop-pet-event-notice");
        if (lyricNotice && !notice.getStyleClass().contains("desktop-pet-lyric"))
            notice.getStyleClass().add("desktop-pet-lyric");
        else if (!lyricNotice) notice.getStyleClass().remove("desktop-pet-lyric");
        double width = lyricNotice ? LYRIC_NOTICE_WIDTH : EVENT_NOTICE_WIDTH;
        notice.setMaxWidth(width);
        noticeRoot.setPrefWidth(width);
        noticeRoot.setMaxWidth(width);
        notice.setText(text);
        layoutNotice();
        // Creating the native peer establishes its real DPI and inherited owner
        // styles. Finish sizing with those values before the next paint.
        if (!noticePopup.isShowing()) {
            noticePopup.show(window);
            layoutNotice();
        }
        noticePopup.sizeToScene();
        positionNotice();
    }

    private void layoutNotice() {
        noticeRoot.applyCss();
        limitNoticeHeight();
        noticeRoot.autosize();
        noticeRoot.layout();
        // PopupWindow measures the union of its scene root's layout/bounds. Its
        // wrapper Pane can retain the previous scene width after content shrinks;
        // resize that root as well before asking the native window to follow it.
        Parent popupRoot = noticePopup.getScene().getRoot();
        popupRoot.applyCss();
        popupRoot.autosize();
        popupRoot.layout();
    }

    private void limitNoticeHeight() {
        double padding = notice.snappedTopInset() + notice.snappedBottomInset();
        var skinText = notice.lookup(".text");
        // Modena applies LOGICAL_VERTICAL_CENTER to control .text nodes, unlike
        // a new Text's LOGICAL default. Measure with the actual skin's bounds type.
        TextBoundsType boundsType = skinText instanceof Text text ? text.getBoundsType()
                : TextBoundsType.LOGICAL_VERTICAL_CENTER;
        Text twoLineText = new Text("Ag\nAg");
        twoLineText.setFont(notice.getFont());
        twoLineText.setBoundsType(boundsType);
        twoLineText.setLineSpacing(notice.getLineSpacing());
        double twoLines = twoLineText.getLayoutBounds().getHeight();
        Text oneLineText = new Text("Ag");
        oneLineText.setFont(notice.getFont());
        oneLineText.setBoundsType(boundsType);
        double oneLine = oneLineText.getLayoutBounds().getHeight();
        String original = eventNotice.isEmpty() ? lyric : eventNotice;
        Text wrapped = new Text(original);
        wrapped.setFont(notice.getFont());
        wrapped.setBoundsType(boundsType);
        wrapped.setLineSpacing(notice.getLineSpacing());
        wrapped.setWrappingWidth(Math.max(1, notice.getMaxWidth()
                - notice.snappedLeftInset() - notice.snappedRightInset()));
        String display = fitTwoLines(original, wrapped, twoLines);
        notice.setText(display);
        wrapped.setText(display);
        // Some glyph strings have smaller standalone logical bounds than the
        // skin's complete line box. Allocate whole measured line boxes so the
        // wrapped second line and its ellipsis remain inside the skin's clip.
        double contentHeight = wrapped.getLayoutBounds().getHeight() <= oneLine ? oneLine : twoLines;
        double height = notice.snapSizeY(contentHeight) + padding;
        double maxHeight = notice.snapSizeY(twoLines) + padding;
        notice.setPrefHeight(height);
        notice.setMaxHeight(maxHeight);
        noticeRoot.setMaxHeight(maxHeight);
        noticeRoot.setPrefHeight(height);
    }

    private static String fitTwoLines(String original, Text measured, double maxHeight) {
        if (measured.getLayoutBounds().getHeight() <= maxHeight) return original;
        int low = 0, high = original.codePointCount(0, original.length());
        while (low < high) {
            int count = low + (high - low + 1) / 2;
            String candidate = original.substring(0, original.offsetByCodePoints(0, count)) + "…";
            measured.setText(candidate);
            if (measured.getLayoutBounds().getHeight() <= maxHeight) low = count;
            else high = count - 1;
        }
        return original.substring(0, original.offsetByCodePoints(0, low)) + "…";
    }

    private void positionNotice() {
        if (window == null || !noticePopup.isShowing()) return;
        Rectangle2D bounds = nearestScreen(window.getX(), window.getY()).getVisualBounds();
        noticePopup.setX(Math.max(bounds.getMinX() + 8,
                Math.min(window.getX() + (SIZE - noticePopup.getWidth()) / 2,
                        bounds.getMaxX() - noticePopup.getWidth() - 8)));
        double above = window.getY() - noticePopup.getHeight() - 8;
        double y = above >= bounds.getMinY() + 8 ? above : window.getY() + SIZE + 8;
        noticePopup.setY(Math.max(bounds.getMinY() + 8,
                Math.min(y, bounds.getMaxY() - noticePopup.getHeight() - 8)));
    }

    private void dismissNotice() {
        hideNotice.stop();
        eventNotice = "";
        noticePopup.hide();
    }
    public double getX() { return placementMotion == null && window != null && window.isShowing() ? window.getX() : savedX; }
    public double getY() { return placementMotion == null && window != null && window.isShowing() ? window.getY() : savedY; }
    public void setX(double x) {
        double y = getY();
        if (!Double.isFinite(y)) y = Screen.getPrimary().getVisualBounds().getMaxY() - SIZE - 32;
        position(x, y);
    }
    public void setY(double y) {
        double x = getX();
        if (!Double.isFinite(x)) x = Screen.getPrimary().getVisualBounds().getMaxX() - SIZE - 32;
        position(x, y);
    }

    public void setPosition(double x, double y) { position(x, y); }

    private void position(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return;
        Rectangle2D bounds = nearestScreen(x, y).getVisualBounds();
        savedX = Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - SIZE));
        savedY = Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - SIZE));
        if (window != null) {
            window.setX(savedX);
            window.setY(savedY);
        }
    }

    private static Screen nearestScreen(double x, double y) {
        var screens = Screen.getScreensForRectangle(x, y, SIZE, SIZE);
        if (!screens.isEmpty()) return screens.get(0);
        return Screen.getPrimary();
    }

    private void detach(Parent parent) {
        if (parent instanceof Pane pane) pane.getChildren().remove(pet);
        else if (parent != null) throw new IllegalStateException("unsupported pet parent: " + parent);
    }

    private void pressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || window == null) return;
        cancelPlacement();
        pressTime = System.nanoTime();
        pressX = event.getScreenX();
        pressY = event.getScreenY();
        windowPressX = window.getX();
        windowPressY = window.getY();
        dragging = false;
        event.consume();
    }

    private void moved(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) return;
        if (System.nanoTime() - pressTime >= DRAG_DELAY_NANOS) {
            dragging = true;
            position(windowPressX + event.getScreenX() - pressX,
                    windowPressY + event.getScreenY() - pressY);
        }
        event.consume();
    }

    private void released(MouseEvent event) { event.consume(); }

    private void clicked(MouseEvent event) {
        if (!dragging && System.nanoTime() - pressTime < DRAG_DELAY_NANOS) {
            if (event.getClickCount() >= 2) restoreMain.run();
            else pet.onInteraction("desktop");
        }
        event.consume();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        hideToDock();
        hideNotice.setOnFinished(null);
        lyric = "";
        pet.visibleProperty().removeListener(petVisibility);
        noticePopup.widthProperty().removeListener(followWindow);
        noticePopup.heightProperty().removeListener(followWindow);
        noticePopup.getContent().clear();
        root.setOnMousePressed(null);
        root.setOnMouseDragged(null);
        root.setOnMouseReleased(null);
        root.setOnMouseClicked(null);
        if (window != null) {
            window.xProperty().removeListener(followWindow);
            window.yProperty().removeListener(followWindow);
            window.setOnCloseRequest(null);
            window.close();
            window = null;
        }
        pet.close();
    }
}
