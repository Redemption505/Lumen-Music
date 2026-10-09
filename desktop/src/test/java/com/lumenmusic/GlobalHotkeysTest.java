package com.lumenmusic;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GlobalHotkeysTest {
    @TempDir Path directory;

    @Test void settingsPersistRemovedBindingsAndConvertPunctuation() throws Exception {
        var store = new HotkeySettingsStore(directory.resolve("hotkeys.properties"));
        assertEquals(GlobalHotkeys.defaults(), store.load());
        var binding = new GlobalHotkeys.Combination(GlobalHotkeys.MOD_CONTROL | GlobalHotkeys.MOD_SHIFT, 186);
        store.save(Map.of(GlobalHotkeys.Action.TOGGLE, binding));
        assertEquals(Map.of(GlobalHotkeys.Action.TOGGLE, binding), store.load());
        var event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SEMICOLON, true, true, false, false);
        assertEquals(binding, GlobalHotkeys.fromKeyEvent(event).orElseThrow());
        assertEquals("Ctrl + Shift + ;", binding.displayName());
        assertEquals(13, GlobalHotkeys.fromKeyEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                false, true, false, false)).orElseThrow().virtualKey());
        assertEquals(46, GlobalHotkeys.fromKeyEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE,
                false, true, false, false)).orElseThrow().virtualKey());
        assertEquals(135, GlobalHotkeys.fromKeyEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F24,
                false, false, false, false)).orElseThrow().virtualKey());
        store.save(Map.of());
        assertTrue(store.load().isEmpty());
    }

    @Test void modifiersAloneAndUnmodifiedLetterCannotBeRegistered() {
        assertThrows(IllegalArgumentException.class, () -> new GlobalHotkeys.Combination(0, 65));
        assertThrows(IllegalArgumentException.class, () -> new GlobalHotkeys.Combination(2, 17));
        assertTrue(GlobalHotkeys.fromKeyEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.CONTROL,
                false, true, false, false)).isEmpty());
    }

    @Test void nativeRegistrationReportsConflictAndReleasesOnClose() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        var bindings = Map.of(GlobalHotkeys.Action.TOGGLE, new GlobalHotkeys.Combination(7, 135));
        GlobalHotkeys first = new GlobalHotkeys(ignored -> { }, ignored -> { }, Runnable::run);
        GlobalHotkeys second = new GlobalHotkeys(ignored -> { }, ignored -> { }, Runnable::run);
        try {
            assertTrue(first.apply(bindings).get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(second.apply(bindings).get(5, TimeUnit.SECONDS).containsKey(GlobalHotkeys.Action.TOGGLE));
            first.close();
            first.termination().get(5, TimeUnit.SECONDS);
            assertTrue(second.apply(bindings).get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(bindings, second.installed());
            assertTrue(second.apply(Map.of()).get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(second.installed().isEmpty());
        } finally {
            first.close(); second.close();
            first.termination().get(5, TimeUnit.SECONDS);
            second.termination().get(5, TimeUnit.SECONDS);
        }
    }
}
