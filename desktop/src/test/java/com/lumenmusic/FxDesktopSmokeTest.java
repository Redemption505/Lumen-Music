package com.lumenmusic;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Opens the actual JavaFX window and exercises local MP3 playback controls. */
public final class FxDesktopSmokeTest extends Application {
    private LumenFxApp app;
    private Stage stage;
    private PauseTransition timeout;

    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            if ("1".equals(System.getenv("LUMEN_SMOKE_PERSONAL_DATA"))) {
                app = new LumenFxApp();
                app.startUi(stage, java.util.List.of());
                timeout = after(10_000, () -> fail("本地曲库加载超时"));
                after(1800, () -> {
                    try {
                        var list = (ListView<?>) stage.getScene().lookup("#favorites-list");
                        check(list.getItems().size() == Integer.parseInt(
                                System.getenv("LUMEN_SMOKE_EXPECT_FAVORITES")), "收藏数量与原数据不符");
                        check(((Label) stage.getScene().lookup("#library-status")).getText()
                                .equals("个人曲库已载入"), "曲库未完成加载");
                        ((Button) stage.getScene().lookup("#nav-favorites")).fire();
                        savePreview(stage.getScene().snapshot(null), "personal-data-loaded.png");
                        System.out.println("PASS: personal favorites loaded in actual window without playback or writes");
                        timeout.stop();
                        app.stop();
                        stage.close();
                        Platform.exit();
                    } catch (Exception error) { fail(error.toString()); }
                });
                return;
            }
            if ("1".equals(System.getenv("LUMEN_SMOKE_DEMO"))) {
                if ("1".equals(System.getenv("LUMEN_SMOKE_MANAGEMENT"))) {
                    var library = new LibraryClient(java.net.URI.create(System.getenv("LUMEN_SERVICE_URL")));
                    library.createPlaylist("晨间");
                    library.createPlaylist("夜晚");
                    app = new LumenFxApp();
                    app.startUi(stage, java.util.List.of());
                    timeout = after(20_000, () -> fail("曲目管理窗口验收超时"));
                    after(850, this::verifyManagementNavigation);
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_PLAYLIST_PREVIEW"))) {
                    app = new LumenFxApp();
                    app.startUi(stage, java.util.List.of());
                    ((Button) stage.getScene().lookup("#nav-playlists")).fire();
                    after(700, () -> {
                        try {
                            check(stage.getScene().lookup("#playlist-scroll") instanceof ScrollPane,
                                    "歌单滚动容器缺失");
                            var dark = stage.getScene().snapshot(null);
                            check(dark.getPixelReader().getColor(1000, 160).getBrightness() < 0.45,
                                    "深色主题歌单背景仍然过亮");
                            savePreview(dark, "playlist-dark.png");
                            ((Button) stage.getScene().lookup("#theme-button")).fire();
                            after(350, () -> {
                                try {
                                    var light = stage.getScene().snapshot(null);
                                    check(light.getPixelReader().getColor(1000, 160).getBrightness() > 0.70,
                                            "浅色主题歌单背景未保持浅色");
                                    savePreview(light, "playlist-light.png");
                                    System.out.println("PASS: playlist canvas follows dark and light themes");
                                    app.stop();
                                    stage.close();
                                    Platform.exit();
                                } catch (Exception error) { fail(error.toString()); }
                            });
                        } catch (Exception error) { fail(error.toString()); }
                    });
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_HOME"))) {
                    app = new LumenFxApp();
                    app.startUi(stage, java.util.List.of());
                    after("1".equals(System.getenv("LUMEN_SMOKE_REAL_HOME")) ? 16000 : 2600, () -> {
                        try {
                            savePreview(stage.getScene().snapshot(null), "phase2-home.png");
                            if (((Button) stage.getScene().lookup("#theme-button")).getText().endsWith("Lumen")) {
                                var accentPicker = (ComboBox<?>) stage.getScene().lookup("#accent-picker");
                                check(accentPicker.isDisabled()
                                        && accentPicker.getValue() == ThemeController.Accent.GOLD,
                                        "Lumen 主题未固定暖金色");
                            }
                            if ("1".equals(System.getenv("LUMEN_SMOKE_LIGHT_PREVIEW"))) {
                                ((Button) stage.getScene().lookup("#theme-button")).fire();
                                savePreview(stage.getScene().snapshot(null), "phase2-home-light.png");
                            }
                            Button ai = (Button) stage.getScene().lookup("#ai-settings-button");
                            TitledPane audioSettings = (TitledPane) stage.getScene().lookup("#settings-audio");
                            check(ai != null && audioSettings != null, "AI 或音频设置入口缺失");
                            ai.fire();
                            after(450, () -> {
                                Stage aiWindow = javafx.stage.Window.getWindows().stream()
                                        .filter(window -> window instanceof Stage candidate
                                                && "AI 推荐设置".equals(candidate.getTitle()))
                                        .map(window -> (Stage) window).findFirst().orElse(null);
                                check(aiWindow != null && aiWindow.isShowing(), "AI 设置窗口未打开");
                                aiWindow.close();
                                ((TitledPane) stage.getScene().lookup("#sidebar-settings")).setExpanded(true);
                                audioSettings.setExpanded(true);
                                stage.getScene().getRoot().applyCss();
                                stage.getScene().getRoot().layout();
                                check(stage.getScene().lookup("#audio-cache-enabled") instanceof CheckBox,
                                        "音频设置没有缓存开关");
                                check(stage.getScene().lookup("#audio-cache-limit") instanceof ComboBox,
                                        "音频设置没有缓存容量");
                                check(stage.getScene().lookup("#audio-cache-clear") instanceof Button,
                                        "音频设置没有缓存清理入口");
                                check(stage.getScene().lookup("#audio-normalization-enabled") instanceof CheckBox,
                                        "音频设置没有归一化开关");
                                audioSettings.setExpanded(false);
                                ((TitledPane) stage.getScene().lookup("#sidebar-settings")).setExpanded(false);
                                Button queueToggle = (Button) stage.getScene().lookup("#toggle-queue");
                                check(queueToggle != null, "播放队列入口缺失");
                                queueToggle.fire();
                                check(stage.getScene().lookup("#right-rail").isVisible(), "播放队列未展开");
                                checkPlaybackBar();
                                queueToggle.fire();
                                check(!stage.getScene().lookup("#right-rail").isVisible(), "播放队列未收起");
                                stage.setHeight(600);
                                stage.getScene().getRoot().applyCss();
                                stage.getScene().getRoot().layout();
                                checkPlaybackBar();
                                ((Button) stage.getScene().lookup("#open-immersive")).fire();
                                checkPlaybackBar();
                                check(stage.getScene().lookup("#immersive-match-lyrics") instanceof Button,
                                        "沉浸页没有手动歌词匹配入口");
                                System.out.println("PASS: redesigned home, settings and queue drawer rendered");
                                app.stop();
                                stage.close();
                                Platform.exit();
                            });
                        } catch (Exception error) { fail(error.toString()); }
                    });
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_DRAG"))) {
                    var library = new LibraryClient(java.net.URI.create(System.getenv("LUMEN_SERVICE_URL")));
                    library.createPlaylist("拖动一");
                    library.createPlaylist("拖动二");
                    app = new LumenFxApp();
                    app.startUi(stage, java.util.List.of());
                    ((Button) stage.getScene().lookup("#nav-playlists")).fire();
                    timeout = after(15_000, () -> {
                        var list = (ListView<?>) stage.getScene().lookup("#playlist-order-list");
                        fail("拖拽排序等待超时：条目=" + (list == null ? -1 : list.getItems().size())
                                + "，可见行=" + (list == null ? -1 : list.lookupAll(".list-cell").size()));
                    });
                    waitForDragList();
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_LIBRARY")))
                    new LibraryClient(java.net.URI.create(System.getenv("LUMEN_SERVICE_URL")))
                            .createPlaylist("桌面曲库验收");
                app = new LumenFxApp();
                app.startUi(stage, java.util.List.of());
                ((TabPane) stage.getScene().lookup("#main-tabs")).getSelectionModel().select(4);
                check(stage.getScene().lookup("#import-favorite") instanceof Button,
                        "公开收藏夹导入入口缺失");
                check(stage.getScene().lookup("#export-backup") instanceof Button
                        && stage.getScene().lookup("#restore-backup") instanceof Button,
                        "备份导出或恢复入口缺失");
                check(stage.getScene().lookup("#playlist-order-list") instanceof ListView,
                        "歌单拖拽排序列表缺失");
                check(stage.getScene().lookup("#lyrics-candidates") instanceof ComboBox
                        && stage.getScene().lookup("#lyrics-earlier") instanceof Button,
                        "在线歌词选择或校准控件缺失");
                ((TabPane) stage.getScene().lookup("#main-tabs")).getSelectionModel().select(1);
                if ("1".equals(System.getenv("LUMEN_SMOKE_SEARCH_PREVIEW"))) {
                    timeout = after(45_000, () -> fail("真实搜索封面预览等待超时"));
                    ((TextField) stage.getScene().lookup("#search-query")).setText("周杰伦 晴天");
                    ((Button) stage.getScene().lookup("#search-button")).fire();
                    waitForSearchPreview();
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_LISTENING"))) {
                    timeout = after(20_000, () -> fail("有效听歌记录等待超时"));
                    waitForListeningSearch();
                    return;
                }
                timeout = after(25_000, () -> fail("演示队列播放状态等待超时"));
                waitForDemoSearch();
                return;
            }
            if ("1".equals(System.getenv("LUMEN_SMOKE_ONLINE"))) {
                app = new LumenFxApp();
                app.startUi(stage, java.util.List.of());
                ((TabPane) stage.getScene().lookup("#main-tabs")).getSelectionModel().select(1);
                if ("1".equals(System.getenv("LUMEN_SMOKE_DIRECT_SEARCH"))) {
                    timeout = after(60_000, () -> fail("BV 链接搜索等待超时"));
                    ((TextField) stage.getScene().lookup("#search-query")).setText(
                            System.getenv().getOrDefault("LUMEN_SMOKE_QUERY",
                                    "https://www.bilibili.com/video/BV1hRNe6wEzV/"));
                    ((Button) stage.getScene().lookup("#search-button")).fire();
                    waitForDirectSearch();
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_ONLINE_IMMERSIVE_PREVIEW"))) {
                    timeout = after(45_000, () -> fail("真实封面沉浸页等待超时："
                            + ((Label) stage.getScene().lookup("#search-status")).getText()));
                    ((TextField) stage.getScene().lookup("#search-query")).setText(
                            System.getenv().getOrDefault("LUMEN_SMOKE_QUERY", "BV1BZbSzZEGT"));
                    ((Button) stage.getScene().lookup("#search-button")).fire();
                    waitForOnlineImmersivePreview();
                    return;
                }
                if ("1".equals(System.getenv("LUMEN_SMOKE_COLLECTION"))) {
                    timeout = after(75_000, () -> fail("合集分 P 入队等待超时"));
                    ((ComboBox<String>) stage.getScene().lookup("#search-kind")).setValue("合集");
                    ((TextField) stage.getScene().lookup("#search-query")).setText("周杰伦 歌曲合集");
                    ((Button) stage.getScene().lookup("#search-button")).fire();
                    waitForCollection();
                    return;
                }
                timeout = after(120_000, () -> fail("在线搜索或播放等待超时"));
                TextField query = (TextField) stage.getScene().lookup("#search-query");
                query.setText(System.getenv().getOrDefault("LUMEN_SMOKE_QUERY", "周杰伦 官方MV"));
                ((Button) stage.getScene().lookup("#search-button")).fire();
                waitForOnlineSearch();
                return;
            }
            Path sample = Path.of(System.getenv("LUMEN_SMOKE_SAMPLE")).toAbsolutePath().normalize();
            if (!Files.isRegularFile(sample)) throw new IllegalArgumentException("测试音频不存在：" + sample);
            app = new LumenFxApp();
            app.startUi(stage, java.util.List.of("--open=" + sample));
            if ("1".equals(System.getenv("LUMEN_SMOKE_LYRIC_MEMORY"))) {
                try {
                    var local = (ListView<Track>) stage.getScene().lookup("#local-list");
                    new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory()).save(
                            local.getItems().get(0),
                            new LyricsSelectionStore.Selection("记住的歌词", "测试歌手",
                                    "[00:00.00]已经保存的第一句\n[00:01.00]已经保存的第二句", 500));
                    local.getSelectionModel().select(0);
                    ((Button) stage.getScene().lookup("#select-local")).fire();
                    check(((Label) stage.getScene().lookup("#lyrics-status")).getText()
                                    .startsWith("已恢复手动匹配："), "播放时未读取上次手动歌词");
                    check(((ListView<?>) stage.getScene().lookup("#lyrics-list")).getItems().size() == 2
                                    && ((Label) stage.getScene().lookup("#lyric-offset-label"))
                                            .getText().contains("+0.5"),
                            "已保存歌词没有显示在播放器中");
                    System.out.println("PASS: saved manual lyrics restored in playback window");
                    app.stop(); stage.close(); Platform.exit();
                } catch (Exception error) { fail(error.toString()); }
                return;
            }
            if ("1".equals(System.getenv("LUMEN_SMOKE_IMMERSIVE_PREVIEW"))) {
                var localPreview = (ListView<?>) stage.getScene().lookup("#local-list");
                localPreview.getSelectionModel().select(0);
                ((Button) stage.getScene().lookup("#select-local")).fire();
                after(600, () -> {
                    ((Button) stage.getScene().lookup("#open-immersive")).fire();
                    if ("1".equals(System.getenv("LUMEN_SMOKE_LUMEN_PREVIEW"))) {
                        Button theme = (Button) stage.getScene().lookup("#theme-button");
                        for (int index = 0; index < 5 && !theme.getText().endsWith("Lumen"); index++)
                            theme.fire();
                        check(theme.getText().endsWith("Lumen"), "Lumen 主题未启用");
                    }
                    if ("1".equals(System.getenv("LUMEN_SMOKE_SMALL_WINDOW"))) {
                        stage.setWidth(860);
                        stage.setHeight(600);
                    }
                    after(350, () -> {
                        try {
                            check(((ListView<?>) stage.getScene().lookup("#immersive-lyrics"))
                                    .getItems().size() >= 5, "沉浸页同步歌词未载入");
                            savePreview(stage.getScene().snapshot(null),
                                    "1".equals(System.getenv("LUMEN_SMOKE_LUMEN_PREVIEW"))
                                            ? "player-lumen-theme.png"
                                            : "1".equals(System.getenv("LUMEN_SMOKE_SMALL_WINDOW"))
                                                    ? "player-small-window.png" : "phase2-immersive-dark.png");
                            ((Button) stage.getScene().lookup("#immersive-match-lyrics")).fire();
                            Stage lyricDialog = javafx.stage.Window.getWindows().stream()
                                    .filter(window -> window instanceof Stage candidate
                                            && "手动匹配歌词".equals(candidate.getTitle()))
                                    .map(window -> (Stage) window).findFirst().orElse(null);
                            check(lyricDialog != null && lyricDialog.isShowing(),
                                    "手动歌词匹配窗口未打开");
                            lyricDialog.close();
                            System.out.println("PASS: dark immersive artwork, lyrics and controls rendered");
                            app.stop();
                            stage.close();
                            Platform.exit();
                        } catch (Exception error) { fail(error.toString()); }
                    });
                });
                return;
            }
            if ("1".equals(System.getenv("LUMEN_SMOKE_DESKTOP"))) {
                timeout = after(20_000, () -> fail("桌面体验窗口验收超时"));
                ((ListView<?>) stage.getScene().lookup("#local-list")).getSelectionModel().select(0);
                ((Button) stage.getScene().lookup("#select-local")).fire();
                verifyDesktopExperience();
                return;
            }
            timeout = after(8_000, () -> fail("窗口播放状态等待超时"));
            check(stage.getScene().lookup("#theme-button") instanceof Button, "主题切换控件缺失");
            check(stage.getScene().lookup("#volume-slider") instanceof Slider, "音量控件缺失");
            check(stage.getScene().lookup("#lyrics-list") instanceof ListView, "歌词列表缺失");
            var local = (ListView<?>) stage.getScene().lookup("#local-list");
            check(local != null && local.getItems().size() == 1, "本地 MP3 未导入");
            local.getSelectionModel().select(0);
            ((Button) stage.getScene().lookup("#select-local")).fire();
            after(650, () -> {
                Button play = (Button) stage.getScene().lookup("#play-button");
                check(!play.isDisabled() && play.getText().equals("暂停"), "MP3 未进入播放状态");
                check(((ListView<?>) stage.getScene().lookup("#queue-list")).getItems().size() == 1,
                        "本地曲目未进入队列");
                Slider volume = (Slider) stage.getScene().lookup("#volume-slider");
                volume.setValue(0.35);
                check(Math.abs(volume.getValue() - 0.35) < 0.01, "音量滑块未响应");
                Button theme = (Button) stage.getScene().lookup("#theme-button");
                String previousTheme = theme.getText();
                theme.fire();
                check(!theme.getText().equals(previousTheme), "主题切换未响应");
                theme.fire();
                if ("1".equals(System.getenv("LUMEN_SMOKE_LYRICS"))) {
                    check(((ListView<?>) stage.getScene().lookup("#lyrics-list")).getItems().size() > 0,
                            "同名 LRC 歌词未载入");
                }
                play.fire();
                after(250, () -> {
                    check(play.getText().equals("播放"), "暂停未更新控件");
                    play.fire();
                    after(300, () -> {
                        check(play.getText().equals("暂停"), "恢复未更新控件");
                        try {
                            savePreview(stage.getScene().snapshot(null));
                            timeout.stop();
                            System.out.println("PASS: JavaFX window, local MP3 import, queue, play/pause/resume, rendered preview");
                            app.stop();
                            stage.close();
                            Platform.exit();
                        } catch (Exception error) { fail(error.toString()); }
                    });
                });
            });
        } catch (Exception error) { fail(error.toString()); }
    }

    private void verifyDesktopExperience() {
        Button mainPlay = (Button) stage.getScene().lookup("#play-button");
        if (mainPlay == null || !"暂停".equals(mainPlay.getText())) {
            after(120, this::verifyDesktopExperience);
            return;
        }
        check(stage.getStyle() == javafx.stage.StageStyle.UNDECORATED,
                "主窗口未使用自定义标题栏");
        var mode = (Button) stage.getScene().lookup("#theme-button");
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 5; i++) {
            seen.add(stage.getScene().getRoot().getStyleClass().stream()
                    .filter(name -> name.endsWith("-theme")).findFirst().orElse(""));
            mode.fire();
        }
        check(seen.size() == 5, "五档主题未全部切换");
        if (mode.getText().endsWith("Lumen")) mode.fire();
        var accents = (ComboBox<ThemeController.Accent>) stage.getScene().lookup("#accent-picker");
        check(accents != null && accents.getItems().size() == 6, "六种主题色入口缺失");
        accents.setValue(ThemeController.Accent.PINK);
        check(stage.getScene().getRoot().getStyle().contains(ThemeController.Accent.PINK.hex()),
                "主题色选择未应用到窗口");
        try {
            Path image = Path.of("../build/step4-background-source.png").toAbsolutePath().normalize();
            Files.createDirectories(image.getParent());
            var bitmap = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
                bitmap.setRGB(x, y, 0x4669a1);
            ImageIO.write(bitmap, "png", image.toFile());
            app.setBackgroundImage(image);
            check(stage.getScene().getRoot().getStyle().contains("-fx-background-image"),
                    "背景图主题未应用选中的图片");
        } catch (Exception error) { fail("背景图主题验收失败：" + error); }
        var pets = (ComboBox<PetCompanion.Species>) stage.getScene().lookup("#pet-picker");
        var pet = (PetCompanion) stage.getScene().lookup("#pet-companion");
        check(pets != null && pets.getItems().size() == 3 && pet != null,
                "桌宠选择或视图缺失");
        pets.setValue(PetCompanion.Species.RICE_GHOST);
        check(pet.species() == PetCompanion.Species.RICE_GHOST, "糯米飘形象未切换");
        pets.setValue(PetCompanion.Species.ORANGE_DOG);
        check(pet.species() == PetCompanion.Species.ORANGE_DOG && pet.isPlaying(),
                "桌宠未响应可观测播放事件或形象切换");
        ((Button) stage.getScene().lookup("#window-maximize")).fire();
        check(stage.isMaximized(), "自定义标题栏最大化失败");
        ((Button) stage.getScene().lookup("#window-maximize")).fire();
        var trackEntry = stage.getScene().lookup("#player-track-entry");
        check(trackEntry != null && trackEntry.getOnMouseClicked() != null,
                "底部播放栏缺少沉浸页入口");
        trackEntry.getOnMouseClicked().handle(null);
        check(((TabPane) stage.getScene().lookup("#main-tabs")).getSelectionModel().getSelectedIndex() == 5,
                "沉浸播放页未打开");
        check(!stage.getScene().lookup("#main-sidebar").isVisible(), "沉浸播放仍被侧栏占据");
        ((Button) stage.getScene().lookup("#immersive-collapse")).fire();
        check(stage.getScene().lookup("#main-sidebar").isVisible(), "沉浸页收起后侧栏未恢复");
        ((Button) stage.getScene().lookup("#open-immersive")).fire();
        Label immersive = (Label) stage.getScene().lookup("#immersive-now-playing");
        check(immersive != null && immersive.getText().equals(
                ((Label) stage.getScene().lookup("#now-playing")).getText()),
                "沉浸页曲目状态未与播放条同步");
        check(stage.getScene().lookup("#immersive-play") == null,
                "沉浸页仍重复显示播放控制");
        mainPlay.fire();
        after(250, () -> verifyMiniAfterImmersive(mainPlay));
    }

    private void verifyMiniAfterImmersive(Button mainPlay) {
        check("播放".equals(mainPlay.getText()), "沉浸页播放条暂停失败");
        try { savePreview(stage.getScene().snapshot(null), "step4-immersive.png"); }
        catch (Exception error) { fail("沉浸页截图失败：" + error); }
        ((Button) stage.getScene().lookup("#open-mini")).fire();
        Stage mini = javafx.stage.Window.getWindows().stream()
                .filter(window -> window instanceof Stage candidate
                        && candidate.getTitle().contains("迷你播放器"))
                .map(window -> (Stage) window).findFirst().orElse(null);
        check(mini != null && mini.isShowing() && !stage.isShowing() && mini.isAlwaysOnTop(),
                "迷你播放器未置顶或主窗口未收起");
        Button miniPlay = (Button) mini.getScene().lookup("#mini-play");
        check(miniPlay != null && miniPlay.getText().equals(mainPlay.getText()),
                "迷你播放器未共享播放状态");
        miniPlay.fire();
        after(300, () -> finishDesktopExperience(mainPlay, mini, miniPlay));
    }

    private void checkPlaybackBar() {
        var bar = stage.getScene().lookup(".playback");
        check(bar != null, "底部播放栏缺失");
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        double height = ((javafx.scene.layout.Region) bar).getHeight();
        double bottom = bar.localToScene(0, 0).getY() + height;
        check(Math.abs(bottom - stage.getScene().getHeight()) < 2.5,
                "播放栏移出窗口底部：" + bottom + " / " + stage.getScene().getHeight());
        check(Math.abs(height - 94) < 2.5,
                "播放栏高度在操作中变化：" + height);
    }

    private void finishDesktopExperience(Button mainPlay, Stage mini, Button miniPlay) {
        check("暂停".equals(mainPlay.getText()), "迷你播放器播放操作未同步主窗口");
        try { savePreview(mini.getScene().snapshot(null), "step4-mini.png"); }
        catch (Exception error) { fail("迷你播放器截图失败：" + error); }
        ((Button) mini.getScene().lookup("#mini-return")).fire();
        check(stage.isShowing() && !mini.isShowing(), "关闭迷你播放器未返回主窗口");
        timeout.stop();
        System.out.println("PASS: immersive, four themes, accents, background, custom titlebar, mini, pet shared state");
        app.stop();
        stage.close();
        Platform.exit();
    }

    private void playSelectedSearch() {
        var search = (ListView<?>) stage.getScene().lookup("#search-list");
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        int index = search.getSelectionModel().getSelectedIndex();
        var cell = search.lookupAll(".list-cell").stream()
                .filter(item -> item instanceof javafx.scene.control.ListCell<?> row && row.getIndex() == index && !row.isEmpty())
                .map(item -> (javafx.scene.control.ListCell<?>) item).findFirst().orElseThrow();
        ((Button) cell.lookup(".track-more")).fire();
        var menu = cell.getContextMenu();
        menu.getItems().stream().filter(item -> "track-menu-play".equals(item.getId())).findFirst().orElseThrow().fire();
        menu.hide();
    }

    private void waitForDirectSearch() {
        String result = ((Label) stage.getScene().lookup("#search-status")).getText();
        if (result.startsWith("搜索失败")) { fail(result); return; }
        var search = (ListView<Track>) stage.getScene().lookup("#search-list");
        if (search.getItems().isEmpty()) { after(250, this::waitForDirectSearch); return; }
        check(((ListView<?>) stage.getScene().lookup("#queue-list")).getItems().isEmpty()
                        && ((Label) stage.getScene().lookup("#now-playing")).getText().equals("未选择曲目"),
                "BV 搜索结果出现前已经自动播放");
        search.getSelectionModel().select(0);
        playSelectedSearch();
        check(!((ListView<?>) stage.getScene().lookup("#queue-list")).getItems().isEmpty(),
                "手动选择搜索结果后未进入播放队列");
        timeout.stop();
        System.out.println("PASS: BV link search shows results without autoplay, selection starts playback");
        app.stop(); stage.close(); Platform.exit();
    }

    private void waitForOnlineImmersivePreview() {
        String result = ((Label) stage.getScene().lookup("#search-status")).getText();
        if (result.startsWith("搜索失败")) { fail(result); return; }
        var search = (ListView<?>) stage.getScene().lookup("#search-list");
        if (search == null || search.getItems().isEmpty()) {
            after(250, this::waitForOnlineImmersivePreview);
            return;
        }
        Track candidate = search.getItems().stream().map(item -> (Track) item)
                .filter(item -> item.coverUri() != null && item.partCount() <= 1)
                .findFirst().orElse(null);
        if (candidate == null) { fail("真实搜索结果没有带封面的单 P 曲目"); return; }
        search.getSelectionModel().select(search.getItems().indexOf(candidate));
        playSelectedSearch();
        ((Button) stage.getScene().lookup("#open-immersive")).fire();
        waitForArtworkImage();
    }

    private void waitForArtworkImage() {
        javafx.scene.image.ImageView image = (javafx.scene.image.ImageView)
                stage.getScene().lookup("#immersive-artwork-image");
        if (image != null && image.getImage() != null && image.getImage().isError()) {
            fail("B 站封面加载失败：" + image.getImage().getException());
            return;
        }
        if (image == null || image.getImage() == null || image.getImage().getWidth() <= 0) {
            after(350, this::waitForArtworkImage);
            return;
        }
        if ("1".equals(System.getenv("LUMEN_SMOKE_ONLINE_LYRICS"))) {
            String lyricStatus = ((Label) stage.getScene().lookup("#lyrics-status")).getText();
            if (lyricStatus.startsWith("正在")) {
                after(350, this::waitForArtworkImage);
                return;
            }
            check(lyricStatus.startsWith("自动匹配："), "真实歌曲未自动匹配歌词：" + lyricStatus);
            check(((ListView<?>) stage.getScene().lookup("#immersive-lyrics")).getItems().size() > 3,
                    "自动匹配结果没有载入沉浸歌词列表");
        }
        try {
            savePreview(stage.getScene().snapshot(null), "player-online-cover.png");
            timeout.stop();
            System.out.println("PASS: real Bilibili cover and optional auto lyrics rendered");
            app.stop();
            stage.close();
            Platform.exit();
        } catch (Exception error) { fail(error.toString()); }
    }

    private void waitForOnlineSearch() {
        String searchStatus = ((Label) stage.getScene().lookup("#search-status")).getText();
        if (searchStatus.startsWith("搜索失败")) { fail(searchStatus); return; }
        if ("1".equals(System.getenv("LUMEN_SMOKE_JAY_LIBRARY"))) {
            String libraryStatus = ((Label) stage.getScene().lookup("#library-status")).getText();
            if (libraryStatus.startsWith("正在")) {
                after(200, this::waitForOnlineSearch);
                return;
            }
            check(((ListView<?>) stage.getScene().lookup("#favorites-list")).getItems().size() == 2,
                    "重启后未恢复两首周杰伦相关收藏：" + libraryStatus);
            check(((ListView<?>) stage.getScene().lookup("#playlist-tracks")).getItems().size() == 2,
                    "重启后未恢复周杰伦相关歌单：" + libraryStatus);
        }
        var search = (ListView<?>) stage.getScene().lookup("#search-list");
        if (search == null || search.getItems().isEmpty()) {
            after(200, this::waitForOnlineSearch);
            return;
        }
        search.getSelectionModel().select(0);
        playSelectedSearch();
        waitForOnlinePlay();
    }

    private void waitForOnlinePlay() {
        String status = ((Label) stage.getScene().lookup("#status")).getText();
        if (status.contains("失败")) { fail(status); return; }
        Button play = (Button) stage.getScene().lookup("#play-button");
        if (!status.equals("正在播放") || !play.getText().equals("暂停")) {
            after(250, this::waitForOnlinePlay);
            return;
        }
        Slider slider = (Slider) stage.getScene().lookup("#progress-slider");
        if (slider.isDisabled()) {
            after(250, this::waitForOnlinePlay);
            return;
        }
        slider.setValueChanging(true);
        slider.setValue(0.3);
        slider.setValueChanging(false);
        after(4_000, () -> {
            if ("1".equals(System.getenv("LUMEN_SMOKE_SEEK_FALLBACK"))) {
                waitForSeekFallback(0);
                return;
            }
            String currentStatus = ((Label) stage.getScene().lookup("#status")).getText();
            check(!currentStatus.contains("失败") && slider.getValue() > 0.15,
                    "在线流进度拖动失败：" + currentStatus + "，位置=" + slider.getValue());
            timeout.stop();
            System.out.println("PASS: real Bilibili search, JavaFX streaming playback, seek");
            app.stop();
            stage.close();
            Platform.exit();
        });
    }

    private void waitForSeekFallback(int attempts) {
        String currentStatus = ((Label) stage.getScene().lookup("#status")).getText();
        Slider slider = (Slider) stage.getScene().lookup("#progress-slider");
        if (currentStatus.equals("正在播放") && slider.getValue() > 0.15) {
            timeout.stop();
            System.out.println("PASS: unsupported online seek recovered through complete-file playback");
            app.stop();
            stage.close();
            Platform.exit();
            return;
        }
        if (attempts >= 100 || currentStatus.contains("失败")) {
            fail("在线拖动回退失败：" + currentStatus + "，位置=" + slider.getValue());
            return;
        }
        after(300, () -> waitForSeekFallback(attempts + 1));
    }

    private void waitForDemoSearch() {
        var tabs = (TabPane) stage.getScene().lookup("#main-tabs");
        tabs.getSelectionModel().select(0);
        var recommended = (ListView<?>) stage.getScene().lookup("#recommendation-list");
        if (recommended == null || recommended.getItems().size() != 3) {
            after(120, this::waitForDemoSearch);
            return;
        }
        tabs.getSelectionModel().select(1);
        check(((ComboBox<?>) stage.getScene().lookup("#search-kind")).getItems().size() == 3,
                "单曲/合集筛选控件缺失");
        var search = (ListView<?>) stage.getScene().lookup("#search-list");
        if (search == null || search.getItems().size() != 3) {
            after(120, this::waitForDemoSearch);
            return;
        }
        search.getSelectionModel().select(0);
        playSelectedSearch();
        after(850, () -> {
            Button play = (Button) stage.getScene().lookup("#play-button");
            check(!play.isDisabled() && play.getText().equals("暂停"), "演示音频未进入播放状态");
            check(((ListView<?>) stage.getScene().lookup("#queue-list")).getItems().size() == 3,
                    "演示搜索结果未成为队列快照");
            String first = ((Label) stage.getScene().lookup("#now-playing")).getText();
            Slider progress = (Slider) stage.getScene().lookup("#progress-slider");
            check(progress != null && !progress.isDisabled(), "进度拖动控件不可用");
            progress.setValueChanging(true);
            progress.setValue(0.5);
            progress.setValueChanging(false);
            after(550, () -> {
                String time = ((Label) stage.getScene().lookup("#timing")).getText();
                check(time.matches("00:0[4-6] / 00:08"), "拖动进度后位置未更新：" + time);
                ((Button) stage.getScene().lookup("#next-button")).fire();
                after(700, () -> {
                    String second = ((Label) stage.getScene().lookup("#now-playing")).getText();
                    check(!first.equals(second) && play.getText().equals("暂停"), "下一首未真正切换播放");
                    ((Button) stage.getScene().lookup("#previous-button")).fire();
                    after(700, () -> {
                        check(first.equals(((Label) stage.getScene().lookup("#now-playing")).getText())
                                && play.getText().equals("暂停"), "上一首未真正切换播放");
                        verifyRepeatAndRapidSwitch(first);
                    });
                });
            });
        });
    }

    private void verifyManagementNavigation() {
        try {
            Button nav = (Button) stage.getScene().lookup("#nav-playlists");
            nav.fire();
            var sidebar = stage.getScene().lookup("#sidebar-playlists");
            var playlists = (ListView<?>) stage.getScene().lookup("#playlist-order-list");
            check(sidebar.isVisible() && playlists.getItems().size() == 2,
                    "歌单没有在侧栏展开");
            playlists.getSelectionModel().select(1);
            check(((Label) stage.getScene().lookup("#playlist-page-name")).getText().equals("夜晚"),
                    "点击侧栏歌单未打开对应内容");
            savePreview(stage.getScene().snapshot(null), "playlist-sidebar-management.png");
            ((TabPane) stage.getScene().lookup("#main-tabs")).getSelectionModel().select(1);
            ((TextField) stage.getScene().lookup("#search-query")).setText("demo");
            ((Button) stage.getScene().lookup("#search-button")).fire();
            waitForManagementSearch();
        } catch (Exception error) { fail(error.toString()); }
    }

    private void waitForManagementSearch() {
        var results = (ListView<Track>) stage.getScene().lookup("#search-list");
        if (results.getItems().isEmpty()) { after(120, this::waitForManagementSearch); return; }
        check(((ListView<?>) stage.getScene().lookup("#queue-list")).getItems().isEmpty(),
                "仅搜索时不应改变播放队列");
        results.getSelectionModel().select(0);
        playSelectedSearch();
        after(450, () -> {
            try {
                var queued = (ListView<Track>) stage.getScene().lookup("#queue-list");
                check(queued.getItems().size() == 3, "播放后未建立队列");
                ((Button) stage.getScene().lookup("#toggle-queue")).fire();
                queued.getSelectionModel().select(1);
                ((Button) stage.getScene().lookup("#queue-selected-favorite")).fire();
                after(550, () -> verifyManagementActions(queued));
            } catch (Exception error) { fail(error.toString()); }
        });
    }

    private void verifyManagementActions(ListView<Track> queued) {
        try {
            var favorites = (ListView<Track>) stage.getScene().lookup("#favorites-list");
            check(favorites.getItems().size() == 1
                    && favorites.getItems().get(0).id().equals(queued.getItems().get(1).id()),
                    "队列里的非当前曲目无法收藏");
            MenuButton menu = (MenuButton) stage.getScene().lookup("#queue-selected-playlist");
            menu.show();
            check(menu.getItems().size() == 2, "加入指定歌单菜单缺项");
            menu.getItems().get(1).fire();
            ((Button) stage.getScene().lookup("#queue-selected-next")).fire();
            check(queued.getItems().size() == 4, "指定队列曲目未插入下一首");
            after(550, () -> {
                try {
                    var playlistTracks = (ListView<Track>) stage.getScene().lookup("#playlist-tracks");
                    check(playlistTracks.getItems().size() == 1, "队列曲目未加入指定歌单");
                    favorites.getSelectionModel().select(0);
                    playlistTracks.getSelectionModel().select(0);
                    check(stage.getScene().lookup("#favorite-selected-playlist") instanceof MenuButton
                                    && stage.getScene().lookup("#playlist-selected-favorite") instanceof Button,
                            "收藏页或具体歌单缺少曲目管理操作");
                    verifyQueueDrag(queued);
                } catch (Exception error) { fail(error.toString()); }
            });
        } catch (Exception error) { fail(error.toString()); }
    }

    private void verifyQueueDrag(ListView<Track> queued) {
        queued.applyCss();
        queued.layout();
        var cells = queued.lookupAll(".list-cell").stream()
                .filter(node -> node instanceof ListCell<?> cell && !cell.isEmpty())
                .map(node -> (ListCell<?>) node)
                .sorted(java.util.Comparator.comparingInt(ListCell::getIndex)).toList();
        check(cells.size() >= 4, "队列可见条目不足，无法拖拽验收");
        String movedId = queued.getItems().get(3).id();
        var from = cells.get(3).localToScreen(cells.get(3).getBoundsInLocal());
        var to = cells.get(1).localToScreen(cells.get(1).getBoundsInLocal());
        int x = (int) from.getMinX() + 24;
        int fromY = (int) from.getCenterY();
        int toY = (int) to.getCenterY();
        stage.requestFocus();
        Thread dragger = new Thread(() -> {
            try {
                var robot = new java.awt.Robot();
                robot.mouseMove(x, fromY);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(150);
                robot.mouseMove(x, (fromY + toY) / 2);
                Thread.sleep(150);
                robot.mouseMove(x, toY);
                Thread.sleep(150);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            } catch (Exception error) { System.err.println("队列拖拽失败：" + error.getMessage()); }
        }, "lumen-queue-drag-smoke");
        dragger.setDaemon(true);
        dragger.start();
        after(2_000, () -> {
            try {
                check(queued.getItems().get(1).id().equals(movedId), "鼠标拖拽未改变播放队列顺序");
                    ((Button) stage.getScene().lookup("#open-immersive")).fire();
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    for (String id : java.util.List.of("immersive-favorite", "immersive-add-playlist", "immersive-match-lyrics")) {
                        Button action = (Button) stage.getScene().lookup("#" + id);
                        check(action != null && action.getWidth() + 1 >= action.prefWidth(-1),
                                "沉浸页按钮文字被省略：" + id);
                    }
                    timeout.stop();
                    System.out.println("PASS: sidebar playlists, search selection, collection and queue drag actions");
                    app.stop(); stage.close(); Platform.exit();
            } catch (Exception error) { fail(error.toString()); }
        });
    }

    private void waitForListeningSearch() {
        var search = (ListView<?>) stage.getScene().lookup("#search-list");
        if (search == null || search.getItems().size() != 3) {
            after(120, this::waitForListeningSearch);
            return;
        }
        search.getSelectionModel().select(0);
        playSelectedSearch();
        after(10_000, () -> {
            try {
                String url = System.getenv("LUMEN_SERVICE_URL") + "/api/history";
                var response = java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 200 && response.body().contains("demo-1"),
                        "实际播放满阈值后未写入听歌记录：" + response.body());
                timeout.stop();
                System.out.println("PASS: JavaFX actual playback qualified a listening history entry");
                app.stop();
                stage.close();
                Platform.exit();
            } catch (Exception error) { fail("读取听歌记录失败：" + error.getMessage()); }
        });
    }

    private void waitForCollection() {
        var search = (ListView<Track>) stage.getScene().lookup("#search-list");
        if (search == null || search.getItems().isEmpty()) {
            after(250, this::waitForCollection);
            return;
        }
        Track selected = search.getItems().stream().filter(item -> item.partCount() > 1)
                .findFirst().orElse(null);
        check(selected != null, "合集筛选没有多 P 结果");
        search.getSelectionModel().select(selected);
        playSelectedSearch();
        waitForCollectionQueue(selected);
    }

    private void waitForSearchPreview() {
        var search = (ListView<Track>) stage.getScene().lookup("#search-list");
        if (search == null || search.getItems().isEmpty()) {
            after(200, this::waitForSearchPreview);
            return;
        }
        check(search.getItems().stream().anyMatch(track -> track.coverUri() != null),
                "真实搜索结果缺少封面地址");
        after(2300, () -> {
            try {
                savePreview(stage.getScene().snapshot(null), "phase2-search.png");
                timeout.stop();
                System.out.println("PASS: real search cards and cover thumbnails rendered");
                app.stop();
                stage.close();
                Platform.exit();
            } catch (Exception error) { fail(error.toString()); }
        });
    }

    private void waitForCollectionQueue(Track selected) {
        var queued = (ListView<Track>) stage.getScene().lookup("#queue-list");
        if (queued.getItems().isEmpty()) {
            after(250, () -> waitForCollectionQueue(selected));
            return;
        }
        check(queued.getItems().size() == selected.partCount(), "合集没有全部分 P 入队");
        check(queued.getItems().get(0).page() == 1 && queued.getItems().get(1).page() == 2,
                "合集分 P 顺序不正确");
        ((Button) stage.getScene().lookup("#next-button")).fire();
        check(((Label) stage.getScene().lookup("#now-playing")).getText()
                        .contains(queued.getItems().get(1).title()), "切换分 P 后标题未更新");
        timeout.stop();
        System.out.println("PASS: ordinary collection search expanded all pages and switched title");
        app.stop();
        stage.close();
        Platform.exit();
    }

    private void waitForDragList() {
        var list = (ListView<?>) stage.getScene().lookup("#playlist-order-list");
        if (list == null || list.getItems().size() != 2) {
            after(120, this::waitForDragList);
            return;
        }
        list.applyCss();
        list.layout();
        var cells = list.lookupAll(".list-cell").stream()
                .filter(node -> node instanceof ListCell<?> cell && !cell.isEmpty())
                .map(node -> (ListCell<?>) node)
                .sorted(java.util.Comparator.comparingInt(ListCell::getIndex)).toList();
        if (cells.size() < 2) {
            after(120, this::waitForDragList);
            return;
        }
        var first = cells.get(0).localToScreen(cells.get(0).getBoundsInLocal());
        var second = cells.get(1).localToScreen(cells.get(1).getBoundsInLocal());
        int x = (int) first.getMinX() + 24;
        int fromY = (int) first.getCenterY();
        int toY = (int) second.getCenterY();
        stage.requestFocus();
        Thread dragger = new Thread(() -> {
            try {
                var robot = new java.awt.Robot();
                robot.mouseMove(x, fromY);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(150);
                robot.mouseMove(x, (fromY + toY) / 2);
                Thread.sleep(150);
                robot.mouseMove(x, toY);
                Thread.sleep(150);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            } catch (Exception error) {
                System.err.println("拖拽机器人失败：" + error.getMessage());
            }
        }, "lumen-drag-smoke");
        dragger.setDaemon(true);
        dragger.start();
        after(2_500, () -> {
            check(list.getItems().get(0).toString().contains("拖动二"),
                    "鼠标拖拽未更新歌单顺序");
            timeout.stop();
            System.out.println("PASS: JavaFX mouse drag reordered playlists");
            app.stop();
            stage.close();
            Platform.exit();
        });
    }

    private void verifyRepeatAndRapidSwitch(String first) {
        Button repeat = (Button) stage.getScene().lookup("#repeat-button");
        Button play = (Button) stage.getScene().lookup("#play-button");
        Slider slider = (Slider) stage.getScene().lookup("#progress-slider");
        Label nowPlaying = (Label) stage.getScene().lookup("#now-playing");
        Label status = (Label) stage.getScene().lookup("#status");
        repeat.fire();
        check(repeat.getText().equals("单曲循环"), "单曲循环未启用");
        seek(slider, 0.92);
        after(1_300, () -> {
            check(nowPlaying.getText().equals(first) && play.getText().equals("暂停")
                    && slider.getValue() < 0.8, "单曲循环未重新播放当前曲目");
            repeat.fire();
            check(repeat.getText().equals("随机播放"), "随机播放模式未启用");
            repeat.fire();
            check(repeat.getText().equals("不循环"), "不循环模式未启用");
            ((Button) stage.getScene().lookup("#next-button")).fire();
            ((Button) stage.getScene().lookup("#next-button")).fire();
            after(800, () -> {
                check(!nowPlaying.getText().equals(first) && play.getText().equals("暂停"),
                        "快速切歌后旧曲目重新播放");
                seek(slider, 0.95);
                after(1_100, () -> {
                    check(status.getText().equals("播放队列已结束")
                            && nowPlaying.getText().startsWith("播放结束："), "不循环模式未在末曲停止");
                    repeat.fire();
                    check(repeat.getText().equals("列表循环"), "列表循环未启用");
                    play.fire();
                    after(800, () -> {
                        check(play.getText().equals("暂停"), "末曲结束后无法重新播放");
                        seek(slider, 0.95);
                        after(1_100, () -> {
                            check(nowPlaying.getText().equals(first) && play.getText().equals("暂停"),
                                    "列表循环未在末曲后播放首曲");
                            if ("1".equals(System.getenv("LUMEN_SMOKE_LIBRARY"))) verifyLibraryUi();
                            else finishDemoSmoke();
                        });
                    });
                });
            });
        });
    }

    private void verifyLibraryUi() {
        Button favorite = (Button) stage.getScene().lookup("#favorite-current");
        var favoriteList = (ListView<?>) stage.getScene().lookup("#favorites-list");
        var picker = (ComboBox<?>) stage.getScene().lookup("#playlist-picker");
        var playlistTracks = (ListView<?>) stage.getScene().lookup("#playlist-tracks");
        check(picker.getItems().size() == 1, "歌单未在桌面端载入");
        favorite.fire();
        after(650, () -> {
            check(favoriteList.getItems().size() == 1 && favorite.getText().equals("取消收藏"),
                    "当前曲目收藏未更新界面");
            ((Button) stage.getScene().lookup("#add-to-playlist")).fire();
            after(650, () -> {
                check(playlistTracks.getItems().size() == 1, "当前曲目未加入歌单界面");
                Object first = playlistTracks.getItems().get(0);
                ((Button) stage.getScene().lookup("#next-button")).fire();
                ((Button) stage.getScene().lookup("#add-to-playlist")).fire();
                after(650, () -> {
                    check(playlistTracks.getItems().size() == 2, "第二首未加入歌单");
                    Object second = playlistTracks.getItems().get(1);
                    playlistTracks.getSelectionModel().select(1);
                    ((Button) stage.getScene().lookup("#playlist-up")).fire();
                    after(650, () -> {
                        check(playlistTracks.getItems().get(0).equals(second)
                                && playlistTracks.getItems().get(1).equals(first), "歌单上移未更新顺序");
                        playlistTracks.getSelectionModel().select(0);
                        ((Button) stage.getScene().lookup("#remove-from-playlist")).fire();
                        after(650, () -> {
                            favoriteList.getSelectionModel().select(0);
                            ((Button) stage.getScene().lookup("#unfavorite-button")).fire();
                            ((Button) stage.getScene().lookup("#delete-playlist")).fire();
                            after(650, () -> {
                                check(favoriteList.getItems().isEmpty() && picker.getItems().isEmpty(),
                                        "曲库删除操作未更新界面");
                                finishDemoSmoke();
                            });
                        });
                    });
                });
            });
        });
    }

    private void finishDemoSmoke() {
        timeout.stop();
        System.out.println("PASS: demo playback, rapid switching, repeat modes, library UI");
        app.stop();
        stage.close();
        Platform.exit();
    }

    private void seek(Slider slider, double fraction) {
        check(!slider.isDisabled(), "进度条不可用");
        slider.setValueChanging(true);
        slider.setValue(fraction);
        slider.setValueChanging(false);
    }

    private void savePreview(WritableImage image) throws Exception {
        savePreview(image, "javafx-preview.png");
    }

    private void savePreview(WritableImage image, String name) throws Exception {
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        var pixels = image.getPixelReader();
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, pixels.getArgb(x, y));
        }
        Path file = Path.of("../build", name).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        ImageIO.write(output, "png", file.toFile());
    }

    private PauseTransition after(int milliseconds, Runnable action) {
        var timer = new PauseTransition(Duration.millis(milliseconds));
        timer.setOnFinished(event -> action.run());
        timer.play();
        return timer;
    }
    private void check(boolean condition, String message) { if (!condition) fail(message); }
    private void fail(String message) {
        System.err.println("FAIL: " + message);
        if (app != null) app.stop();
        System.exit(1);
    }
    public static void main(String[] args) { launch(args); }
}


