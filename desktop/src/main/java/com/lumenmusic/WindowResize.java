package com.lumenmusic;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/** Edge dragging for an undecorated stage, using the pressed screen position as its anchor. */
final class WindowResize {
    static final double BORDER = 7;

    enum Edge {
        NONE(false, false, false, false, Cursor.DEFAULT),
        N(false, false, true, false, Cursor.N_RESIZE),
        NE(false, true, true, false, Cursor.NE_RESIZE),
        E(false, true, false, false, Cursor.E_RESIZE),
        SE(false, true, false, true, Cursor.SE_RESIZE),
        S(false, false, false, true, Cursor.S_RESIZE),
        SW(true, false, false, true, Cursor.SW_RESIZE),
        W(true, false, false, false, Cursor.W_RESIZE),
        NW(true, false, true, false, Cursor.NW_RESIZE);

        final boolean west, east, north, south;
        final Cursor cursor;

        Edge(boolean west, boolean east, boolean north, boolean south, Cursor cursor) {
            this.west = west;
            this.east = east;
            this.north = north;
            this.south = south;
            this.cursor = cursor;
        }
    }

    record Bounds(double x, double y, double width, double height) { }

    private final Stage stage;
    private final Scene scene;
    private Edge dragging = Edge.NONE;
    private Bounds initial;
    private double screenX, screenY;
    private Cursor previousSceneCursor;
    private Node cursorNode;
    private Cursor previousNodeCursor;
    private boolean cursorActive;
    private boolean suppressClick;

    static void install(Stage stage, Scene scene) { new WindowResize(stage, scene); }

    private WindowResize(Stage stage, Scene scene) {
        this.stage = stage;
        this.scene = scene;
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, this::moved);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::pressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::dragged);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, this::released);
        scene.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (suppressClick) { suppressClick = false; event.consume(); }
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (dragging == Edge.NONE && (event.getTarget() == scene
                    || outside(event.getSceneX(), event.getSceneY()))) restoreCursor();
        });
        stage.maximizedProperty().addListener((ignored, oldValue, maximized) -> cancel());
        stage.fullScreenProperty().addListener((ignored, oldValue, fullScreen) -> cancel());
        stage.focusedProperty().addListener((ignored, oldValue, focused) -> {
            if (!focused) cancel();
        });
        stage.showingProperty().addListener((ignored, oldValue, showing) -> {
            if (!showing) cancel();
        });
    }

    static Edge edgeAt(double x, double y, double width, double height) {
        if (x < 0 || y < 0 || x > width || y > height) return Edge.NONE;
        boolean west = x <= BORDER, east = x >= width - BORDER;
        boolean north = y <= BORDER, south = y >= height - BORDER;
        if (north) return west ? Edge.NW : east ? Edge.NE : Edge.N;
        if (south) return west ? Edge.SW : east ? Edge.SE : Edge.S;
        return west ? Edge.W : east ? Edge.E : Edge.NONE;
    }

    static Bounds resize(Bounds initial, Edge edge, double dx, double dy,
                         double minWidth, double minHeight, double maxWidth, double maxHeight) {
        double width = initial.width(), height = initial.height();
        if (edge.west || edge.east)
            width = Math.max(minWidth, Math.min(maxWidth, width + (edge.west ? -dx : dx)));
        if (edge.north || edge.south)
            height = Math.max(minHeight, Math.min(maxHeight, height + (edge.north ? -dy : dy)));
        return new Bounds(edge.west ? initial.x() + initial.width() - width : initial.x(),
                edge.north ? initial.y() + initial.height() - height : initial.y(), width, height);
    }

    private boolean enabled() {
        return stage.isResizable() && !stage.isMaximized() && !stage.isFullScreen();
    }

    private boolean outside(double x, double y) {
        return x < 0 || y < 0 || x >= scene.getWidth() || y >= scene.getHeight();
    }

    private void moved(MouseEvent event) {
        Edge edge = enabled() ? edgeAt(event.getSceneX(), event.getSceneY(),
                scene.getWidth(), scene.getHeight()) : Edge.NONE;
        showCursor(edge, event.getTarget() instanceof Node node ? node : null);
    }

    private void pressed(MouseEvent event) {
        suppressClick = false;
        if (!enabled() || event.getButton() != MouseButton.PRIMARY) return;
        dragging = edgeAt(event.getSceneX(), event.getSceneY(), scene.getWidth(), scene.getHeight());
        if (dragging == Edge.NONE) return;
        initial = new Bounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        screenX = event.getScreenX();
        screenY = event.getScreenY();
        showCursor(dragging, event.getTarget() instanceof Node node ? node : null);
        event.consume();
    }

    private void dragged(MouseEvent event) {
        if (dragging == Edge.NONE) return;
        if (!enabled()) { cancel(); return; }
        Bounds bounds = resize(initial, dragging, event.getScreenX() - screenX,
                event.getScreenY() - screenY, stage.getMinWidth(), stage.getMinHeight(),
                stage.getMaxWidth(), stage.getMaxHeight());
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
        stage.setX(bounds.x());
        stage.setY(bounds.y());
        event.consume();
    }

    private void released(MouseEvent event) {
        if (dragging == Edge.NONE) return;
        dragging = Edge.NONE;
        suppressClick = true;
        restoreCursor();
        event.consume();
    }

    private void cancel() {
        dragging = Edge.NONE;
        restoreCursor();
    }

    private void showCursor(Edge edge, Node node) {
        if (edge == Edge.NONE) { restoreCursor(); return; }
        if (!cursorActive) {
            previousSceneCursor = scene.getCursor();
            cursorActive = true;
        }
        if (cursorNode != node) {
            if (cursorNode != null) cursorNode.setCursor(previousNodeCursor);
            cursorNode = node;
            previousNodeCursor = node == null ? null : node.getCursor();
        }
        scene.setCursor(edge.cursor);
        if (cursorNode != null) cursorNode.setCursor(edge.cursor);
    }

    private void restoreCursor() {
        if (!cursorActive) return;
        scene.setCursor(previousSceneCursor);
        if (cursorNode != null) cursorNode.setCursor(previousNodeCursor);
        cursorNode = null;
        cursorActive = false;
    }
}
