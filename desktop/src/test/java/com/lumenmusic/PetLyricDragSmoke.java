package com.lumenmusic;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;

/** Separate real JavaFX smoke for lyric-popup lifecycle and long-press drag gestures. */
public final class PetLyricDragSmoke {
    private static Throwable failure;

    public static void main(String[] args) {
        Application.launch(Checks.class, args);
        if (failure != null) throw new AssertionError("Pet lyric/drag smoke failed", failure);
    }

    public static final class Checks extends Application {
        private DesktopPetWindow desktop;
        private Stage host;
        private int assertions;

        @Override public void start(Stage stage) {
            host = stage;
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
            try {
                verifyProductionOrigin();
                PetCompanion pet = new PetCompanion();
                VBox dock = new VBox(pet);
                host.setScene(new Scene(dock, 320, 240));
                host.show();
                dock.applyCss();
                dock.layout();
                desktop = new DesktopPetWindow(pet, dock, () -> { });
                verifyLyrics(pet);
                verifyDrag();
            } catch (Throwable error) { fail(error); }
        }

        private void verifyProductionOrigin() throws Exception {
            String configured = System.getenv("LUMEN_SMOKE_PRODUCTION_JAR");
            if (configured == null || configured.isBlank())
                configured = System.getProperty("lumen.test.installedJar", "");
            check(!configured.isBlank(), "Expected production JAR path is missing");
            Path expected = Path.of(configured).toRealPath();
            Path actual = null;
            for (Class<?> production : new Class<?>[] {LumenFxApp.class, LongPressWindowDrag.class,
                    DesktopPetWindow.class}) {
                actual = Path.of(production.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                check(Files.isSameFile(expected, actual), "Production class origin differs for "
                        + production.getSimpleName() + ": expected=" + expected + " actual=" + actual);
            }
            System.out.println("Verified production class origin: " + actual);
        }

        private void verifyLyrics(PetCompanion pet) {
            Popup popup = field(desktop, "noticePopup", Popup.class);
            Label text = field(desktop, "notice", Label.class);
            int[] shows = {0};
            popup.addEventHandler(WindowEvent.WINDOW_SHOWING, event -> shows[0]++);
            desktop.show();
            // A first-ever popup with a long line also tests native peer/DPI and
            // owner stylesheet initialization, unlike a short-line warm-up.
            desktop.setLyric("这是一句检查桌面歌词换行和两行高度的长歌词，".repeat(12), true);
            verifyLongLyric(popup, text, shows);
            desktop.setLyric("第一句歌词", true);
            check(popup.isShowing() && text.getText().equals("第一句歌词"), "Playing matched lyric is visible");
            for (int i = 0; i < 30; i++) desktop.setLyric("第一句歌词", true);
            desktop.setLyric("下一句歌词", true);
            check(shows[0] == 1 && text.getText().equals("下一句歌词"), "Ticks and line changes reuse the shown popup");
            var bounds = Screen.getPrimary().getVisualBounds();
            desktop.setPosition(bounds.getMinX() + 260, bounds.getMinY() + 180);
            double before = popup.getX();
            desktop.setPosition(bounds.getMinX() + 320, bounds.getMinY() + 180);
            check(Math.abs(popup.getX() - before - 60) < 2, "Lyric follows pet movement");
            desktop.setLyric("下一句歌词", false);
            check(!popup.isShowing(), "Pause hides the lyric");
            desktop.setLyric("下一句歌词", true);
            int showsBeforeNotice = shows[0];
            desktop.showNotice("音乐事件");
            desktop.setLyric("事件期间的新歌词", true);
            check(text.getText().equals("音乐事件"), "Milestone temporarily occupies the same popup");
            logPopupSize(popup, text, "event");
            check(popup.getWidth() > 0 && popup.getWidth() <= 240,
                    "Milestone keeps the compact event bubble width: " + popup.getWidth());
            PauseTransition timer = field(desktop, "hideNotice", PauseTransition.class);
            timer.stop();
            timer.getOnFinished().handle(new javafx.event.ActionEvent());
            check(text.getText().equals("事件期间的新歌词"), "After milestone the latest lyric returns");
            logPopupSize(popup, text, "restored lyric");
            check(popup.getWidth() > 0 && popup.getWidth() <= 260
                            && text.isVisible() && text.getWidth() > 20 && text.getHeight() > 0,
                    "Restored lyric stays within 260px and has visible text: " + popup.getWidth());
            check(shows[0] == showsBeforeNotice, "Switching event and lyric modes does not re-show the popup");
            desktop.setLyric("", true);
            check(!popup.isShowing(), "Unmatched or cleared lyrics disappear immediately");
            desktop.setLyric("隐藏前歌词", true);
            pet.setSpecies(PetCompanion.Species.HIDDEN);
            check(!popup.isShowing(), "Hidden species closes its popup");
            pet.setSpecies(PetCompanion.Species.BLACK_CAT);
            desktop.hideAnimated();
            desktop.setLyric("返回期间歌词", true);
            check(!popup.isShowing(), "Returning to dock suppresses lyric callbacks");
            desktop.hideToDock();
            check(!popup.isShowing(), "Dock has no residual desktop popup");
            desktop.show();
            desktop.setLyric("退出前歌词", true);
            desktop.close();
            desktop.setLyric("迟到歌词", true);
            desktop.show();
            check(!popup.isShowing() && !desktop.isShowing(), "Close rejects late lyric/show callbacks");
        }

        private void verifyLongLyric(Popup popup, Label label, int[] shows) {
            Text skinText = (Text) label.lookup(".text");
            Text line = new Text("Ag");
            line.setFont(label.getFont());
            line.setBoundsType(skinText.getBoundsType());
            double lineHeight = line.getLayoutBounds().getHeight();
            Text twoLines = new Text("Ag\nAg");
            twoLines.setFont(label.getFont());
            twoLines.setBoundsType(skinText.getBoundsType());
            twoLines.setLineSpacing(label.getLineSpacing());
            double twoLineHeight = twoLines.getLayoutBounds().getHeight();
            Text threeLines = new Text("Ag\nAg\nAg");
            threeLines.setFont(label.getFont());
            threeLines.setBoundsType(skinText.getBoundsType());
            threeLines.setLineSpacing(label.getLineSpacing());
            double threeLineHeight = threeLines.getLayoutBounds().getHeight();
            double pixel = 1 / host.getOutputScaleY();
            int before = shows[0];
            for (String lyric : new String[] {
                    "这是一句检查桌面歌词换行和两行高度的长歌词，".repeat(12),
                    "A long lyric should wrap into two visible lines while its popup remains compact. ".repeat(12).strip(),
                    "🎵🎶这句歌词带有补充平面字符，截断不应拆开字符。".repeat(12)}) {
                desktop.setLyric(lyric, true);
                logPopupSize(popup, label, "two-line lyric");
                check(popup.getWidth() > 0 && popup.getWidth() <= 260,
                        "Long lyric popup stays within 260px: " + popup.getWidth());
                Node node = label.lookup(".text");
                check(node instanceof Text, "Long lyric has its actual rendered skin text");
                Text rendered = (Text) node;
                String display = label.getText();
                check(field(desktop, "lyric", String.class).equals(lyric), "Truncation preserves the full current lyric");
                check(display.endsWith("…") && lyric.startsWith(display.substring(0, display.length() - 1))
                                && display.codePoints().noneMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF),
                        "Measured truncation uses a Unicode-safe original prefix and ellipsis");
                check(rendered.getText().equals(display), "Skin renders the measured text without a second truncation");
                if (rendered.getClip() != null) {
                    var visible = rendered.getClip().getBoundsInParent();
                    var body = rendered.getLayoutBounds();
                    check(visible.getMinX() <= body.getMinX() + .001 && visible.getMinY() <= body.getMinY() + .001
                                    && visible.getMaxX() + .001 >= body.getMaxX() && visible.getMaxY() + .001 >= body.getMaxY(),
                            "Long lyric's clip keeps both text lines and ellipsis visible: " + visible);
                }
                double height = rendered.getLayoutBounds().getHeight();
                System.out.println("LONG LYRIC renderedHeight=" + height + " oneLineHeight=" + lineHeight
                        + " twoLineHeight=" + twoLineHeight + " threeLineHeight=" + threeLineHeight
                        + " boundsType=" + rendered.getBoundsType() + " font=" + rendered.getFont()
                        + " displayedText=" + rendered.getText());
                check(height > lineHeight * 1.8 && Math.abs(height - twoLineHeight) <= pixel,
                        "Long lyric renders two actual lines rather than one clipped line: " + height);
                check(height < threeLineHeight - pixel, "Long lyric never renders a third line: " + height);
                double padding = label.snappedTopInset() + label.snappedBottomInset();
                check(label.getHeight() >= height + padding - pixel
                                && popup.getHeight() <= label.snapSizeY(twoLineHeight) + padding + pixel,
                        "Long lyric allocates two lines with padding and no third line: " + popup.getHeight());
            }
            check(shows[0] == before, "Long lyric wrapping reuses the shown popup");
        }

