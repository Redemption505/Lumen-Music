package com.lumenmusic;

import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.util.Duration;

/** Keeps layout stable while auxiliary immersive controls fade during listening. */
final class IdlePlaybackControls implements AutoCloseable {
    private final List<Node> controls;
    private final BooleanSupplier playing;
    private final BooleanSupplier editing;
    private final PauseTransition idle = new PauseTransition(Duration.seconds(5));
    private final ChangeListener<Boolean> motionListener = (ignored, before, after) -> activity();
    private Timeline fade;
    private boolean active;
    private boolean closed;

    IdlePlaybackControls(BooleanSupplier playing, BooleanSupplier editing, Node... controls) {
        this.controls = List.of(controls);
        this.playing = playing;
        this.editing = editing;
        idle.setOnFinished(event -> hide());
        MotionPreferences.reducedProperty().addListener(motionListener);
    }

    void setActive(boolean active) { this.active = active; activity(); }

    void activity() {
        idle.stop();
        if (fade != null) { fade.stop(); fade = null; }
        for (Node control : controls) {
            control.setOpacity(1);
            control.setMouseTransparent(false);
        }
        if (!closed && active && playing.getAsBoolean() && !MotionPreferences.reduced()) idle.playFromStart();
    }

    private void hide() {
        if (closed || !active || !playing.getAsBoolean() || MotionPreferences.reduced()
                || editing.getAsBoolean() || controls.stream().anyMatch(Node::isHover)) return;
        fade = new Timeline(new KeyFrame(Duration.millis(280), controls.stream()
                .map(control -> new KeyValue(control.opacityProperty(), 0, Interpolator.EASE_BOTH))
                .toArray(KeyValue[]::new)));
        fade.setOnFinished(event -> {
            for (Node control : controls) control.setMouseTransparent(true);
            fade = null;
        });
        fade.play();
    }

    boolean hidden() { return controls.get(0).getOpacity() < .01; }

    @Override public void close() {
        closed = true;
        activity();
        MotionPreferences.reducedProperty().removeListener(motionListener);
    }
}
