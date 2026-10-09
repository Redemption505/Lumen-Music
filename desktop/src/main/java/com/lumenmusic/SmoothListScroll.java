package com.lumenmusic;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeView;
import javafx.scene.control.TableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;

/** Adds cumulative wheel motion to virtualized lists, trees and tables. */
public final class SmoothListScroll {
    private static final double TIME_CONSTANT_SECONDS = 0.075;

    private final Control view;
    private final AnimationTimer timer;
    private final ChangeListener<Boolean> reducedMotionListener;
    private VirtualFlow<?> flow;
    private double remaining;
    private long lastFrame;
    private boolean running;
    private boolean centering;
    private double centerFrom;
    private double centerTarget;
    private long centerStarted;

    private SmoothListScroll(Control view) {
        this.view = view;
        timer = new AnimationTimer() {
            @Override public void handle(long now) { advance(now); }
        };
        view.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        view.addEventFilter(KeyEvent.KEY_PRESSED, event -> stop());
        view.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> stop());
        view.addEventFilter(TouchEvent.TOUCH_PRESSED, event -> stop());
        view.addEventFilter(ScrollEvent.SCROLL_STARTED, event -> stop());
        view.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) stop();
        });
        view.skinProperty().addListener((observable, oldSkin, newSkin) -> stop());
        reducedMotionListener = (observable, before, reduced) -> {
            if (reduced) stop();
        };
        MotionPreferences.reducedProperty().addListener(new WeakChangeListener<>(reducedMotionListener));
    }

    /** Idempotent on each view. Call on the JavaFX application thread. */
    public static SmoothListScroll install(ListView<?> view) {
        return installControl(view);
    }

    static SmoothListScroll install(TreeView<?> view) {
        return installControl(view);
    }

    static SmoothListScroll install(TableView<?> view) {
        return installControl(view);
    }

    private static SmoothListScroll installControl(Control view) {
        Object existing = view.getProperties().get(SmoothListScroll.class);
        if (existing instanceof SmoothListScroll helper) return helper;
        SmoothListScroll helper = new SmoothListScroll(view);
        view.getProperties().put(SmoothListScroll.class, helper);
        return helper;
    }

    private void onScroll(ScrollEvent event) {
        if (!SmoothScrolling.ownsScroll(view, event.getTarget())) { stop(); return; }
        if (centering) stop();
        if (MotionPreferences.reduced()) {
            stop();
            return;
        }
        if (!SmoothScrolling.discreteWheel(event)) {
            stop();
            return;
        }

        javafx.scene.Node found = view.lookup(".virtual-flow");
        if (!(found instanceof VirtualFlow<?> virtualFlow) || !virtualFlow.isVertical()) return;
        flow = virtualFlow;
        IndexedCell<?> cell = virtualFlow.getFirstVisibleCell();
        double lineHeight = cell == null ? 40 : cell.getHeight();
        if (lineHeight <= 0) return;
        lineHeight = Math.min(lineHeight, virtualFlow.getHeight() / 4);
        double pixels = -event.getTextDeltaY() * lineHeight;
        if (!Double.isFinite(pixels) || pixels == 0) return;
        if ((pixels < 0 && virtualFlow.getPosition() <= 0 && remaining <= 0)
                || (pixels > 0 && virtualFlow.getPosition() >= 1 && remaining >= 0)) {
            stop();
            return;
        }

        if (remaining * pixels < 0) remaining = 0;
        double limit = Math.max(100, virtualFlow.getHeight() * 3);
        remaining = Math.max(-limit, Math.min(limit, remaining + pixels));
        if (Math.abs(remaining) < 0.5) stop();
        else if (!running) {
            lastFrame = 0;
            running = true;
            timer.start();
        }
        event.consume();
    }

    private void advance(long now) {
        if (MotionPreferences.reduced() || view.getScene() == null || flow == null) {
            stop();
            return;
        }
        if (centering) {
            if (centerStarted == 0) centerStarted = now;
            double progress = Math.min(1, (now - centerStarted) / 400_000_000.0);
            double eased = 1 - Math.pow(1 - progress, 3);
            flow.setPosition(centerFrom + (centerTarget - centerFrom) * eased);
            if (progress >= 1) stop();
            return;
        }
        double seconds = lastFrame == 0 ? 1.0 / 60 : Math.min((now - lastFrame) / 1_000_000_000.0, 0.05);
        lastFrame = now;
        double step = remaining * (1 - Math.exp(-seconds / TIME_CONSTANT_SECONDS));
        if (Math.abs(step) < 0.75) step = Math.copySign(Math.min(0.75, Math.abs(remaining)), remaining);
        if (Math.abs(remaining) < 0.75) step = remaining;
        double moved = flow.scrollPixels(step);
        if (Math.abs(moved) < 0.01) {
            stop();
            return;
        }
        remaining -= moved;
        if (Math.abs(remaining) < 0.5) stop();
    }

    private void stop() {
        timer.stop();
        running = false;
        remaining = 0;
        lastFrame = 0;
        flow = null;
        centering = false;
        centerStarted = 0;
    }

    /** Centers a fixed-height lyric row; wheel and direct input interrupt the same animation. */
    public void centerOn(int index, boolean animated) {
        stop();
        if (!(view instanceof ListView<?> list) || index < 0 || index >= list.getItems().size()) return;
        javafx.scene.Node found = view.lookup(".virtual-flow");
        if (!(found instanceof VirtualFlow<?> virtualFlow) || !virtualFlow.isVertical()
                || list.getFixedCellSize() <= 0 || virtualFlow.getHeight() <= 0) {
            list.scrollTo(index);
            return;
        }
        double cellHeight = list.getFixedCellSize();
        double viewportHeight = virtualFlow.getHeight();
        double overflow = list.getItems().size() * cellHeight - viewportHeight;
        double target = overflow <= 0 ? 0 : Math.max(0, Math.min(1,
                (index * cellHeight - (viewportHeight - cellHeight) / 2) / overflow));
        if (!animated || MotionPreferences.reduced() || view.getScene() == null
                || Math.abs(target - virtualFlow.getPosition()) < 0.00001) {
            virtualFlow.setPosition(target);
            return;
        }
        flow = virtualFlow;
        centerFrom = flow.getPosition();
        centerTarget = target;
        centering = true;
        running = true;
        timer.start();
    }

    boolean isRunning() { return running; }
}