        private void logPopupSize(Popup popup, Label label, String mode) {
            var popupRoot = popup.getScene().getRoot();
            System.out.println("POPUP " + mode + " window=" + popup.getWidth() + "x" + popup.getHeight()
                    + " wrapper=" + popupRoot.getLayoutBounds() + " wrapperPrefWidth=" + popupRoot.prefWidth(-1)
                    + " content=" + popup.getContent().get(0).getLayoutBounds()
                    + " label=" + label.getWidth() + "x" + label.getHeight());
        }

        private void verifyDrag() {
            Label free = new Label("Hold here");
            Button button = new Button("Playback control");
            Slider slider = new Slider();
            VBox surface = new VBox(12, free, button, slider);
            host.setScene(new Scene(surface, 320, 240));
            var bounds = Screen.getPrimary().getVisualBounds();
            host.setX(bounds.getMinX() + 240);
            host.setY(bounds.getMinY() + 180);
            System.out.println("DRAG OUTPUT SCALE " + host.getOutputScaleX() + " / " + host.getOutputScaleY());
            host.getScene().addEventFilter(MouseEvent.ANY, event -> {
                if (event.getEventType() == MouseEvent.MOUSE_PRESSED
                        || event.getEventType() == MouseEvent.MOUSE_DRAGGED)
                    System.out.println("DRAG EVENT " + event.getEventType() + " target="
                            + event.getTarget().getClass().getSimpleName() + " scene="
                            + event.getSceneX() + "," + event.getSceneY() + " screen="
                            + event.getScreenX() + "," + event.getScreenY());
            });
            LongPressWindowDrag.install(surface, host);
            WindowResize.install(host, host.getScene());
            after(100, () -> {
                double x = host.getX(), y = host.getY();
                mouse(free, MouseEvent.MOUSE_PRESSED, x + 20, y + 20, true);
                mouse(free, MouseEvent.MOUSE_DRAGGED, x + 70, y + 30, true);
                after(340, () -> {
                    check(Math.abs(host.getX() - x) < 1, "Waiting hold does not move the window");
                    mouse(free, MouseEvent.MOUSE_DRAGGED, x + 90, y + 40, true);
                    check(Math.abs(host.getX() - x - 20) < 1 && Math.abs(host.getY() - y - 10) < 1,
                            "Activation anchors the latest pointer without a jump");
                    mouse(free, MouseEvent.MOUSE_RELEASED, x + 90, y + 40, false);
                    double releasedX = host.getX();
                    mouse(free, MouseEvent.MOUSE_DRAGGED, x + 130, y + 40, true);
                    check(host.getX() == releasedX, "Release cancels drag");
                    verifyControl(button, () -> verifyControl(slider, () -> {
                        mouse(free, MouseEvent.MOUSE_PRESSED, host.getX() + 20, host.getY() + 20, true);
                        mouse(free, MouseEvent.MOUSE_RELEASED, host.getX() + 20, host.getY() + 20, false);
                        after(340, () -> {
                            double cancelled = host.getX();
                            mouse(free, MouseEvent.MOUSE_DRAGGED, cancelled + 150, host.getY() + 20, true);
                            check(host.getX() == cancelled, "Short press cancels pending long hold");
                            verifyResizeEdge(surface);
                        });
                    }));
                });
            });
        }

