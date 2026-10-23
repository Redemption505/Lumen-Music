package com.lumenmusic;

import java.net.URL;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

/** Offscreen render smoke for slider geometry and readable lyric context. */
public final class FrontendRenderingSmoke extends Application {
    private static final String CONTEXT = "上下文歌词 Context lyrics";
    private static final double REQUIRED_CONTRAST = 4.5;
    private static volatile int exitCode = 1;

    @Override public void start(Stage unused) {
        boolean previousReducedMotion = MotionPreferences.reduced();
        try {
            MotionPreferences.setReduced(true);
            URL stylesheet = FrontendRenderingSmoke.class.getResource("/lumen-theme.css");
            check(stylesheet != null, "lumen-theme.css missing from test classpath");
            for (ThemeController.Mode mode : ThemeController.Mode.values()) {
                for (int width : new int[] {1280, 860}) {
                    auditScene(mode, width, stylesheet.toExternalForm());
                }
            }
            System.out.println("PASS offscreen slider geometry and lyric contrast across five themes and two widths");
            exitCode = 0;
        } catch (Throwable failure) {
            System.err.println("FAIL offscreen frontend rendering: " + failure.getMessage());
            failure.printStackTrace(System.err);
        } finally {
            MotionPreferences.setReduced(previousReducedMotion);
            Platform.exit();
        }
    }

    static int exitCode() { return exitCode; }

    private static void auditScene(ThemeController.Mode mode, int width, String stylesheet) {
        Pane root = new Pane();
        Scene scene = new Scene(root, width, 620);
        scene.getStylesheets().add(stylesheet);
        ThemeController theme = new ThemeController(scene);
        theme.setMode(mode);
        root.resize(width, 620);

        Slider progress = new Slider(0, 1, .5);
        progress.setId("progress-slider");
        Slider mini = new Slider(0, 1, .5);
        mini.setId("mini-progress-slider");
        Slider volume = new Slider(0, 1, .5);
        volume.setId("volume-slider");
        progress.setMinWidth(0); progress.setPrefSize(width == 1280 ? 400 : 310, 32);
        mini.setMinWidth(0); mini.setPrefSize(width == 1280 ? 190 : 145, 32);
        volume.setMinWidth(0); volume.setPrefSize(width == 1280 ? 100 : 78, 32);
        root.getChildren().addAll(progress, mini, volume);

        ListView<String> lyrics = new ListView<>();
        lyrics.setId("immersive-lyrics");
        lyrics.getItems().setAll("当前演唱行 Current singing", CONTEXT, "下一句 Next line");
        lyrics.setPrefSize(Math.min(580, width - 80), 210);
        root.getChildren().add(lyrics);

        Label marqueeStyle = new Label();
        marqueeStyle.getStyleClass().add("mini-current-lyric");
        LyricMarquee marquee = new LyricMarquee(marqueeStyle, () -> .5);
        marquee.setText("短句歌词 Short lyric");
        marqueeStyle.setGraphic(marquee);
        marqueeStyle.resizeRelocate(350, 510, Math.min(480, width - 80), 36);
        root.getChildren().add(marqueeStyle);

        root.applyCss();
        root.layout();
        progress.resizeRelocate(36, 35, width == 1280 ? 400 : 310, 32);
        mini.resizeRelocate(36, 90, width == 1280 ? 190 : 145, 32);
        volume.resizeRelocate(36, 145, width == 1280 ? 100 : 78, 32);
        lyrics.resizeRelocate(36, 205, Math.min(580, width - 80), 235);
        root.applyCss();
        root.layout();

        for (Slider slider : List.of(progress, mini, volume)) {
            check(Math.abs(slider.getWidth() - slider.getPrefWidth()) < 1,
                    "offscreen fixture did not retain requested slider width " + slider.getId());
            for (double value : new double[] {0, .25, .5, 1}) {
                slider.setValue(value);
                root.layout();
                Bounds track = FrontendRenderAssertions.sliderTrack(slider);
                System.out.printf("PASS slider geometry theme=%s width=%d id=%s value=%.2f track=%s%n",
                        mode, width, slider.getId(), value, track);
            }
        }

        ListCell<String> contextCell = contextCell(lyrics);
        double contextContrast = FrontendRenderAssertions.lyricContrast(contextCell, REQUIRED_CONTRAST);
        System.out.printf("PASS lyric contrast theme=%s width=%d context max=%.2f%n",
                mode, width, contextContrast);
        double marqueeContrast = FrontendRenderAssertions.lyricContrast(marquee, REQUIRED_CONTRAST);
        System.out.printf("PASS lyric contrast theme=%s width=%d marquee max=%.2f%n",
                mode, width, marqueeContrast);

        if (mode == ThemeController.Mode.DARK && width == 1280) {
            checkRejectsRadius999(progress);
            checkRejectsLowOpacity(contextCell);
        }
        theme.close();
    }

    private static ListCell<String> contextCell(ListView<String> lyrics) {
        for (javafx.scene.Node node : lyrics.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && CONTEXT.equals(cell.getItem())) {
                @SuppressWarnings("unchecked") ListCell<String> typed = (ListCell<String>) cell;
                return typed;
            }
        }
        throw new AssertionError("context lyric ListCell was not created");
    }

    private static void checkRejectsRadius999(Slider slider) {
        javafx.scene.Node track = slider.lookup(".track");
        check(track != null, "progress track missing for negative radius check");
        String oldStyle = track.getStyle();
        try {
            track.setStyle(oldStyle + " -fx-background-radius: 999;");
            slider.getScene().getRoot().applyCss();
            slider.getScene().getRoot().layout();
            expectAssertion(() -> FrontendRenderAssertions.sliderTrack(slider), "SLIDER_TRACK_");
            System.out.println("PASS negative check rejects temporary slider track radius 999");
        } finally {
            track.setStyle(oldStyle);
            slider.getScene().getRoot().applyCss();
            slider.getScene().getRoot().layout();
        }
        FrontendRenderAssertions.sliderTrack(slider);
    }

    private static void checkRejectsLowOpacity(ListCell<String> cell) {
        String oldStyle = cell.getStyle();
        try {
            cell.setStyle(oldStyle + " -fx-opacity: 0.55;");
            cell.getScene().getRoot().applyCss();
            cell.getScene().getRoot().layout();
            expectAssertion(() -> FrontendRenderAssertions.lyricContrast(cell, REQUIRED_CONTRAST),
                    "LYRIC_RENDERED_CONTRAST_LOW");
            System.out.println("PASS negative check rejects temporary lyric cell opacity 0.55");
        } finally {
            cell.setStyle(oldStyle);
            cell.getScene().getRoot().applyCss();
            cell.getScene().getRoot().layout();
        }
        FrontendRenderAssertions.lyricContrast(cell, REQUIRED_CONTRAST);
    }

    private static void expectAssertion(Runnable action, String expectedPrefix) {
        try {
            action.run();
        } catch (AssertionError expected) {
            check(expected.getMessage() != null && expected.getMessage().startsWith(expectedPrefix),
                    "unexpected negative-check failure: " + expected.getMessage());
            return;
        }
        throw new AssertionError("negative regression was not detected: " + expectedPrefix);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
