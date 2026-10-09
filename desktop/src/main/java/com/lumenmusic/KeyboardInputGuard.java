package com.lumenmusic;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;

/** Prevents global playback shortcuts from taking over an active text editor. */
public final class KeyboardInputGuard {
    private KeyboardInputGuard() { }

    public static boolean isTextEditing(Node focusOwner) {
        for (Node node = focusOwner; node != null; node = node.getParent()) {
            if (node instanceof TextInputControl) return true;
            if (node instanceof ComboBox<?> comboBox && comboBox.isEditable()) return true;
        }
        return false;
    }

    public static boolean allowsPlaybackShortcut(KeyEvent event, Node focusOwner, boolean recordingHotkey) {
        return event != null && !recordingHotkey && !isTextEditing(focusOwner)
                && !event.isControlDown() && !event.isAltDown()
                && !event.isMetaDown() && !event.isShiftDown();
    }
}