        private void verifyResizeEdge(Node surface) {
            Scene scene = host.getScene();
            double x = host.getX(), y = host.getY();
            double width = host.getWidth(), height = host.getHeight();
            double sceneX = scene.getWidth() - 3, sceneY = scene.getHeight() - 3;
            double screenX = x + sceneX, screenY = y + sceneY;
            mouseAt(surface, MouseEvent.MOUSE_PRESSED, sceneX, sceneY, screenX, screenY, true);
            after(340, () -> {
                mouseAt(surface, MouseEvent.MOUSE_DRAGGED, sceneX + 24, sceneY + 16,
                        screenX + 24, screenY + 16, true);
                check(Math.abs(host.getWidth() - width - 24) < 1
                                && Math.abs(host.getHeight() - height - 16) < 1,
                        "Holding the resize corner does not let long-press drag steal resize events");
                check(host.getX() == x && host.getY() == y,
                        "Southeast resize changes size without simultaneously moving the window: before="
                                + x + "," + y + " after=" + host.getX() + "," + host.getY());
                mouseAt(surface, MouseEvent.MOUSE_RELEASED, sceneX + 24, sceneY + 16,
                        screenX + 24, screenY + 16, false);
                System.out.println("PASS PetLyricDragSmoke assertions=" + assertions);
                host.close();
                Platform.exit();
            });
        }

