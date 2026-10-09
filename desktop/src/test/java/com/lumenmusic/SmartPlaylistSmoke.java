package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Real demo service and JavaFX organizer workflow, run by smart-playlist-smoke.ps1. */
public final class SmartPlaylistSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private URI service;
    private SmartPlaylistClient client;
    private Track studio;
    private Track live;
    private Track clip;
    private Track unknown;
    private String ordinaryId;
    private String smartId;
    private String folderId;
    private String snapshotId;

    @Override public void start(Stage initialStage) {
        Platform.setImplicitExit(false);
        stage = initialStage;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(135_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL smart playlist watchdog: " + phase);
                System.exit(1);
            }
        }, "smart-playlist-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread worker = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL smart playlist phase: " + phase);
                error.printStackTrace(System.err);
                finish(1);
            }
        }, "smart-playlist-smoke-driver");
        worker.setDaemon(true);
        worker.start();
    }

    private void exercise() throws Exception {
        prepareFixture();
        phase = "main window and library";
        fx(() -> { app = new LumenFxApp(); app.startUi(stage, List.of()); stage.setWidth(860); stage.setHeight(600); });
        await(12_000, () -> fxValue(() -> favorites().size() == 3), "favorites did not load");
        pause(300);
        fx(() -> screenshot(stage.getScene().getRoot(), "main-860x600.png"));
        phase = "open dark organizer";
        fx(() -> button(stage.getScene().getRoot(), "smart-playlists").fire());
        await(10_000, () -> fxValue(() -> dialogOrNull() != null
                && list("organization-track-list").getItems().size() >= 5), "organizer did not load");
        fx(() -> ((Label) node(dialog(), "organization-feedback")).textProperty()
                .addListener((ignored, before, after) -> System.out.println("ORGANIZATION FEEDBACK: " + after)));
        fx(() -> { dialog().getScene().getWindow().setWidth(480); });
        pause(300);
        fx(() -> { screenshot(dialog(), "smart-dark-480.png"); checkFit(dialog()); });

        phase = "batch add, replace and remove manual tags";
        Object previousState = fxValue(this::dialogState);
        fx(() -> {
            tab(1);
            selectEntries(studio.id(), clip.id(), unknown.id());
            field("smartDialog", SmartPlaylistDialog.class).refresh();
        });
        await(8_000, () -> fxValue(() -> dialogState() != previousState
                && selectedEntryIds().containsAll(List.of(studio.id(), clip.id(), unknown.id()))),
                "multi-selection was lost on organization refresh");
        fx(() -> {
            text("organization-tag-input").setText("Focus");
            button(dialog(), "organization-tag-add").fire();
        });
        await(8_000, () -> fxValue(() -> !dialogBusy())
                && tagged(studio.id(), "Focus") && tagged(clip.id(), "Focus")
                && tagged(unknown.id(), "Focus"), "batch add did not persist");
        fx(() -> {
            selectEntries(unknown.id());
            text("organization-tag-input").setText("Focus, Temporary");
            respondThen(() -> button(dialog(), "organization-tag-replace").fire(), false);
        });
        check(tagged(unknown.id(), "Focus") && !tagged(unknown.id(), "Temporary"),
                "cancelled tag replacement changed persisted tags");
        fx(() -> respondThen(() -> button(dialog(), "organization-tag-replace").fire(), true));
        await(8_000, () -> fxValue(() -> !dialogBusy()) && tagged(unknown.id(), "Temporary"),
                "replace tags did not persist");
        fx(() -> {
            selectEntries(unknown.id());
            text("organization-tag-input").setText("Temporary");
            respondThen(() -> button(dialog(), "organization-tag-remove").fire(), false);
        });
        check(tagged(unknown.id(), "Temporary"), "cancelled tag removal discarded persisted tag");
        fx(() -> respondThen(() -> button(dialog(), "organization-tag-remove").fire(), true));
        await(8_000, () -> fxValue(() -> !dialogBusy())
                && tagged(unknown.id(), "Focus") && !tagged(unknown.id(), "Temporary"),
                "remove tags did not persist");
        System.out.println("PASS UI bulk tags add/replace/remove");

        phase = "combined rules and unknown duration exclusion";
        fx(() -> {
            tab(0);
            button(dialog(), "smart-new").fire();
            text("smart-name").setText("Focus 长音频");
            ((CheckBox) node(dialog(), "smart-favorite-only")).setSelected(true);
            combo("smart-listening").setValue("未记录收听");
            text("smart-min-duration").setText("1");
            text("smart-tags-all").setText("Focus");
            button(dialog(), "smart-preview").fire();
        });
        await(8_000, () -> fxValue(() -> resultIds().equals(List.of(studio.id(), clip.id()))),
                "AND rules or unknown duration exclusion failed");
        fx(this::fireSmartSave);
        await(8_000, () -> fxValue(() -> !dialogBusy()) && client.load().smartPlaylists().stream()
                .anyMatch(item -> "Focus 长音频".equals(item.name())), "smart playlist save failed");
        smartId = client.load().smartPlaylists().stream()
                .filter(item -> "Focus 长音频".equals(item.name())).findFirst().orElseThrow().id();
        await(8_000, () -> fxValue(() -> smartId.equals(selectedSmartId())),
                "newly saved smart playlist was not selected for editing");
        fx(() -> {
            System.out.println("EDIT selected=" + selectedSmartId() + " busy=" + dialogBusy()
                    + " max-before=" + text("smart-max-duration").getText());
            text("smart-max-duration").setText("100");
            fireSmartSave();
            System.out.println("EDIT fired selected=" + selectedSmartId() + " busy=" + dialogBusy()
                    + " max-after=" + text("smart-max-duration").getText());
        });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && client.load().smartPlaylists().stream()
                .filter(item -> item.id().equals(smartId))
                .anyMatch(item -> Integer.valueOf(100).equals(item.rules().maxDuration())),
                "edited smart rule did not persist");
        check(client.load().smartPlaylists().stream()
                .filter(item -> "Focus 长音频".equals(item.name())).count() == 1,
                "editing smart rule created a duplicate playlist");
        await(8_000, () -> fxValue(() -> resultIds().equals(List.of(clip.id()))),
                "edited rule did not update preview");
        fx(() -> { text("smart-max-duration").clear(); fireSmartSave(); });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && client.load().smartPlaylists().stream()
                .filter(item -> item.id().equals(smartId))
                .anyMatch(item -> item.rules().maxDuration() == null),
                "restored max-duration rule did not persist");
        await(8_000, () -> fxValue(() -> resultIds().equals(List.of(studio.id(), clip.id()))),
                "restored rule did not update preview");
        fx(() -> {
            field("smartDialog", SmartPlaylistDialog.class).setTrackManagement(view -> {
                throw new AssertionError("track management controls installed more than once");
            });
            checkTrackManagement("smart-preview-results", false);
        });
        System.out.println("PASS UI combined rules save/edit and unknown duration exclusion");

        phase = "single selection and explicit selected resource playback";
        fx(() -> {
            ListView<Track> view = list("smart-preview-results");
            view.getSelectionModel().select(0);
            view.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, true, false, false, null));
            check(queue().current() == null, "single result click started playback");
        });
        fx(() -> {
            ListView<Track> view = list("smart-preview-results");
            view.scrollTo(0);
            view.applyCss(); view.layout();
            Button play = view.lookupAll(".list-cell").stream()
                    .filter(ListCell.class::isInstance).map(ListCell.class::cast)
                    .filter(cell -> cell.getItem() instanceof Track track && studio.id().equals(track.id()))
                    .map(cell -> cell.lookup("#organization-track-play"))
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .findFirst().orElseThrow();
            play.fire();
        });
        await(12_000, () -> fxValue(() -> studio.id().equals(loadedId())
                && audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() > .25), "selected local resource did not actually play");
        check(live.id().equals(new VersionCatalogClient(service).load().preferredTrack(studio).id()),
                "fixture preferred version changed");
        List<String> queueIds = fxValue(() -> queue().tracks().stream().map(Track::id).toList());
        check(queueIds.equals(List.of(studio.id(), clip.id())), "explicit play did not take result snapshot");
        AudioPlayer playing = fxValue(this::audio);
        double position = fxValue(() -> audio().position().toSeconds());
        phase = "refresh and tag/rule changes preserve loaded audio and queue";
        fx(() -> {
            button(dialog(), "smart-preview").fire();
            tab(1);
            selectEntries(clip.id());
            text("organization-tag-input").setText("Focus");
            respondThen(() -> button(dialog(), "organization-tag-remove").fire(), true);
        });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && !tagged(clip.id(), "Focus"),
                "tag change did not persist");
        fx(() -> { tab(0); selectSmart(smartId); button(dialog(), "smart-preview").fire(); });
        await(8_000, () -> fxValue(() -> resultIds().equals(List.of(studio.id()))),
                "dynamic result did not refresh after tag change");
        fx(() -> { text("smart-min-duration").setText("121"); fireSmartSave(); });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && client.load().smartPlaylists().stream()
                .filter(item -> item.id().equals(smartId))
                .anyMatch(item -> Integer.valueOf(121).equals(item.rules().minDuration())),
                "playback-time rule edit did not persist");
        await(8_000, () -> fxValue(() -> resultIds().isEmpty()), "rule edit did not clear result");
        fx(() -> {
            check(audio() == playing, "audio instance was replaced by organizer refresh");
            check(studio.id().equals(loadedId()), "loaded resource changed after refresh");
            check(queue().tracks().stream().map(Track::id).toList().equals(queueIds),
                    "queue snapshot changed after refresh/rule/tag edits");
            check(queue().index() == 0, "queue index changed after refresh/rule/tag edits");
            check(audio().position().toSeconds() >= position - .2, "playback position reset after organizer edits");
        });
        System.out.println("PASS actual local audio, preferred-version bypass, stable loaded audio/position/queue");

        phase = "snapshot ordinary playlist and folder browsing";
        fx(() -> { text("smart-min-duration").setText("1"); fireSmartSave(); });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && client.load().smartPlaylists().stream()
                .filter(item -> item.id().equals(smartId))
                .anyMatch(item -> Integer.valueOf(1).equals(item.rules().minDuration())),
                "snapshot rule restore did not persist");
        await(8_000, () -> fxValue(() -> resultIds().equals(List.of(studio.id()))),
                "restored snapshot rule not reflected");
        fx(() -> button(dialog(), "smart-snapshot").fire());
        await(8_000, () -> fxValue(() -> !dialogBusy())
                && new LibraryClient(service).load().playlists().stream()
                .anyMatch(item -> item.name().equals("Focus 长音频（快照）")), "ordinary snapshot not persisted");
        LibraryClient.Playlist snapshot = new LibraryClient(service).load().playlists().stream()
                .filter(item -> item.name().equals("Focus 长音频（快照）")).findFirst().orElseThrow();
        snapshotId = snapshot.id();
        check(snapshot.tracks().stream().map(Track::id).toList().equals(List.of(studio.id())),
                "ordinary snapshot did not freeze selected resource IDs");
        await(8_000, () -> fxValue(() -> appPlaylistIds().contains(snapshotId)),
                "ordinary snapshot did not reach the main library view");
        Object beforeFolderRefresh = fxValue(this::dialogState);
        fx(() -> field("smartDialog", SmartPlaylistDialog.class).refresh());
        await(8_000, () -> fxValue(() -> dialogState() != beforeFolderRefresh
                && folderItemIds().contains(snapshotId)), "folder view did not refresh ordinary snapshot");
        fx(() -> {
            tab(2);
            text("organization-folder-name").setText("夜间收藏");
            button(dialog(), "organization-folder-save").fire();
        });
        await(8_000, () -> fxValue(() -> !dialogBusy())
                && client.load().folders().stream().anyMatch(item -> item.name().equals("夜间收藏")),
                "folder creation did not persist");
        folderId = client.load().folders().stream()
                .filter(item -> item.name().equals("夜间收藏")).findFirst().orElseThrow().id();
        fx(() -> { chooseCombo("organization-folder-assignment", "夜间收藏");
            selectFolderItem(snapshotId); button(dialog(), "organization-folder-assign").fire(); });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && assigned(snapshotId, folderId),
                "snapshot folder assignment did not persist");
        fx(() -> { chooseCombo("organization-folder-assignment", "夜间收藏");
            selectFolderItem(smartId); button(dialog(), "organization-folder-assign").fire(); });
        await(8_000, () -> fxValue(() -> !dialogBusy()) && assigned(smartId, folderId),
                "smart folder assignment did not persist");
        fx(() -> { chooseCombo("organization-folder-filter", "夜间收藏");
            check(folderItemIds().containsAll(List.of(snapshotId, smartId)), "folder browsing missed assigned playlists"); });
        System.out.println("PASS ordinary snapshot persistence, folder create/assign/browse");

        phase = "folder track batch management uses shared queue action";
        int beforeBatch = fxValue(() -> queue().tracks().size());
        fx(() -> {
            chooseCombo("organization-folder-filter", "全部文件夹");
            selectFolderItem(ordinaryId);
            checkTrackManagement("organization-folder-tracks", true);
        });
        await(3_000, () -> fxValue(() -> queue().tracks().size() == beforeBatch + 2),
                "folder batch queue callback did not append both selected songs");
        fx(() -> {
            List<String> appended = queue().tracks().subList(beforeBatch, beforeBatch + 2).stream().map(Track::id).toList();
            check(appended.equals(List.of(live.id(), clip.id())), "folder batch did not use both selected songs: " + appended);
        });
        System.out.println("PASS smart results/folder tracks shared batch controls, multi-selection and queue callback");

        phase = "related resources explicit version entry";
        fx(() -> {
            tab(3);
            check(!list("organization-related-list").getItems().isEmpty(), "same-name versions absent from candidates");
            list("organization-related-list").getSelectionModel().select(0);
            button(dialog(), "organization-open-version").fire();
        });
        await(8_000, () -> fxValue(() -> versionDialogOrNull() != null),
                "explicit related-resource version entry did not open");
        fx(() -> ((Button) versionDialogOrNull().lookupButton(ButtonType.CLOSE)).fire());

        phase = "light narrow organizer and persisted reload";
        fx(() -> ((Button) dialog().lookupButton(ButtonType.CLOSE)).fire());
        fx(() -> { button(stage.getScene().getRoot(), "theme-button").fire();
            button(stage.getScene().getRoot(), "organize-favorites").fire(); });
        await(8_000, () -> fxValue(() -> dialogOrNull() != null
                && list("smart-playlist-list").getItems().stream()
                .anyMatch(item -> ((SmartPlaylistClient.SmartPlaylist) item).id().equals(smartId))),
                "saved smart playlist not visible after reopening");
        fx(() -> { dialog().getScene().getWindow().setWidth(480); });
        pause(300);
        fx(() -> { screenshot(dialog(), "smart-light-480.png"); checkFit(dialog()); });
        check(client.load().smartPlaylists().stream().anyMatch(item -> item.id().equals(smartId)),
                "saved smart playlist not persisted after reopening");
        check(new LibraryClient(service).load().playlists().stream()
                .anyMatch(item -> item.id().equals(snapshotId)), "ordinary snapshot not persisted after reopening");
        System.out.println("PASS dark/light 480px organizer, 860x600 main window, persisted reopen");

        phase = "folder delete cancellation and explicit confirmation";
        fx(() -> {
            tab(2);
            chooseCombo("organization-folder-filter", "夜间收藏");
            respondThen(() -> button(dialog(), "organization-folder-delete").fire(), false);
        });
        check(client.load().folders().stream().anyMatch(folder -> folder.id().equals(folderId))
                && assigned(snapshotId, folderId) && assigned(smartId, folderId),
                "cancelled folder deletion changed folder or assignments");
        fx(() -> respondThen(() -> button(dialog(), "organization-folder-delete").fire(), true));
        await(8_000, () -> fxValue(() -> !dialogBusy())
                && client.load().folders().stream().noneMatch(folder -> folder.id().equals(folderId)),
                "confirmed folder deletion did not persist");
        check(client.load().smartPlaylists().stream().anyMatch(item -> item.id().equals(smartId))
                && new LibraryClient(service).load().playlists().stream().anyMatch(item -> item.id().equals(snapshotId)),
                "folder deletion removed an assigned playlist");
        check(!assigned(snapshotId, folderId) && !assigned(smartId, folderId),
                "confirmed folder deletion left stale assignments");

        phase = "smart playlist delete cancellation and explicit confirmation";
        fx(() -> { tab(0); selectSmart(smartId);
            respondThen(() -> button(dialog(), "smart-delete").fire(), false); });
        check(client.load().smartPlaylists().stream().anyMatch(item -> item.id().equals(smartId)),
                "cancelled smart playlist deletion discarded its rule");
        fx(() -> respondThen(() -> button(dialog(), "smart-delete").fire(), true));
        await(8_000, () -> fxValue(() -> !dialogBusy())
                && client.load().smartPlaylists().stream().noneMatch(item -> item.id().equals(smartId)),
                "confirmed smart playlist deletion did not persist");
        check(new LibraryClient(service).load().playlists().stream().anyMatch(item -> item.id().equals(snapshotId)),
                "smart playlist deletion removed an ordinary snapshot");
        System.out.println("PASS default cancel, tag/folder/smart deletion cancellation and confirmed persistence");
    }

    private void prepareFixture() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("build") && !Files.exists(root.resolve("library.json")),
                "fresh isolated profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH",
                "LUMEN_AI_SETTINGS_PATH")) {
            Path value = Path.of(required(key)).toAbsolutePath().normalize();
            check(value.equals(root) || root.equals(value.getParent()), key + " outside isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null,
                "personal API keys must not enter this smoke");
        service = URI.create(required("LUMEN_SERVICE_URL"));
        check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost())
                && service.getPort() > 0, "isolated loopback demo service required");
        client = new SmartPlaylistClient(service);
        List<Track> demo = new MusicClient(service).search("");
        check(demo.size() == 3 && demo.stream().allMatch(track -> "demo".equals(track.source())),
                "real demo catalog is missing");
        Path wav = root.resolve("smart-local-120s.wav");
        writeLongWav(wav);
        Track source = new Track("smart-local-source", "同名夜曲", "Fixture", 120, "local", wav.toUri());
        studio = new Track("smart-studio", "同名夜曲", "Fixture", 120, "local", wav.toUri());
        live = new Track("smart-live", "同名夜曲", "Fixture", 120, "local", wav.toUri());
        clip = Track.segment(source, "深夜片段", "Fixture", 5_000, 95_000);
        unknown = new Track("smart-unknown", "未知时长", "Fixture", 0, "local", wav.toUri());
        LibraryClient library = new LibraryClient(service);
        check(library.load().favorites().isEmpty(), "demo library not empty");
        library.favorite(studio); library.favorite(clip); library.favorite(unknown);
        ordinaryId = library.createPlaylist("普通测试歌单").playlists().get(0).id();
        for (Track track : List.of(studio, live, clip, unknown, demo.get(0)))
            library.addToPlaylist(ordinaryId, track);
        VersionCatalog catalog = new VersionCatalogClient(service).create("同名夜曲", "两个资源版本",
                List.of(new VersionCatalog.Version("录音室", "Fixture", studio),
                        new VersionCatalog.Version("现场", "Fixture", live)));
        VersionCatalog.Song song = catalog.findByResourceId(studio.id()).orElseThrow();
        String liveVersion = catalog.versionByResourceId(live.id()).orElseThrow().id();
        new VersionCatalogClient(service).update(song.id(), null, null, liveVersion);
        check(client.load().entries().stream().map(entry -> entry.track().id()).toList()
                .containsAll(List.of(studio.id(), live.id(), clip.id(), unknown.id(), demo.get(0).id())),
                "organization fixture did not index all resource sources");
    }

    private static void writeLongWav(Path path) throws Exception {
        int rate = 11_025, seconds = 120;
        byte[] pcm = new byte[rate * seconds * 2];
        for (int frame = 0; frame < rate * seconds; frame++) {
            short value = (short) (1600 * Math.sin(frame * 2 * Math.PI * 220 / rate));
            pcm[frame * 2] = (byte) value;
            pcm[frame * 2 + 1] = (byte) (value >>> 8);
        }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, rate * seconds)) {
            check(AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile()) > 0,
                    "long local WAV was not generated");
        }
    }

    private boolean tagged(String id, String tag) throws Exception {
        return client.load().entries().stream().filter(entry -> entry.track().id().equals(id))
                .anyMatch(entry -> entry.tags().contains(tag));
    }
    private void respondThen(Runnable action, boolean accepted) {
        Platform.runLater(() -> {
            DialogPane confirmation = Window.getWindows().stream().filter(Window::isShowing)
                    .filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance)
                    .map(DialogPane.class::cast).filter(pane -> "destructive-confirmation".equals(pane.getId()))
                    .findFirst().orElseThrow(() -> new AssertionError("destructive confirmation did not open"));
            Button cancel = button(confirmation, "destructive-cancel");
            Button confirm = button(confirmation, "destructive-confirm");
            check(cancel.isDefaultButton() && !confirm.isDefaultButton(), "confirmation did not default to cancel");
            (accepted ? confirm : cancel).fire();
        });
        action.run();
    }
    private void checkTrackManagement(String id, boolean enqueue) {
        ListView<Track> view = list(id);
        check(view.getSelectionModel().getSelectionMode() == SelectionMode.MULTIPLE, "organizer tracks do not support multiple selection: " + id);
        check(view.getParent() instanceof javafx.scene.layout.VBox, "track list body is not accessible: " + id);
        javafx.scene.layout.VBox body = (javafx.scene.layout.VBox) view.getParent();
        int position = body.getChildren().indexOf(view);
        check(position > 0 && body.getChildren().get(position - 1).lookup("#" + id + "-batch-mode") != null,
                "shared batch controls are not immediately before track list: " + id);
        check(body.lookupAll("#" + id + "-batch-mode").size() == 1, "duplicate batch controls: " + id);
        ((ButtonBase) node(dialog(), id + "-batch-mode")).fire();
        ((CheckBox) node(dialog(), id + "-select-all")).fire();
        check(view.getSelectionModel().getSelectedItems().size() == view.getItems().size(), "organizer select-all callback failed: " + id);
        if (enqueue) {
            view.getSelectionModel().clearSelection();
            view.getSelectionModel().selectIndices(0, 2);
        }
        check(view.getSelectionModel().getSelectedItems().size() == 2, "organizer batch fixture must select two songs: " + id);
        button(dialog(), id + "-batch-more").fire();
        ContextMenu menu = Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(Window::isShowing).findFirst().orElseThrow(() -> new AssertionError("organizer batch menu did not open"));
        for (String menuId : List.of("batch-menu-favorite", "batch-menu-playlist", "batch-menu-next", "batch-menu-enqueue"))
            check(menu.getItems().stream().anyMatch(item -> menuId.equals(item.getId())), "shared batch command missing: " + menuId);
        check(menu.getItems().stream().noneMatch(item -> "batch-menu-remove".equals(item.getId())),
                "generated organizer view exposes a misleading remove command: " + id);
        MenuItem favorite = menu.getItems().stream().filter(item -> "batch-menu-favorite".equals(item.getId())).findFirst().orElseThrow();
        check(favorite.getText().contains("2"), "batch menu callback did not receive two selected songs");
        if (enqueue) menu.getItems().stream().filter(item -> "batch-menu-enqueue".equals(item.getId())).findFirst().orElseThrow().fire();
        menu.hide();
        ((ButtonBase) node(dialog(), id + "-batch-mode")).fire();
        check(view.getSelectionModel().getSelectedItems().isEmpty(), "leaving batch mode did not clear selection: " + id);
    }
    private boolean assigned(String playlistId, String expectedFolder) throws Exception {
        return client.load().assignments().stream().anyMatch(item -> item.playlistId().equals(playlistId)
                && item.folderId().equals(expectedFolder));
    }
    private void selectEntries(String... ids) {
        ListView<SmartPlaylistClient.Entry> view = list("organization-track-list");
        view.getSelectionModel().clearSelection();
        for (String id : ids) {
            int index = -1;
            for (int i = 0; i < view.getItems().size(); i++)
                if (view.getItems().get(i).track().id().equals(id)) { index = i; break; }
            check(index >= 0, "missing tag entry " + id);
            view.getSelectionModel().select(index);
        }
    }
    private void selectSmart(String id) {
        ListView<SmartPlaylistClient.SmartPlaylist> view = list("smart-playlist-list");
        view.getItems().stream().filter(item -> item.id().equals(id)).findFirst()
                .ifPresentOrElse(view.getSelectionModel()::select, () -> { throw new AssertionError("missing smart item"); });
    }
    private void selectFolderItem(String id) {
        ListView<?> view = list("organization-folder-playlists");
        Object item = view.getItems().stream().filter(value -> id.equals(recordId(value))).findFirst().orElseThrow();
        selectItem(view, item);
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static void selectItem(ListView view, Object item) {
        view.getSelectionModel().select(item);
    }
    private List<String> folderItemIds() {
        return list("organization-folder-playlists").getItems().stream().map(this::recordId).toList();
    }
    @SuppressWarnings("unchecked") private List<String> appPlaylistIds() {
        List<LibraryClient.Playlist> values = field("playlists", List.class);
        return values.stream().map(LibraryClient.Playlist::id).toList();
    }
    private String recordId(Object value) {
        try { return (String) value.getClass().getDeclaredMethod("id").invoke(value); }
        catch (ReflectiveOperationException error) {
            try {
                var method = value.getClass().getDeclaredMethod("id");
                method.setAccessible(true);
                return (String) method.invoke(value);
            } catch (ReflectiveOperationException nested) { throw new AssertionError(nested); }
        }
    }
    private void chooseCombo(String id, String label) {
        ComboBox<?> box = combo(id);
        Object item = box.getItems().stream().filter(value -> label.equals(value.toString()))
                .findFirst().orElseThrow();
        setCombo(box, item);
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static void setCombo(ComboBox box, Object item) {
        box.setValue(item);
    }
    private List<String> resultIds() {
        ListView<Track> view = list("smart-preview-results");
        return view.getItems().stream().map(Track::id).toList();
    }
    private String selectedSmartId() {
        ListView<SmartPlaylistClient.SmartPlaylist> view = list("smart-playlist-list");
        var selected = view.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.id();
    }
    private void fireSmartSave() {
        check(!dialogBusy(), "smart save started while another mutation was in progress");
        button(dialog(), "smart-save").fire();
        check(dialogBusy(), "smart save was ignored: "
                + ((Label) node(dialog(), "organization-feedback")).getText());
    }
    private List<String> selectedEntryIds() {
        ListView<SmartPlaylistClient.Entry> view = list("organization-track-list");
        return view.getSelectionModel().getSelectedItems().stream()
                .map(item -> item.track().id()).toList();
    }
    private Object dialogState() { return dialogField("state"); }
    private boolean dialogBusy() { return (Boolean) dialogField("busy"); }
    private Object dialogField(String name) {
        try {
            Field value = SmartPlaylistDialog.class.getDeclaredField(name);
            value.setAccessible(true);
            return value.get(field("smartDialog", SmartPlaylistDialog.class));
        } catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect organizer " + name, error); }
    }

    private void tab(int index) { ((TabPane) node(dialog(), "organization-tabs")).getSelectionModel().select(index); }
    private TextField text(String id) { return (TextField) node(dialog(), id); }
    @SuppressWarnings("unchecked") private ComboBox<String> combo(String id) { return (ComboBox<String>) node(dialog(), id); }
    @SuppressWarnings("unchecked") private <T> ListView<T> list(String id) { return (ListView<T>) node(dialog(), id); }
    private static Node node(Node root, String id) {
        Node value = root.lookup("#" + id);
        check(value != null, "missing UI control #" + id);
        return value;
    }
    private static Button button(Node root, String id) { return (Button) node(root, id); }
    private DialogPane dialogOrNull() {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()
                && window.getScene() != null && window.getScene().lookup("#smart-playlist-dialog") != null)
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElse(null);
    }
    private DialogPane versionDialogOrNull() {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()
                && window.getScene() != null && window.getScene().lookup("#version-dialog") != null)
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElse(null);
    }
    private DialogPane dialog() {
        DialogPane value = dialogOrNull();
        check(value != null, "organizer is closed");
        return value;
    }
    private void checkFit(DialogPane pane) {
        pane.applyCss(); pane.layout();
        double right = pane.getScene().getWidth() + 1;
        Node header = pane.lookup("#organization-navigation");
        check(header != null && header.isVisible() && header.getBoundsInParent().getHeight() >= 24,
                "organizer navigation is hidden or collapsed");
        for (int index = 0; index < 4; index++) {
            ButtonBase entry = (ButtonBase) node(pane, "organization-nav-" + index);
            check(entry.isVisible() && !entry.getText().isBlank(), "organizer navigation entry missing: " + index);
            Bounds bounds = entry.localToScene(entry.getBoundsInLocal());
            check(bounds.getMinX() >= -1 && bounds.getMaxX() <= right
                    && bounds.getMinY() >= 0 && bounds.getMaxY() <= pane.getScene().getHeight(),
                    "organizer navigation clipped: " + entry.getText() + " " + bounds);
            check(entry.getWidth() + 1 >= entry.prefWidth(-1), "organizer navigation text is truncated: " + entry.getText());
        }
        for (String id : List.of("organization-navigation", "organization-tabs", "organization-feedback", "smart-playlist-list",
                "smart-preview-results", "smart-name", "smart-min-duration", "smart-max-duration", "smart-tags-all")) {
            Node item = pane.lookup("#" + id);
            if (item == null || !item.isVisible() || item.getScene() == null) continue;
            Bounds bounds = item.localToScene(item.getBoundsInLocal());
            check(bounds.getMinX() >= -1 && bounds.getMaxX() <= right,
                    "horizontal overflow #" + id + ": " + bounds + " > " + right);
        }
    }
    private void screenshot(Node rootNode, String filename) throws Exception {
        rootNode.applyCss();
        if (rootNode instanceof javafx.scene.Parent parent) parent.layout();
        WritableImage image = rootNode.getScene().snapshot(null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path path = root.resolve(filename);
        check(ImageIO.write(png, "png", path.toFile()) && Files.size(path) > 1_000,
                "screenshot was not saved: " + filename);
        if (filename.startsWith("smart-")) {
            int dark = 0;
            for (int y = 8; y < Math.min(38, png.getHeight()); y++)
                for (int x = 20; x < png.getWidth() - 20; x++) {
                    int rgb = png.getRGB(x, y);
                    if (((rgb >>> 16) & 255) < 190 && ((rgb >>> 8) & 255) < 190
                            && (rgb & 255) < 190) dark++;
                }
            check(dark > 80, "organizer tab header screenshot is visually blank: " + filename);
        }
        System.out.println("SCREENSHOT: " + path);
    }
    private String loadedId() { Track value = field("loadedTrack", Track.class); return value == null ? null : value.id(); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    @SuppressWarnings("unchecked") private List<Track> favorites() { return field("favorites", List.class); }
    private <T> T field(String name, Class<T> type) {
        try {
            Field field = LumenFxApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(app));
        } catch (ReflectiveOperationException error) { throw new AssertionError("cannot inspect " + name, error); }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new AssertionError("missing " + key);
        return value;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private void await(long millis, CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            pause(70);
        }
        throw new AssertionError(message + "; feedback=" + fxValue(() -> {
            DialogPane pane = dialogOrNull();
            return pane == null ? "closed" : ((javafx.scene.control.Label) pane.lookup("#organization-feedback")).getText();
        }));
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (app != null) app.stop();
            for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
