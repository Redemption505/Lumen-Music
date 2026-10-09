package com.lumenmusic;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeyboardInputGuardTest {
    @Test void playbackGuardRejectsRecordingAndModifierKeys() {
        KeyEvent plain = key(false, false, false, false);
        assertTrue(KeyboardInputGuard.allowsPlaybackShortcut(plain, null, false));
        assertFalse(KeyboardInputGuard.allowsPlaybackShortcut(plain, null, true));
        assertFalse(KeyboardInputGuard.allowsPlaybackShortcut(key(false, true, false, false), null, false));
        assertFalse(KeyboardInputGuard.allowsPlaybackShortcut(key(true, false, false, false), null, false));
        assertFalse(KeyboardInputGuard.allowsPlaybackShortcut(null, null, false));
        assertFalse(KeyboardInputGuard.isTextEditing(null));
    }

    private static KeyEvent key(boolean shift, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE,
                shift, control, alt, meta);
    }
}
