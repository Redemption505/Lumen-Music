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
import javafx.scene.layout.Region;
import javafx.css.PseudoClass;
import javafx.scene.paint.Paint;
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
            System.out.println("PASS offscreen slider geometry, lyric tiers, timer typography, theme paints, and lyric contrast across five themes and two widths");
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
        if (width < 1080) root.pseudoClassStateChanged(PseudoClass.getPseudoClass("compact"), true);
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

        FocusPane focus = new FocusPane(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }, ignored -> { });
        focus.resizeRelocate(width == 1280 ? 650 : 610, 450, Math.max(230, width - (width == 1280 ? 670 : 630)), 160);
        root.getChildren().add(focus);

        Label commandBadge = new Label("命令");
        commandBadge.getStyleClass().addAll("quick-search-badge", "badge-command");
        commandBadge.resizeRelocate(36, 470, 80, 24);
        root.getChildren().add(commandBadge);
        Label resultAction = new Label("执行");
        resultAction.getStyleClass().add("quick-search-cell-action");
        ListCell<String> resultCell = new ListCell<>();
        resultCell.setText("切换主题");
        resultCell.setGraphic(resultAction);
        resultCell.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
        Pane results = new Pane(resultCell);
        results.setId("quick-search-results");
        results.resizeRelocate(130, 470, 140, 32);
        root.getChildren().add(results);

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

        checkLyricTiers(lyrics, root, mode, width);
        checkFocusTimerTypography(focus, root, mode, width);
        checkThemePaints(commandBadge, resultAction, focus, root, mode, width);

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
        focus.close();
        theme.close();
    }

    private static void checkLyricTiers(ListView<String> lyrics, Pane root,
                                        ThemeController.Mode mode, int width) {
        String[][] tiers = {
                {"lyric-size-small", "13.5", "17.5"},
                {"lyric-size-medium", "15.0", "20.0"},
                {"lyric-size-large", "18.5", "25.0"}
        };
        for (String[] tier : tiers) {
            lyrics.getStyleClass().removeAll("lyric-size-small", "lyric-size-medium", "lyric-size-large");
            lyrics.getStyleClass().add(tier[0]);
            root.applyCss();
            root.layout();
            ListCell<String> context = contextCell(lyrics);
            ListCell<String> current = lyricCell(lyrics, "当前演唱行 Current singing");
            if (!current.getStyleClass().contains("current-lyric")) current.getStyleClass().add("current-lyric");
            root.applyCss();
            root.layout();
            context = contextCell(lyrics);
            current = lyricCell(lyrics, "当前演唱行 Current singing");
            double contextSize = context.getFont().getSize();
            double currentSize = current.getFont().getSize();
            check(context.getTextFill() != null && current.getTextFill() != null,
                    "lyric text paint was not computed theme=" + mode + " width=" + width + " tier=" + tier[0]);
            check(close(contextSize, Double.parseDouble(tier[1]))
                            && close(currentSize, Double.parseDouble(tier[2])),
                    "lyric tier mismatch theme=" + mode + " width=" + width + " tier=" + tier[0]
                            + " context=" + contextSize + " current=" + currentSize);
            System.out.printf("PASS lyric tier theme=%s width=%d compact=%s tier=%s context=%.1f current=%.1f%n",
                    mode, width, width < 1080, tier[0], contextSize, currentSize);
        }
    }

    private static void checkFocusTimerTypography(FocusPane focus, Pane root,
                                                 ThemeController.Mode mode, int width) {
        for (String id : List.of("focus-focus-time", "focus-sleep-time")) {
            javafx.scene.Node node = focus.lookup("#" + id);
            check(node instanceof Label, "FocusPane timer missing: " + id);
            double size = ((Label) node).getFont().getSize();
            check(close(size, 26), "FocusPane timer font mismatch theme=" + mode + " width=" + width
                    + " id=" + id + " actual=" + size);
            System.out.printf("PASS FocusPane timer theme=%s width=%d id=%s font=%.1f%n", mode, width, id, size);
        }
        root.applyCss();
    }

    private static void checkThemePaints(Label badge, Label resultAction, FocusPane focus, Pane root,
                                        ThemeController.Mode mode, int width) {
        root.applyCss();
        root.layout();
        checkPaints(badge, "command badge", mode, width);
        checkBorderPaints(resultAction, "hovered search action", mode, width);
        javafx.scene.Node pod = focus.lookup("#focus-pod");
        check(pod instanceof Region, "FocusPane zen pod missing");
        pod.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
        root.applyCss();
        root.layout();
        checkPaints((Region) pod, "hovered focus pod", mode, width);
    }

    private static void checkPaints(Region region, String description, ThemeController.Mode mode, int width) {
        check(region.getBackground() != null && !region.getBackground().getFills().isEmpty(),
                "missing computed background " + description + " theme=" + mode + " width=" + width);
        for (var fill : region.getBackground().getFills()) {
            Paint paint = fill.getFill();
            check(paint != null, "uncomputed background paint " + description + " theme=" + mode + " width=" + width);
        }
        checkBorderPaints(region, description, mode, width);
        System.out.printf("PASS computed paints theme=%s width=%d control=%s%n", mode, width, description);
    }

    private static void checkBorderPaints(Region region, String description, ThemeController.Mode mode, int width) {
        check(region.getBorder() != null && !region.getBorder().getStrokes().isEmpty(),
                "missing computed border " + description + " theme=" + mode + " width=" + width);
        for (var stroke : region.getBorder().getStrokes()) {
            check(stroke.getTopStroke() != null && stroke.getRightStroke() != null
                            && stroke.getBottomStroke() != null && stroke.getLeftStroke() != null,
                    "uncomputed border paint " + description + " theme=" + mode + " width=" + width);
        }
        System.out.printf("PASS computed border paint theme=%s width=%d control=%s%n", mode, width, description);
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < .01;
    }

    private static ListCell<String> lyricCell(ListView<String> lyrics, String text) {
        for (javafx.scene.Node node : lyrics.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && text.equals(cell.getItem())) {
                @SuppressWarnings("unchecked") ListCell<String> typed = (ListCell<String>) cell;
                return typed;
            }
        }
        throw new AssertionError("lyric ListCell was not created: " + text);
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
