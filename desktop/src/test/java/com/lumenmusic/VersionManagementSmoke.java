package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Exercises the version editor against the real isolated demo service. */
public final class VersionManagementSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private URI service;
    private Track first;
    private Track second;
    private Track third;
    private String playlistId;
    private String originalVersionId;
    private final DemoAudioLibrary demoAudio = new DemoAudioLibrary();

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(100_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL version timeout: " + phase);
                cleanup(); System.exit(1);
            }
        }, "version-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
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
                    && service.getPort() > 0, "Version smoke requires isolated loopback service");
            List<Track> demo = new MusicClient(service).search("");
            check(demo.size() == 3 && demo.stream().allMatch(track -> "demo".equals(track.source())),
                    "Smoke did not receive the real demo catalog");
            first = demo.get(0); second = demo.get(1); third = demo.get(2);
            LibraryClient library = new LibraryClient(service);
            check(library.load().favorites().isEmpty(), "Demo library was not empty");
            library.favorite(first); library.favorite(second);
            playlistId = library.createPlaylist("版本验收").playlists().get(0).id();
            library.addToPlaylist(playlistId, first);
            library.addToPlaylist(playlistId, second);
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(1100); stage.setHeight(760);
            phase = "library favorites load";
            waitFor(10_000, () -> favorites().size() == 2, this::openDarkEditor);
        });
    }

    private void openDarkEditor() throws Exception {
        invoke("showVersions", new Class<?>[] {Track.class}, first);
        phase = "dark version editor opens";
        waitFor(8_000, () -> dialogOrNull() != null, () -> {
            DialogPane pane = dialog();
            pane.getScene().getWindow().setWidth(460);
            afterResizeSnapshot(pane, "version-dark-narrow.png", () -> {
                text(pane, "version-song-title").setText("月光航线 · 私藏");
                ((TextArea) pane.lookup("#version-song-note")).setText("两种真实资源，按心情切换");
                text(pane, "version-label").setText("录音室");
                text(pane, "version-performer").setText("林间回声");
                button(pane, "version-save").fire();
                phase = "create song through UI";
                waitFor(8_000, () -> catalog().findByResourceId(first.id()).isPresent(),
                        this::searchSecondResource);
            });
        });
    }

    private void searchSecondResource() throws Exception {
        VersionCatalog.Song song = catalog().findByResourceId(first.id()).orElseThrow();
        check("月光航线 · 私藏".equals(song.title()) && "两种真实资源，按心情切换".equals(song.note()),
                "Song title/note did not round trip through service");
        originalVersionId = song.versions().get(0).id();
        DialogPane pane = dialog();
        text(pane, "version-query").setText("星尘");
        button(pane, "version-search").fire();
        phase = "search demo provider for second resource";
        waitFor(8_000, () -> candidates(pane).getItems().stream().anyMatch(t -> t.id().equals(second.id())), () -> {
            candidates(pane).getSelectionModel().select(second);
            text(pane, "version-candidate-label").setText("现场");
            text(pane, "version-candidate-performer").setText("安和 & 乐团");
            confirmThen(() -> button(pane, "version-add").fire());
            phase = "explicitly confirm version addition";
            waitFor(8_000, () -> catalog().findByResourceId(first.id())
                    .map(value -> value.versions().size() == 2).orElse(false), this::editAndPreferSecond);
        });
    }

    private void editAndPreferSecond() throws Exception {
        DialogPane pane = dialog();
        VersionCatalog.Song song = catalog().findByResourceId(first.id()).orElseThrow();
        VersionCatalog.Version added = song.versions().stream()
                .filter(value -> value.track().id().equals(second.id())).findFirst().orElseThrow();
        versions(pane).getSelectionModel().select(added);
        text(pane, "version-label").setText("现场修订");
        text(pane, "version-performer").setText("演出嘉宾");
        button(pane, "version-save-info").fire();
        phase = "edit version metadata";
        waitFor(8_000, () -> catalog().versionByResourceId(second.id())
                .map(value -> "现场修订".equals(value.label()) && "演出嘉宾".equals(value.performer()))
                .orElse(false), () -> {
            versions(pane).getSelectionModel().select(catalog().versionByResourceId(second.id()).orElseThrow());
            button(pane, "version-prefer").fire();
            phase = "choose preferred resource";
            waitFor(8_000, () -> second.id().equals(catalog().preferredTrack(first).id()),
                    this::playOriginalExplicitly);
        });
    }

    private void playOriginalExplicitly() throws Exception {
        DialogPane pane = dialog();
        VersionCatalog.Version original = catalog().versionByResourceId(first.id()).orElseThrow();
        versions(pane).getSelectionModel().select(original);
        button(pane, "version-play").fire();
        phase = "explicit play bypasses preference";
        waitFor(8_000, () -> queue().current() != null && first.id().equals(queue().current().id())
                && dialogOrNull() == null, () -> {
            check(second.id().equals(catalog().preferredTrack(first).id()),
                    "Explicit play changed preferred resource");
            check(first.id().equals(field("loadedTrack", Track.class).id()),
                    "Explicit play did not load the selected original resource");
            queue().restore(List.of(first, second, first), 0);
            button("theme-button").fire();
            invoke("showVersions", new Class<?>[] {Track.class}, first);
            phase = "light version editor opens";
            waitFor(8_000, () -> dialogOrNull() != null, this::replaceThenUndo);
        });
    }

    private void replaceThenUndo() throws Exception {
        DialogPane pane = dialog();
        pane.getScene().getWindow().setWidth(460);
        afterResizeSnapshot(pane, "version-light-narrow.png", () -> {
            check("月光航线 · 私藏".equals(text(pane, "version-song-title").getText()),
                    "Song title did not survive reopening");
            check("两种真实资源，按心情切换".equals(((TextArea) pane.lookup("#version-song-note")).getText()),
                    "Song note did not survive reopening");
            text(pane, "version-query").setText("雨巷");
            button(pane, "version-search").fire();
            phase = "search third demo resource";
            waitFor(8_000, () -> candidates(pane).getItems().stream().anyMatch(t -> t.id().equals(third.id())), () -> {
                candidates(pane).getSelectionModel().select(third);
                versions(pane).getSelectionModel().select(catalog().versionByResourceId(first.id()).orElseThrow());
                VersionCatalog beforeCatalog = new VersionCatalogClient(service).load();
                LibraryClient.Snapshot beforeLibrary = new LibraryClient(service).load();
                List<String> beforeQueue = queue().tracks().stream().map(Track::id).toList();
                rejectThen(() -> button(pane, "version-replace").fire(), () -> {
                    check(beforeCatalog.equals(new VersionCatalogClient(service).load()),
                            "Cancelled replacement changed persisted catalog");
                    check(beforeLibrary.equals(new LibraryClient(service).load()),
                            "Cancelled replacement changed favorites or playlists");
                    check(beforeQueue.equals(queue().tracks().stream().map(Track::id).toList()),
                            "Cancelled replacement changed playback queue");
                    check(first.id().equals(field("loadedTrack", Track.class).id()),
                            "Cancelled replacement changed loaded playback resource");
                    System.out.println("PASS default cancel and unchanged catalog/library/playback after cancelled replacement");
                    confirmThen(() -> button(pane, "version-replace").fire());
                    phase = "replace resource across catalog and references";
                    waitFor(8_000, () -> catalog().versionByResourceId(third.id()).isPresent(),
                            this::checkReplacement);
                });
            });
        });
    }

    private void checkReplacement() throws Exception {
        LibraryClient.Snapshot library = new LibraryClient(service).load();
        check(library.favorites().stream().map(Track::id).toList().equals(List.of(third.id(), second.id())),
                "Replacement did not preserve favorites order");
        check(library.playlists().get(0).id().equals(playlistId) && library.playlists().get(0).tracks()
                .stream().map(Track::id).toList().equals(List.of(third.id(), second.id())),
                "Replacement did not preserve playlist order");
        check(queue().tracks().stream().map(Track::id).toList()
                .equals(List.of(first.id(), second.id(), third.id())),
                "Replacement changed current playback or missed a future queue reference");
        check(first.id().equals(field("loadedTrack", Track.class).id()),
                "Replacement changed the loaded playback snapshot");
        VersionCatalog.Song song = catalog().findByResourceId(third.id()).orElseThrow();
        check(song.versions().stream().anyMatch(value -> value.id().equals(originalVersionId)
                && value.track().id().equals(third.id())), "Replacement changed version identity");
        button(dialog(), "version-undo").fire();
        phase = "undo replacement";
        waitFor(8_000, () -> catalog().versionByResourceId(first.id()).isPresent()
                && catalog().versionByResourceId(third.id()).isEmpty(), () -> {
            LibraryClient.Snapshot restored = new LibraryClient(service).load();
            check(restored.favorites().stream().map(Track::id).toList()
                    .equals(List.of(first.id(), second.id())), "Undo did not restore favorites");
            check(restored.playlists().get(0).tracks().stream().map(Track::id).toList()
                    .equals(List.of(first.id(), second.id())), "Undo did not restore playlist");
            System.out.println("PASS real demo service UI create/search/add/edit/prefer/explicit play/replace/undo");
            detachUndoAndExerciseGroups();
        });
    }

    private void detachUndoAndExerciseGroups() throws Exception {
        DialogPane pane = dialog();
        VersionCatalog.Song grouped = catalog().findByResourceId(first.id()).orElseThrow();
        String secondVersionId = catalog().versionByResourceId(second.id()).orElseThrow().id();
        versions(pane).getSelectionModel().select(catalog().versionByResourceId(second.id()).orElseThrow());
        confirmThen(() -> button(pane, "version-detach").fire());
        phase = "detach selected version while preserving library references";
        waitFor(8_000, () -> catalog().versionByResourceId(second.id()).isEmpty(), () -> {
            check(new LibraryClient(service).load().favorites().stream().map(Track::id).toList()
                    .equals(List.of(first.id(), second.id())), "Detach changed existing favorites");
            check(new LibraryClient(service).load().playlists().get(0).tracks().stream()
                    .map(Track::id).toList().equals(List.of(first.id(), second.id())),
                    "Detach changed existing playlist entries");
            button(dialog(), "version-undo").fire();
            phase = "undo detached version";
            waitFor(8_000, () -> catalog().versionByResourceId(second.id())
                    .map(value -> value.id().equals(secondVersionId)).orElse(false), () -> {
                check(catalog().findByResourceId(first.id()).orElseThrow().id().equals(grouped.id()),
                        "Detach undo changed the original song identity");
                createBrowseAndMergeGroups();
            });
        });
    }

    private void createBrowseAndMergeGroups() throws Exception {
        VersionCatalog.Song target = new VersionCatalogClient(service).create("雨巷独立", "目标备注",
                List.of(new VersionCatalog.Version("偏好版本", "第三方", third)))
                .findByResourceId(third.id()).orElseThrow();
        ((Button) dialog().lookupButton(ButtonType.CLOSE)).fire();
        invoke("showVersions", new Class<?>[] {Track.class}, first);
        phase = "reload groups and browse an existing song";
        waitFor(8_000, () -> dialogOrNull() != null && catalog().songs().stream()
                .anyMatch(song -> song.id().equals(target.id())), () -> {
            DialogPane pane = dialog();
            selectGroup(pane, target.id());
            button(pane, "version-browse").fire();
            phase = "browse selected song group";
            waitFor(8_000, () -> dialogOrNull() != null
                    && "雨巷独立".equals(text(dialog(), "version-song-title").getText()), () -> {
                check(catalog().findByResourceId(third.id()).orElseThrow().id().equals(target.id()),
                        "Browse opened the wrong song group");
                ((Button) dialog().lookupButton(ButtonType.CLOSE)).fire();
                invoke("showVersions", new Class<?>[] {Track.class}, first);
                phase = "reload source and merge into selected song";
                waitFor(8_000, () -> dialogOrNull() != null
                        && catalog().findByResourceId(first.id()).isPresent(), () -> mergeAndUndo(target));
            });
        });
    }

    private void mergeAndUndo(VersionCatalog.Song target) throws Exception {
        DialogPane pane = dialog();
        selectGroup(pane, target.id());
        confirmThen(() -> button(pane, "version-merge").fire());
        phase = "merge song groups and preserve preference and both notes";
        waitFor(8_000, () -> catalog().songs().size() == 1
                && catalog().findByResourceId(first.id()).map(song -> song.id().equals(target.id())).orElse(false), () -> {
            VersionCatalog.Song merged = catalog().findByResourceId(first.id()).orElseThrow();
            check(merged.versions().stream().map(value -> value.track().id()).toList()
                    .containsAll(List.of(first.id(), second.id(), third.id())),
                    "Merge did not preserve every resource version");
            check("雨巷独立".equals(merged.title()) && "目标备注\n两种真实资源，按心情切换".equals(merged.note()),
                    "Merge did not preserve the target title and both song notes");
            check(third.id().equals(merged.preferredTrack().orElseThrow().id()),
                    "Merge changed the target preferred resource");
            button(dialog(), "version-undo").fire();
            phase = "undo song merge";
            waitFor(8_000, () -> catalog().songs().size() == 2
                    && catalog().findByResourceId(first.id()).isPresent()
                    && catalog().findByResourceId(third.id()).map(song -> song.id().equals(target.id())).orElse(false), () -> {
                check("两种真实资源，按心情切换".equals(
                        catalog().findByResourceId(first.id()).orElseThrow().note()),
                        "Merge undo did not restore the source note");
                check("目标备注".equals(catalog().findByResourceId(third.id()).orElseThrow().note()),
                        "Merge undo did not restore the target note");
                System.out.println("PASS real demo service UI detach/undo/browse/merge/undo");
                verifyLocalSegment();
            });
        });
    }

    private void selectGroup(DialogPane pane, String songId) {
        @SuppressWarnings("unchecked") ComboBox<VersionCatalog.Song> groups =
                (ComboBox<VersionCatalog.Song>) pane.lookup("#version-group-target");
        VersionCatalog.Song target = groups.getItems().stream()
                .filter(value -> value.id().equals(songId)).findFirst().orElseThrow();
        groups.getSelectionModel().select(target);
    }

    private void verifyLocalSegment() throws Exception {
        ((Button) dialog().lookupButton(ButtonType.CLOSE)).fire();
        Path sourceFile = root.resolve("local-source.wav");
        Files.copy(Path.of(demoAudio.resolve("demo-1")), sourceFile);
        Files.writeString(root.resolve("local-source.lrc"),
                "[00:02.000]local clip line\n[00:04.000]second line\n", java.nio.charset.StandardCharsets.UTF_8);
        Track source = new Track(sourceFile.toUri().toString(), "Local source", "Fixture", 8,
                "local", sourceFile.toUri());
        Track clip = Track.segment(source, "Local clip", "Fixture", 2_000, 5_500);
        queue().select(List.of(clip, second), 0);
        queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
        invoke("loadCurrent", new Class<?>[] {boolean.class}, true);
        phase = "native JavaFX local segment starts at source offset";
        waitFor(8_000, () -> audio() instanceof JavaFxAudioPlayer && queue().current() != null
                && clip.id().equals(queue().current().id())
                && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && physical() >= 2.1 && physical() < 5.2, () -> {
            check(Math.abs(relative() - (physical() - 2)) < .5,
                    "Local clip progress is not segment relative");
            check(Math.abs(duration() - 3.5) < .2, "Local clip duration is not the selected range");
            button("play-button").fire();
            phase = "paused local clip relative seek";
            waitFor(3_000, () -> audio().state() == JavaFxAudioPlayer.State.PAUSED, () -> {
                invoke("seekToFraction", new Class<?>[] {double.class}, .72);
                waitFor(4_000, () -> Math.abs(physical() - 4.52) < .5, () -> {
                    check(audio().state() == JavaFxAudioPlayer.State.PAUSED,
                            "Local segment seek resumed playback");
                    check(Math.abs(relative() - 2.52) < .5, "Local seek displays source position");
                    queue().setRepeatMode(PlayerQueue.RepeatMode.ONE);
                    int before = field("loadVersion", Long.class).intValue();
                    button("play-button").fire();
                    phase = "local segment end repeats from offset";
                    waitFor(5_000, () -> field("loadVersion", Long.class).intValue() == before + 1
                            && queue().index() == 0 && audio().state() == JavaFxAudioPlayer.State.PLAYING
                            && physical() >= 2 && physical() < 3.3, () -> {
                        queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
                        invoke("seekToFraction", new Class<?>[] {double.class}, .9);
                        phase = "local segment end advances once";
                        int afterRepeat = field("loadVersion", Long.class).intValue();
                        waitFor(5_000, () -> queue().index() == 1
                                && field("loadVersion", Long.class).intValue() == afterRepeat + 1, () -> {
                            System.out.println("PASS native JavaFX local segment offset/relative seek/end/repeat");
                            succeed();
                        });
                    });
                });
            });
        });
    }

    private void confirmThen(Runnable action) {
        Platform.runLater(() -> {
            Platform.runLater(() -> respond(true));
            action.run();
        });
    }

    private void rejectThen(Runnable action, Checked next) {
        Platform.runLater(() -> safely(() -> {
            Platform.runLater(() -> respond(false));
            action.run();
            next.run();
        }));
    }

    private void respond(boolean accepted) {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null || window == stage
                    || window == dialog().getScene().getWindow()) continue;
            if (!(window.getScene().getRoot() instanceof DialogPane pane)) continue;
            if (!"destructive-confirmation".equals(pane.getId())) continue;
            Button confirm = (Button) pane.lookup("#destructive-confirm");
            Button cancel = (Button) pane.lookup("#destructive-cancel");
            check(confirm != null && cancel != null, "Version confirmation buttons are missing");
            check(cancel.isDefaultButton() && !confirm.isDefaultButton(), "Version confirmation does not default to cancel");
            (accepted ? confirm : cancel).fire();
            return;
        }
        throw new AssertionError("Version confirmation did not open");
    }

    private void snapshot(DialogPane pane, String name) throws Exception {
        pane.applyCss(); pane.layout();
        WritableImage image = pane.getScene().snapshot(null);
        check(image.getWidth() >= 420 && image.getWidth() <= 500 && image.getHeight() >= 500,
                "Version editor screenshot has unexpected size: " + image.getWidth() + "x" + image.getHeight());
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path file = root.resolve(name);
        check(ImageIO.write(png, "png", file.toFile()) && Files.size(file) > 1_000,
                "Could not save version screenshot");
        System.out.println("SCREENSHOT: " + file);
    }

    private void afterResizeSnapshot(DialogPane pane, String name, Checked next) {
        PauseTransition pulse = new PauseTransition(Duration.millis(220));
        pulse.setOnFinished(event -> safely(() -> { snapshot(pane, name); next.run(); }));
        pulse.play();
    }

    private void waitFor(int millis, BooleanSupplier condition, Checked next) {
        poll(System.nanoTime() + millis * 1_000_000L, condition, next);
    }

    private void poll(long deadline, BooleanSupplier condition, Checked next) {
        safely(() -> {
            if (condition.getAsBoolean()) { next.run(); return; }
            check(System.nanoTime() < deadline, "Timeout in " + phase + "; feedback="
                    + (dialogOrNull() == null ? "closed" : dialogOrNull().lookup("#version-feedback"))
                    + (phase.startsWith("native JavaFX local segment") ? playbackDiagnostic() : ""));
            PauseTransition timer = new PauseTransition(Duration.millis(70));
            timer.setOnFinished(event -> poll(deadline, condition, next)); timer.play();
        });
    }

    private String playbackDiagnostic() {
        try {
            Label status = (Label) stage.getScene().lookup("#status");
            Label failure = (Label) stage.getScene().lookup("#playback-failure-notice");
            Label nowPlaying = (Label) stage.getScene().lookup("#now-playing");
            Track loaded = field("loadedTrack", Track.class);
            Object pendingResume = field("pendingResumeSeconds", Object.class);
            boolean segmentSeeking = field("segmentSeeking", Boolean.class);
            return "; audio=" + audio().getClass().getSimpleName() + "/" + audio().state()
                    + ", position=" + physical() + ", relative=" + relative() + ", duration=" + duration()
                    + ", segmentSeeking=" + segmentSeeking + ", pendingResume=" + pendingResume
                    + ", loaded=" + (loaded == null ? "null" : loaded.id())
                    + ", current=" + (queue().current() == null ? "null" : queue().current().id())
                    + ", status=" + (status == null ? "missing" : status.getText())
                    + ", failure=" + (failure == null ? "missing" : failure.getText())
                    + ", nowPlaying=" + (nowPlaying == null ? "missing" : nowPlaying.getText());
        } catch (Throwable error) { return "; playback diagnostics unavailable: " + error; }
    }

    private DialogPane dialogOrNull() {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()
                && window.getScene() != null && window.getScene().lookup("#version-dialog") != null)
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElse(null);
    }

    private DialogPane dialog() {
        DialogPane pane = dialogOrNull();
        check(pane != null, "Version editor is not open");
        return pane;
    }

    @SuppressWarnings("unchecked") private ComboBox<Track> candidates(DialogPane pane) {
        return (ComboBox<Track>) pane.lookup("#version-candidate");
    }

    @SuppressWarnings("unchecked") private ListView<VersionCatalog.Version> versions(DialogPane pane) {
        return (ListView<VersionCatalog.Version>) pane.lookup("#version-list");
    }

    private TextField text(DialogPane pane, String id) { return (TextField) pane.lookup("#" + id); }
    private Button button(DialogPane pane, String id) { return (Button) pane.lookup("#" + id); }
    private Button button(String id) { return (Button) stage.getScene().lookup("#" + id); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private double physical() { return audio().position().toSeconds(); }
    private double relative() { return ((Duration) invokeValue("playbackPosition")).toSeconds(); }
    private double duration() { return ((Duration) invokeValue("playbackDuration")).toSeconds(); }
    private VersionCatalog catalog() { return field("versionCatalog", VersionCatalog.class); }

    @SuppressWarnings("unchecked") private ObservableList<Track> favorites() {
        return field("favorites", ObservableList.class);
    }

    private <T> T field(String name, Class<T> type) {
        try {
            Field value = LumenFxApp.class.getDeclaredField(name);
            value.setAccessible(true);
            return type.cast(value.get(app));
        } catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect " + name, error); }
    }

    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = LumenFxApp.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(app, arguments);
    }

    private Object invokeValue(String name) {
        try {
            Method method = LumenFxApp.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(app);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Cannot invoke " + name, error); }
    }

    private void safely(Checked action) {
        if (finished.get()) return;
        try { action.run(); }
        catch (Throwable error) {
            System.err.println("FAIL version phase: " + phase);
            error.printStackTrace(System.err);
            if (finished.compareAndSet(false, true)) { cleanup(); System.exit(1); }
        }
    }

    private void succeed() {
        if (finished.compareAndSet(false, true)) { cleanup(); System.exit(0); }
    }

    private void cleanup() {
        try { if (app != null) app.stop(); } catch (Exception error) { error.printStackTrace(System.err); }
        demoAudio.close();
        Platform.runLater(() -> { for (Window window : Window.getWindows()) window.hide(); });
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new AssertionError("Missing " + name);
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
