package com.lumenmusic;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Interactive Windows integration probe: real thumbnail registration and Win32 click dispatch. */
public final class WindowsTaskbarSmokeTest extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        AtomicInteger previous = new AtomicInteger();
        AtomicInteger toggle = new AtomicInteger();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger favorite = new AtomicInteger();
        stage.setTitle("Lumen Taskbar Probe");
        stage.setScene(new Scene(new Label("Taskbar thumbnail toolbar probe"), 360, 180));
        WindowsTaskbarControls controls = WindowsTaskbarControls.attach(stage,
                previous::incrementAndGet, toggle::incrementAndGet, next::incrementAndGet,
                favorite::incrementAndGet);
        stage.show();
        PauseTransition registration = new PauseTransition(Duration.seconds(5));
        registration.setOnFinished(ignored -> {
            if (controls == null || !controls.isInstalled()) fail("Thumbnail buttons were not registered: "
                    + (controls == null ? "non-Windows" : controls.lastError()), controls, stage);
            HWND hwnd = User32.INSTANCE.FindWindow(null, stage.getTitle());
            if (hwnd == null) fail("Stage HWND missing", controls, stage);
            for (int id : new int[] {41, 42, 43, 44}) {
                User32.INSTANCE.SendMessage(hwnd, 0x0111, new WPARAM(((long) 0x1800 << 16) | id), new LPARAM(0));
            }
            controls.setPlaying(true);
            controls.setFavorite(true);
            controls.setFavorite(false);
            if (!controls.isInstalled()) fail("Thumbnail state update failed: " + controls.lastError(), controls, stage);
            PauseTransition dispatch = new PauseTransition(Duration.millis(300));
            dispatch.setOnFinished(event -> {
                if (previous.get() != 1 || toggle.get() != 1 || next.get() != 1 || favorite.get() != 1)
                    fail("Thumbnail commands did not route to callbacks", controls, stage);
                stage.hide();
                stage.show();
                PauseTransition reopen = new PauseTransition(Duration.seconds(1));
                reopen.setOnFinished(done -> {
                    if (!controls.isInstalled()) fail("Thumbnail toolbar lost after window reopen: "
                            + controls.lastError(), controls, stage);
                    System.out.println("TASKBAR_THUMBNAIL_OK registered=true previous=1 toggle=1 next=1 favorite=1 reopen=true");
                    controls.close();
                    stage.close();
                    Platform.exit();
                });
                reopen.play();
            });
            dispatch.play();
        });
        registration.play();
    }

    private static void fail(String message, WindowsTaskbarControls controls, Stage stage) {
        System.err.println("TASKBAR_THUMBNAIL_FAIL " + message);
        if (controls != null) controls.close();
        stage.close();
        Platform.exit();
        throw new IllegalStateException(message);
    }
}
