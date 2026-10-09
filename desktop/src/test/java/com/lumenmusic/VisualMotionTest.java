package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class VisualMotionTest {
    @AfterEach void resetPreference() { MotionPreferences.setReduced(false); }

    @Test void reducedMotionPropertyNotifiesChanges() {
        boolean[] observed = {false};
        MotionPreferences.reducedProperty().addListener((observable, before, after) -> observed[0] = after);
        MotionPreferences.setReduced(true);
        assertTrue(MotionPreferences.reduced());
        assertTrue(observed[0]);
        MotionPreferences.setReduced(false);
        assertFalse(MotionPreferences.reduced());
    }

    @Test void coverColorSamplingIgnoresTransparentPixels() {
        WritableImage image = new WritableImage(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) image.getPixelWriter().setColor(x, y, Color.rgb(60, 140, 180));
        }
        image.getPixelWriter().setColor(5, 5, Color.TRANSPARENT);
        Color sampled = ThemeController.sampleCoverColor(image);
        assertEquals(60 / 255.0, sampled.getRed(), 0.01);
        assertEquals(140 / 255.0, sampled.getGreen(), 0.01);
        assertEquals(180 / 255.0, sampled.getBlue(), 0.01);
    }
}
