package com.lumenmusic;

import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import java.util.function.DoubleSupplier;

/** A clipped lyric whose horizontal position follows the media clock, not a separate animation. */
final class LyricMarquee extends Region {
    private final Text text = new Text();
    private final Rectangle clip = new Rectangle();
    private final DoubleSupplier progress;
    private double target;
    private double lastProgress = Double.NaN;
    private long lastFrame;
    private final AnimationTimer motion = new AnimationTimer() {
        @Override public void handle(long now) {
            if (getScene() == null || !isVisible() || MotionPreferences.reduced()) {
                finishMotion();
                return;
            }
            double seconds = lastFrame == 0 ? 1.0 / 60 : Math.min(.05, (now - lastFrame) / 1_000_000_000.0);
            lastFrame = now;
            double current = -text.getTranslateX();
            double next = current + (target - current) * (1 - Math.exp(-seconds / .045));
            text.setTranslateX(-next);
            if (Math.abs(target - next) < .1) finishMotion();
        }
    };
    private final ChangeListener<Boolean> reducedListener = (ignored, before, reduced) -> {
        if (reduced) finishMotion();
    };

    LyricMarquee(Labeled style, DoubleSupplier progress) {
        this.progress = progress;
        getStyleClass().add("lyric-marquee");
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setMouseTransparent(true);
        text.fontProperty().bind(style.fontProperty());
        text.fillProperty().bind(style.textFillProperty());
        getChildren().add(text);
        setClip(clip);
        MotionPreferences.reducedProperty().addListener(new WeakChangeListener<>(reducedListener));
        sceneProperty().addListener((ignored, before, after) -> { if (after == null) finishMotion(); });
    }

    void setText(String value) {
        String next = value == null ? "" : value;
        if (next.equals(text.getText())) return;
        motion.stop();
        lastFrame = 0;
        lastProgress = Double.NaN;
        target = 0;
        text.setTranslateX(0);
        text.setText(next);
        requestLayout();
    }

    void updatePosition() {
        double overflow = Math.max(0, text.getLayoutBounds().getWidth() - getWidth());
        double sampled = progress.getAsDouble();
        target = offset(overflow, sampled);
        // Media clocks arrive in discrete steps. Interpolate their normal forward movement,
        // while a new line, a seek or reduced motion takes its exact position immediately.
        if (getScene() == null || overflow == 0 || MotionPreferences.reduced()
                || !Double.isFinite(lastProgress) || !Double.isFinite(sampled)
                || sampled < lastProgress || sampled - lastProgress > .2) finishMotion();
        else if (Math.abs(target + text.getTranslateX()) > .1) motion.start();
        lastProgress = sampled;
    }

    private void finishMotion() {
        motion.stop();
        lastFrame = 0;
        text.setTranslateX(-target);
    }

    static double offset(double overflow, double progress) {
        if (!Double.isFinite(progress) || overflow <= 0) return 0;
        // Brief holds make both ends readable; seek and pause use the same position as playback.
        double fraction = Math.max(0, Math.min(1, (progress - .08) / .84));
        return overflow * fraction;
    }

    @Override protected double computePrefHeight(double width) { return text.getLayoutBounds().getHeight(); }
    @Override protected double computePrefWidth(double height) { return text.getLayoutBounds().getWidth(); }
    @Override protected void layoutChildren() {
        clip.setWidth(getWidth()); clip.setHeight(getHeight());
        text.relocate(0, Math.max(0, (getHeight() - text.getLayoutBounds().getHeight()) / 2));
        updatePosition();
    }
}
