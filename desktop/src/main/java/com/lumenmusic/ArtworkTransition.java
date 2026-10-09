package com.lumenmusic;

import java.util.Objects;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** A single temporary artwork shared by scene changes; it never owns playback. */
final class ArtworkTransition implements AutoCloseable {
    record Frame(Image image, Rectangle2D viewport, Bounds bounds) { }
    private final StackPane layer;
    private final ImageView image = new ImageView();
    private Timeline animation;
    private ImageView destination;

    ArtworkTransition(StackPane layer) {
        this.layer = Objects.requireNonNull(layer);
        image.setId("artwork-transition-image");
        image.setManaged(false);
        image.setMouseTransparent(true);
        image.setSmooth(true);
    }

    Frame capture(ImageView view) {
        if (view.getImage() == null || view.getScene() == null) return null;
        Bounds bounds = layer.sceneToLocal(view.localToScene(view.getBoundsInLocal()));
        return new Frame(view.getImage(), viewport(view), bounds);
    }

    void play(Frame from, ImageView to) {
        cancel();
        if (MotionPreferences.reduced() || from == null || to.getScene() == null
                || from.image() != to.getImage() || from.bounds().getWidth() <= 0) return;
        Frame target = capture(to);
        if (target == null || target.bounds().getWidth() <= 0) return;
        destination = to;
        to.setOpacity(0);
        image.setImage(from.image());
        image.setViewport(from.viewport());
        image.setLayoutX(from.bounds().getMinX());
        image.setLayoutY(from.bounds().getMinY());
        image.setFitWidth(from.bounds().getWidth());
        image.setFitHeight(from.bounds().getHeight());
        layer.getChildren().add(image);
        DoubleProperty amount = new SimpleDoubleProperty();
        amount.addListener((ignored, before, after) -> {
            double t = after.doubleValue();
            Rectangle2D a = from.viewport(), b = target.viewport();
            image.setViewport(new Rectangle2D(mix(a.getMinX(), b.getMinX(), t),
                    mix(a.getMinY(), b.getMinY(), t), mix(a.getWidth(), b.getWidth(), t),
                    mix(a.getHeight(), b.getHeight(), t)));
        });
        animation = new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(image.layoutXProperty(), target.bounds().getMinX(), Interpolator.EASE_BOTH),
                new KeyValue(image.layoutYProperty(), target.bounds().getMinY(), Interpolator.EASE_BOTH),
                new KeyValue(image.fitWidthProperty(), target.bounds().getWidth(), Interpolator.EASE_BOTH),
                new KeyValue(image.fitHeightProperty(), target.bounds().getHeight(), Interpolator.EASE_BOTH),
                new KeyValue(amount, 1, Interpolator.EASE_BOTH)));
        animation.setOnFinished(event -> cancel());
        animation.play();
    }

    boolean active() { return animation != null; }

    void cancel() {
        if (animation != null) { animation.stop(); animation = null; }
        if (destination != null) { destination.setOpacity(1); destination = null; }
        layer.getChildren().remove(image);
        image.setImage(null);
    }

    private static Rectangle2D viewport(ImageView view) {
        Image source = view.getImage();
        return view.getViewport() != null ? view.getViewport()
                : new Rectangle2D(0, 0, source.getWidth(), source.getHeight());
    }

    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
    @Override public void close() { cancel(); }
}
