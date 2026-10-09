package com.lumenmusic;

import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.util.Duration;

/** Allows compact windows to move without taking presses away from playback controls. */
final class LongPressWindowDrag {
    private LongPressWindowDrag() { }

    static void install(Node surface, Stage stage) {
        Gesture gesture = new Gesture(surface, stage);
        gesture.attach(surface.getScene());
        surface.sceneProperty().addListener((ignored, before, after) -> {
            gesture.detach(before);
            gesture.attach(after);
        });
        stage.showingProperty().addListener((ignored, before, showing) -> {
            if (!showing) gesture.cancel();
        });
        stage.focusedProperty().addListener((ignored, before, focused) -> {
            if (!focused) gesture.cancel();
        });
    }

    private static final class Gesture {
        private final Node surface;
        private final Stage stage;
        private final PauseTransition hold = new PauseTransition(Duration.millis(300));
        private final EventHandler<MouseEvent> handler = this::handle;
        private boolean pressed;
        private boolean dragging;
        private boolean suppressClick;
        private double pointerX;
        private double pointerY;
        private double offsetX;
        private double offsetY;
        private Cursor originalCursor;

        Gesture(Node surface, Stage stage) {
            this.surface = surface;
            this.stage = stage;
            hold.setOnFinished(event -> {
                if (!pressed || stage.isMaximized() || !stage.isShowing()) return;
                // Pointer movement while waiting must not cause the first frame to jump.
                offsetX = pointerX - stage.getX();
                offsetY = pointerY - stage.getY();
                dragging = true;
                surface.setCursor(Cursor.MOVE);
            });
        }

        void attach(Scene scene) {
            if (scene != null) scene.addEventFilter(MouseEvent.ANY, handler);
        }

        void detach(Scene scene) {
            cancel();
            if (scene != null) scene.removeEventFilter(MouseEvent.ANY, handler);
        }

        void handle(MouseEvent event) {
            if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                cancel();
                suppressClick = false;
                if (event.getButton() != MouseButton.PRIMARY || stage.isMaximized()
                        || resizeEdge(event) || !withinSurface(event.getTarget())
                        || interactive(event.getTarget())) return;
                pressed = true;
                originalCursor = surface.getCursor();
                pointerX = event.getScreenX();
                pointerY = event.getScreenY();
                hold.playFromStart();
            } else if (event.getEventType() == MouseEvent.MOUSE_DRAGGED && pressed) {
                if (!event.isPrimaryButtonDown()) { cancel(); return; }
                pointerX = event.getScreenX();
                pointerY = event.getScreenY();
                if (!dragging || stage.isMaximized()) return;
                moveWindow(pointerX - offsetX, pointerY - offsetY);
                suppressClick = true;
                event.consume();
            } else if (event.getEventType() == MouseEvent.MOUSE_RELEASED) {
                if (dragging) {
                    suppressClick = true;
                    event.consume();
                }
                cancel();
            } else if (event.getEventType() == MouseEvent.MOUSE_CLICKED && suppressClick) {
                suppressClick = false;
                event.consume();
            } else if (event.getEventType() == MouseEvent.MOUSE_MOVED && pressed
                    && !event.isPrimaryButtonDown()) {
                // Recover if a native operation swallowed the release event.
                cancel();
            }
        }

        private void moveWindow(double x, double y) {
            double width = Math.max(1, stage.getWidth()), height = Math.max(1, stage.getHeight());
            var screens = Screen.getScreensForRectangle(pointerX, pointerY, 1, 1);
            Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
            double maxX = Math.max(bounds.getMinX(), bounds.getMaxX() - width);
            double maxY = Math.max(bounds.getMinY(), bounds.getMaxY() - height);
            stage.setX(Math.max(bounds.getMinX(), Math.min(x, maxX)));
            stage.setY(Math.max(bounds.getMinY(), Math.min(y, maxY)));
        }

        private boolean withinSurface(Object target) {
            for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent())
                if (node == surface) return true;
            return false;
        }

        private boolean resizeEdge(MouseEvent event) {
            Scene scene = surface.getScene();
            // Both gestures use scene filters. Leave the same 7px edge region to
            // WindowResize even when its filter was registered after this one.
            return scene != null && WindowResize.edgeAt(event.getSceneX(), event.getSceneY(),
                    scene.getWidth(), scene.getHeight()) != WindowResize.Edge.NONE;
        }

        void cancel() {
            hold.stop();
            pressed = false;
            if (dragging) surface.setCursor(originalCursor);
            dragging = false;
        }
    }

    private static boolean interactive(Object target) {
        for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
            if (node instanceof ButtonBase || node instanceof Slider || node instanceof ScrollBar || node instanceof TextInputControl
                    || node instanceof ComboBoxBase<?> || node instanceof ListView<?>) return true;
        }
        return false;
    }
}
