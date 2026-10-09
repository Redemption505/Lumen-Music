package com.lumenmusic;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/** Owns the visual modes for one window. All methods are called on the JavaFX thread. */
public final class ThemeController implements AutoCloseable {
    public enum Mode { DARK, LIGHT, BACKGROUND, DYNAMIC, LUMEN }

    public enum Accent {
        BLUE("海蓝", "#517ae0"), PURPLE("星紫", "#8b69d6"), PINK("樱粉", "#d56a9a"),
        ORANGE("暖橙", "#d17a42"), GREEN("青绿", "#399a84"), GOLD("琥金", "#b58a38");

        private final String label;
        private final String hex;

        Accent(String label, String hex) { this.label = label; this.hex = hex; }
        public String label() { return label; }
        public String hex() { return hex; }
        @Override public String toString() { return label; }
    }

    private final Scene scene;
    private final String originalStyle;
    private final Timeline dynamicMotion;
    private final Timeline coverMotion;
    private final ChangeListener<Boolean> reducedMotionListener;
    private Mode mode = Mode.DARK;
    private Accent accent = Accent.PINK;
    private URI backgroundUri;
    private Image coverImage;
    private Color coverColor;
    private Color displayedCoverColor;
    private int gradientFrame;
    private ChangeListener<Number> imageProgressListener;

    public ThemeController(Scene scene) {
        this.scene = Objects.requireNonNull(scene);
        originalStyle = scene.getRoot().getStyle();
        dynamicMotion = new Timeline(new KeyFrame(Duration.millis(250), event -> {
            gradientFrame = (gradientFrame + 1) % 240;
            if (mode == Mode.DYNAMIC) apply();
        }));
        dynamicMotion.setCycleCount(Timeline.INDEFINITE);
        coverMotion = new Timeline();
        reducedMotionListener = (observable, before, reduced) -> {
            if (reduced) {
                dynamicMotion.stop();
                coverMotion.stop();
                displayedCoverColor = coverColor;
            } else if (mode == Mode.DYNAMIC) dynamicMotion.play();
            apply();
        };
        MotionPreferences.reducedProperty().addListener(reducedMotionListener);
        apply();
    }

