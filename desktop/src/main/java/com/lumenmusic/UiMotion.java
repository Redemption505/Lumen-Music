package com.lumenmusic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/** Interruptible visual entrance effects, confined to the JavaFX thread. */
public final class UiMotion {
    private static final Duration ENTRANCE_DURATION = Duration.millis(220);
    private static final Map<Node, ParallelTransition> ACTIVE = new HashMap<>();

    static {
        MotionPreferences.reducedProperty().addListener((observable, before, reduced) -> {
            if (reduced) cancelAll();
        });
    }

    private UiMotion() { }

    public static void entrance(Node node, double offsetX, double offsetY) {
        Objects.requireNonNull(node);
        clear(node);
        if (MotionPreferences.reduced()) return;
        node.setOpacity(0);
        node.setTranslateX(offsetX);
        node.setTranslateY(offsetY);
        FadeTransition fade = new FadeTransition(ENTRANCE_DURATION, node);
        fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(ENTRANCE_DURATION, node);
        move.setToX(0);
        move.setToY(0);
        ParallelTransition transition = new ParallelTransition(fade, move);
        ACTIVE.put(node, transition);
        transition.setOnFinished(event -> {
            if (ACTIVE.get(node) == transition) ACTIVE.remove(node);
            normalize(node);
        });
        transition.play();
    }

    public static void clear(Node node) {
        Objects.requireNonNull(node);
        ParallelTransition transition = ACTIVE.remove(node);
        if (transition != null) transition.stop();
        normalize(node);
    }

    public static void cancelAll() {
        for (Map.Entry<Node, ParallelTransition> entry : ACTIVE.entrySet()) {
            entry.getValue().stop();
            normalize(entry.getKey());
        }
        ACTIVE.clear();
    }

    private static void normalize(Node node) {
        node.setOpacity(1);
        node.setTranslateX(0);
        node.setTranslateY(0);
    }
}
