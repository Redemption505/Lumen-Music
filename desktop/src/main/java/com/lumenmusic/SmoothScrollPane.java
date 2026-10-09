package com.lumenmusic;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;

/** Cumulative, pixel-based wheel smoothing that leaves native gestures unchanged. */
final class SmoothScrollPane {
    private final ScrollPane pane;
    private final AnimationTimer timer;
    private final ChangeListener<Boolean> reducedMotionListener;
    private double remaining;
    private long lastFrame;
    private boolean running;
    private boolean advancing;

    private SmoothScrollPane(ScrollPane pane) {
        this.pane = pane;
        timer = new AnimationTimer() {
            @Override public void handle(long now) { advance(now); }
        };
        pane.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        pane.addEventFilter(ScrollEvent.SCROLL_STARTED, event -> stop());
        pane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> stop());
        pane.addEventFilter(TouchEvent.TOUCH_PRESSED, event -> stop());
        pane.addEventFilter(KeyEvent.KEY_PRESSED, event -> stop());
        pane.vvalueProperty().addListener((ignored, before, after) -> { if (!advancing) stop(); });
        pane.contentProperty().addListener((ignored, before, after) -> stop());
        pane.sceneProperty().addListener((ignored, before, after) -> { if (after == null) stop(); });
        reducedMotionListener = (ignored, before, reduced) -> { if (reduced) stop(); };
        MotionPreferences.reducedProperty().addListener(new WeakChangeListener<>(reducedMotionListener));
    }

    static SmoothScrollPane install(ScrollPane pane) {
        Object existing = pane.getProperties().get(SmoothScrollPane.class);
        if (existing instanceof SmoothScrollPane helper) return helper;
        SmoothScrollPane helper = new SmoothScrollPane(pane);
        pane.getProperties().put(SmoothScrollPane.class, helper);
        return helper;
    }

    private void onScroll(ScrollEvent event) {
        if (!SmoothScrolling.ownsScroll(pane, event.getTarget())) { stop(); return; }
        if (MotionPreferences.reduced() || !SmoothScrolling.discreteWheel(event)) { stop(); return; }
        double overflow = overflow();
        double range = pane.getVmax() - pane.getVmin();
        if (overflow <= 0 || range <= 0) { stop(); return; }
        double pixels = -event.getTextDeltaY() * 34;
        if (!Double.isFinite(pixels) || pixels == 0) return;
        if ((pixels < 0 && pane.getVvalue() <= pane.getVmin())
                || (pixels > 0 && pane.getVvalue() >= pane.getVmax())) { stop(); return; }
        if (remaining * pixels < 0) remaining = 0;
        double position = (pane.getVvalue() - pane.getVmin()) / range * overflow;
        double limit = Math.max(100, pane.getViewportBounds().getHeight() * 3);
        remaining = Math.max(-Math.min(position, limit), Math.min(overflow - position, Math.min(limit, remaining + pixels)));
        if (!running) { running = true; lastFrame = 0; timer.start(); }
        event.consume();
    }

    private double overflow() {
        return pane.getContent() == null ? 0 : pane.getContent().getLayoutBounds().getHeight()
                - pane.getViewportBounds().getHeight();
    }

    private void advance(long now) {
        double overflow = overflow();
        double range = pane.getVmax() - pane.getVmin();
        if (MotionPreferences.reduced() || pane.getScene() == null || overflow <= 0 || range <= 0) { stop(); return; }
        double seconds = lastFrame == 0 ? 1.0 / 60 : Math.min((now - lastFrame) / 1_000_000_000.0, 0.05);
        lastFrame = now;
        double step = remaining * (1 - Math.exp(-seconds / 0.075));
        if (Math.abs(step) < 0.75) step = Math.copySign(Math.min(0.75, Math.abs(remaining)), remaining);
        double before = pane.getVvalue();
        advancing = true;
        try { pane.setVvalue(Math.max(pane.getVmin(), Math.min(pane.getVmax(), before + step / overflow * range))); }
        finally { advancing = false; }
        double moved = (pane.getVvalue() - before) / range * overflow;
        remaining -= moved;
        if (Math.abs(moved) < 0.01 || Math.abs(remaining) < 0.5) stop();
    }

    private void stop() { timer.stop(); running = false; remaining = 0; lastFrame = 0; }
    boolean isRunning() { return running; }
}