    public Mode mode() { return mode; }
    public Accent accent() { return accent; }
    public URI backgroundUri() { return backgroundUri; }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode);
        coverMotion.stop();
        displayedCoverColor = coverColor;
        if (mode == Mode.DYNAMIC && !MotionPreferences.reduced()) dynamicMotion.play();
        else dynamicMotion.stop();
        apply();
    }

    public void setAccent(Accent accent) {
        this.accent = Objects.requireNonNull(accent);
        apply();
    }

    public void setBackgroundUri(URI uri) {
        if (uri != null && !"file".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("背景图必须是本地文件");
        backgroundUri = uri;
        if (mode == Mode.BACKGROUND) apply();
    }

    /** Updates the palette after the current track's cover has finished loading. */
    public void setCoverImage(Image image) {
        if (coverImage != null && imageProgressListener != null)
            coverImage.progressProperty().removeListener(imageProgressListener);
        coverImage = image;
        imageProgressListener = null;
        if (image != null && image.getProgress() < 1) {
            imageProgressListener = (observable, oldValue, newValue) -> {
                if (newValue.doubleValue() >= 1) {
                    updateCoverColor(sampleCoverColor(image));
                }
            };
            image.progressProperty().addListener(imageProgressListener);
        } else updateCoverColor(sampleCoverColor(image));
    }

    private void updateCoverColor(Color color) {
        coverColor = color;
        if (mode != Mode.DYNAMIC && mode != Mode.LUMEN) {
            displayedCoverColor = color;
            return;
        }
        Color target = color == null ? Color.web(accent.hex()) : color;
        Color start = displayedCoverColor == null ? Color.web(accent.hex()) : displayedCoverColor;
        coverMotion.stop();
        if (MotionPreferences.reduced() || start.equals(target)) {
            displayedCoverColor = target;
            apply();
            return;
        }
        long started = System.nanoTime();
        coverMotion.getKeyFrames().setAll(new KeyFrame(Duration.ZERO),
                new KeyFrame(Duration.millis(25), event -> {
                    double fraction = Math.min(1, (System.nanoTime() - started) / 240_000_000.0);
                    displayedCoverColor = start.interpolate(target, fraction * fraction * (3 - 2 * fraction));
                    apply();
                }));
        coverMotion.setCycleCount(10);
        coverMotion.setOnFinished(event -> {
            displayedCoverColor = target;
            apply();
        });
        coverMotion.playFromStart();
    }

    private void apply() {
        var root = scene.getRoot();
        root.getStyleClass().removeAll("dark-theme", "light-theme", "background-theme", "dynamic-theme", "lumen-theme");
        root.getStyleClass().add(switch (mode) {
            case DARK -> "dark-theme";
            case LIGHT -> "light-theme";
            case BACKGROUND -> "background-theme";
            case DYNAMIC -> "dynamic-theme";
            case LUMEN -> "lumen-theme";
        });
        Color selected = effectiveAccent();
        Color seed = displayedCoverColor == null ? selected : displayedCoverColor;
        String style = paletteStyle();
        if (mode == Mode.BACKGROUND && backgroundUri != null) {
            String cssUri = backgroundUri.toASCIIString().replace("'", "%27");
            style += " -fx-background-image: url('" + cssUri + "');"
                    + " -fx-background-size: cover; -fx-background-position: center center;";
        } else if (mode == Mode.DYNAMIC) {
            Color[] colors = dynamicBackgroundColors(seed);
            double angle = gradientFrame * Math.PI / 120;
            double fromX = 50 + 48 * Math.cos(angle), fromY = 50 + 48 * Math.sin(angle);
            String direction = String.format(Locale.ROOT,
                    "from %.1f%% %.1f%% to %.1f%% %.1f%%",
                    fromX, fromY, 100 - fromX, 100 - fromY);
            style += " -fx-background-color: linear-gradient(" + direction + ", "
                    + hex(colors[0]) + " 0%, " + hex(colors[1]) + " 52%, " + hex(colors[2]) + " 100%);";
        } else if (mode == Mode.LUMEN) {
            Color glow = coverGlow(seed).interpolate(Color.web("#29251f"), .55);
            style += " -fx-background-color: linear-gradient(to bottom right, "
                    + hex(glow.interpolate(Color.web("#1d1e20"), .70))
                    + " 0%, #1d1e20 55%, #191c1e 100%);";
        }
        root.setStyle(originalStyle + " " + style);
    }

    /** Controller-owned color roles for popups; no scene background or user style. */
    String paletteStyle() {
        Color selected = effectiveAccent();
        Color readableAccent = accentText(selected, mode == Mode.LIGHT);
        Color seed = displayedCoverColor == null ? selected : displayedCoverColor;
        return "-lumen-accent: " + hex(selected)
                + "; -lumen-accent-hover: " + hex(accentHover(selected))
                + "; -lumen-accent-text: " + hex(readableAccent)
                + "; -lumen-on-accent: " + hex(accentForeground(selected))
                + "; -lumen-focus: " + hex(readableAccent)
                + "; -lumen-glow: " + hex(coverGlow(seed)) + ";";
    }

    private Color effectiveAccent() {
        return Color.web(mode == Mode.LUMEN ? "#f2b85b" : accent.hex());
    }

    /** Button foreground and hover are paired, so a hover never loses contrast. */
    static Color accentForeground(Color accent) {
        Color ink = Color.web("#0e1215"), paper = Color.web("#fffaf2");
        return contrast(accent, ink) >= contrast(accent, paper) ? ink : paper;
    }

    static Color accentHover(Color accent) {
        return accent.interpolate(accentForeground(accent).getBrightness() < .5
                ? Color.WHITE : Color.BLACK, .12);
    }

    /** Accent text is separate from decorative color, across the full surface range. */
    static Color accentText(Color accent, boolean light) {
        Color surface = Color.web(light ? "#dcd8d0" : "#484a46");
        Color target = Color.web(light ? "#202426" : "#fffaf2");
        for (int step = 0; step <= 100; step++) {
            Color candidate = accent.interpolate(target, step / 100.0);
            // Leave a small margin for CSS's 8-bit rounding.
            if (contrast(candidate, surface) >= 4.6) return candidate;
        }
        return target;
    }

    /** Artwork supplies hue, never the brightness or saturation of a reading surface. */
    static Color coverGlow(Color seed) {
        return Color.hsb(seed.getHue(), Math.min(.20, seed.getSaturation()), .64);
    }

    static Color[] dynamicBackgroundColors(Color seed) {
        Color glow = coverGlow(seed), ink = Color.web("#191c1e");
        return new Color[] {glow.interpolate(ink, .86), glow.interpolate(ink, .76),
                glow.interpolate(ink, .94)};
    }

    static double contrast(Color first, Color second) {
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

    static Color sampleCoverColor(Image image) {
        if (image == null || image.isError() || image.getWidth() < 1 || image.getHeight() < 1)
            return null;
        PixelReader reader = image.getPixelReader();
        if (reader == null) return null;
        double red = 0, green = 0, blue = 0, total = 0;
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        for (int y = height / 8; y < height * 7 / 8; y += Math.max(1, height / 20)) {
            for (int x = width / 8; x < width * 7 / 8; x += Math.max(1, width / 20)) {
                Color color = reader.getColor(x, y);
                if (color.getOpacity() < .5) continue;
                double weight = .2 + color.getSaturation();
                red += color.getRed() * weight;
                green += color.getGreen() * weight;
                blue += color.getBlue() * weight;
                total += weight;
            }
        }
        return total == 0 ? null : new Color(red / total, green / total, blue / total, 1);
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255), (int) Math.round(color.getBlue() * 255));
    }

    @Override public void close() {
        dynamicMotion.stop();
        coverMotion.stop();
        MotionPreferences.reducedProperty().removeListener(reducedMotionListener);
        if (coverImage != null && imageProgressListener != null)
            coverImage.progressProperty().removeListener(imageProgressListener);
        scene.getRoot().setStyle(originalStyle);
    }

    public void dispose() { close(); }
}
