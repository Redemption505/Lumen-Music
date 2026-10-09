package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Installed-JAR UI acceptance; OS file dialogs are handled by the native test operator. */
public final class UpdateFeedbackSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private final List<String> observations = new ArrayList<>(List.of("phase,state,position,loadVersion,trackId"));
    private volatile String phase = "startup";
    private Path root, packages;
    private LumenFxApp app;
    private Stage stage;
    private Track baseline;
    private long baselineLoad;
    private Map<String, String> installedFiles;

    @Override public void start(Stage window) {
        stage = window; Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(1_800_000); } catch (InterruptedException ignored) { return; }
            System.err.println("FAIL update feedback watchdog: " + phase); System.exit(1);
        }, "update-feedback-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            int code = 0;
            try { exercise(); }
            catch (Throwable error) { code = 1; System.err.println("FAIL update feedback phase: " + phase); error.printStackTrace(System.err); }
            finally { finish(code); }
        }, "update-feedback-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        prepare();
        fx(() -> {
            app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(1280); stage.setHeight(800);
            queue().select(List.of(baseline), 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true);
        });
        await(10_000, () -> fxValue(() -> audio() instanceof JavaFxAudioPlayer
                && audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() > .1), "real WAV did not play");
        baselineLoad = fxValue(() -> field("loadVersion", Long.class));
        visualChecks();
        if (Boolean.getBoolean("lumen.update.smoke.visualOnly")) {
            System.out.println("PASS installed-JAR final version settings drawn with correct dark/light contrast at actual DPI"); return;
        }
        fx(() -> button("play-button").fire());
        await(4_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "baseline did not pause");
        fx(() -> invoke("seekToFraction", new Class<?>[] {double.class}, 5.0 / 120));
        await(4_000, () -> fxValue(() -> Math.abs(audio().position().toSeconds() - 5) < .2), "paused WAV seek failed");
        diagnosticsChecks();
        for (String name : List.of("wrong-from", "bad-hash", "missing-manifest")) rejectedPackage(name);
        cancelValidPackage(); checkPaused();
        check(installationHashes().equals(installedFiles), "package rejection/cancellation modified isolated installed files");
        check(!Files.exists(root.resolve("update-applied.marker")), "cancelled package ran synthetic payload");
        System.out.println("PASS installed-JAR version/build/release notes, real OS diagnostic export with privacy whitelist,"
                + " rejected local packages and valid candidate cancellation without playback/queue change; no upgrade executed");
    }

    private void prepare() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("update-feedback-smoke"), "isolated update feedback profile required");
        for (String name : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(name)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), name + " escaped isolation");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null, "personal API key entered test");
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        check("127.0.0.1".equals(service.getHost()), "non-loopback service entered test");
        Path codeSource = Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(Files.isRegularFile(codeSource) && codeSource.getFileName().toString().equals("lumen-desktop-0.2.0.jar")
                && codeSource.startsWith(root), "UI is not running from isolated 0.2.0 installation JAR: " + codeSource);
        packages = root.resolve("candidates");
        installedFiles = installationHashes();
        for (String name : List.of("wrong-from", "bad-hash", "missing-manifest", "valid-future"))
            check(Files.size(packages.resolve(name + ".zip")) > 0, "candidate fixture missing: " + name);
        baseline = new Track("update-private-history-canary", "PRIVATE_TITLE_CANARY", "PRIVATE_ARTIST_CANARY", 120,
                "local", root.resolve("feedback-baseline.wav").toUri());
        new HotkeySettingsStore(root.resolve("hotkeys.properties")).save(java.util.Map.of());
        System.out.println("INSTALLED JAR: " + codeSource);
    }

    private void visualChecks() throws Exception {
        phase = "version settings readability and playback preservation";
        for (ThemeController.Mode theme : List.of(ThemeController.Mode.DARK, ThemeController.Mode.LIGHT)) {
            for (int[] size : new int[][] {{860, 600}, {1280, 800}}) {
                double before = fxValue(() -> audio().position().toSeconds());
                fx(() -> {
                    for (int count = 0; count < 8 && field("themeMode", ThemeController.Mode.class) != theme; count++) button("theme-button").fire();
                    stage.setWidth(size[0]); stage.setHeight(size[1]); settings();
                });
                Thread.sleep(300);
                fx(() -> {
                    settings(); Node version = node("settings-app-version"), build = node("settings-build-id"), notes = node("settings-release-notes");
                    check(text(version).contains("0.2.0"), "settings displays stale application version: " + text(version));
                    check(!text(build).isBlank() && !text(build).contains("unknown"), "settings build ID missing");
                    check(!text(notes).isBlank(), "release notes missing");
                    ensureVisible(version); fits(version); fits(build);
                    screenshot(theme.name().toLowerCase() + "-version-" + size[0] + "x" + size[1] + "-top.png");
                    ensureVisible(notes); notesContrast((TextArea) notes, theme);
                    ensureVisible(node("settings-select-update")); fits(node("settings-select-update")); fits(node("settings-export-diagnostics"));
                    check(!button("settings-select-update").isDisabled(), "installed-JAR update entry is disabled");
                    screenshot(theme.name().toLowerCase() + "-version-" + size[0] + "x" + size[1] + "-bottom.png");
                    check(queue().tracks().equals(List.of(baseline)) && queue().index() == 0 && field("loadVersion", Long.class) == baselineLoad
                            && audio().state() == JavaFxAudioPlayer.State.PLAYING && audio().position().toSeconds() >= before,
                            "settings/theme/size changed actual playing audio");
                });
            }
        }
        fx(() -> { stage.setWidth(1280); stage.setHeight(800); settings(); });
        observe("settings-kept-playing");
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()));
        System.out.println("PASS dark/light 860x600/1280x800 version settings; queue/load and real WAV playback preserved");
    }

    private void diagnosticsChecks() throws Exception {
        phase = "real OS diagnostic save dialog and privacy whitelist";
        fx(() -> {
            for (Field field : LumenFxApp.class.getDeclaredFields()) {
                if (field.getType().getSimpleName().equals("DiagnosticLog")) {
                    field.setAccessible(true); Object log = field.get(app);
                    check(log != null, "application diagnostic log not initialized");
                    Method record = log.getClass().getMethod("record", String.class);
                    record.invoke(log, "canaryKey PRIVATE_NOTE_CANARY " + root);
                }
            }
        });
        Path output = root.resolve("diagnostics-export.zip");
        requestFileDialog("export-diagnostics", output, "settings-export-diagnostics");
        await(240_000, () -> Files.exists(output) && Files.size(output) > 0, "OS save dialog did not produce diagnostic ZIP");
        String content;
        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            List<String> names = zip.stream().map(java.util.zip.ZipEntry::getName).toList();
            check(names.equals(List.of("diagnostics.txt")), "diagnostic ZIP contains non-whitelisted private files: " + names);
            content = new String(zip.getInputStream(zip.getEntry("diagnostics.txt")).readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String canary : List.of("canaryKey", "PRIVATE_NOTE_CANARY", "PRIVATE_TITLE_CANARY", "PRIVATE_ARTIST_CANARY",
                "PRIVATE_HISTORY_CANARY", "update-private-history-canary", root.toString(), root.toUri().toString(),
                System.getProperty("user.home"), System.getProperty("user.name")))
            check(!content.contains(canary), "diagnostics leaked raw key/note/history/user/path: " + canary);
        check(content.contains("0.2.0") && content.contains("UNKNOWN_ERROR"), "diagnostics omitted build metadata or sanitized recent error code");
        check(content.contains("Engine: JAVA_FX") && content.contains("Playback state: PAUSED")
                && content.contains("Preloading: false") && content.contains("Short fade: false"),
                "diagnostics did not contain the actual engine/state/feature snapshot");
        Files.writeString(root.resolve("diagnostic-verification.txt"), "entries=diagnostics.txt\nprivacyCanariesAbsent=true\nUNKNOWN_ERROR=true\n");
        clearRequest(); checkPaused(); observe("diagnostic-export-kept-paused");
        System.out.println("PASS real system save dialog exported only diagnostics.txt with version and sanitized error, no key/private note/history/path");
    }

    private void rejectedPackage(String name) throws Exception {
        phase = "real OS local package selection rejects " + name;
        String previous = fxValue(() -> text(node("settings-update-status")));
        String reason = switch (name) { case "wrong-from" -> "不适用于"; case "bad-hash" -> "校验"; default -> "清单"; };
        requestFileDialog(name, packages.resolve(name + ".zip"), "settings-select-update");
        await(240_000, () -> fxValue(() -> {
            String value = text(node("settings-update-status"));
            return value.contains(reason) && !value.equals(previous) && findConfirmation() == null;
        }), "selected invalid package was not rejected: " + name);
        String response = fxValue(() -> text(node("settings-update-status")));
        check(!response.contains("0.2.1 已安装"), "invalid package announced successful upgrade");
        Files.writeString(root.resolve(name + "-ui-status.txt"), response);
        clearRequest(); checkPaused(); observe(name + "-rejected");
        System.out.println("REJECTED " + name + ": " + response);
    }

    private void cancelValidPackage() throws Exception {
        phase = "real OS valid future candidate preflight and explicit cancel";
        requestFileDialog("valid-future", packages.resolve("valid-future.zip"), "settings-select-update");
        await(240_000, () -> fxValue(() -> findConfirmation() != null), "valid local candidate did not open update confirmation");
        fx(() -> {
            DialogPane pane = findConfirmation();
            check(pane != null && pane.getId().equals("local-update-confirm"), "wrong confirmation dialog");
            Node cancel = pane.lookup("#local-update-cancel"), apply = pane.lookup("#local-update-apply");
            check(cancel instanceof Button && apply instanceof Button && !apply.isDisabled(), "valid candidate confirmation actions missing");
            screenshot(pane.getScene(), "valid-future-confirmation-cancelled.png"); ((Button) cancel).fire();
        });
        await(4_000, () -> fxValue(() -> findConfirmation() == null && stage.isShowing()), "cancel closed application or left confirmation open");
        clearRequest(); checkPaused(); observe("valid-update-cancelled");
        System.out.println("PASS valid 0.2.0->0.2.1 synthetic candidate preflight and explicit cancel; application remains open, no update/helper applied");
    }

    private void requestFileDialog(String name, Path target, String buttonId) throws Exception {
        Files.writeString(root.resolve("file-dialog-request.txt"), "phase=" + name + "\ntarget=" + target.toAbsolutePath() + "\n");
        System.out.println("NATIVE FILE DIALOG REQUEST: " + name + " => " + target);
        Platform.runLater(() -> { try { ensureVisible(node(buttonId)); button(buttonId).fire(); } catch (Throwable error) { error.printStackTrace(System.err); } });
    }
    private void clearRequest() throws Exception { Files.deleteIfExists(root.resolve("file-dialog-request.txt")); }
    private Map<String, String> installationHashes() throws Exception {
        Path install = root.resolve("install"); Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(install)) {
            for (Path path : files.filter(Files::isRegularFile).toList())
                result.put(install.relativize(path).toString(), java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
    private void checkPaused() throws Exception {
        check(fxValue(() -> stage.isShowing() && queue().tracks().equals(List.of(baseline)) && queue().index() == 0
                && queue().repeatMode() == PlayerQueue.RepeatMode.OFF && field("loadVersion", Long.class) == baselineLoad
                && audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(audio().position().toSeconds() - 5) < .2),
                "feedback/update dialog changed original paused queue/load/position");
        double before = fxValue(() -> audio().position().toSeconds()); Thread.sleep(350);
        check(fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED && Math.abs(audio().position().toSeconds() - before) < .03),
                "feedback/update interaction resumed audio or moved paused clock");
    }
    private void settings() { button("sidebar-settings").fire(); ((TitledPane) node("settings-version")).setExpanded(true); }
    private static DialogPane findConfirmation() { return Window.getWindows().stream().filter(Window::isShowing)
            .filter(window -> window.getScene() != null).map(window -> window.getScene().getRoot())
            .filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
            .filter(pane -> "local-update-confirm".equals(pane.getId())).findFirst().orElse(null); }
    private static String text(Node node) { return node instanceof Label label ? label.getText() : node instanceof TextArea area ? area.getText() : ""; }
    private static void notesContrast(TextArea notes, ThemeController.Mode theme) {
        Node content = notes.lookup(".content"), glyph = notes.lookup(".text");
        check(content instanceof javafx.scene.layout.Region, "release notes content surface missing");
        var background = ((javafx.scene.layout.Region) content).getBackground();
        check(background != null && !background.getFills().isEmpty()
                && background.getFills().get(0).getFill() instanceof javafx.scene.paint.Color, "release notes computed background missing");
        var color = (javafx.scene.paint.Color) background.getFills().get(0).getFill();
        double luminance = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
        check(theme == ThemeController.Mode.DARK ? luminance < .55 : luminance > .65, "release notes computed surface uses wrong theme");
        check(glyph instanceof javafx.scene.text.Text && ((javafx.scene.text.Text) glyph).getFill() instanceof javafx.scene.paint.Color,
                "release notes computed text color missing");
        var foreground = (javafx.scene.paint.Color) ((javafx.scene.text.Text) glyph).getFill();
        double textLuminance = (foreground.getRed() + foreground.getGreen() + foreground.getBlue()) / 3;
        check(Math.abs(textLuminance - luminance) > .35, "release notes text has inadequate actual theme contrast");
        WritableImage rendered = notes.snapshot(null, null); int matching = 0;
        int right = (int) rendered.getWidth() - 25, bottom = (int) rendered.getHeight() - 8;
        for (int[] point : new int[][] {{5, 8}, {right, 8}, {5, bottom}, {right, bottom}}) {
            var pixel = rendered.getPixelReader().getColor(point[0], point[1]);
            double value = (pixel.getRed() + pixel.getGreen() + pixel.getBlue()) / 3;
            if (theme == ThemeController.Mode.DARK ? value < .55 : value > .65) matching++;
        }
        check(matching >= 3, "release notes actual rendered pixels use wrong theme");
        System.out.println("NOTES CONTRAST: " + theme + " background=" + luminance + " text=" + textLuminance + " matchedPixels=" + matching);
    }
    private static void ensureVisible(Node node) {
        check(node != null, "settings control missing");
        for (Parent parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getContent() != null) {
                double excess = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
                if (excess > 0) {
                    Bounds bounds = scroll.getContent().sceneToLocal(node.localToScene(node.getLayoutBounds()));
                    scroll.setVvalue(Math.max(0, Math.min(1, (bounds.getMinY() - 12) / excess)));
                }
            }
        }
        node.getScene().getRoot().applyCss(); node.getScene().getRoot().layout(); fits(node);
    }
    private static void fits(Node node) {
        check(node != null && node.isVisible() && node.isManaged(), "settings control unavailable");
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        check(bounds.getMinX() >= -1 && bounds.getMaxX() <= node.getScene().getWidth() + 1
                && bounds.getMinY() >= -1 && bounds.getMaxY() <= node.getScene().getHeight() + 1, "settings control clipped: " + node.getId());
    }
    private void screenshot(String name) { screenshot(stage.getScene(), name); }
    private void screenshot(javafx.scene.Scene scene, String name) {
        try {
            scene.getRoot().applyCss(); scene.getRoot().layout(); WritableImage rendered = scene.snapshot(null);
            BufferedImage image = new BufferedImage((int) rendered.getWidth(), (int) rendered.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, rendered.getPixelReader().getArgb(x, y));
            Path output = root.resolve(name); check(ImageIO.write(image, "png", output.toFile()) && Files.size(output) > 1000, "empty screenshot");
            System.out.println("SCREENSHOT: " + output);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private void observe(String label) throws Exception { fx(() -> observations.add(label + "," + audio().state() + "," + audio().position().toSeconds()
            + "," + field("loadVersion", Long.class) + "," + queue().current().id())); }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); });
            if (root != null) Files.write(root.resolve("feedback-playback.csv"), observations);
        } catch (Exception error) { error.printStackTrace(System.err); code = 1; }
        Platform.exit(); System.exit(code);
    }
    private Node node(String id) { Node value = stage.getScene().lookup("#" + id); if (value == null) throw new AssertionError("UI control missing: " + id); return value; }
    private Button button(String id) { return (Button) node(id); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private <T> T field(String name, Class<T> type) { try { Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app)); } catch (Exception error) { throw new AssertionError(error); } }
    private void invoke(String name, Class<?>[] types, Object... args) { try { Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(app, args); } catch (Exception error) { throw new AssertionError(error); } }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    private static void fx(CheckedRunnable action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private static <T> T fxValue(CheckedSupplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>(); Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(250, TimeUnit.SECONDS);
    }
    private void await(long millis, CheckedSupplier<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(50); }
        throw new AssertionError(message + "; phase=" + phase);
    }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new AssertionError("Missing " + name); return value; }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