        private void verifyControl(Node control, Runnable next) {
            String name = control.getClass().getSimpleName();
            double requestedX = host.getX(), requestedY = host.getY();
            // The preceding drag called Stage.setX/Y in this same FX callback.
            // Give the native window's position notification a pulse before taking
            // the control baseline, rather than treating it as a control movement.
            after(100, () -> {
                double x = host.getX(), y = host.getY();
                System.out.println("CONTROL " + name + " settle requested=" + requestedX + "," + requestedY
                        + " baseline=" + x + "," + y);
                mouse(control, MouseEvent.MOUSE_PRESSED, x + 20, y + 20, true);
                after(340, () -> {
                    double heldX = host.getX(), heldY = host.getY();
                    mouse(control, MouseEvent.MOUSE_DRAGGED, x + 120, y + 60, true);
                    System.out.println("CONTROL " + name + " before=" + x + "," + y + " afterHold="
                            + heldX + "," + heldY + " afterDrag=" + host.getX() + "," + host.getY());
                    check(host.getX() == x && host.getY() == y,
                            "Control gesture does not move window: " + name + " before=" + x + "," + y
                                    + " afterHold=" + heldX + "," + heldY
                                    + " afterDrag=" + host.getX() + "," + host.getY());
                    mouse(control, MouseEvent.MOUSE_RELEASED, x + 120, y + 60, false);
                    next.run();
                });
            });
        }

        private void after(int millis, Runnable action) {
            PauseTransition pause = new PauseTransition(Duration.millis(millis));
            pause.setOnFinished(event -> {
                try { action.run(); } catch (Throwable error) { fail(error); }
            });
            pause.play();
        }

        private void check(boolean value, String message) {
            assertions++;
            if (!value) throw new AssertionError(message);
        }

        private void fail(Throwable error) {
            failure = error;
            error.printStackTrace(System.err);
            if (desktop != null) desktop.close();
            host.close();
            Platform.exit();
        }
    }

    private static void mouse(Node target, javafx.event.EventType<MouseEvent> type,
                              double screenX, double screenY, boolean down) {
        mouseAt(target, type, 10, 10, screenX, screenY, down);
    }

    private static void mouseAt(Node target, javafx.event.EventType<MouseEvent> type,
                                double sceneX, double sceneY, double screenX, double screenY, boolean down) {
        Event.fireEvent(target, new MouseEvent(type, sceneX, sceneY, screenX, screenY, MouseButton.PRIMARY,
                1, false, false, false, false, down, false, false, false, false, true, null));
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
}
