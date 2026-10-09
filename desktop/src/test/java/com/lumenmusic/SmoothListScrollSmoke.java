package com.lumenmusic;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real JavaFX viewport and pulse smoke for the wheel helper. */
public final class SmoothListScrollSmoke {
    private SmoothListScrollSmoke() { }

    public static void main(String[] args) { Application.launch(Harness.class, args); }

    public static final class Harness extends Application {
        private Stage stage;
        private ListView<Integer> view;
        private VirtualFlow<?> flow;
        private SmoothListScroll helper;

        @Override public void start(Stage stage) {
            this.stage = stage;
            try {
                view = new ListView<>();
                for (int index = 0; index < 500; index++) view.getItems().add(index);
                view.setFixedCellSize(52);
                helper = SmoothListScroll.install(view);
                check(helper == SmoothListScroll.install(view), "install is idempotent");
                stage.setScene(new Scene(view, 400, 350));
                stage.show();
                view.applyCss();
                view.layout();
                flow = (VirtualFlow<?>) view.lookup(".virtual-flow");
                check(flow != null && flow.getFirstVisibleCell() != null, "real long list laid out");
                wheel(-3);
                check(helper.isRunning(), "wheel starts animation");
                after(300, this::verifySingle);
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifySingle() {
            try {
                double single = flow.getPosition();
                check(single > 0 && single < 0.03, "one wheel step advances gradually");
                wheel(-3);
                wheel(-3);
                wheel(-3);
                after(350, () -> verifyBurst(single));
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyBurst(double single) {
            try {
                check(flow.getPosition() > single * 3.5, "rapid wheel steps accumulate");
                flow.setPosition(1);
                wheel(-3);
                after(180, this::verifyBoundary);
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyBoundary() {
            try {
                check(flow.getPosition() > 0.999, "bottom boundary holds");
                check(!helper.isRunning(), "boundary stops animation");
                flow.setPosition(0);
                wheel(3);
                check(flow.getPosition() == 0 && !helper.isRunning(), "top boundary holds");
                flow.setPosition(0.3);
                Event.fireEvent(view, new ScrollEvent(ScrollEvent.SCROLL,
                        100, 100, 100, 100, false, false, false, false, false, true,
                        0, -40, 0, 0, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        ScrollEvent.VerticalTextScrollUnits.LINES, -3, 0, null));
                check(!helper.isRunning(), "inertia stays native");
                Event.fireEvent(view, new ScrollEvent(ScrollEvent.SCROLL,
                        100, 100, 100, 100, false, false, false, false, false, false,
                        40, -40, 0, 0, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        ScrollEvent.VerticalTextScrollUnits.LINES, -3, 0, null));
                check(!helper.isRunning(), "horizontal scroll stays native");
                wheel(-3);
                Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN,
                        false, false, false, false));
                check(!helper.isRunning(), "keyboard input cancels wheel animation");
                wheel(-3);
                check(helper.isRunning(), "animation restarts after keyboard input");
                MotionPreferences.setReduced(true);
                check(!helper.isRunning(), "reduced motion cancels active wheel animation");
                wheel(-3);
                check(!helper.isRunning(), "reduced motion leaves wheel input native");
                MotionPreferences.setReduced(false);
                wheel(-3);
                check(helper.isRunning(), "wheel animation can resume when motion is enabled");
                MotionPreferences.setReduced(true);
                stage.getScene().setRoot(new Pane());
                after(100, this::verifyDetach);
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyDetach() {
            try {
                check(!helper.isRunning() && view.getScene() == null, "detaching view releases animation");
                MotionPreferences.setReduced(false);
                Pane entrance = new Pane();
                UiMotion.entrance(entrance, 24, 18);
                check(entrance.getOpacity() == 0 && entrance.getTranslateX() == 24
                        && entrance.getTranslateY() == 18, "entrance starts from both offsets");
                UiMotion.entrance(entrance, -12, 8);
                check(entrance.getTranslateX() == -12 && entrance.getTranslateY() == 8,
                        "new entrance interrupts the previous transition");
                MotionPreferences.setReduced(true);
                check(entrance.getOpacity() == 1 && entrance.getTranslateX() == 0
                        && entrance.getTranslateY() == 0, "reduced motion normalizes an active entrance");
                stage.close();
                System.out.println("SmoothListScrollSmoke PASS: wheel, burst, boundaries, inertia, keyboard, reduced motion, entrance, detach");
                Platform.exit();
            } catch (Throwable failure) { fail(failure); }
        }

        private void wheel(double lines) {
            Event.fireEvent(view, new ScrollEvent(ScrollEvent.SCROLL,
                    100, 100, 100, 100, false, false, false, false, false, false,
                    0, lines * 40, 0, 0,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.LINES, lines, 0, null));
        }

        private void after(int millis, Runnable action) {
            PauseTransition pause = new PauseTransition(Duration.millis(millis));
            pause.setOnFinished(event -> action.run());
            pause.play();
        }

        private static void check(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }

        private void fail(Throwable failure) {
            failure.printStackTrace();
            stage.close();
            Platform.exit();
            System.exit(1);
        }
    }
}
