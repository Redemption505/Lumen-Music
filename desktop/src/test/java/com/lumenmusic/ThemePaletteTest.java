package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

final class ThemePaletteTest {
    @Test void everyAccentHasReadableButtonAndTextRoles() {
        Stream.concat(Stream.of(ThemeController.Accent.values()).map(accent -> Color.web(accent.hex())),
                Stream.of(Color.web("#f2b85b"))).forEach(accent -> {
            Color foreground = ThemeController.accentForeground(accent);
            assertTrue(ThemeController.contrast(accent, foreground) >= 4.5, "button " + accent);
            assertTrue(ThemeController.contrast(ThemeController.accentHover(accent), foreground) >= 4.5,
                    "hover " + accent);
            for (boolean light : new boolean[] {false, true}) {
                Color text = ThemeController.accentText(accent, light);
                String[] surfaces = light
                        ? new String[] {"#fffdf7", "#eeeae1", "#dcd8d0", "#e8ddce"}
                        : new String[] {"#25292c", "#414a4d", "#464641", "#4b4435"};
                for (String surface : surfaces) {
                    assertTrue(ThemeController.contrast(text, Color.web(surface)) >= 4.5,
                            "accent text " + accent + " on " + surface);
                }
            }
        });
    }

    @Test void extremeArtworkOnlyProducesSoftReadableBackgrounds() {
        for (Color cover : new Color[] {Color.WHITE, Color.BLACK, Color.RED, Color.LIME,
                Color.BLUE, Color.MAGENTA, Color.YELLOW}) {
            assertTrue(ThemeController.coverGlow(cover).getSaturation() <= .201);
            for (Color background : ThemeController.dynamicBackgroundColors(cover)) {
                assertTrue(ThemeController.contrast(Color.web("#bfc5c4"), background) >= 4.5,
                        "muted text over cover " + cover);
            }
        }
    }
}
