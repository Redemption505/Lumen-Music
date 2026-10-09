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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;

/** Real UI/audio acceptance. Only the historical seed is synthetic. */
public final class MusicDiarySmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private DiaryClient client;
    private String phase = "startup";
    private Track old;
    private Track fresh;
    private String playlistId;
    private final TestClock eventClock = new TestClock();

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL diary phase: " + phase);
                error.printStackTrace(System.err);
                finish(1);
            }
        }, "diary-smoke-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        root = Path.of(System.getenv("LOCALAPPDATA"));
        URI service = URI.create(System.getenv("LUMEN_SERVICE_URL"));
        client = new DiaryClient(service);
        var fixture = new LibraryClient(service).load().playlists().stream()
                .filter(item -> item.name().equals("日记验收歌单")).findFirst().orElseThrow();
        playlistId = fixture.id();
        old = fixture.tracks().get(0); fresh = fixture.tracks().get(1);
        fx(() -> {
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            stage.setWidth(1280); stage.setHeight(800);
        });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX()));
        await(15_000, () -> fxValue(() -> picker().getItems().stream()
                .anyMatch(item -> item.id().equals(playlistId))), "fixture library not loaded");
        var seed = client.load();
        check(seed.summary().unknownFirstCount() == 1, "legacy first date was invented");
        check(seed.section("weekly-first").count() == 1, "last-week seed not represented");
        check(seed.section("monthly-playlists").count() == 0, "playlist listening invented by membership");
        check(seed.section("recent").entries().stream().anyMatch(item -> item.track().id().equals("diary-legacy")),
                "legacy last-played evidence not visible");
        fx(() -> {
            tabs().getSelectionModel().select(4);
            picker().setValue(picker().getItems().stream().filter(item -> item.id().equals(playlistId)).findFirst().orElseThrow());
            list("playlist-tracks").getSelectionModel().clearAndSelect(0);
            list("playlist-tracks").fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8,
                    MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, true, false, false, null));
        });
        await(15_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING
                && audio().position().toSeconds() > .5), "actual WAV did not start");
        AudioPlayer audio = fxValue(this::audio);
        var queue = fxValue(() -> queue().snapshot());
        long load = fxValue(() -> field("loadVersion", Long.class));
        phase = "diary and private edits during actual playback";
        fx(() -> {
            installEventClock();
            button("nav-diary").fire();
            check(!node("pet-event-notice").isVisible(), "replay pet event preceded effective listening");
        });
        await(8_000, () -> fxValue(() -> ((Label) node("diary-feedback")).getText().equals("已更新")), "diary not refreshed");
        fx(() -> field("diaryPane", DiaryPane.class).editTrackNote(old));
        fx(() -> { ((TextArea) node("diary-note-input")).setText("雨天重新听见的旋律 & 私人回忆"); button("diary-note-save").fire(); });
        await(8_000, () -> client.load().notes().stream().anyMatch(note -> note.text().contains("私人回忆")), "note not saved");
        fx(() -> {
            ((TextArea) node("diary-note-input")).setText("尚未保存的草稿");
            field("diaryPane", DiaryPane.class).refresh();
        });
        pause(600);
        fx(() -> check(((TextArea) node("diary-note-input")).getText().equals("尚未保存的草稿"), "refresh overwrote draft"));
        String savedNote = client.load().notes().stream().filter(note -> note.track().id().equals(old.id()))
                .findFirst().orElseThrow().text();
        check(savedNote.contains("私人回忆"), "draft preservation changed the persisted note");
        // Finish the deliberate unsaved-draft fixture before testing row-menu reload of saved data.
        fx(() -> { ((TextArea) node("diary-note-input")).setText(savedNote); button("diary-note-save").fire(); });
        await(4_000, () -> fxValue(() -> !button("diary-note-save").isDisabled()
                && ((Label) node("diary-feedback")).getText().equals("已保存备注"))
                && client.load().notes().stream().anyMatch(note -> note.track().id().equals(old.id()) && note.text().equals(savedNote)),
                "draft fixture did not finish saving the original note content");
        fx(() -> {
            invoke("setLyrics", new Class<?>[] {List.class, String.class}, List.of(
                    new Lyrics.Line(0, "第一句"), new Lyrics.Line(4_000, "被记住的第二句"),
                    new Lyrics.Line(7_000, "第三句")), "Synthetic fixture lyrics");
            ListView<String> lyrics = field("lyricsView", ListView.class);
            lyrics.getSelectionModel().clearAndSelect(1);
            ContextMenu menu = (ContextMenu) invoke("lyricFragmentMenu", new Class<?>[] {ListView.class, int.class, int.class}, lyrics, 1, 0);
            menu.show(button("nav-diary"), Side.RIGHT, 0, 0);
            menu.getItems().stream().filter(item -> "lyric-menu-save".equals(item.getId())).findFirst().orElseThrow().fire();
            menu.hide();
        });
        await(8_000, () -> client.load().lyricFavorites().size() == 1, "lyric fragment not persisted");
        var lyric = client.load().lyricFavorites().get(0);
        check(lyric.track().id().equals(old.id()) && lyric.positionMillis() == 4_000
                && lyric.text().equals("被记住的第二句"), "lyric provenance or timing wrong");
        fx(() -> check(audio() == audio && queue().snapshot().equals(queue) && field("loadVersion", Long.class) == load,
                "diary edits interrupted playback"));
        System.out.println("PASS real playback remains unchanged during diary, notes, refresh and lyric menu save");

        phase = "qualified real listening and playlist provenance";
        await(35_000, () -> client.load().section("reheard").entries().stream().anyMatch(item -> item.track().id().equals(old.id())),
                "real WAV never qualified as old-favorite replay");
        fx(() -> {
            check(node("pet-event-notice").isVisible(), "qualified replay did not display compact pet notice");
            screenshot("qualified-replay-pet.png");
            field("playButton", Button.class).fire();
        });
        await(3_000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "pause after qualification failed");
        var qualified = client.load();
        check(qualified.section("monthly-playlists").entries().stream()
                .anyMatch(item -> playlistId.equals(item.playlistId()) && item.playCount() == 1), "actual playlist context not counted");
        check(qualified.summary().thisMonthPlays() == seed.summary().thisMonthPlays() + 1, "incomplete/duplicate listening counted");
        System.out.println("PASS effective real listening, first-entry callback, old-favorite replay and actual playlist context");

        phase = "row note, themes and narrow layout";
        fx(() -> {
            button("nav-playlists").fire();
            list("playlist-tracks").applyCss(); list("playlist-tracks").layout();
            @SuppressWarnings("unchecked") ListCell<Track> cell = (ListCell<Track>) list("playlist-tracks").lookupAll(".list-cell").stream()
                    .filter(ListCell.class::isInstance).map(ListCell.class::cast).filter(item -> item.getIndex() == 0 && !item.isEmpty())
                    .findFirst().orElseThrow();
            ContextMenu menu = cell.getContextMenu();
            menu.show(cell, Side.RIGHT, 0, 0);
            menu.getItems().stream().filter(item -> "track-menu-diary-note".equals(item.getId())).findFirst().orElseThrow().fire();
            menu.hide();
            check(((TextArea) node("diary-note-input")).getText().contains("私人回忆"), "row note did not load saved data");
        });
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> {
                while (field("themeMode", ThemeController.Mode.class) != mode) button("theme-button").fire();
                stage.setWidth(860); stage.setHeight(600);
                field("diaryPane", DiaryPane.class).tabs().getSelectionModel().select(0);
            });
            pause(500);
            fx(() -> {
                layout(); fits("diary-pane"); fits("diary-summary");
                Node header = field("diaryPane", DiaryPane.class).tabs().lookup(".tab-header-area");
                check(header != null && header.isVisible() && header.getLayoutBounds().getHeight() >= 34,
                        "diary view tabs inaccessible");
                screenshot(mode.name().toLowerCase() + "-overview-860.png");
            });
            fx(() -> { field("diaryPane", DiaryPane.class).tabs().getSelectionModel().select(1); layout();
                fits("diary-note-input"); fits("diary-note-save"); screenshot(mode.name().toLowerCase() + "-note-860.png"); });
            fx(() -> { field("diaryPane", DiaryPane.class).tabs().getSelectionModel().select(2); layout();
                screenshot(mode.name().toLowerCase() + "-lyrics-860.png"); });
        }
        fx(() -> { stage.setWidth(1280); stage.setHeight(800); layout(); screenshot("diary-1280.png"); });
        System.out.println("PASS all five themes and narrow diary tabs; row-menu note entry");

        phase = "pet milestones and quiet settings";
        fx(() -> {
            eventClock.advanceSeconds(600);
            button("nav-playlists").fire();
            invoke("toggleTrackFavorite", new Class<?>[] {Track.class}, fresh);
        });
        await(8_000, () -> new LibraryClient(service).load().favorites().stream().anyMatch(item -> item.id().equals(fresh.id())), "new favorite not saved");
        await(4_000, () -> fxValue(() -> node("pet-event-notice").isVisible()), "new-favorite milestone not shown");
        fx(() -> {
            PetCompanion pet = field("pet", PetCompanion.class);
            check(!pet.onMusicEvent(PetCompanion.Event.NEW_FAVORITE, fresh.title()), "duplicate milestone bypassed cooldown");
            pet.setSpecies(PetCompanion.Species.HIDDEN);
            eventClock.advanceSeconds(600);
            check(!pet.onMusicEvent(PetCompanion.Event.NEW_FAVORITE, "hidden"), "hidden pet emitted milestone");
            pet.setSpecies(PetCompanion.Species.BLACK_CAT);
            button("nav-focus").fire();
            field("focusPane", FocusPane.class).focusSession().setDuration(java.time.Duration.ofMillis(500));
            field("focusPane", FocusPane.class).focusSession().start();
        });
        await(3_000, () -> fxValue(() -> ((Label) field("status", Label.class)).getText().equals("专注完成")), "focus not completed");
        fx(() -> {
            PetCompanion pet = field("pet", PetCompanion.class);
            check(pet.isQuiet() && node("pet-event-notice").isVisible(), "focus completion quiet notice missing");
            pet.setNightMode(true); pet.setReducedMotion(true);
            check(pet.isNightMode() && pet.isReducedMotion(), "night/reduced flags not respected");
            check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "pet/focus changed paused playback");
            button("focus-back").fire(); button("nav-diary").fire();
        });
        System.out.println("PASS real saved favorite, focus completion and compact pet notices; hidden/cooldown/night/reduced");

        phase = "desktop pet milestone while minimized";
        fx(() -> {
            eventClock.advanceSeconds(1_800);
            setField("petOnDesktopMinimized", true);
            stage.setIconified(true);
            field("updatePetPlacement", Runnable.class).run();
            check(field("desktopPet", DesktopPetWindow.class).isShowing(), "desktop pet not shown");
            invoke("toggleTrackFavorite", new Class<?>[] {Track.class}, seed.section("weekly-first").entries().get(0).track());
        });
        await(8_000, () -> fxValue(() -> desktopNoticeWindow() != null && desktopNoticeWindow().getWidth() > 0
                && desktopNoticeWindow().getHeight() > 20), "saved favorite did not reach a laid-out desktop pet bubble");
        fx(() -> {
            Window popup = desktopNoticeWindow();
            screenshot("desktop-pet-notice.png", popup.getScene());
            check(popup.getWidth() <= 240 && popup.getHeight() > 20,
                    "desktop bubble layout invalid: " + popup.getWidth() + " x " + popup.getHeight());
            button("sidebar-settings").fire();
            @SuppressWarnings("unchecked") ComboBox<PetCompanion.Species> picker = (ComboBox<PetCompanion.Species>) node("pet-picker");
            picker.setValue(PetCompanion.Species.HIDDEN);
            check(desktopNoticeWindow() == null && !field("desktopPet", DesktopPetWindow.class).isShowing(),
                    "hidden desktop pet left a bubble behind");
            stage.setIconified(false); stage.show(); stage.toFront();
            picker.setValue(PetCompanion.Species.BLACK_CAT);
            button("nav-diary").fire();
            check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "desktop pet changed paused playback");
        });
        System.out.println("PASS real favorite reaches minimized desktop pet; hiding clears popup without changing audio");

        phase = "backup restore, local export and restart";
        fx(() -> invoke("finishListening", new Class<?>[0]));
        confirmationChecks(service);
        phase = "backup restore, local export and restart";
        String export = DiaryPane.exportText(client.load(), true);
        check(export.contains("私人回忆") && export.contains("被记住的第二句"), "manual export lost diary content");
        check(!export.contains("file:///") && !export.contains("apiKey"), "export leaked source path or config");
        Files.writeString(root.resolve("diary-export.md"), export, StandardCharsets.UTF_8);
        BackupClient backups = new BackupClient(service);
        Path zip = root.resolve("diary-backup.zip");
        backups.export(zip); backups.inspect(zip);
        client.saveNote(old, ""); client.deleteLyric(lyric.id());
        check(client.load().notes().isEmpty() && client.load().lyricFavorites().isEmpty(), "delete did not persist");
        backups.restore(zip);
        var restored = client.load();
        check(restored.notes().size() == 1 && restored.lyricFavorites().size() == 1
                && restored.summary().thisMonthPlays() == qualified.summary().thisMonthPlays(), "ZIP lost diary data or events");
        fx(() -> {
            field("diaryPane", DiaryPane.class).editTrackNote(old);
            ((TextArea) node("diary-note-input")).setText("雨天重新听见的旋律 & 私人回忆；保存后立即关闭");
            button("diary-note-save").fire();
            app.stop(); stage.hide();
        });
        check(client.load().notes().stream().anyMatch(note -> note.text().contains("保存后立即关闭")),
                "close cancelled a submitted note save");
        fx(() -> {
            stage = new Stage(); app = new LumenFxApp();
            app.startUi(stage, List.of()); button("nav-diary").fire();
        });
        await(8_000, () -> fxValue(() -> ((Label) node("diary-feedback")).getText().equals("已更新")), "restart diary not loaded");
        fx(() -> {
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING, "restart auto-played");
            field("diaryPane", DiaryPane.class).showLyricsTab(); layout(); screenshot("restart-restored.png");
        });
        System.out.println("PASS local Markdown export, HTTP delete/ZIP restore, UI restart and no autoplay");
    }

    private void confirmationChecks(URI service) throws Exception {
        phase = "GUI note/lyric confirmation cancel and targeted delete";
        long started = System.nanoTime();
        BackupClient backup = new BackupClient(service);
        Path original = root.resolve("diary-before-confirmation-checks.zip");
        backup.export(original);
        DiaryClient.Diary baseline = client.load();
        LibraryClient.Snapshot libraryBaseline = new LibraryClient(service).load();
        String noteText = baseline.notes().stream().filter(note -> note.track().id().equals(old.id()))
                .findFirst().orElseThrow().text();
        DiaryClient.LyricFavorite targetLyric = baseline.lyricFavorites().stream()
                .filter(lyric -> lyric.track().id().equals(old.id())).findFirst().orElseThrow();
        client.saveNote(fresh, "确认验收期间必须保留的另一条备注");
        client.saveLyric(fresh, 1_200, "确认验收期间必须保留的另一条歌词");
        refreshDiary();

        fx(() -> { field("diaryPane", DiaryPane.class).editTrackNote(old);
            ((TextArea) node("diary-note-input")).setText(noteText); });
        DiaryClient.Diary before = client.load();
        cancelWithoutWrite(service, () -> button("diary-note-delete").fire(), old.title(), "1 条私人备注");
        fx(() -> respond(() -> button("diary-note-delete").fire(), true, old.title(), "1 条私人备注"));
        await(4_000, () -> client.load().notes().stream().noneMatch(note -> note.track().id().equals(old.id())), "confirmed single note deletion did not persist");
        checkDiary(before, client.load(), before.notes().stream().filter(note -> !note.track().id().equals(old.id())).toList(), before.lyricFavorites(), "single note delete changed unrelated data");

        client.saveNote(old, noteText); refreshDiary();
        fx(() -> { field("diaryPane", DiaryPane.class).editTrackNote(old); ((TextArea) node("diary-note-input")).clear(); });
        before = client.load();
        cancelWithoutWrite(service, () -> button("diary-note-save").fire(), old.title(), "1 条私人备注");
        fx(() -> check(((TextArea) node("diary-note-input")).getText().isBlank(), "cancel changed the unsaved empty draft"));
        fx(() -> respond(() -> button("diary-note-save").fire(), true, old.title(), "1 条私人备注"));
        await(4_000, () -> client.load().notes().stream().noneMatch(note -> note.track().id().equals(old.id())), "confirmed empty note save did not delete note");
        checkDiary(before, client.load(), before.notes().stream().filter(note -> !note.track().id().equals(old.id())).toList(), before.lyricFavorites(), "empty note save changed unrelated data");

        client.saveNote(old, noteText); refreshDiary();
        fx(() -> field("diaryPane", DiaryPane.class).showLyricsTab());
        before = client.load();
        cancelWithoutWrite(service, () -> lyricDelete(targetLyric.id()).fire(), old.title(), "1 条歌词片段");
        fx(() -> respond(() -> lyricDelete(targetLyric.id()).fire(), true, old.title(), "1 条歌词片段"));
        await(4_000, () -> client.load().lyricFavorites().stream().noneMatch(lyric -> lyric.id().equals(targetLyric.id())), "confirmed single lyric deletion did not persist");
        checkDiary(before, client.load(), before.notes(), before.lyricFavorites().stream().filter(lyric -> !lyric.id().equals(targetLyric.id())).toList(), "single lyric delete changed unrelated data");

        client.saveLyric(old, targetLyric.positionMillis(), targetLyric.text()); refreshDiary();
        fx(() -> prepareDiaryBatch(1));
        before = client.load();
        cancelWithoutWrite(service, () -> batchDelete("diary-batch-delete-notes").fire(), old.title(), "2 条私人备注");
        fx(() -> respond(() -> batchDelete("diary-batch-delete-notes").fire(), true, old.title(), "2 条私人备注"));
        await(4_000, () -> client.load().notes().isEmpty(), "confirmed two-note batch deletion did not persist");
        checkDiary(before, client.load(), List.of(), before.lyricFavorites(), "batch note deletion changed unrelated data");

        fx(() -> prepareDiaryBatch(2));
        before = client.load();
        cancelWithoutWrite(service, () -> batchDelete("diary-batch-delete-lyrics").fire(), old.title(), "2 条歌词片段");
        fx(() -> respond(() -> batchDelete("diary-batch-delete-lyrics").fire(), true, old.title(), "2 条歌词片段"));
        await(4_000, () -> client.load().lyricFavorites().isEmpty(), "confirmed two-lyric batch deletion did not persist");
        checkDiary(before, client.load(), before.notes(), List.of(), "batch lyric deletion changed unrelated data");
        check(libraryBaseline.equals(new LibraryClient(service).load()), "confirmed private deletion changed favorites or playlists");
        fx(() -> screenshot("diary-deletion-confirmed.png"));

        backup.restore(original);
        refreshDiary();
        check(stable(baseline).equals(stable(client.load())), "confirmation fixture restore changed original records or history");
        System.out.println("PASS GUI single note/empty save/single lyric/two-note batch/two-lyric batch: cancel zero writes, explicit targeted delete, default cancel and object counts; elapsed ms="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private void refreshDiary() throws Exception {
        fx(() -> field("diaryPane", DiaryPane.class).refresh());
        await(4_000, () -> fxValue(() -> ((Label) node("diary-feedback")).getText().equals("已更新")), "confirmation fixture UI did not refresh");
    }

    private void cancelWithoutWrite(URI service, Runnable action, String object, String count) throws Exception {
        DiaryClient.Diary before = stable(client.load());
        LibraryClient.Snapshot libraryBefore = new LibraryClient(service).load();
        Path libraryPath = Path.of(System.getenv("LUMEN_LIBRARY_PATH"));
        byte[] bytes = Files.readAllBytes(libraryPath);
        fx(() -> respond(action, false, object, count));
        check(before.equals(stable(client.load())) && libraryBefore.equals(new LibraryClient(service).load())
                && Arrays.equals(bytes, Files.readAllBytes(libraryPath)), "cancelled " + count + " wrote or changed personal records");
    }

    private static DiaryClient.Diary stable(DiaryClient.Diary diary) {
        // generatedAt is a fresh response timestamp, not persisted personal state.
        return new DiaryClient.Diary(diary.zone(), "", diary.summary(), diary.sections(), diary.notes(), diary.lyricFavorites());
    }

    private static void checkDiary(DiaryClient.Diary before, DiaryClient.Diary after,
                                   List<DiaryClient.Note> notes, List<DiaryClient.LyricFavorite> lyrics, String message) {
        check(stable(after).equals(new DiaryClient.Diary(before.zone(), "", before.summary(), before.sections(), notes, lyrics)), message);
    }

    private void respond(Runnable action, boolean accepted, String object, String count) {
        Platform.runLater(() -> {
            DialogPane pane = Window.getWindows().stream().filter(Window::isShowing).filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                    .filter(dialog -> "destructive-confirmation".equals(dialog.getId())).findFirst().orElseThrow();
            Button cancel = (Button) pane.lookup("#destructive-cancel"), confirm = (Button) pane.lookup("#destructive-confirm");
            check(cancel != null && confirm != null && cancel.isDefaultButton() && !confirm.isDefaultButton(), "diary confirmation default is not cancel");
            check(pane.getContentText() != null && pane.getContentText().contains(object) && pane.getContentText().contains(count),
                    "diary confirmation does not clearly identify object/count: " + pane.getContentText());
            (accepted ? confirm : cancel).fire();
        });
        action.run();
    }

    private MenuItem lyricDelete(String id) {
        Parent row = (Parent) node("diary-lyrics").lookupAll("#diary-lyric-row").stream()
                .filter(item -> id.equals(item.getProperties().get("lyricId"))).findFirst().orElseThrow();
        ((Button) row.lookup("#diary-track-more")).fire();
        ContextMenu menu = showingDiaryMenu();
        MenuItem item = menu.getItems().stream().filter(value -> "diary-lyric-delete".equals(value.getId())).findFirst().orElseThrow();
        menu.hide(); return item;
    }

    private void prepareDiaryBatch(int tab) {
        DiaryPane pane = field("diaryPane", DiaryPane.class);
        pane.tabs().getSelectionModel().select(tab);
        if (!((javafx.scene.control.ToggleButton) node("diary-batch-mode")).isSelected()) ((ButtonBase) node("diary-batch-mode")).fire();
        ((CheckBox) node("diary-select-all")).fire();
        check(pane.selectedTracks().size() == 2, "diary batch confirmation fixture did not select exactly two tracks");
    }

    private MenuItem batchDelete(String id) {
        button("diary-batch-more").fire(); ContextMenu menu = showingDiaryMenu();
        MenuItem item = menu.getItems().stream().filter(value -> id.equals(value.getId())).findFirst().orElseThrow();
        menu.hide(); return item;
    }

    private static ContextMenu showingDiaryMenu() {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(Window::isShowing).findFirst().orElseThrow();
    }

    private void installEventClock() throws Exception {
        PetCompanion pet = field("pet", PetCompanion.class);
        Field policy = PetCompanion.class.getDeclaredField("eventPolicy");
        policy.setAccessible(true); policy.set(pet, new PetEventPolicy(eventClock));
    }
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try { Method m = LumenFxApp.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(app, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private <T> T field(String name, Class<T> type) {
        try { Field f = LumenFxApp.class.getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private void setField(String name, Object value) throws Exception {
        Field f = LumenFxApp.class.getDeclaredField(name); f.setAccessible(true); f.set(app, value);
    }
    private Window desktopNoticeWindow() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(window -> window.getScene() != null
                && window.getScene().lookup("#desktop-pet-event-notice") != null).findFirst().orElse(null);
    }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private Node node(String id) { Node n = stage.getScene().lookup("#" + id); check(n != null, "missing " + id); return n; }
    private Button button(String id) { return (Button) node(id); }
    @SuppressWarnings("unchecked") private ListView<Track> list(String id) { return (ListView<Track>) node(id); }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return (ComboBox<LibraryClient.Playlist>) node("playlist-picker"); }
    private TabPane tabs() { return (TabPane) node("main-tabs"); }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private void fits(String id) {
        Node n = node(id); Bounds b = n.localToScene(n.getLayoutBounds());
        check(b.getMinX() >= -1 && b.getMaxX() <= stage.getScene().getWidth() + 1
                && b.getMinY() >= -1 && b.getMaxY() <= stage.getScene().getHeight() + 1, "overflow " + id + " " + b);
    }
    private void screenshot(String name) {
        screenshot(name, stage.getScene());
    }
    private void screenshot(String name, Scene scene) {
        try {
            WritableImage shot = scene.snapshot(null);
            BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            check(ImageIO.write(image, "png", root.resolve(name).toFile()), "screenshot failed");
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void pause(long ms) throws InterruptedException { Thread.sleep(ms); }
    private void await(long ms, CheckedBoolean condition, String message) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000;
        while (System.nanoTime() < end) { if (condition.get()) return; pause(80); }
        throw new AssertionError(message);
    }
    private void fx(Checked operation) throws Exception { fxValue(() -> { operation.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(operation.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(15, TimeUnit.SECONDS);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (app != null) app.stop(); for (Window w : List.copyOf(Window.getWindows())) w.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        System.exit(code);
    }
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
