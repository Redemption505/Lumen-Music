package com.lumenmusic;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Registers and triggers a temporary shortcut without touching personal settings. */
public final class GlobalHotkeysNativeProbe {
    public static void main(String[] args) throws Exception {
        CountDownLatch triggered = new CountDownLatch(1);
        var binding = Map.of(GlobalHotkeys.Action.TOGGLE, new GlobalHotkeys.Combination(7, 121));
        GlobalHotkeys first = new GlobalHotkeys(action -> {
            if (action == GlobalHotkeys.Action.TOGGLE) triggered.countDown();
        }, message -> { throw new AssertionError(message); }, Runnable::run);
        GlobalHotkeys second = new GlobalHotkeys(action -> { }, message -> { throw new AssertionError(message); }, Runnable::run);
        try {
            var registered = first.apply(binding).get(5, TimeUnit.SECONDS);
            if (!registered.isEmpty()) throw new AssertionError("Unable to register fixture: " + registered);
            if (second.apply(binding).get(5, TimeUnit.SECONDS).isEmpty()) throw new AssertionError("Conflict not reported");
            Robot robot = new Robot();
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_ALT); robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(KeyEvent.VK_F10); robot.keyRelease(KeyEvent.VK_F10);
            robot.keyRelease(KeyEvent.VK_SHIFT); robot.keyRelease(KeyEvent.VK_ALT); robot.keyRelease(KeyEvent.VK_CONTROL);
            if (!triggered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Native WM_HOTKEY was not dispatched");
            first.close(); first.termination().get(5, TimeUnit.SECONDS);
            if (!second.apply(binding).get(5, TimeUnit.SECONDS).isEmpty()) throw new AssertionError("Shortcut not released");
            System.out.println("PASS real RegisterHotKey: system key trigger, explicit conflict, close unregister, re-registration");
        } finally {
            first.close(); second.close();
            first.termination().get(5, TimeUnit.SECONDS); second.termination().get(5, TimeUnit.SECONDS);
        }
    }
}
