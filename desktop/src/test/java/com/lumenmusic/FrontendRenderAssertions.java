package com.lumenmusic;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Slider;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

/** Pixel and geometry assertions for offscreen frontend rendering checks. */
final class FrontendRenderAssertions {
    private static final double EDGE_TOLERANCE = 1.0;
    private static final double CORE_COVERAGE = .72;
    private static final double MIN_PIXEL_DELTA = 18;

    private FrontendRenderAssertions() { }

    /** Checks the styled track's real layout and scene bounds against its owning slider. */
    static Bounds sliderTrack(Slider slider) {
        require(slider != null, "SLIDER_MISSING");
        Scene scene = slider.getScene();
        require(scene != null, "SLIDER_HAS_NO_SCENE");
        layout(scene);
        Node node = slider.lookup(".track");
        require(node instanceof Region, "SLIDER_TRACK_NOT_REGION " + node);
        Region track = (Region) node;
        Bounds sliderBounds = slider.localToScene(slider.getLayoutBounds());
        Bounds trackBounds = track.localToScene(track.getLayoutBounds());
        Bounds trackLayout = track.getLayoutBounds();
        Bounds sliderLayout = slider.getLayoutBounds();
        require(trackLayout.getWidth() > 0 && trackLayout.getHeight() > 0,
                "SLIDER_TRACK_EMPTY layout=" + trackLayout);
        require(trackBounds.getMinX() >= sliderBounds.getMinX() - EDGE_TOLERANCE
                        && trackBounds.getMaxX() <= sliderBounds.getMaxX() + EDGE_TOLERANCE
                        && trackBounds.getMinY() >= sliderBounds.getMinY() - EDGE_TOLERANCE
                        && trackBounds.getMaxY() <= sliderBounds.getMaxY() + EDGE_TOLERANCE,
                "SLIDER_TRACK_OUTSIDE_SLIDER trackScene=" + trackBounds + " sliderScene=" + sliderBounds);
        require(trackLayout.getWidth() <= sliderLayout.getWidth() + EDGE_TOLERANCE
                        && trackLayout.getHeight() <= sliderLayout.getHeight() + EDGE_TOLERANCE,
                "SLIDER_TRACK_LAYOUT_EXCEEDS_SLIDER trackLayout=" + trackLayout
                        + " sliderLayout=" + sliderLayout);
        return trackBounds;
    }

    /**
     * Measures glyph contrast from shown/hidden scene snapshots. Low coverage antialiasing
     * pixels are excluded; at least ten core glyph pixels must remain measurable.
     */
    static double lyricContrast(Node container, double minimumContrast) {
        require(container != null, "LYRIC_CONTAINER_MISSING");
        require(Double.isFinite(minimumContrast) && minimumContrast > 1,
                "LYRIC_CONTRAST_THRESHOLD_INVALID " + minimumContrast);
        Scene scene = container.getScene();
        require(scene != null, "LYRIC_CONTAINER_HAS_NO_SCENE");
        layout(scene);
        Text text = firstText(container);
        require(text != null, "LYRIC_GLYPH_MISSING");
        Bounds glyphBounds = text.localToScene(text.getBoundsInLocal());
        require(glyphBounds.getWidth() > 0 && glyphBounds.getHeight() > 0,
                "LYRIC_GLYPH_EMPTY " + glyphBounds);

        WritableImage shown = scene.snapshot(null);
        boolean wasVisible = text.isVisible();
        WritableImage hidden;
        try {
            text.setVisible(false);
            hidden = scene.snapshot(null);
        } finally {
            text.setVisible(wasVisible);
        }

        int minX = Math.max(0, (int) Math.floor(glyphBounds.getMinX()));
        int minY = Math.max(0, (int) Math.floor(glyphBounds.getMinY()));
        int maxX = Math.min((int) shown.getWidth(), (int) Math.ceil(glyphBounds.getMaxX()));
        int maxY = Math.min((int) shown.getHeight(), (int) Math.ceil(glyphBounds.getMaxY()));
        double peakDelta = 0;
        for (int y = minY; y < maxY; y++) for (int x = minX; x < maxX; x++)
            peakDelta = Math.max(peakDelta, colorDelta(shown.getPixelReader().getColor(x, y),
                    hidden.getPixelReader().getColor(x, y)));
        require(peakDelta >= MIN_PIXEL_DELTA,
                "LYRIC_GLYPH_NOT_RENDERED peakPixelDelta=" + peakDelta + " bounds=" + glyphBounds);

        double coreDelta = Math.max(MIN_PIXEL_DELTA, peakDelta * CORE_COVERAGE);
        int glyphPixels = 0;
        double maximum = 1;
        for (int y = minY; y < maxY; y++) for (int x = minX; x < maxX; x++) {
            Color foreground = shown.getPixelReader().getColor(x, y);
            Color backdrop = hidden.getPixelReader().getColor(x, y);
            if (colorDelta(foreground, backdrop) < coreDelta) continue;
            glyphPixels++;
            maximum = Math.max(maximum, contrast(foreground, backdrop));
        }
        require(glyphPixels >= 10, "LYRIC_CORE_GLYPH_PIXELS_TOO_FEW pixels=" + glyphPixels
                + " peakPixelDelta=" + peakDelta);
        require(maximum >= minimumContrast, "LYRIC_RENDERED_CONTRAST_LOW maximum=" + maximum
                + " minimum=" + minimumContrast + " glyphPixels=" + glyphPixels);
        return maximum;
    }

    private static Text firstText(Node node) {
        if (node instanceof Text text && !text.getText().isBlank()) return text;
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            Text text = firstText(child);
            if (text != null) return text;
        }
        return null;
    }

    private static void layout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static double colorDelta(Color first, Color second) {
        return 255 * Math.max(Math.max(Math.abs(first.getRed() - second.getRed()),
                        Math.abs(first.getGreen() - second.getGreen())),
                Math.abs(first.getBlue() - second.getBlue()));
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen())
                + .0722 * linear(color.getBlue());
    }

    private static double linear(double channel) {
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
