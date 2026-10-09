package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Real JavaFX radio workflow against a fresh demo service and native audio backend. */
public final class RadioSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private URI service;
    private List<Track> demo;
    private Track previewTrack;
    private long historyBeforeNeutral;

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(115_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL radio timeout: " + phase);
                cleanup(); System.exit(1);
            }
        }, "radio-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        safely(() -> {
            stage = initialStage;
            root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
            check(root != null && root.toString().contains("build")
                    && !Files.exists(root.resolve("library.json")), "Fresh isolated profile required");
            for (String name : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                    "LUMEN_AI_SETTINGS_PATH")) {
                Path value = Path.of(required(name)).toAbsolutePath().normalize();
                check(value.equals(root) || value.getParent().equals(root), name + " outside isolated profile");
            }
            service = URI.create(required("LUMEN_SERVICE_URL"));
            check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost())
                    && service.getPort() > 0, "Radio smoke requires isolated loopback service");
            demo = new MusicClient(service).search("");
            check(demo.size() == 3 && demo.stream().allMatch(t -> "demo".equals(t.source())),
                    "Radio smoke did not receive the real demo catalog");
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(860); stage.setHeight(600);
            invoke("select", new Class<?>[] {List.class, int.class}, List.of(demo.get(0)), 0);
            phase = "real demo audio starts";
            waitFor(12_000, () -> playing() && audio().position().toSeconds() > .4,
                    this::openRadio);
        });
    }

    private void openRadio() throws Exception {
        check(queue().current().id().equals(demo.get(0).id()), "Initial demo playback changed");
        snapshot(stage.getScene().getRoot(), "radio-main-dark-860x600.png", 800, 550);
        button("home-radio").fire();
        phase = "home radio dialog opens without starting";
        waitFor(4_000, () -> radioPaneOrNull() != null, () -> {
            DialogPane pane = radioPane();
            check(!radio().active(), "Opening radio started a session without confirmation");
            check(queue().current().id().equals(demo.get(0).id()) && playing(),
                    "Opening radio interrupted current playback");
            check(pane.lookup("#radio-dialog") != null, "Radio dialog id missing");
            check(pane.lookup("#radio-list") instanceof ListView, "Radio candidate list missing");
            check(pane.lookup("#radio-reason") instanceof Label, "Radio explanation missing");
            for (String id : List.of("radio-start", "radio-refresh", "radio-stop", "radio-play",
                    "radio-remove", "radio-feedback-open")) button(pane, id);
            pane.getScene().getWindow().setWidth(460);
            delay(250, () -> {
                snapshot(pane, "radio-dialog-dark-narrow.png", 420, 350);
                configureAndStart(pane);
            });
        });
    }

    private void configureAndStart(DialogPane pane) {
        TextField hint = (TextField) pane.lookup("#radio-hint");
        check(hint != null, "Hint input missing");
        hint.setText("");
        @SuppressWarnings("unchecked") ComboBox<String> mode = (ComboBox<String>) pane.lookup("#radio-mode");
        check(mode != null && mode.getItems().containsAll(List.of("熟悉一些", "多发现一些")),
                "Radio mode choices missing");
        mode.getSelectionModel().select("多发现一些");
        CheckBox neutral = (CheckBox) pane.lookup("#radio-neutral");
        CheckBox auto = (CheckBox) pane.lookup("#radio-auto");
        check(neutral != null && auto != null, "Neutral/auto controls missing");
        neutral.setSelected(true);
        auto.setSelected(true);
        List<String> before = ids(queue().tracks());
        double position = audio().position().toSeconds();
        button(pane, "radio-start").fire();
        check(ids(queue().tracks()).equals(before) && queue().current().id().equals(demo.get(0).id()),
                "Starting radio rewrote the playing queue");
        check(audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() >= position - .3,
                "Starting radio restarted the current audio");
        phase = "real service returns insufficient demo candidates";
        waitFor(10_000, () -> radio().active() && !radioList(pane).getItems().isEmpty()
                && radioStatus().getText().contains("不足"), () -> checkPreview(pane, before));
    }

    private void checkPreview(DialogPane pane, List<String> originalQueue) throws Exception {
        check(radio().mode().equals("discover") && radio().neutral(), "UI radio settings not applied");
        check(ids(queue().tracks()).equals(originalQueue) && playing(),
                "Preview generation changed playback");
        check(radioList(pane).getItems().stream().allMatch(t -> demo.stream()
                .anyMatch(source -> source.id().equals(t.id()))), "Preview contains fabricated demo tracks");
        check(radioList(pane).getItems().size() <= 3, "Demo preview has too many tracks");
        previewTrack = radioList(pane).getItems().get(0);
        radioList(pane).getSelectionModel().select(previewTrack);
        check(!((Label) pane.lookup("#radio-reason")).getText().isBlank(),
                "Selected candidate has no explanation");
        pane.getScene().getWindow().setWidth(460);
        delay(250, () -> {
            snapshot(pane, "radio-active-dark-narrow.png", 420, 350);
            refreshPreview(pane);
        });
    }

    private void refreshPreview(DialogPane pane) {
        double position = audio().position().toSeconds();
        button(pane, "radio-refresh").fire();
        phase = "refresh preview preserves current audio";
        waitFor(8_000, () -> !radioList(pane).getItems().isEmpty()
                && radioStatus().getText().contains("不足"), () -> {
            check(queue().current().id().equals(demo.get(0).id()) && playing(),
                    "Refreshing preview switched playback");
            check(audio().position().toSeconds() >= position - .5,
                    "Refreshing preview reset playback position");
            exerciseRemoval(pane);
        });
    }

    private void exerciseRemoval(DialogPane pane) throws Exception {
        Track removed = radioList(pane).getItems().get(radioList(pane).getItems().size() - 1);
        radioList(pane).getSelectionModel().select(removed);
        button(pane, "radio-remove").fire();
        check(radioList(pane).getItems().stream().noneMatch(t -> t.id().equals(removed.id())),
                "Remove left the candidate visible");
        button(pane, "radio-refresh").fire();
        phase = "removed candidate stays excluded";
        waitFor(8_000, () -> radioStatus().getText().contains("不足"), () -> {
            check(radioList(pane).getItems().stream().noneMatch(t -> t.id().equals(removed.id())),
                    "Refresh returned an excluded candidate");
            exerciseQueueAndEnd(pane);
        });
    }

    private void exerciseQueueAndEnd(DialogPane pane) throws Exception {
        menuItem(trackMenu(demo.get(2)), "track-menu-next").fire();
        check(ids(queue().tracks()).equals(List.of(demo.get(0).id(), demo.get(2).id())),
                "Manual play-next was not inserted ahead of radio preview");
        check(radioList(pane).getItems().size() == 1
                && radioList(pane).getItems().get(0).id().equals(demo.get(1).id()),
                "Manual queue unexpectedly consumed the radio candidate");
        phase = "natural end honors manual play-next before radio preview";
        waitFor(12_000, () -> queue().current() != null
                && queue().current().id().equals(demo.get(2).id()) && playing()
                && audio().position().toSeconds() > .3, () -> verifyOneRepeat(pane));
    }

    private void verifyOneRepeat(DialogPane pane) throws Exception {
        check(radioList(pane).getItems().get(0).id().equals(demo.get(1).id()),
                "Manual next drained radio preview");
        queue().setRepeatMode(PlayerQueue.RepeatMode.ONE);
        long before = field("loadVersion", Long.class);
        invoke("seekToFraction", new Class<?>[] {double.class}, .9);
        phase = "ONE repeats the current real audio at natural end";
        waitFor(8_000, () -> field("loadVersion", Long.class) > before
                && queue().current().id().equals(demo.get(2).id()) && playing()
                && audio().position().toSeconds() < 2.5, () -> {
            check(radioList(pane).getItems().size() == 1,
                    "ONE consumed a radio candidate");
            queue().setRepeatMode(PlayerQueue.RepeatMode.ALL);
            invoke("seekToFraction", new Class<?>[] {double.class}, .9);
            phase = "queue boundary automatically plays real radio candidate";
            waitFor(9_000, () -> queue().current().id().equals(demo.get(1).id())
                    && playing() && audio().position().toSeconds() > .3, () -> verifyNeutralHistory(pane));
        });
    }

    private void verifyNeutralHistory(DialogPane pane) throws Exception {
        check(field("loadedNeutral", Boolean.class), "Auto-continued radio track is not neutral");
        check(ids(queue().tracks()).equals(List.of(demo.get(0).id(), demo.get(2).id(), demo.get(1).id())),
                "Queue boundary did not append exactly one radio candidate");
        historyBeforeNeutral = historySize();
        phase = "neutral real audio passes ninety percent without recording history";
        waitFor(11_000, () -> queue().current().id().equals(demo.get(1).id()) && playing()
                && audio().position().toSeconds() >= 7.25, () -> {
            check(historySize() == historyBeforeNeutral,
                    "Neutral playback wrote local history after ninety percent");
            long requestVersion = field("radioVersion", Long.class);
            phase = "insufficient catalog stops at queue end without retry loop";
            waitFor(8_000, () -> radioStatus().getText().contains("不足") && !playing(), () -> {
                delay(550, () -> {
                    check(field("radioVersion", Long.class) == requestVersion + 1,
                            "Insufficient candidates caused repeated requests");
                    check(historySize() == historyBeforeNeutral,
                            "Neutral playback wrote history at completion");
                    secondRoundAutoOff(pane);
                });
            });
        });
    }

    private void secondRoundAutoOff(DialogPane oldPane) throws Exception {
        button(oldPane, "radio-stop").fire();
        invoke("select", new Class<?>[] {List.class, int.class}, List.of(demo.get(0)), 0);
        button("home-radio").fire();
        phase = "second radio session disables automatic continuation";
        waitFor(4_000, () -> radioPaneOrNull() != null && radioPaneOrNull() != oldPane, () -> {
            DialogPane pane = radioPane();
            CheckBox auto = (CheckBox) pane.lookup("#radio-auto");
            check(auto != null, "Auto continuation checkbox missing");
            auto.setSelected(false);
            ((CheckBox) pane.lookup("#radio-neutral")).setSelected(true);
            button(pane, "radio-start").fire();
            waitFor(8_000, () -> radio().active() && !radioList(pane).getItems().isEmpty()
                    && radioStatus().getText().contains("不足"), () -> {
                check(!radio().autoContinue(), "Auto-off UI was not applied");
                menuItem(trackMenu(demo.get(2)), "track-menu-next").fire();
                invoke("seekToFraction", new Class<?>[] {double.class}, .9);
                phase = "auto-off keeps manual queued track";
                waitFor(8_000, () -> queue().current().id().equals(demo.get(2).id()) && playing()
                        && audio().position().toSeconds() > .25, () -> {
                    int remaining = radioList(pane).getItems().size();
                    invoke("seekToFraction", new Class<?>[] {double.class}, .9);
                    phase = "auto-off does not consume radio preview at queue end";
                    waitFor(8_000, () -> queue().current().id().equals(demo.get(2).id())
                            && !playing(), () -> {
                        check(radioList(pane).getItems().size() == remaining,
                                "Auto-off consumed a candidate");
                        playCandidate(pane);
                    });
                });
            });
        });
    }

    private void testFeedback(DialogPane pane) throws Exception {
        ContextMenu menu = trackMenu(demo.get(1));
        menuItem(menu, "track-menu-radio");
        menuItem(menu, "track-menu-tired").fire();
        phase = "tired feedback persists in real service";
        waitFor(8_000, () -> feedback().stream().anyMatch(f -> f.track().id().equals(demo.get(1).id())
                && "tired".equals(f.kind())), () -> {
            menuItem(trackMenu(demo.get(2)), "track-menu-less-version").fire();
            phase = "version feedback persists in real service";
            waitFor(8_000, () -> feedback().stream().anyMatch(f -> f.track().id().equals(demo.get(2).id())
                    && "version".equals(f.kind())), () -> restoreFeedback(pane));
        });
    }

    private void restoreFeedback(DialogPane pane) throws Exception {
        button(pane, "radio-feedback-open").fire();
        phase = "feedback dialog shows and restores entries";
        waitFor(5_000, () -> feedbackPaneOrNull() != null, () -> {
            DialogPane feedbackPane = feedbackPaneOrNull();
            @SuppressWarnings("unchecked") ListView<RadioClient.Feedback> list =
                    (ListView<RadioClient.Feedback>) feedbackPane.lookup("#radio-feedback-list");
            check(list != null, "Feedback list missing");
            waitFor(8_000, () -> list.getItems().size() >= 2, () -> {
                RadioClient.Feedback entry = list.getItems().stream()
                        .filter(f -> f.track().id().equals(demo.get(1).id())).findFirst().orElseThrow();
                list.getSelectionModel().select(entry);
                button(feedbackPane, "radio-feedback-restore").fire();
                phase = "restore persisted tired feedback";
                waitFor(8_000, () -> feedback().stream()
                        .noneMatch(f -> f.track().id().equals(demo.get(1).id())), () -> {
                    check(feedback().stream().anyMatch(f -> f.track().id().equals(demo.get(2).id())),
                            "Restoring one feedback removed another entry");
                    Window window = feedbackPane.getScene().getWindow();
                    window.hide();
                    button(pane, "radio-start").fire();
                    check(radio().active(), "Radio could not restart for departure check");
                    manualDeparture();
                });
            });
        });
    }

    private void playCandidate(DialogPane pane) throws Exception {
        check(!radioList(pane).getItems().isEmpty(), "No candidate remains for playback");
        Track candidate = radioList(pane).getItems().get(0);
        radioList(pane).getSelectionModel().select(candidate);
        historyBeforeNeutral = historySize();
        button(pane, "radio-play").fire();
        phase = "candidate button starts actual neutral audio";
        waitFor(12_000, () -> queue().current() != null && queue().current().id().equals(candidate.id())
                && playing() && audio().position().toSeconds() > .35, () -> {
            check(field("loadedNeutral", Boolean.class), "Neutral radio track was not marked neutral");
            double at = audio().position().toSeconds();
            delay(1_500, () -> {
                check(playing() && audio().position().toSeconds() > at + .5,
                        "Radio playback position did not advance");
                check(historySize() == historyBeforeNeutral,
                        "Neutral playback grew local listening history");
                System.out.println("PASS real service preview/feedback and neutral native audio");
                checkStopAndDeparture(pane);
            });
        });
    }

    private void checkStopAndDeparture(DialogPane pane) throws Exception {
        button(pane, "radio-refresh").fire();
        button(pane, "radio-stop").fire();
        check(!radio().active(), "Stop button left radio active");
        Set<String> stoppedPreview = radioList(pane).getItems().stream().map(Track::id)
                .collect(java.util.stream.Collectors.toSet());
        delay(700, () -> {
            check(!radio().active() && radioList(pane).getItems().stream().map(Track::id)
                    .collect(java.util.stream.Collectors.toSet()).equals(stoppedPreview),
                    "Late fetch mutated stopped radio");
            testFeedback(pane);
        });
    }

    private void manualDeparture() throws Exception {
        invoke("select", new Class<?>[] {List.class, int.class}, List.of(demo.get(0)), 0);
        check(!radio().active(), "Manual selection did not leave radio");
        phase = "manual departure plays actual demo audio";
        waitFor(10_000, () -> queue().current() != null && queue().current().id().equals(demo.get(0).id())
                && playing() && audio().position().toSeconds() > .25
                && field("listeningActive", Boolean.class), this::verifyNormalSessionContinuity);
    }

    private void verifyNormalSessionContinuity() throws Exception {
        Object session = currentListeningSession();
        check(session != null, "Normal playback did not establish a listening session");
        double position = audio().position().toSeconds();
        invoke("startRadio", new Class<?>[] {List.class, String.class, String.class, boolean.class},
                List.of(), "", "familiar", false);
        check(radio().active() && !radio().neutral() && !field("loadedNeutral", Boolean.class),
                "Normal radio session settings were not applied");
        check(field("listeningActive", Boolean.class) && currentListeningSession() == session,
                "Normal radio start split the existing listening session");
        check(playing() && audio().position().toSeconds() >= position - .3
                && queue().current().id().equals(demo.get(0).id()),
                "Normal radio start reset current playback");
        invoke("stopRadio", new Class<?>[0]);
        System.out.println("PASS normal radio preserves the active listening session");
        lightSnapshot();
    }

    private void lightSnapshot() throws Exception {
        button("theme-button").fire();
        snapshot(stage.getScene().getRoot(), "radio-main-light-860x600.png", 800, 550);
        button("home-radio").fire();
        phase = "light narrow radio dialog";
        waitFor(4_000, () -> radioPaneOrNull() != null, () -> {
            DialogPane pane = radioPane();
            pane.getScene().getWindow().setWidth(460);
            delay(250, () -> {
                snapshot(pane, "radio-dialog-light-narrow.png", 420, 350);
                ((TextField) pane.lookup("#radio-hint")).setText("");
                ((CheckBox) pane.lookup("#radio-neutral")).setSelected(true);
                button(pane, "radio-start").fire();
                phase = "light radio preview shows real candidate and reason";
                waitFor(10_000, () -> !radioList(pane).getItems().isEmpty()
                        && radioStatus().getText().contains("不足"), () -> {
                    Track candidate = radioList(pane).getItems().get(0);
                    radioList(pane).getSelectionModel().select(candidate);
                    check(!((Label) pane.lookup("#radio-reason")).getText().isBlank(),
                            "Light radio candidate has no explanation");
                    delay(250, () -> {
                        snapshot(pane, "radio-active-light-narrow.png", 420, 350);
                        System.out.println("PASS radio main/dialog dark/light narrow and active preview screenshots");
                        succeed();
                    });
                });
            });
        });
    }

    private ContextMenu trackMenu(Track track) throws Exception {
        ContextMenu menu = new ContextMenu();
        ListView<Track> list = new ListView<>(javafx.collections.FXCollections.observableArrayList(track));
        invoke("populateTrackMenu", new Class<?>[] {ContextMenu.class, ListView.class, Track.class, int.class},
                menu, list, track, 0);
        return menu;
    }

    private MenuItem menuItem(ContextMenu menu, String id) {
        return menu.getItems().stream().filter(item -> id.equals(item.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing menu #" + id));
    }

    private List<RadioClient.Feedback> feedback() {
        try { return new RadioClient(service).feedback(); }
        catch (Exception error) { throw new AssertionError("Cannot read real feedback", error); }
    }

    private long historySize() throws Exception {
        Path file = Path.of(required("LUMEN_HISTORY_PATH"));
        return Files.exists(file) ? Files.size(file) : 0;
    }

    private void snapshot(Node node, String name, int minWidth, int minHeight) throws Exception {
        node.applyCss(); node.getScene().getRoot().layout();
        WritableImage image = node.getScene().snapshot(null);
        check(image.getWidth() >= minWidth && image.getHeight() >= minHeight,
                "Radio screenshot too small: " + image.getWidth() + "x" + image.getHeight());
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path file = root.resolve(name);
        check(ImageIO.write(png, "png", file.toFile()) && Files.size(file) > 1_000,
                "Could not save radio screenshot");
        System.out.println("SCREENSHOT: " + file);
    }

    private DialogPane radioPaneOrNull() { return paneWith("radio-dialog"); }
    private DialogPane radioPane() {
        DialogPane pane = radioPaneOrNull();
        check(pane != null, "Radio dialog is not open"); return pane;
    }
    private DialogPane feedbackPaneOrNull() { return paneWith("radio-feedback-list"); }
    private DialogPane paneWith(String id) {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()
                && window.getScene() != null && window.getScene().lookup("#" + id) != null)
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElse(null);
    }
    @SuppressWarnings("unchecked") private ListView<Track> radioList(DialogPane pane) {
        return (ListView<Track>) pane.lookup("#radio-list");
    }
    private Button button(DialogPane pane, String id) {
        Button button = (Button) pane.lookup("#" + id);
        check(button != null, "Missing #" + id); return button;
    }
    private Button button(String id) {
        Button button = (Button) stage.getScene().lookup("#" + id);
        check(button != null, "Missing #" + id); return button;
    }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private RadioSession radio() { return field("radio", RadioSession.class); }
    private Label radioStatus() { return field("radioStatus", Label.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private boolean playing() { return audio().state() == JavaFxAudioPlayer.State.PLAYING; }
    private Object currentListeningSession() {
        try {
            Field value = ListeningReporter.class.getDeclaredField("current");
            value.setAccessible(true);
            return value.get(field("listening", ListeningReporter.class));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect active listening session", error);
        }
    }
    private static List<String> ids(List<Track> tracks) { return tracks.stream().map(Track::id).toList(); }

    private <T> T field(String name, Class<T> type) {
        try { Field value = LumenFxApp.class.getDeclaredField(name); value.setAccessible(true); return type.cast(value.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect " + name, error); }
    }
    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types); method.setAccessible(true);
        method.invoke(app, arguments);
    }
    private void waitFor(int millis, BooleanSupplier condition, Checked next) {
        poll(System.nanoTime() + millis * 1_000_000L, condition, next);
    }
    private void poll(long deadline, BooleanSupplier condition, Checked next) {
        safely(() -> {
            if (condition.getAsBoolean()) { next.run(); return; }
            check(System.nanoTime() < deadline, "Timeout in " + phase + "; status=" + radioStatus().getText()
                    + ", audio=" + audio().state() + ", position=" + audio().position().toSeconds());
            delay(70, () -> poll(deadline, condition, next));
        });
    }
    private void delay(int millis, Checked next) {
        PauseTransition timer = new PauseTransition(Duration.millis(millis));
        timer.setOnFinished(event -> safely(next)); timer.play();
    }
    private void safely(Checked action) {
        if (finished.get()) return;
        try { action.run(); }
        catch (Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            System.err.println("FAIL radio phase: " + phase); error.printStackTrace(System.err);
            cleanup(); System.exit(1);
        }
    }
    private void succeed() {
        if (finished.compareAndSet(false, true)) { cleanup(); System.exit(0); }
    }
    private void cleanup() {
        try { if (app != null) app.stop(); } catch (Exception error) { error.printStackTrace(System.err); }
        Platform.runLater(() -> { for (Window window : Window.getWindows()) window.hide(); });
    }
    private static String required(String name) {
        String value = System.getenv(name);
        check(value != null && !value.isBlank(), "Missing " + name); return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
