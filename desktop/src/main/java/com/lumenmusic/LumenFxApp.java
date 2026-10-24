package com.lumenmusic;

import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import com.lumenmusic.audio.DemoAudioLibrary;
import com.lumenmusic.audio.OnlineAudioCache;
import com.lumenmusic.audio.PersistentAudioCache;
import com.lumenmusic.audio.NextTrackPreloader;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/** JavaFX desktop shell. Real local playback is independent from the demo search service. */
public final class LumenFxApp extends Application {
    private final PlayerQueue queue = new PlayerQueue();
    private final ObservableList<Track> searchResults = FXCollections.observableArrayList();
    private final ObservableList<Track> recommendations = FXCollections.observableArrayList();
    private final ObservableList<Track> radioTracks = FXCollections.observableArrayList();
    private final RadioSession radio = new RadioSession();
    private final RadioClient radioClient = new RadioClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final Label radioStatus = new Label("电台尚未开启");
    private Dialog<Void> radioDialog;
    private long radioVersion;
    private boolean radioLoading;
    private boolean radioContinuationPending;
    private long radioWaitingLoad;
    private boolean loadedNeutral;
    private final ExecutorService radioTasks = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "lumen-radio");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Map<URI, javafx.beans.property.ObjectProperty<Image>> coverThumbnails = new java.util.LinkedHashMap<>(16, .75f, true);
    private final java.util.Set<URI> loadingCovers = new java.util.HashSet<>();
    private final java.util.Set<URI> failedCovers = new java.util.HashSet<>();
    private CoverCache coverCache;
    private final java.util.Set<LyricMarquee> lyricMarquees = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private boolean sidebarIconOnly;
    private Timeline sidebarMotion;
    private final ObservableList<Track> localTracks = FXCollections.observableArrayList();
    private final javafx.collections.transformation.FilteredList<Track> visibleLocalTracks =
            new javafx.collections.transformation.FilteredList<>(localTracks);
    private final Label localStatus = new Label("正在读取本地音乐…");
    private final ComboBox<String> localFolder = new ComboBox<>();
    private final TextField localSearch = new TextField();
    private final Label localFilterCount = new Label();
    private LocalMusicStore localMusicStore;
    private boolean localCatalogueExisted;
    private boolean localImportBusy;
    private final java.util.Map<String, String> localLyrics = new java.util.HashMap<>();
    private final java.util.Map<URI, CompletableFuture<LocalAudioMetadata.Metadata>> localMetadataReads = new java.util.HashMap<>();
    private final CompletableFuture<Void> localCatalogueReady = new CompletableFuture<>();
    private final ExecutorService localTasks = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lumen-local-music");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Boolean> vlcReady;
    private final java.util.concurrent.atomic.AtomicReference<uk.co.caprica.vlcj.factory.MediaPlayerFactory>
            pendingVlcFactory = new java.util.concurrent.atomic.AtomicReference<>();
    private Button retryStartupButton;
    private boolean serviceInitialized;
    private String plainLyrics = "";
    private String lyricRecoveryNotice = "";
    private AppearancePreferencesStore appearancePreferences;
    private final Label appearanceRecoveryNotice = new Label();
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
    private final java.util.List<Button> themeSwatchButtons = new java.util.ArrayList<>();
    private final ObservableList<Track> queued = FXCollections.observableArrayList();
    private final java.util.Deque<QueueEdit> queueEdits = new java.util.ArrayDeque<>();
    private Button queueUndoButton;
    private Button playlistUndoButton;
    private String playlistUndoId;
    private String playlistUndoToken;
    private boolean playlistEditing;
    private ListView<Track> playlistTrackView;
    private ScrollPane playlistPageScroll;
    private String displayedPlaylistId;
    private final java.util.Map<String, ListBrowseState> playlistBrowseStates = new java.util.HashMap<>();
    private QuickSearchDialog quickSearchDialog;
    private Runnable openQueuePanel = () -> { };
    private Runnable openFocusScene = () -> { };
    private Runnable openDiaryScene = () -> { };
    private Runnable openLocalScene = () -> { };
    private Runnable openFavoriteScene = () -> { };
    private java.util.function.Consumer<String> openHomePlaylist = ignored -> { };
    private PersonalHomePane personalHome;
    private long homeRequestVersion;
    private boolean homePreferencesSaving;
    private String homeSessionKey = "";
    private SmartPlaylistClient.State homeOrganization = new SmartPlaylistClient.State(
            List.of(), List.of(), List.of(), List.of(), List.of(), null);
    private DiaryPane diaryPane;
    private PlaylistPresentationDialog playlistPresentation;
    private Tab diaryTab;
    private ListeningReporter.Context queueListeningContext;
    private java.util.Set<String> playlistQueueResources = java.util.Set.of();
    private final Label petEventNotice = new Label();
    private final PauseTransition petEventTimer = new PauseTransition(Duration.seconds(4));
    private long lastNightMinute = -1;
    private Tab lastBrowseTab;
    private Tab focusTab;
    private FocusPane focusPane;
    private ArtworkTransition artworkTransition;
    private IdlePlaybackControls idleControls;
    private long sceneMotionVersion;
    private boolean sleepPaused;
    private javafx.beans.value.ChangeListener<Boolean> motionListener;
    private Runnable clearSceneOverlays = () -> { };
    private final ObservableList<Track> favorites = FXCollections.observableArrayList();
    private final ObservableList<Track> playlistTracks = FXCollections.observableArrayList();
    private final ObservableList<LibraryClient.Playlist> playlists = FXCollections.observableArrayList();
    private final java.util.Map<Button, Supplier<Track>> managedFavoriteButtons = new java.util.HashMap<>();
    private final Label status = new Label("选择曲目开始播放");
    private final Label libraryStatus = new Label("个人曲库连接中…");
    private final Label searchStatus = new Label("搜索数据源连接中…");
    private final Label recommendationStatus = new Label("正在准备推荐…");
    private Button generateRecommendationsButton;
    private Button refreshRecommendationsButton;
    private RecommendationStore recommendationStore;
    private PlaybackStateStore playbackStateStore;
    private Double restoredPositionSeconds;
    private Double pendingResumeSeconds;
    private Button repeatControl;
    private javafx.scene.Node backgroundControls;
    private volatile boolean closing;
    private final Label nowPlaying = new Label("未选择曲目");
    private final Label coverPlaceholder = artworkPlaceholder();
    private final ImageView cover = new ImageView();
    private final Label lyricsStatus = new Label("暂无歌词");
    private final Label currentLyric = new Label("♪");
    private final ListView<String> lyricsView = new ListView<>();
    private final ListView<String> immersiveLyrics = new ListView<>();
    private int immersiveLyricPadding;
    private final Label immersiveTrackTitle = new Label("还没有播放歌曲");
    private final Label immersiveTrackArtist = new Label("从首页选一首喜欢的音乐");
    private final Label immersiveTrackSource = new Label();
    private final Label immersiveTonearmBadge = new Label("○ 唱针已抬起 · 待机");
    private Button immersiveSourceButton;
    private final ComboBox<LyricsClient.Candidate> lyricCandidates = new ComboBox<>();
    private final TextField lyricQuery = new TextField();
    private final Label lyricOffsetLabel = new Label("偏移 0.0 秒");
    private final LyricsClient lyricsClient = new LyricsClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final Label timing = new Label("00:00 / 00:00");
    private final Slider progress = new Slider(0, 1, 0);
    private final Button playButton = new Button("播放");
    private final Button favoriteButton = new Button("收藏当前");
    private final DemoAudioLibrary demoAudio = new DemoAudioLibrary();
    private final MusicClient client = new MusicClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final LibraryClient libraryClient = new LibraryClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final DiaryClient diaryClient = new DiaryClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final HomeClient homeClient = new HomeClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final VersionCatalogClient versionClient = new VersionCatalogClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private VersionCatalog versionCatalog = new VersionCatalog(List.of());
    private final SmartPlaylistClient smartClient = new SmartPlaylistClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private SmartPlaylistDialog smartDialog;
    private final BackupClient backupClient = new BackupClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final ListeningReporter listening = new ListeningReporter(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final ExecutorService libraryTasks = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lumen-library");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService lyricWrites = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lumen-lyrics-cache");
        thread.setDaemon(true);
        return thread;
    });
    private OnlineAudioCache onlineAudio;
    private PersistentAudioCache persistentCache;
    private NextTrackPreloader nextPreloader;
    private boolean preloadEnabled = true;
    private boolean shortFadeEnabled;
    private final PlaybackEnvelope playbackEnvelope = new PlaybackEnvelope();
    private boolean fadeInPending;
    private JavaFxAudioPlayer.State previousPlaybackState = JavaFxAudioPlayer.State.IDLE;
    private double outputVolume = -1;
    private double outputEnvelope = -1;
    private URI preparedToRetain;
    private Track trackToCache;
    private final AiSettingsClient aiSettingsClient = new AiSettingsClient(URI.create(
            System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765")));
    private final ExecutorService onlineDownloads = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "lumen-online-audio");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> pendingOnline;
    private MusicClient.DownloadCancellation pendingCancellation;
    private AudioPlayer audio;
    private JavaFxAudioPlayer localAudio;
    private VlcAudioPlayer streamingAudio;
    private double playbackVolume = 1.0;
    private double playbackRate = 1.0;
    private final PracticeLoop practiceLoop = new PracticeLoop();
    private Dialog<Void> practiceDialog;
    private SegmentBatchDialog segmentBatchDialog;
    private TextField practiceStart;
    private TextField practiceEnd;
    private CheckBox practiceEnabled;
    private ComboBox<Double> practiceRate;
    private Label practiceFeedback;
    private Button practiceSave;
    private final Label practiceSummary = new Label();
    private final List<Button> practiceButtons = new ArrayList<>();
    private Long practiceSeekTarget;
    private long practiceSeekStartedNanos;
    private long searchVersion;
    private long recommendationVersion;
    private long loadVersion;
    private long collectionVersion;
    private long seekVersion;
    private Track loadedTrack;
    private long endedLoadVersion = -1;
    private boolean segmentFinished;
    private boolean segmentSeeking;
    private long segmentSeekStartedNanos;
    private long lastSegmentSeekNanos;
    private SegmentPreview segmentPreview;
    private Double pendingLocalSeek;
    private Timeline clock;
    private Timeline volumeClock;
    private double unmutedVolume = 1.0;
    private final javafx.beans.property.ObjectProperty<JavaFxAudioPlayer.State> playbackUiState =
            new javafx.beans.property.SimpleObjectProperty<>(JavaFxAudioPlayer.State.IDLE);
    private final ToggleGroup lyricSizeGroup = new ToggleGroup();
    private ComboBox<LibraryClient.Playlist> playlistPicker;
    private ListView<LibraryClient.Playlist> playlistOrderList;
    private enum LyricSize {
        SMALL("标准", "A", 50, "lyric-size-small"),
        MEDIUM("舒适", "A+", 58, "lyric-size-medium"),
        LARGE("巨幕", "A++", 68, "lyric-size-large");

        final String label;
        final String shortLabel;
        final int cellHeight;
        final String styleClass;

        LyricSize(String label, String shortLabel, int cellHeight, String styleClass) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.cellHeight = cellHeight;
            this.styleClass = styleClass;
        }
    }
    private LyricSize currentLyricSize = LyricSize.MEDIUM;
    private Button lyricSizeButton;
    private List<Lyrics.Line> activeLyrics = List.of();
    private int activeLyricIndex = -2;
    private long lyricsReadVersion;
    private long lyricOffsetMillis;
    private long lyricStartMillis;
    private long lyricMediaVersion = -1;
    private LyricsSelectionStore lyricSelectionStore;
    private LyricsSelectionStore.Selection manualLyricSelection;
    private long lastListeningSampleNanos;
    private boolean listeningActive;
    private ThemeController themeController;
    private ThemeController miniThemeController;
    private ThemeController.Mode themeMode = ThemeController.Mode.DARK;
    private ThemeController.Accent accent = ThemeController.Accent.PINK;
    private URI backgroundUri;
    private PetCompanion pet;
    private DesktopPetWindow desktopPet;
    private Runnable updatePetPlacement = () -> {};
    private Runnable updateSidebarLayout = () -> {};
    private boolean petOnDesktopImmersive = true;
    private boolean petOnDesktopMinimized;
    private double petDesktopX = Double.NaN;
    private double petDesktopY = Double.NaN;
    private PetCompanion.Species petSpecies = PetCompanion.Species.BLACK_CAT;
    private Stage miniStage;
    private Stage primaryStage;
    private WindowsTaskbarControls taskbarControls;
    private WindowsTaskbarControls miniTaskbarControls;
    private double sidebarWidth = 228;
    private double queueWidth = 360;
    private boolean sidebarCollapsed;
    private final List<ListView<Track>> trackViews = new ArrayList<>();
    private TrackPreferencesStore trackPreferences;
    private final Label failureNotice = new Label();
    private final Label normalizationStatus = new Label();
    private GlobalHotkeys globalHotkeys;
    private HotkeySettingsStore hotkeySettingsStore;
    private final java.util.Map<GlobalHotkeys.Action, GlobalHotkeys.Combination> hotkeyBindings =
            new java.util.EnumMap<>(GlobalHotkeys.Action.class);
    private boolean normalizationEnabled;
    private boolean recordingHotkey;
    private Stage collectionStage;
    private Label hotkeyStatus;
    private boolean retryingPlayback;
    private VBox queueRail;
    private int openingRetries;
    private boolean openingRetry;
    private boolean currentPlaybackStarted;
    private boolean currentAutoplay;
    private boolean onlineStreamOpening;
    private DiagnosticLog diagnosticLog;
    private final Label updateStatus = new Label();
    private boolean updateBusy;
    private Dialog<ButtonType> updateConfirm;

    @Override public void start(Stage stage) { startUi(stage, getParameters().getRaw()); }

    void startUi(Stage stage, List<String> launchArgs) {
        primaryStage = stage;
        try { diagnosticLog = new DiagnosticLog(dataDirectory().resolve("diagnostics.xml")); }
        catch (IOException error) { updateStatus.setText("诊断记录暂不可用，原文件已保留"); }
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.getIcons().add(new Image(getClass().getResource("/icons/lumen-256.png").toExternalForm()));
        try { persistentCache = new PersistentAudioCache(dataDirectory().resolve("audio-cache")); }
        catch (IOException error) { status.setText("音频缓存不可用：" + error.getMessage()); }
        try { lyricSelectionStore = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory()); }
        catch (IOException error) { System.err.println("手动歌词记录不可用：" + error.getMessage()); }
        try { coverCache = new CoverCache(dataDirectory().resolve("covers")); }
        catch (IOException error) { System.err.println("封面缓存暂不可用：" + error.getMessage()); }
        try { trackPreferences = new TrackPreferencesStore(dataDirectory().resolve("track-preferences.properties")); }
        catch (IOException error) { status.setText("曲目偏好读取失败：" + error.getMessage()); }
        updateQueuePreferences();
        lyricOffsetLabel.setId("lyric-offset-label");
        onlineAudio = new OnlineAudioCache(client, persistentCache);
        nextPreloader = new NextTrackPreloader(client, onlineAudio, persistentCache);
        localAudio = new JavaFxAudioPlayer(this::onPlaybackState, this::onAudioError);
        audio = localAudio;
        Label brand = new Label("Lumen Music");
        brand.getStyleClass().add("brand");
        ImageView brandGlyph = new ImageView(getClass().getResource("/icons/lumen-256.png").toExternalForm());
        brandGlyph.setFitWidth(24);
        brandGlyph.setFitHeight(24);
        Button importButton = new Button("导入文件");
        importButton.setId("import-local-audio");
        importButton.getStyleClass().add("accent-button");
        importButton.setOnAction(event -> importFiles(stage));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button themeButton = new Button();
        themeButton.setId("theme-button");
        readAppearance();
        themeButton.setText(themeLabel());
        ComboBox<ThemeController.Accent> accentPicker = new ComboBox<>(
                FXCollections.observableArrayList(ThemeController.Accent.values()));
        accentPicker.setId("accent-picker");
        if (themeMode == ThemeController.Mode.LUMEN) accent = ThemeController.Accent.GOLD;
        accentPicker.setValue(accent);
        accentPicker.setDisable(themeMode == ThemeController.Mode.LUMEN);
        accentPicker.setTooltip(new Tooltip("Lumen 主题使用固定暖金色；其他主题可自选强调色"));
        accentPicker.setOnAction(event -> {
            accent = accentPicker.getValue();
            applyAppearance();
            saveAppearance();
        });
        java.util.function.Consumer<ThemeController.Mode> selectTheme = targetMode -> {
            themeMode = targetMode;
            if (themeMode == ThemeController.Mode.LUMEN) accentPicker.setValue(ThemeController.Accent.GOLD);
            accentPicker.setDisable(themeMode == ThemeController.Mode.LUMEN);
            themeButton.setText(themeLabel());
            updateThemeSwatches();
            applyAppearance();
            saveAppearance();
        };
        themeButton.setOnAction(event -> {
            ThemeController.Mode[] values = ThemeController.Mode.values();
            selectTheme.accept(values[(themeMode.ordinal() + 1) % values.length]);
        });
        Button chooseBackground = new Button("背景图");
        chooseBackground.setId("choose-background");
        chooseBackground.setOnAction(event -> chooseBackground(stage, themeButton));
        backgroundControls = chooseBackground;
        Button minimizeWindow = windowButton(stage, "minimize", "window-minimize");
        Button maximizeWindow = windowButton(stage, "maximize", "window-maximize");
        Button closeWindow = windowButton(stage, "close", "window-close");
        Label dragTitle = new Label("");
        dragTitle.getStyleClass().add("window-drag-title");
        installWindowDrag(dragTitle, stage);
        HBox brandBlock = new HBox(9, brandGlyph, brand);
        brandBlock.setAlignment(Pos.CENTER_LEFT);
        installWindowDrag(brandBlock, stage);
        installWindowDrag(spacer, stage);
        HBox header = new HBox(12, brandBlock, spacer, dragTitle,
                minimizeWindow, maximizeWindow, closeWindow);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        header.setId("native-titlebar");

        TextField query = new TextField();
        query.setId("search-query");
        query.setPromptText("搜索音乐，或粘贴 BV 号 / 视频链接…");
        ComboBox<String> searchKind = new ComboBox<>(FXCollections.observableArrayList("全部", "单曲", "合集"));
        searchKind.setId("search-kind");
        searchKind.setValue("全部");
        Button searchButton = new Button("搜索");
        searchButton.setId("search-button");
        searchButton.setOnAction(event -> search(query.getText(), searchKind.getValue()));
        query.setOnAction(event -> search(query.getText(), searchKind.getValue()));
        PauseTransition pastedBv = new PauseTransition(Duration.millis(320));
        pastedBv.setOnFinished(event -> {
            if (isBvInput(query.getText())) search(query.getText(), searchKind.getValue());
        });
        query.textProperty().addListener((ignored, before, after) -> {
            pastedBv.stop();
            if (isBvInput(after)) pastedBv.playFromStart();
        });
        HBox searchRow = new HBox(8, query, searchKind, searchButton);
        HBox.setHgrow(query, Priority.ALWAYS);
        query.setMinWidth(0);
        searchKind.setMinWidth(80);
        searchKind.setPrefWidth(90);
        searchButton.setMinWidth(Region.USE_PREF_SIZE);

        ListView<Track> searchList = trackList(searchResults);
        searchList.setId("search-list");
        ListView<Track> localList = trackList(visibleLocalTracks);
        ListView<Track> queueList = trackList(queued);
        localList.setId("local-list");
        queueList.setId("queue-list");
        queueList.getStyleClass().add("queue-track-list");
        searchList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                selectSearchResult(searchList.getSelectionModel().getSelectedIndex());
        });
        localList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                select(List.copyOf(visibleLocalTracks), localList.getSelectionModel().getSelectedIndex());
        });
        queueList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                int index = queueList.getSelectionModel().getSelectedIndex();
                if (index >= 0) {
                    queue.selectIndex(index);
                    loadCurrent(true);
                }
            }
        });
        enableDragReorder(queueList, Track::id, (from, to) -> {
            editQueue(() -> queue.reorder(from, to), "播放队列顺序已调整");
            queueList.getSelectionModel().select(to);
            status.setText("播放队列顺序已调整");
        });

        Button selectSearch = new Button("播放选中曲目");
        selectSearch.setId("select-search");
        selectSearch.setMaxWidth(Double.MAX_VALUE);
        selectSearch.setOnAction(event -> selectSearchResult(searchList.getSelectionModel().getSelectedIndex()));
        Label searchTitle = new Label("搜索音乐");
        searchTitle.getStyleClass().add("page-title");
        Label searchHint = new Label("搜索关键词，或粘贴 BV 号 / 视频链接");
        searchHint.getStyleClass().add("page-subtitle");
        searchHint.setWrapText(true);
        VBox searchPane = new VBox(14, searchTitle, searchHint, searchRow,
                searchStatus, trackManagementBar(searchList), searchList);
        searchPane.getStyleClass().add("content-page");
        searchStatus.setId("search-status");
        searchStatus.getStyleClass().add("muted");
        VBox.setVgrow(searchList, Priority.ALWAYS);
        searchPane.setPadding(new Insets(30, 32, 24, 32));
        Tab searchTab = new Tab("曲库搜索", searchPane);
        searchTab.setClosable(false);

        ListView<Track> recommendationList = trackList(recommendations, true);
        recommendationList.setId("recommendation-list");
        recommendationList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                select(recommendations, recommendationList.getSelectionModel().getSelectedIndex());
        });
        Button refreshRecommendations = new Button("刷新");
        refreshRecommendationsButton = refreshRecommendations;
        decorateIcon(refreshRecommendations, "repeat", "action-icon");
        refreshRecommendations.setId("refresh-recommendations");
        refreshRecommendations.setOnAction(event -> loadRecommendations(""));
        Button playRecommendation = new Button("播放选中推荐");
        playRecommendation.setId("select-recommendation");
        playRecommendation.setOnAction(event -> select(recommendations,
                recommendationList.getSelectionModel().getSelectedIndex()));
        recommendationStatus.setId("recommendation-status");
        recommendationStatus.getStyleClass().add("muted");
        recommendationStatus.setWrapText(true);
        recommendationStatus.setMinWidth(0);
        Button homeRadio = actionButton("私人电台", "home-radio", () -> showRadio(List.of(), homeRadioHint()));
        decorateIcon(homeRadio, "sparkle", "action-icon");
        TextField homeQuery = new TextField();
        homeQuery.setId("home-query");
        homeQuery.setPromptText("想听点安静的钢琴");
        homeQuery.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= 160 ? change : null));
        Button exploreMood = new Button("生成推荐");
        generateRecommendationsButton = exploreMood;
        exploreMood.setId("home-search-button");
        exploreMood.getStyleClass().add("accent-button");
        HBox moodRow = new HBox(10, homeQuery, exploreMood);
        moodRow.getStyleClass().add("home-search-row");
        HBox.setHgrow(homeQuery, Priority.ALWAYS);
        homeQuery.setMinWidth(0);
        Label moodTitle = new Label("此刻想听");
        moodTitle.getStyleClass().add("recommendation-heading");
        FlowPane moodChips = new FlowPane(6, 6);
        moodChips.getStyleClass().add("home-mood-chips");
        for (String mood : List.of("☕ 静心纯音", "🌙 夜色温柔", "⚡ 轻快律动", "🍃 放松治愈")) {
            Button chip = new Button(mood);
            chip.getStyleClass().add("home-mood-chip");
            chip.setFocusTraversable(false);
            chip.setOnAction(event -> {
                homeQuery.setText(mood.substring(2).strip());
                exploreMood.fire();
            });
            moodChips.getChildren().add(chip);
        }
        FlowPane discoveryActions = new FlowPane(8, 8, homeRadio, refreshRecommendations);
        VBox moodCard = new VBox(7, moodTitle, moodRow, moodChips, discoveryActions);
        moodCard.setId("recommendation-intent");
        moodCard.getStyleClass().add("recommendation-intent");
        Label recommendationHeading = new Label("推荐列表");
        recommendationHeading.getStyleClass().add("recommendation-heading");
        Region recommendationSpacer = new Region();
        HBox.setHgrow(recommendationSpacer, Priority.ALWAYS);
        HBox listHeading = new HBox(8, recommendationHeading, recommendationSpacer);
        listHeading.setAlignment(Pos.CENTER_LEFT);
        VBox recommendationSurface = new VBox(10, listHeading, trackManagementBar(recommendationList), recommendationList);
        recommendationSurface.getStyleClass().add("recommendation-surface");
        VBox.setVgrow(recommendationList, Priority.ALWAYS);
        recommendationList.setPlaceholder(emptyState("recommendation-empty", "还没有推荐",
                "写下此刻想听的音乐，点击生成推荐；也可以刷新热门音乐。"));
        VBox discoveryContent = new VBox(10, moodCard, recommendationStatus);
        personalHome = new PersonalHomePane(discoveryContent, recommendationSurface,
                new PersonalHomePane.Actions(this::continueHomePlayback, this::openHomePin,
                        track -> select(List.of(track), 0), () -> openLocalScene.run(),
                        () -> openFavoriteScene.run(), () -> openDiaryScene.run(),
                        () -> showSmartLibrary(null), this::saveHomePreferences),
                this::themePopup, this::themeSegmentDialog);
        personalHome.setIdentityFunctions(this::songTitle, this::performerCaption, this::coverImage);
        PersonalHomePane homePane = personalHome;
        Tab homeTab = new Tab("我的音乐", homePane);
        homeTab.setClosable(false);

        Button selectLocal = new Button("将选中本地曲目加入播放队列");
        selectLocal.setId("select-local");
        selectLocal.setMaxWidth(Double.MAX_VALUE);
        selectLocal.setOnAction(event -> select(List.copyOf(visibleLocalTracks), localList.getSelectionModel().getSelectedIndex()));
        VBox localPane = new VBox(12, trackManagementBar(localList), localList);
        VBox.setVgrow(localList, Priority.ALWAYS);
        localPane.setPadding(new Insets(30, 32, 24, 32));
        localPane.getStyleClass().add("content-page");
        localPane.getChildren().add(0, pageHeading("本地音乐", "导入文件或整个文件夹，封面和歌词随音乐一起呈现。"));
        Button importFolder = actionButton("导入文件夹", "import-local-folder", () -> importFolder(stage));
        Button refreshLocal = actionButton("刷新标签", "refresh-local-tags", () -> refreshLocalMetadata());
        Button removeLocal = actionButton("移除所选", "remove-local-selected",
                () -> removeLocalTracks(List.copyOf(localList.getSelectionModel().getSelectedItems())));
        Button locateLocal = actionButton("打开所在文件夹", "locate-local-file", () -> {
            Track track = localList.getSelectionModel().getSelectedItem();
            if (track != null) locateLocalTrack(track);
        });
        localSearch.setId("local-search");
        localSearch.setPromptText("搜索歌名、歌手或文件名");
        localSearch.setMinWidth(100);
        HBox.setHgrow(localSearch, Priority.ALWAYS);
        localFolder.setId("local-folder-filter");
        localFolder.getItems().add("全部文件夹");
        localFolder.setValue("全部文件夹");
        localFolder.setMaxWidth(240);
        Tooltip folderTip = readableTooltip("");
        folderTip.textProperty().bind(localFolder.valueProperty());
        localFolder.setTooltip(folderTip);
        localSearch.textProperty().addListener((ignored, before, after) -> filterLocalMusic());
        localFolder.valueProperty().addListener((ignored, before, after) -> filterLocalMusic());
        localTracks.addListener((javafx.collections.ListChangeListener<Track>) change -> refreshLocalFolders());
        Button localPlayAll = new Button("▶ 播放全部");
        localPlayAll.setId("local-play-all");
        localPlayAll.getStyleClass().add("playlist-play-all");
        localPlayAll.setDisable(visibleLocalTracks.isEmpty());
        localPlayAll.setOnAction(event -> {
            if (!visibleLocalTracks.isEmpty()) select(List.copyOf(visibleLocalTracks), 0);
        });
        visibleLocalTracks.addListener((javafx.collections.ListChangeListener<Track>) change ->
                localPlayAll.setDisable(visibleLocalTracks.isEmpty()));
        localFilterCount.setId("local-filter-count");
        localFilterCount.getStyleClass().addAll("muted", "playlist-filter-count");
        localFilterCount.setVisible(false);
        localFilterCount.setManaged(false);
        localStatus.setId("local-music-status");
        localStatus.setWrapText(true);
        localStatus.getStyleClass().add("muted");
        HBox localSearchRow = new HBox(10, localSearch, localFolder, localFilterCount);
        localSearchRow.setAlignment(Pos.CENTER_LEFT);
        localPane.getChildren().add(1, new VBox(10,
                new FlowPane(8, 8, localPlayAll, importButton, importFolder, refreshLocal, locateLocal, removeLocal),
                localSearchRow, localStatus));
        Tab localTab = new Tab("本地文件", localPane);
        localTab.setClosable(false);
        localPane.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) { event.acceptTransferModes(TransferMode.COPY); event.consume(); }
        });
        localPane.setOnDragDropped(event -> {
            boolean accepted = event.getDragboard().hasFiles();
            if (accepted) addFiles(event.getDragboard().getFiles());
            event.setDropCompleted(accepted);
            event.consume();
        });

        ListView<Track> favoriteList = trackList(favorites);
        favoriteList.setId("favorites-list");
        enableDragReorder(favoriteList, Track::id,
                (from, to) -> runLibrary(() -> libraryClient.moveFavorite(from, to),
                        "收藏顺序已更新", () -> favoriteList.getSelectionModel().select(to)));
        favoriteList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                select(favorites, favoriteList.getSelectionModel().getSelectedIndex());
        });
        Button playFavorite = new Button("播放选中收藏");
        playFavorite.setOnAction(event -> select(favorites, favoriteList.getSelectionModel().getSelectedIndex()));
        Button removeFavorite = new Button("取消收藏");
        removeFavorite.setId("unfavorite-button");
        removeFavorite.setOnAction(event -> {
            Track selected = favoriteList.getSelectionModel().getSelectedItem();
            if (selected != null) runLibrary(() -> libraryClient.unfavorite(selected.id()), "已取消收藏");
        });
        Button favoriteUp = new Button("上移");
        favoriteUp.setOnAction(event -> moveFavorite(favoriteList, -1));
        Button favoriteDown = new Button("下移");
        favoriteDown.setOnAction(event -> moveFavorite(favoriteList, 1));
        Button favoriteSelected = favoriteSelectionButton("favorite-selected-favorite", favoriteList);
        MenuButton favoritePlaylistMenu = playlistSelectionMenu("favorite-selected-playlist",
                () -> favoriteList.getSelectionModel().getSelectedItem());
        Button favoritePlayNext = playNextSelectionButton("favorite-selected-next", favoriteList);
        Button cleanupFavorites = actionButton("清理失效收藏", "cleanup-favorites",
                () -> cleanupUnavailable(null));
        VBox favoritePane = new VBox(10, trackManagementBar(favoriteList), favoriteList);
        favoritePane.setPadding(new Insets(30, 32, 24, 32));
        favoritePane.getStyleClass().add("content-page");
        VBox favoriteHeading = pageHeading("我的收藏", "喜欢的音乐，随时回来听。");
        favoriteHeading.setMinWidth(0);
        for (var child : favoriteHeading.getChildren()) if (child instanceof Label text) {
            text.setMinWidth(0);
            text.setWrapText(true);
        }
        Button catalogButton = actionButton("版本管理", "version-library", this::showVersionLibrary);
        decorateIcon(catalogButton, "versions", "action-icon");
        Button organizeFavorites = actionButton("标签与智能歌单", "organize-favorites", () -> showSmartLibrary(null));
        decorateIcon(organizeFavorites, "tags", "action-icon");
        HBox favoriteHeader = new HBox(12, favoriteHeading, organizeFavorites, catalogButton);
        favoriteHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(favoriteHeading, Priority.ALWAYS);

        Button favoritePlayAll = new Button("▶ 播放全部");
        favoritePlayAll.setId("favorite-play-all");
        favoritePlayAll.getStyleClass().add("playlist-play-all");
        favoritePlayAll.setDisable(favorites.isEmpty());
        favoritePlayAll.setOnAction(event -> {
            if (!favorites.isEmpty()) select(favorites, 0);
        });
        favorites.addListener((javafx.collections.ListChangeListener<Track>) change ->
                favoritePlayAll.setDisable(favorites.isEmpty()));

        TextField favoriteFilter = new TextField();
        favoriteFilter.setId("favorite-quick-filter");
        favoriteFilter.setPromptText("快速查找收藏...");
        favoriteFilter.getStyleClass().add("playlist-filter-field");
        favoriteFilter.setPrefWidth(160);
        favoriteFilter.setDisable(favorites.isEmpty());
        Label favoriteFilterCount = new Label();
        favoriteFilterCount.setId("favorite-filter-count");
        favoriteFilterCount.getStyleClass().addAll("muted", "playlist-filter-count");
        favoriteFilterCount.setVisible(false);
        favoriteFilterCount.setManaged(false);

        installTrackLocator(favoriteFilter, favoriteFilterCount, favoriteList, favorites);

        HBox favoritePrimaryActions = new HBox(10, favoritePlayAll, favoriteFilter, favoriteFilterCount);
        favoritePrimaryActions.setAlignment(Pos.CENTER_LEFT);
        favoritePrimaryActions.setId("favorite-primary-actions");
        VBox favoritePageHeader = new VBox(12, favoriteHeader, favoritePrimaryActions);
        favoritePane.getChildren().add(0, favoritePageHeader);
        VBox.setVgrow(favoriteList, Priority.ALWAYS);
        Tab favoriteTab = new Tab("收藏", favoritePane);
        favoriteTab.setClosable(false);

        playlistPicker = new ComboBox<>(playlists);
        playlistPicker.setId("playlist-picker");
        playlistPicker.setMaxWidth(Double.MAX_VALUE);
        playlistPicker.setPromptText("选择歌单");
        playlistPicker.setOnAction(event -> showPlaylistTracks());
        playlistPicker.setVisible(false);
        playlistPicker.setManaged(false);
        playlistOrderList = new ListView<>(playlists);
        playlistOrderList.setId("playlist-order-list");
        playlistOrderList.getStyleClass().add("sidebar-playlist-list");
        playlistOrderList.setCellFactory(ignored -> new ListCell<>() {
            { setMinWidth(0); setPrefWidth(0); }
            @Override protected void updateItem(LibraryClient.Playlist playlist, boolean empty) {
                super.updateItem(playlist, empty);
                setText(empty || playlist == null ? null : playlist.toString());
            }
        });
        playlistOrderList.setPrefHeight(116);
        playlistOrderList.setMinHeight(42);
        playlistOrderList.setPlaceholder(new Label("暂无歌单，请先新建"));
        playlistOrderList.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) -> {
            if (selected != null && (playlistPicker.getValue() == null
                    || !selected.id().equals(playlistPicker.getValue().id())))
                playlistPicker.setValue(selected);
        });
        enableDragReorder(playlistOrderList, LibraryClient.Playlist::id,
                (from, to) -> runLibrary(() -> libraryClient.movePlaylist(from, to),
                        "歌单顺序已更新", () -> playlistOrderList.getSelectionModel().select(to)));
        ListView<Track> playlistList = trackList(playlistTracks);
        playlistTrackView = playlistList;
        playlistList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        playlistList.setId("playlist-tracks");
        playlistList.setPlaceholder(emptyState("playlist-empty", "歌单里还没有音乐",
                "从搜索结果的更多菜单加入歌曲，或先在左侧选择一个歌单。"));
        playlistList.setPrefHeight(360);
        playlistList.setMinHeight(120);
        enableDragReorder(playlistList, Track::id, (from, to) -> {
            var selected = playlistPicker.getValue();
            if (selected != null) runLibrary(() -> libraryClient.moveInPlaylist(selected.id(), from, to),
                    "歌单曲目顺序已更新", () -> playlistList.getSelectionModel().select(to));
        });
        playlistList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                select(playlistTracks, playlistList.getSelectionModel().getSelectedIndex());
        });
        Button createPlaylist = new Button("新建歌单");
        createPlaylist.setId("create-playlist");
        createPlaylist.setOnAction(event -> askPlaylistName("新建歌单", "歌单名称", null,
                name -> runLibrary(() -> libraryClient.createPlaylist(name), "歌单已创建", () -> {
                    if (!playlists.isEmpty()) playlistPicker.setValue(playlists.get(playlists.size() - 1));
                })));
        Button renamePlaylist = new Button("重命名");
        renamePlaylist.setOnAction(event -> {
            var selected = playlistPicker.getValue();
            if (selected != null) askPlaylistName("重命名歌单", "新名称", selected.name(),
                    name -> runLibrary(() -> libraryClient.renamePlaylist(selected.id(), name), "歌单已重命名"));
        });
        Button deletePlaylist = new Button("删除歌单");
        deletePlaylist.setId("delete-playlist");
        renamePlaylist.disableProperty().bind(playlistPicker.valueProperty().isNull());
        deletePlaylist.disableProperty().bind(playlistPicker.valueProperty().isNull());
        deletePlaylist.setOnAction(event -> {
            var selected = playlistPicker.getValue();
            if (selected != null && confirmRemoval("删除歌单", "删除歌单“" + selected.name()
                    + "”及其中 " + selected.tracks().size() + " 首曲目的排列、封面与说明？\n曲目本身仍保留，此操作不能撤回。"))
                runLibrary(() -> libraryClient.deletePlaylist(selected.id()), "歌单已删除");
        });
        Button addToPlaylist = new Button("加入当前曲目");
        addToPlaylist.setId("add-to-playlist");
        addToPlaylist.setOnAction(event -> {
            var selected = playlistPicker.getValue();
            Track track = queue.current();
            if (selected != null && track != null)
                runLibrary(() -> libraryClient.addToPlaylist(selected.id(), track), "已加入歌单");
            else libraryStatus.setText("请先选择歌单和当前曲目");
        });
        Button removeFromPlaylist = new Button("移除选中");
        removeFromPlaylist.setId("remove-from-playlist");
        removeFromPlaylist.setOnAction(event -> {
            var selected = playlistPicker.getValue();
            if (selected != null) removePlaylistTracks(selected,
                    List.copyOf(playlistList.getSelectionModel().getSelectedItems()));
        });
        Button playlistUp = new Button("上移");
        playlistUp.setId("playlist-up");
        playlistUp.setOnAction(event -> movePlaylistTrack(playlistList, -1));
        Button playlistDown = new Button("下移");
        playlistDown.setOnAction(event -> movePlaylistTrack(playlistList, 1));
        Button playPlaylistTrack = new Button("播放选中");
        playPlaylistTrack.setOnAction(event -> select(playlistTracks,
                playlistList.getSelectionModel().getSelectedIndex()));
        Button playlistFavorite = favoriteSelectionButton("playlist-selected-favorite", playlistList);
        MenuButton playlistSaveMenu = playlistSelectionMenu("playlist-selected-save",
                () -> playlistList.getSelectionModel().getSelectedItem());
        Button playlistPlayNext = playNextSelectionButton("playlist-selected-next", playlistList);
        Button cleanupPlaylist = actionButton("清理失效曲目", "cleanup-playlist", () -> {
            if (playlistPicker.getValue() != null) cleanupUnavailable(playlistPicker.getValue());
        });
        Button importFavorite = new Button("导入公开收藏夹");
        importFavorite.setId("import-favorite");
        importFavorite.setOnAction(event -> importPublicFavorite());
        Button exportBackup = new Button("导出数据备份");
        exportBackup.setId("export-backup");
        exportBackup.setOnAction(event -> exportBackup(stage));
        Button restoreBackup = new Button("从备份恢复");
        restoreBackup.setId("restore-backup");
        restoreBackup.setOnAction(event -> restoreBackup(stage));
        Label playlistPageName = new Label("选择左侧歌单");
        playlistPageName.setId("playlist-page-name");
        playlistPageName.getStyleClass().add("page-title");
        playlistPageName.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip playlistNameTooltip = readableTooltip("");
        playlistNameTooltip.textProperty().bind(playlistPageName.textProperty());
        playlistPageName.setTooltip(playlistNameTooltip);
        Label playlistPageCount = new Label("展开左侧“我的歌单”，选择一个歌单查看曲目");
        playlistPageCount.setId("playlist-page-count");
        playlistPageCount.getStyleClass().add("muted");
        playlistPageCount.setWrapText(true);
        playlistPageName.setMinWidth(0);
        playlistPageName.setMaxWidth(Double.MAX_VALUE);
        PlaylistArtwork playlistArtwork = new PlaylistArtwork(104, this::coverImage);
        playlistArtwork.setId("playlist-cover");
        Label playlistDescription = new Label();
        playlistDescription.setId("playlist-description");
        playlistDescription.getStyleClass().add("muted");
        playlistDescription.setWrapText(true);
        playlistDescription.setMinWidth(0);
        playlistDescription.setMaxWidth(Double.MAX_VALUE);
        Tooltip descriptionTip = readableTooltip("");
        descriptionTip.textProperty().bind(playlistDescription.textProperty());
        playlistDescription.setTooltip(descriptionTip);
        Button playAllButton = new Button("▶ 播放全部");
        playAllButton.setId("playlist-play-all");
        playAllButton.getStyleClass().add("playlist-play-all");
        playAllButton.setDisable(true);
        playAllButton.setOnAction(event -> {
            if (!playlistTracks.isEmpty()) select(playlistTracks, 0);
        });
        TextField playlistFilter = new TextField();
        playlistFilter.setId("playlist-quick-filter");
        playlistFilter.setPromptText("快速查找曲目...");
        playlistFilter.getStyleClass().add("playlist-filter-field");
        playlistFilter.setPrefWidth(160);
        playlistFilter.setDisable(true);
        Label filterCount = new Label();
        filterCount.setId("playlist-filter-count");
        filterCount.getStyleClass().addAll("muted", "playlist-filter-count");
        filterCount.setVisible(false);
        filterCount.setManaged(false);

        installTrackLocator(playlistFilter, filterCount, playlistList, playlistTracks);

        HBox playlistPrimaryActions = new HBox(10, playAllButton, playlistFilter, filterCount);
        playlistPrimaryActions.setAlignment(Pos.CENTER_LEFT);
        playlistPrimaryActions.setPadding(new Insets(4, 0, 0, 0));

        String[] locatorPlaylistId = {null};
        playlistPicker.valueProperty().addListener((ignored, before, selected) -> {
            playlistPageName.setText(selected == null ? "选择左侧歌单" : selected.name());
            playlistPageCount.setText(selected == null ? "展开左侧“我的歌单”，选择一个歌单查看曲目"
                    : selected.tracks().size() + " 首曲目 · 双击播放，拖动调整顺序");
            playlistDescription.setText(selected == null ? "" : selected.description());
            playlistDescription.setVisible(!playlistDescription.getText().isBlank());
            playlistDescription.setManaged(playlistDescription.isVisible());
            playlistArtwork.update(selected);
            boolean empty = selected == null || selected.tracks().isEmpty();
            playAllButton.setDisable(empty);
            playlistFilter.setDisable(empty);
            if (selected != null && !java.util.Objects.equals(locatorPlaylistId[0], selected.id())) {
                playlistFilter.clear();
                locatorPlaylistId[0] = selected.id();
            }
        });
        Button editPresentation = new Button("封面与说明");
        editPresentation.setId("playlist-edit-presentation");
        editPresentation.disableProperty().bind(playlistPicker.valueProperty().isNull());
        editPresentation.setOnAction(event -> {
            var selected = playlistPicker.getValue();
            if (selected == null) return;
            if (playlistPresentation != null && playlistPresentation.isShowing()) return;
            playlistPresentation = new PlaylistPresentationDialog(primaryStage, selected, libraryClient,
                    libraryTasks, snapshot -> { applyLibrary(snapshot); libraryStatus.setText("歌单封面与说明已保存"); },
                    this::coverImage, this::themeSegmentDialog);
            playlistPresentation.show();
        });
        FlowPane playlistHeaderActions = new FlowPane(8, 8,
                createPlaylist, renamePlaylist, deletePlaylist, editPresentation);
        playlistHeaderActions.setId("playlist-header-actions");
        Button smartPlaylists = actionButton("智能歌单与整理", "smart-playlists", () -> showSmartLibrary(null));
        decorateIcon(smartPlaylists, "list", "action-icon");
        playlistHeaderActions.getChildren().add(smartPlaylists);
        playlistUndoButton = actionButton("撤销移除", "playlist-undo", this::undoPlaylistRemoval);
        decorateIcon(playlistUndoButton, "undo", "action-icon");
        playlistUndoButton.setDisable(true);
        playlistHeaderActions.getChildren().add(playlistUndoButton);
        Label playlistFeedback = new Label();
        playlistFeedback.setId("playlist-feedback");
        playlistFeedback.getStyleClass().add("muted");
        playlistFeedback.setWrapText(true);
        playlistFeedback.textProperty().bind(libraryStatus.textProperty());
        VBox playlistIdentity = new VBox(8, playlistPageName, playlistPageCount, playlistDescription, playlistPrimaryActions);
        playlistIdentity.setMinWidth(0);
        HBox.setHgrow(playlistIdentity, Priority.ALWAYS);
        HBox playlistHeading = new HBox(16, playlistArtwork, playlistIdentity);
        playlistHeading.setAlignment(Pos.CENTER_LEFT);
        VBox playlistPageHeader = new VBox(12,
                playlistHeading, playlistHeaderActions, playlistFeedback);
        playlistHeaderActions.getChildren().add(importFavorite);
        playlistHeaderActions.getChildren().add(actionButton("从歌单开启电台", "playlist-radio", () -> {
            if (playlistPicker.getValue() != null) showRadio(playlistPicker.getValue().tracks(), "");
        }));
        VBox playlistPane = new VBox(12, playlistPicker, playlistPageHeader, trackManagementBar(playlistList), playlistList);
        playlistPane.setPadding(new Insets(30, 32, 24, 32));
        playlistPane.getStyleClass().add("content-page");
        playlistPane.setId("playlist-page");
        var playlistScroll = new ScrollPane(playlistPane);
        playlistPageScroll = playlistScroll;
        playlistScroll.setId("playlist-scroll");
        playlistScroll.getStyleClass().add("playlist-scroll");
        playlistScroll.setFitToWidth(true);
        playlistScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Tab playlistTab = new Tab("歌单", playlistScroll);
        playlistTab.setClosable(false);

        ImageView immersiveCover = new ImageView();
        immersiveCover.setId("immersive-artwork-image");
        immersiveCover.imageProperty().bind(cover.imageProperty());
        immersiveCover.setFitWidth(450);
        immersiveCover.setFitHeight(281.25);
        immersiveCover.setPreserveRatio(true);
        immersiveCover.setSmooth(true);
        javafx.scene.shape.Rectangle artworkClip = new javafx.scene.shape.Rectangle(450, 281.25);
        artworkClip.setArcWidth(28);
        artworkClip.setArcHeight(28);
        Label immersivePlaceholder = artworkPlaceholder();
        immersivePlaceholder.visibleProperty().bind(coverPlaceholder.visibleProperty());
        immersivePlaceholder.getStyleClass().add("vinyl-placeholder");
        StackPane immersiveArtwork = new StackPane(immersivePlaceholder, immersiveCover);
        immersiveArtwork.setClip(artworkClip);
        Tooltip immersiveCoverTip = readableTooltip("");
        immersiveCoverTip.textProperty().bind(coverPlaceholder.accessibleTextProperty());
        Tooltip.install(immersiveArtwork, immersiveCoverTip);
        immersiveArtwork.setId("immersive-cover");
        immersiveArtwork.getStyleClass().add("immersive-cover");
        immersiveArtwork.setPrefSize(460, 287.5);
        immersiveArtwork.setMinSize(460, 287.5);
        immersiveArtwork.setMaxSize(460, 287.5);
        Label immersiveNow = new Label();
        immersiveNow.setId("immersive-now-playing");
        immersiveNow.textProperty().bind(nowPlaying.textProperty());
        immersiveNow.getStyleClass().add("immersive-now-caption");
        immersiveNow.setMaxWidth(440);
        immersiveNow.setTextOverrun(OverrunStyle.ELLIPSIS);
        immersiveTrackTitle.getStyleClass().add("immersive-title");
        immersiveTrackTitle.setWrapText(false);
        immersiveTrackTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip immersiveTitleTooltip = new Tooltip();
        immersiveTitleTooltip.textProperty().bind(immersiveTrackTitle.textProperty());
        immersiveTrackTitle.setTooltip(immersiveTitleTooltip);
        immersiveTrackTitle.setMinWidth(0);
        immersiveTrackTitle.setMaxWidth(Double.MAX_VALUE);
        immersiveTrackTitle.setMinHeight(Region.USE_PREF_SIZE);
        immersiveTrackArtist.getStyleClass().add("immersive-artist");
        immersiveTrackArtist.setMinWidth(0);
        immersiveTrackArtist.setMaxWidth(Double.MAX_VALUE);
        immersiveTrackArtist.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip performerTip = readableTooltip("");
        performerTip.textProperty().bind(immersiveTrackArtist.textProperty());
        immersiveTrackArtist.setTooltip(performerTip);
        immersiveTrackSource.setId("immersive-track-source");
        immersiveTrackSource.getStyleClass().addAll("muted", "immersive-source-badge");
        immersiveTrackSource.setMinWidth(0);
        immersiveTrackSource.setMaxWidth(Double.MAX_VALUE);
        immersiveTrackSource.setTextOverrun(OverrunStyle.ELLIPSIS);
        immersiveTrackSource.visibleProperty().bind(immersiveTrackSource.textProperty().isNotEmpty());
        immersiveTrackSource.managedProperty().bind(immersiveTrackSource.visibleProperty());
        Tooltip sourceTip = readableTooltip("");
        sourceTip.textProperty().bind(immersiveTrackSource.textProperty());
        immersiveTrackSource.setTooltip(sourceTip);
        immersiveTonearmBadge.setId("immersive-tonearm-badge");
        immersiveTonearmBadge.getStyleClass().addAll("immersive-tonearm-badge", "badge-idle");
        playbackUiState.addListener((ignored, before, state) -> updateTonearmBadge(state));
        updateTonearmBadge(playbackUiState.get());
        Label immersiveLine = new Label();
        immersiveLine.setId("immersive-current-lyric");
        immersiveLine.textProperty().bind(currentLyric.textProperty());
        immersiveLine.getStyleClass().add("immersive-current-lyric");
        immersiveLine.setMinWidth(0);
        immersiveLine.setMaxWidth(Double.MAX_VALUE);
        immersiveLine.setTextOverrun(OverrunStyle.ELLIPSIS);
        immersiveLine.textProperty().addListener((ignored, before, after) ->
                animateEntrance(immersiveLine, 0, 0));
        installLyricMarquee(immersiveLine);
        immersiveLyrics.setId("immersive-lyrics");
        immersiveLyrics.getStyleClass().add(currentLyricSize.styleClass);
        immersiveLyrics.setFixedCellSize(currentLyricSize.cellHeight);
        immersiveLyrics.heightProperty().addListener((ignored, before, after) -> {
            double cellSize = immersiveLyrics.getFixedCellSize() > 0 ? immersiveLyrics.getFixedCellSize() : 58;
            int padding = Math.max(1, (int) Math.ceil(after.doubleValue() / (cellSize * 2)));
            if (padding != immersiveLyricPadding) rebuildImmersiveLyrics();
            else if (activeLyricIndex >= 0) animateLyricScroll(activeLyricIndex);
        });
        Label emptyLyrics = new Label("暂无同步歌词\n可用左侧“匹配歌词”选择，音乐仍会继续播放。");
        emptyLyrics.setWrapText(true);
        emptyLyrics.getStyleClass().add("immersive-empty-lyrics");
        emptyLyrics.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        emptyLyrics.setMinHeight(Region.USE_PREF_SIZE);
        emptyLyrics.setPrefHeight(72);
        immersiveLyrics.setPlaceholder(emptyLyrics);
        javafx.scene.shape.Rectangle lyricFade = new javafx.scene.shape.Rectangle();
        lyricFade.widthProperty().bind(immersiveLyrics.widthProperty());
        lyricFade.heightProperty().bind(immersiveLyrics.heightProperty());
        lyricFade.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, javafx.scene.paint.Color.TRANSPARENT),
                new javafx.scene.paint.Stop(0.12, javafx.scene.paint.Color.WHITE),
                new javafx.scene.paint.Stop(0.88, javafx.scene.paint.Color.WHITE),
                new javafx.scene.paint.Stop(1, javafx.scene.paint.Color.TRANSPARENT)));
        immersiveLyrics.setClip(lyricFade);
        immersiveLyrics.setCellFactory(ignored -> new ListCell<>() {
            private final LyricMarquee marquee = new LyricMarquee(this,
                    () -> lyricLineProgress(getIndex() - immersiveLyricPadding));
            {
                lyricMarquees.add(marquee);
                marquee.prefWidthProperty().bind(widthProperty().subtract(16));
                setOnMouseClicked(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.PRIMARY
                            && event.getClickCount() == 1 && event.isStillSincePress()
                            && !event.isShiftDown() && !event.isControlDown())
                        seekLyricLine(getIndex() - immersiveLyricPadding);
                });
            }
            @Override protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                marquee.setText(empty ? "" : line);
                setGraphic(empty ? null : marquee);
                getStyleClass().remove("current-lyric");
                if (!empty && getIndex() - immersiveLyricPadding == activeLyricIndex)
                    getStyleClass().add("current-lyric");
                setContextMenu(empty || line == null || line.isBlank() ? null
                        : lyricFragmentMenu(immersiveLyrics, getIndex(), immersiveLyricPadding));
            }
        });
        immersiveLyrics.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Button immersiveFavorite = mirrorButton(favoriteButton, "immersive-favorite");
        Button openBilibili = actionButton("在 B 站打开", "immersive-open-source", () -> {
            Track track = queue.current();
            if (track != null && track.bvid() != null)
                getHostServices().showDocument("https://www.bilibili.com/video/" + track.bvid());
        });
        openBilibili.setDisable(true);
        immersiveSourceButton = openBilibili;
        Button immersiveAddToPlaylist = actionButton("加入歌单", "immersive-add-playlist",
                () -> { });
        immersiveAddToPlaylist.setOnAction(event -> {
            Track track = queue.current();
            if (track == null) return;
            ContextMenu menu = batchTrackMenu(List.of(track), null);
            Menu target = (Menu) menu.getItems().stream().filter(item -> "batch-menu-playlist".equals(item.getId())).findFirst().orElseThrow();
            ContextMenu picker = new ContextMenu();
            List<MenuItem> choices = List.copyOf(target.getItems());
            target.getItems().clear();
            picker.getItems().setAll(choices);
            if (picker.getItems().isEmpty()) {
                MenuItem empty = new MenuItem("请先创建歌单"); empty.setDisable(true); picker.getItems().add(empty);
            }
            picker.setOnShowing(show -> themePopup(picker));
            picker.setOnShown(show -> themePopup(picker));
            picker.show(immersiveAddToPlaylist, Side.BOTTOM, 0, 3);
        });
        Button immersiveMatchLyrics = actionButton("手动匹配歌词", "immersive-match-lyrics",
                () -> showLyricMatchDialog(stage));
        Button createSegment = actionButton("保存片段", "create-segment", () -> {
            if (queue.current() != null) showSegmentDialog(queue.current());
        });
        decorateIcon(createSegment, "segment", "action-icon");
        Button batchSegments = actionButton("合集整理", "immersive-segment-batch", () -> {
            if (queue.current() != null) showSegmentBatch(queue.current());
        });
        decorateIcon(batchSegments, "list", "action-icon");
        Button manageVersions = actionButton("版本", "immersive-versions", () -> {
            if (queue.current() != null) showVersions(queue.current());
        });
        decorateIcon(manageVersions, "versions", "action-icon");
        Button immersiveRadio = actionButton("电台", "immersive-radio", () -> {
            if (queue.current() != null) showRadio(List.of(queue.current()), "");
        });
        decorateIcon(immersiveRadio, "sparkle", "action-icon");
        Button immersiveFocus = actionButton("专注陪伴", "immersive-focus", () -> openFocusScene.run());
        decorateIcon(immersiveFocus, "focus", "action-icon");
        Button immersivePractice = actionButton("精听与练习", "immersive-practice", this::showPracticeDialog);
        decorateIcon(immersivePractice, "repeat-one", "action-icon");
        practiceButtons.add(immersivePractice);
        immersivePractice.getStyleClass().add("practice-toggle");
        decorateIcon(openBilibili, "external", "action-icon");
        decorateIcon(immersiveFavorite, "heart", "action-icon");
        decorateIcon(immersiveAddToPlaylist, "playlist-add", "action-icon");
        decorateIcon(immersiveMatchLyrics, "lyrics", "action-icon");
        Button immersiveMore = actionButton("更多播放操作", "immersive-more", () -> { });
        decorateIcon(immersiveMore, "more", "action-icon");
        FlowPane immersiveActions = new FlowPane(9, 9, openBilibili, immersiveFavorite,
                immersiveAddToPlaylist, immersiveMatchLyrics, immersiveMore);
        immersiveActions.setPrefWrapLength(380);
        for (Button action : new Button[] {openBilibili, immersiveFavorite,
                immersiveAddToPlaylist, immersiveMatchLyrics, createSegment, manageVersions, immersiveRadio, immersiveFocus,
                immersivePractice, batchSegments})
            action.setMinWidth(Region.USE_PREF_SIZE);
        immersiveActions.setAlignment(Pos.CENTER);
        immersiveActions.getStyleClass().add("immersive-actions");
        immersiveActions.setId("immersive-actions");
        Label immersiveOffset = new Label();
        immersiveOffset.textProperty().bind(lyricOffsetLabel.textProperty());
        FlowPane lyricCalibration = new FlowPane(9, 7, new Label("歌词校准"),
                actionButton("−0.5s", "immersive-earlier", () -> shiftLyrics(-500)),
                immersiveOffset,
                actionButton("+0.5s", "immersive-later", () -> shiftLyrics(500)),
                actionButton("全文", "immersive-full-text", this::showFullLyrics),
                actionButton("歌曲起点", "immersive-lyric-start", () -> showLyricStartDialog(stage)),
                actionButton("对齐选中句", "immersive-lyric-align", () -> alignLyric(immersiveLyrics, true)));
        lyricCalibration.setAlignment(Pos.CENTER);
        lyricCalibration.getStyleClass().add("immersive-calibration");
        lyricCalibration.setId("immersive-calibration");
        ContextMenu immersiveMenu = new ContextMenu();
        for (Button action : new Button[] {createSegment, manageVersions, immersiveRadio,
                immersiveFocus, immersivePractice, batchSegments}) {
            MenuItem item = trackMenuItem(action.getText(), switch (action.getId()) {
                case "create-segment" -> "segment";
                case "immersive-versions" -> "versions";
                case "immersive-radio" -> "sparkle";
                case "immersive-focus" -> "focus";
                case "immersive-practice" -> "repeat-one";
                default -> "playlist-add";
            }, action.getId(), action::fire);
            item.disableProperty().bind(action.disableProperty());
            immersiveMenu.getItems().add(item);
        }
        immersiveMenu.getItems().add(new SeparatorMenuItem());
        Menu lyricSizeMenu = new Menu("歌词字号");
        lyricSizeMenu.setId("immersive-lyric-size-menu");
        lyricSizeMenu.setGraphic(icon("lyrics", "menu-icon"));
        for (LyricSize size : LyricSize.values()) {
            RadioMenuItem item = new RadioMenuItem(size.label);
            item.setToggleGroup(lyricSizeGroup);
            item.setUserData(size);
            item.setSelected(size == currentLyricSize);
            item.setOnAction(e -> applyLyricSize(size));
            lyricSizeMenu.getItems().add(item);
        }
        immersiveMenu.getItems().add(lyricSizeMenu);
        immersiveMenu.getItems().add(trackMenuItem("歌词校准", "lyrics", "immersive-calibrate", () -> {
            lyricCalibration.setVisible(!lyricCalibration.isVisible());
            lyricCalibration.setManaged(lyricCalibration.isVisible());
        }));
        immersiveMenu.getItems().add(trackMenuItem("查看完整歌词", "lyrics", "immersive-lyrics-full", this::showFullLyrics));
        immersiveMenu.setOnShowing(event -> themePopup(immersiveMenu));
        immersiveMenu.setOnShown(event -> themePopup(immersiveMenu));
        immersiveMore.setOnAction(event -> immersiveMenu.show(immersiveMore, Side.BOTTOM, 0, 4));
        lyricCalibration.setVisible(false);
        lyricCalibration.setManaged(false);
        HBox tonearmRow = new HBox(immersiveTonearmBadge);
        tonearmRow.setAlignment(Pos.CENTER);
        tonearmRow.setPadding(new Insets(0, 0, 2, 0));
        VBox immersiveLeft = new VBox(12, tonearmRow, immersiveArtwork, immersiveActions, lyricCalibration);
        immersiveLeft.getStyleClass().add("immersive-left");
        immersiveLeft.setAlignment(Pos.CENTER);
        immersiveLeft.setMinWidth(0);
        immersiveLeft.setPrefWidth(410);
        Label lyricHeading = new Label("正在播放");
        lyricHeading.getStyleClass().add("immersive-heading");
        Region lyricHeaderSpacer = new Region();
        HBox.setHgrow(lyricHeaderSpacer, Priority.ALWAYS);
        Button lyricSizeToggle = actionButton(currentLyricSize.shortLabel, "immersive-lyric-size-toggle", () -> {
            LyricSize next = switch (currentLyricSize) {
                case SMALL -> LyricSize.MEDIUM;
                case MEDIUM -> LyricSize.LARGE;
                case LARGE -> LyricSize.SMALL;
            };
            applyLyricSize(next);
        });
        lyricSizeToggle.setFocusTraversable(false);
        lyricSizeToggle.getStyleClass().add("immersive-size-chip");
        lyricSizeToggle.setTooltip(readableTooltip("歌词字号：" + currentLyricSize.label + "（点击切换，支持滚轮）"));
        lyricSizeToggle.setOnScroll(event -> {
            if (event.getDeltaY() > 0) {
                if (currentLyricSize == LyricSize.SMALL) applyLyricSize(LyricSize.MEDIUM);
                else if (currentLyricSize == LyricSize.MEDIUM) applyLyricSize(LyricSize.LARGE);
            } else if (event.getDeltaY() < 0) {
                if (currentLyricSize == LyricSize.LARGE) applyLyricSize(LyricSize.MEDIUM);
                else if (currentLyricSize == LyricSize.MEDIUM) applyLyricSize(LyricSize.SMALL);
            }
            event.consume();
        });
        lyricSizeButton = lyricSizeToggle;
        HBox lyricHeader = new HBox(8, lyricHeading, lyricHeaderSpacer, lyricSizeToggle);
        lyricHeader.setAlignment(Pos.CENTER_LEFT);
        Label immersiveLyricsStatus = new Label();
        immersiveLyricsStatus.textProperty().bind(lyricsStatus.textProperty());
        immersiveLyricsStatus.getStyleClass().add("immersive-lyrics-status");
        immersiveLyricsStatus.setWrapText(true);
        VBox immersiveWords = new VBox(8, lyricHeader, immersiveTrackTitle,
                immersiveTrackArtist, immersiveTrackSource, immersiveLine, immersiveLyrics, immersiveLyricsStatus);
        immersiveWords.getStyleClass().add("immersive-right");
        immersiveWords.setMinWidth(0);
        VBox.setVgrow(immersiveLyrics, Priority.ALWAYS);
        HBox immersiveBody = new HBox(36, immersiveLeft, immersiveWords);
        immersiveBody.getStyleClass().add("immersive-body");
        immersiveBody.setAlignment(Pos.CENTER);
        immersiveBody.setMinHeight(0);
        HBox.setHgrow(immersiveWords, Priority.ALWAYS);
        VBox.setVgrow(immersiveBody, Priority.ALWAYS);
        Button immersiveCollapse = new Button("收起播放页");
        decorateIcon(immersiveCollapse, "collapse", "action-icon");
        immersiveCollapse.setId("immersive-collapse");
        Button immersiveMinimize = windowButton(stage, "minimize", "immersive-minimize");
        Button immersiveMaximize = windowButton(stage, "maximize", "immersive-maximize");
        Button immersiveClose = windowButton(stage, "close", "immersive-close");
        Region immersiveTopSpacer = new Region();
        HBox.setHgrow(immersiveTopSpacer, Priority.ALWAYS);
        HBox immersiveTop = new HBox(8, immersiveCollapse, immersiveNow, immersiveTopSpacer,
                immersiveMinimize, immersiveMaximize, immersiveClose);
        immersiveTop.getStyleClass().add("immersive-top");
        immersiveTop.setAlignment(Pos.CENTER_LEFT);
        installWindowDrag(immersiveTopSpacer, stage);
        installWindowDrag(immersiveNow, stage);
        immersiveNow.setMinWidth(0);
        HBox.setHgrow(immersiveNow, Priority.ALWAYS);
        for (Button control : new Button[] {immersiveCollapse, immersiveMinimize,
                immersiveMaximize, immersiveClose}) control.setMinWidth(Region.USE_PREF_SIZE);
        VBox immersiveContent = new VBox(12, immersiveTop, immersiveBody);
        immersiveContent.setId("immersive-shell");
        immersiveContent.getStyleClass().addAll("immersive-page", "immersive-shell");
        immersiveContent.setPadding(new Insets(16, 24, 10, 24));
        Tab immersiveTab = new Tab("沉浸播放", immersiveContent);
        immersiveTab.setClosable(false);

        TabPane tabs = new TabPane(homeTab, searchTab, localTab, favoriteTab, playlistTab, immersiveTab);
        focusPane = new FocusPane(() -> tabs.getSelectionModel().select(lastBrowseTab),
                () -> advance(-1), () -> playButton.fire(), () -> advance(1),
                this::pauseForSleep, message -> {
                    status.setText(message);
                    if (pet != null && "专注完成".equals(message))
                        notifyPetMusicEvent(PetCompanion.Event.FOCUS_COMPLETED, "");
                }, this::seekToFraction);
        focusPane.titleProperty().bind(immersiveTrackTitle.textProperty());
        focusPane.artistProperty().bind(immersiveTrackArtist.textProperty());
        focusPane.lyricProperty().bind(currentLyric.textProperty());
        focusPane.coverImageProperty().bind(cover.imageProperty());
        focusPane.timeProperty().bind(timing.textProperty());
        focusPane.progressProperty().bindBidirectional(progress.valueProperty());
        focusPane.seekSlider().disableProperty().bind(progress.disableProperty());
        decorateIcon(focusPane.previousButton(), "previous", "transport-icon");
        decorateIcon(focusPane.nextButton(), "next", "transport-icon");
        Button focusPlay = focusPane.playButton();
        focusPlay.disableProperty().bind(playButton.disableProperty());
        stylePlayButton(focusPlay);
        focusTab = new Tab("专注陪伴", focusPane);
        focusTab.setId("focus-tab");
        focusTab.setClosable(false);
        openFocusScene = () -> tabs.getSelectionModel().select(focusTab);
        tabs.setId("main-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        lastBrowseTab = homeTab;
        immersiveCollapse.setOnAction(event -> tabs.getSelectionModel().select(lastBrowseTab));
        exploreMood.setOnAction(event -> {
            loadRecommendations(homeQuery.getText().strip());
        });
        homeQuery.setOnAction(event -> exploreMood.fire());
        Button openImmersive = new Button("展开播放页");
        decorateIcon(openImmersive, "disc", "action-icon");
        openImmersive.setId("open-immersive");
        openImmersive.setOnAction(event -> tabs.getSelectionModel().select(immersiveTab));
        Button openMini = new Button("迷你播放器");
        decorateIcon(openMini, "mini", "action-icon");
        openMini.setId("open-mini");
        openMini.setOnAction(event -> showMini(stage));
        Button openFocus = actionButton("专注陪伴", "open-focus", () -> tabs.getSelectionModel().select(focusTab));
        decorateIcon(openFocus, "focus", "action-icon");
        Button aiSettings = new Button("AI 推荐设置");
        decorateIcon(aiSettings, "sparkle", "nav-icon");
        aiSettings.setId("ai-settings-button");
        aiSettings.setOnAction(event -> showAiSettings(stage));
        Button cacheSettings = new Button("音频缓存设置");
        decorateIcon(cacheSettings, "cache", "nav-icon");
        cacheSettings.setId("cache-settings-button");
        cacheSettings.setOnAction(event -> showCacheSettings(stage));
        Button homeNav = navButton("我的音乐", "nav-home", tabs, homeTab);
        Button searchNav = navButton("搜索音乐", "nav-search", tabs, searchTab);
        Button localNav = navButton("本地音乐", "nav-local", tabs, localTab);
        Button favoriteNav = navButton("我的收藏", "nav-favorites", tabs, favoriteTab);
        Button playlistNav = navButton("我的歌单", "nav-playlists", tabs, playlistTab);
        decorateIcon(homeNav, "home", "nav-icon");
        decorateIcon(searchNav, "search", "nav-icon");
        decorateIcon(localNav, "music", "nav-icon");
        decorateIcon(favoriteNav, "heart", "nav-icon");
        decorateIcon(playlistNav, "list", "nav-icon");
        playlistNav.setText("我的歌单  ▸");
        VBox sidebarPlaylists = new VBox(4, playlistOrderList);
        sidebarPlaylists.setId("sidebar-playlists");
        sidebarPlaylists.getStyleClass().add("sidebar-playlists");
        sidebarPlaylists.setVisible(false);
        sidebarPlaylists.setManaged(false);
        playlistNav.setOnAction(event -> {
            boolean expanded = !sidebarPlaylists.isVisible();
            playlistNav.getProperties().put("expanded", expanded);
            sidebarPlaylists.setVisible(expanded);
            sidebarPlaylists.setManaged(expanded);
            playlistNav.setText(expanded ? "我的歌单  ▾" : "我的歌单  ▸");
            tabs.getSelectionModel().select(playlistTab);
            retryPlaylistResources(List.copyOf(playlistTracks));
        });
        playlistOrderList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY
                    && playlistOrderList.getSelectionModel().getSelectedItem() != null)
                tabs.getSelectionModel().select(playlistTab);
        });
        playlists.addListener((javafx.collections.ListChangeListener<LibraryClient.Playlist>) change ->
                playlistOrderList.setPrefHeight(Math.min(220, Math.max(44, playlists.size() * 38 + 8))));
        Button[] navigation = { homeNav, searchNav, localNav, favoriteNav, playlistNav };
        for (Button item : navigation) {
            item.getStyleClass().add("nav-button");
            item.setMaxWidth(Double.MAX_VALUE);
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) -> {
            Tab[] pages = { homeTab, searchTab, localTab, favoriteTab, playlistTab };
            for (int index = 0; index < navigation.length; index++)
                navigation[index].pseudoClassStateChanged(
                        javafx.css.PseudoClass.getPseudoClass("active"), selected == pages[index]);
        });
        homeNav.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), true);
        pet = new PetCompanion();
        pet.setSpecies(petSpecies);
        pet.setReducedMotion(MotionPreferences.reduced());
        pet.setCompact(true);
        pet.setOnMouseClicked(event -> {
            if (pet.isQuiet()) pet.onInteraction("pet");
        });
        ComboBox<PetCompanion.Species> petPicker = new ComboBox<>(
                FXCollections.observableArrayList(PetCompanion.Species.values()));
        petPicker.setId("pet-picker");
        petPicker.setValue(petSpecies);
        petPicker.setOnAction(event -> {
            petSpecies = petPicker.getValue();
            pet.setSpecies(petSpecies);
            if (petSpecies == PetCompanion.Species.HIDDEN) {
                petEventTimer.stop();
                if (petEventNotice != null) petEventNotice.setVisible(false);
            }
            updatePetPlacement.run();
            saveAppearance();
        });
        Label petCaption = new Label("桌宠陪伴");
        petCaption.getStyleClass().add("sidebar-caption");
        petPicker.setMinWidth(98);
        HBox petControls = new HBox(8, petCaption, petPicker);
        petControls.setAlignment(Pos.CENTER_LEFT);
        VBox petDock = new VBox(8, pet);
        petDock.setId("pet-dock");
        petDock.getStyleClass().add("pet-sidebar");
        petDock.setAlignment(Pos.CENTER_LEFT);
        CheckBox desktopImmersive = new CheckBox("沉浸播放时桌宠移到桌面");
        desktopImmersive.setId("pet-desktop-immersive");
        desktopImmersive.setSelected(petOnDesktopImmersive);
        CheckBox desktopMinimized = new CheckBox("最小化时桌宠留在桌面");
        desktopMinimized.setId("pet-desktop-minimized");
        desktopMinimized.setSelected(petOnDesktopMinimized);
        desktopPet = new DesktopPetWindow(pet, petDock, () -> {
            stage.setIconified(false);
            stage.show();
            stage.toFront();
            sidebarCollapsed = false;
            tabs.getSelectionModel().select(homeTab);
            updateSidebarLayout.run();
            desktopPet.hideToDock();
            saveAppearance();
        });
        desktopPet.setPosition(petDesktopX, petDesktopY);
        updatePetPlacement = () -> {
            if (closing) return;
            petEventTimer.stop();
            if (petEventNotice != null) petEventNotice.setVisible(false);
            boolean floating = petSpecies != PetCompanion.Species.HIDDEN
                    && ((sidebarIconOnly && tabs.getSelectionModel().getSelectedItem() != focusTab)
                    || (petOnDesktopImmersive && tabs.getSelectionModel().getSelectedItem() == immersiveTab)
                    || (petOnDesktopMinimized && stage.isIconified()));
            boolean focusing = tabs.getSelectionModel().getSelectedItem() == focusTab;
            petDock.setVisible(petSpecies != PetCompanion.Species.HIDDEN && (focusing || !sidebarIconOnly));
            petDock.setManaged(petSpecies != PetCompanion.Species.HIDDEN && !focusing && !sidebarIconOnly);
            if (petSpecies == PetCompanion.Species.HIDDEN) desktopPet.hideToDock();
            else if (floating) desktopPet.showAnimated();
            else if (desktopPet.isShowing()) desktopPet.hideAnimated();
        };
        desktopImmersive.setOnAction(event -> { petOnDesktopImmersive = desktopImmersive.isSelected(); updatePetPlacement.run(); saveAppearance(); });
        desktopMinimized.setOnAction(event -> { petOnDesktopMinimized = desktopMinimized.isSelected(); updatePetPlacement.run(); saveAppearance(); });
        stage.iconifiedProperty().addListener((ignored, before, after) -> updatePetPlacement.run());
        TitledPane aiSection = settingsSection("AI 推荐", "settings-ai", new VBox(5, aiSettings), "智能推荐 · 灵感联想");
        Label historyPrivacy = new Label("听歌记录仅保存在本机");
        historyPrivacy.getStyleClass().add("muted");
        historyPrivacy.setWrapText(true);
        Label privacyFeedback = new Label();
        privacyFeedback.setId("privacy-feedback");
        privacyFeedback.textProperty().bind(libraryStatus.textProperty());
        privacyFeedback.getStyleClass().add("muted");
        privacyFeedback.setWrapText(true);
        TitledPane dataSection = settingsSection("数据与隐私", "settings-data",
                new VBox(6, historyPrivacy, exportBackup, restoreBackup,
                        actionButton("清理全部失效曲目", "cleanup-unavailable-all", this::cleanupAllUnavailable), privacyFeedback));
        Label audioMethod = new Label("音频获取方式：免登录在线播放");
        audioMethod.setWrapText(true);
        audioMethod.getStyleClass().add("muted");
        CheckBox normalize = new CheckBox("音量归一化");
        normalize.setId("audio-normalization-enabled");
        normalize.setSelected(normalizationEnabled);
        normalize.setOnAction(event -> {
            normalizationEnabled = normalize.isSelected();
            applyAudioPreferences();
            saveAppearance();
        });
        CheckBox preload = new CheckBox("提前准备下一曲");
        preload.setId("audio-preload-enabled");
        preload.setSelected(preloadEnabled);
        preload.setOnAction(event -> {
            preloadEnabled = preload.isSelected();
            refreshNextPreparation();
            saveAppearance();
        });
        CheckBox shortFade = new CheckBox("短淡入淡出");
        shortFade.setId("audio-short-fade-enabled");
        shortFade.setSelected(shortFadeEnabled);
        shortFade.setOnAction(event -> {
            shortFadeEnabled = shortFade.isSelected();
            playbackEnvelope.start(shortFadeEnabled,
                    System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(PlaybackEnvelope.FADE_MILLIS));
            updateFadeClock();
            applyOutputVolume();
            saveAppearance();
        });
        normalizationStatus.getStyleClass().add("muted");
        normalizationStatus.setWrapText(true);
        normalizationStatus.setId("audio-normalization-status");
        VBox cachePanel = persistentCache == null ? new VBox(new Label("音频缓存不可用"))
                : CacheSettingsDialog.createPanel(persistentCache);
        TitledPane audioSection = settingsSection("音频", "settings-audio",
                new VBox(8, audioMethod, preload, shortFade, normalize, normalizationStatus, cachePanel), "音量平衡 · 增益");
        audioSection.expandedProperty().addListener((ignored, before, expanded) -> {
            if (expanded && cachePanel.getUserData() instanceof Runnable refresh) refresh.run();
        });
        TitledPane hotkeysSection = settingsSection("快捷键", "settings-hotkeys", createHotkeySettings(), "全局按键与操作设置");
        CheckBox reduceMotion = new CheckBox("减少动态效果");
        reduceMotion.setId("reduce-motion");
        reduceMotion.setSelected(MotionPreferences.reduced());
        reduceMotion.setOnAction(event -> {
            MotionPreferences.setReduced(reduceMotion.isSelected());
            saveAppearance();
        });
        Label appearanceHint = new Label("选择熟悉的光与颜色，让封面融入背景。");
        appearanceHint.getStyleClass().add("muted");
        appearanceHint.setWrapText(true);
        appearanceRecoveryNotice.setId("appearance-recovery-notice");
        appearanceRecoveryNotice.setWrapText(true);
        appearanceRecoveryNotice.getStyleClass().add("muted");
        appearanceRecoveryNotice.visibleProperty().bind(appearanceRecoveryNotice.textProperty().isNotEmpty());
        appearanceRecoveryNotice.managedProperty().bind(appearanceRecoveryNotice.visibleProperty());
        HBox themeSwatchBar = new HBox(8);
        themeSwatchBar.getStyleClass().add("theme-swatch-bar");
        themeSwatchBar.setId("theme-swatch-bar");
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            String label = switch (mode) {
                case LUMEN -> "\u2726 \u6696\u91d1";
                case DARK -> "\u25cf \u58a8\u9ed1";
                case LIGHT -> "\u25cb \u6696\u767d";
                case BACKGROUND -> "\ud83d\uddbc \u58c1\u7eb8";
                case DYNAMIC -> "\u273a \u6d41\u5149";
            };
            Button swatch = new Button(label);
            swatch.setUserData(mode);
            swatch.getStyleClass().add("theme-swatch-button");
            swatch.setId("theme-swatch-" + mode.name().toLowerCase(java.util.Locale.ROOT));
            swatch.setOnAction(event -> selectTheme.accept(mode));
            themeSwatchButtons.add(swatch);
            themeSwatchBar.getChildren().add(swatch);
        }
        updateThemeSwatches();
        Label visualSummary = new Label();
        Runnable updateVisualSummary = () -> {
            String theme = switch (themeMode) {
                case LUMEN -> "暖金黑胶";
                case DARK -> "墨黑深色";
                case LIGHT -> "暖白明亮";
                case DYNAMIC -> "黑胶流光";
                case BACKGROUND -> "个性壁纸";
            };
            visualSummary.setText(theme + (MotionPreferences.reduced() ? " · 减弱动效" : " · 标准动效"));
        };
        themeButton.textProperty().addListener((ignored, before, text) -> updateVisualSummary.run());
        updateVisualSummary.run();
        reduceMotion.setOnAction(event -> {
            MotionPreferences.setReduced(reduceMotion.isSelected());
            saveAppearance();
            updateVisualSummary.run();
        });
        VBox appearanceControls = new VBox(10, appearanceHint, appearanceRecoveryNotice, themeSwatchBar, themeButton, accentPicker,
                chooseBackground, reduceMotion);
        TitledPane visualSettings = settingsSection("外观与动效", "visual-settings", appearanceControls, visualSummary);
        visualSettings.setExpanded(true);
        TitledPane companionSettings = settingsSection("桌宠与陪伴", "companion-settings",
                new VBox(10, petControls, desktopImmersive, desktopMinimized, openFocus));
        VBox toolbar = new VBox(14, visualSettings, companionSettings,
                audioSection, hotkeysSection, aiSection, dataSection, createVersionSettings());
        toolbar.setId("main-toolbar");
        for (Control control : new Control[] {themeButton, accentPicker, chooseBackground,
                aiSettings, cacheSettings, exportBackup, restoreBackup}) {
            control.setMaxWidth(360);
            control.setPrefWidth(320);
        }
        ScrollPane toolbarScroll = new ScrollPane(toolbar);
        toolbarScroll.setFitToWidth(true);
        toolbarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        toolbarScroll.setMaxHeight(Double.MAX_VALUE);
        toolbarScroll.setMinHeight(0);
        toolbarScroll.setId("settings-scroll");
        Label navLabel = new Label("音乐空间");
        navLabel.getStyleClass().add("sidebar-caption");
        Button settingsBack = actionButton("返回", "settings-back", () -> tabs.getSelectionModel().select(lastBrowseTab));
        VBox settingsHeading = pageHeading("设置", "外观、声音与个人数据，都在这里。");
        HBox settingsHeader = new HBox(14, settingsBack, settingsHeading);
        settingsHeader.setAlignment(Pos.CENTER_LEFT);
        VBox settingsPage = new VBox(18, settingsHeader, toolbarScroll);
        settingsPage.setId("settings-page");
        settingsPage.getStyleClass().add("settings-page");
        settingsPage.setPadding(new Insets(22, 30, 22, 30));
        VBox.setVgrow(toolbarScroll, Priority.ALWAYS);
        Tab settingsTab = new Tab("设置", settingsPage);
        settingsTab.setClosable(false);
        tabs.getTabs().add(settingsTab);
        tabs.getTabs().add(focusTab);
        diaryPane = new DiaryPane(diaryClient, this::playResource, this::playLyricFavorite, playlistId ->
                playlists.stream().filter(item -> item.id().equals(playlistId)).findFirst().ifPresentOrElse(item -> {
                    playlistPicker.setValue(item);
                    tabs.getSelectionModel().select(playlistTab);
                }, () -> status.setText("这个歌单已不在当前曲库中")), status::setText, this::themePopup);
        diaryPane.setReadFailureRecovery(previousFailure -> {
            if (status.getText().equals(previousFailure)) status.setText("听歌记录已更新");
        });
        diaryPane.setTrackActions(this::isFavorite, this::toggleTrackFavorite,
                this::diaryTrackMenu, selected -> batchTrackMenu(selected, null));
        diaryTab = new Tab("最近播放", diaryPane);
        diaryTab.setId("diary-tab");
        diaryTab.setClosable(false);
        tabs.getTabs().add(diaryTab);
        openDiaryScene = () -> {
            tabs.getSelectionModel().select(diaryTab);
            diaryPane.refresh();
        };
        openLocalScene = () -> tabs.getSelectionModel().select(localTab);
        openFavoriteScene = () -> tabs.getSelectionModel().select(favoriteTab);
        openHomePlaylist = id -> playlists.stream().filter(item -> item.id().equals(id))
                .findFirst().ifPresentOrElse(item -> {
                    playlistPicker.setValue(item);
                    tabs.getSelectionModel().select(playlistTab);
                }, () -> personalHome.setFeedback("这个歌单已不在当前曲库中，请重新调整常驻。"));
        tabs.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) -> {
            if (selected == homeTab) refreshPersonalHome();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) -> {
            if (selected != immersiveTab && selected != settingsTab && selected != focusTab) lastBrowseTab = selected;
        });
        quickSearchDialog = new QuickSearchDialog(stage, () -> QuickSearchIndex.build(
                List.copyOf(favorites), List.copyOf(playlists), versionCatalog.songs().stream()
                        .flatMap(song -> song.versions().stream()).map(VersionCatalog.Version::track).toList(),
                List.copyOf(localTracks), List.of(
                        new QuickSearchIndex.Command("home", "我的音乐", "打开个人首页", () -> tabs.getSelectionModel().select(homeTab)),
                        new QuickSearchIndex.Command("favorites", "我的收藏", "打开收藏", () -> tabs.getSelectionModel().select(favoriteTab)),
                        new QuickSearchIndex.Command("settings", "设置", "打开设置", () -> tabs.getSelectionModel().select(settingsTab)),
                        new QuickSearchIndex.Command("focus", "专注陪伴", "专注与睡眠定时", () -> tabs.getSelectionModel().select(focusTab)),
                        new QuickSearchIndex.Command("practice", "精听与练习", "歌词定位、A–B 循环与播放速度", this::showPracticeDialog),
                        new QuickSearchIndex.Command("diary", "最近播放", "收听回顾、备注与歌词收藏", openDiaryScene),
                        new QuickSearchIndex.Command("toggle", "播放或暂停", "当前歌曲", () -> playButton.fire()),
                        new QuickSearchIndex.Command("next", "下一首", "切换歌曲", () -> advance(1)),
                        new QuickSearchIndex.Command("import", "导入本地音频", "选择音频文件", () -> importFiles(stage)),
                        new QuickSearchIndex.Command("import-folder", "导入音乐文件夹", "包含子文件夹", () -> importFolder(stage)),
                        new QuickSearchIndex.Command("organize", "智能歌单与整理", "整理曲库", () -> showSmartLibrary(null)),
                        new QuickSearchIndex.Command("radio", "私人电台", "打开电台", () -> showRadio(List.of(), "")),
                        new QuickSearchIndex.Command("queue", "播放队列", "打开队列", () -> openQueuePanel.run()))),
                this::playResource, playlist -> {
                    playlists.stream().filter(item -> item.id().equals(playlist.id())).findFirst().ifPresent(item -> {
                        playlistPicker.setValue(item);
                        tabs.getSelectionModel().select(playlistTab);
                    });
                }, this::themeSegmentDialog);
        SmoothScrollPane.install(toolbarScroll);
        Button toolsSection = new Button("设置");
        toolsSection.setId("sidebar-settings");
        decorateIcon(toolsSection, "settings", "nav-icon");
        toolsSection.getStyleClass().add("nav-button");
        toolsSection.setMaxWidth(Double.MAX_VALUE);
        toolsSection.setOnAction(event -> tabs.getSelectionModel().select(settingsTab));
        toolsSection.setMinHeight(Region.USE_PREF_SIZE);
        Button quickSearchButton = actionButton("快捷搜索", "quick-search-open", () -> quickSearchDialog.show());
        decorateIcon(quickSearchButton, "search", "nav-icon");
        quickSearchButton.getStyleClass().add("nav-button");
        quickSearchButton.setMaxWidth(Double.MAX_VALUE);
        quickSearchButton.setTooltip(new Tooltip("快捷搜索 Ctrl + K"));
        Button focusNav = navButton("专注陪伴", "nav-focus", tabs, focusTab);
        decorateIcon(focusNav, "focus", "nav-icon");
        focusNav.getStyleClass().add("nav-button");
        focusNav.setMaxWidth(Double.MAX_VALUE);
        Button diaryNav = navButton("最近播放", "nav-diary", tabs, diaryTab);
        decorateIcon(diaryNav, "history", "nav-icon");
        diaryNav.getStyleClass().add("nav-button");
        diaryNav.setMaxWidth(Double.MAX_VALUE);
        diaryNav.setOnAction(event -> openDiaryScene.run());
        Region sidebarSpace = new Region();
        VBox.setVgrow(sidebarSpace, Priority.ALWAYS);
        Button collapseSidebar = new Button("折叠侧栏");
        collapseSidebar.setId("sidebar-collapse");
        collapseSidebar.getStyleClass().add("sidebar-collapse-button");
        Button compactSettings = new Button("设置");
        compactSettings.setId("sidebar-compact-settings");
        decorateIcon(compactSettings, "settings", "action-icon");
        VBox sidebar = new VBox(6, navLabel, homeNav, searchNav, localNav,
                favoriteNav, playlistNav, diaryNav, focusNav, sidebarPlaylists, sidebarSpace, quickSearchButton, toolsSection,
                petDock);
        sidebar.setId("main-sidebar");
        sidebar.setPrefWidth(sidebarWidth);
        sidebar.setMinWidth(0);
        sidebar.setMaxWidth(Double.MAX_VALUE);
        sidebar.setMinHeight(0);
        sidebarPlaylists.setMinHeight(0);
        sidebarPlaylists.setMaxHeight(220);
        playlistOrderList.setMinHeight(0);
        VBox.setVgrow(sidebarPlaylists, Priority.ALWAYS);
        VBox.setVgrow(playlistOrderList, Priority.ALWAYS);
        ScrollPane sidebarScroll = new ScrollPane(sidebar);
        sidebarScroll.setId("sidebar-scroll");
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setFitToHeight(true);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.setMinWidth(0);
        BorderPane sidebarFrame = new BorderPane(sidebarScroll);
        sidebarFrame.setBottom(collapseSidebar);
        collapseSidebar.setMaxWidth(Double.MAX_VALUE);
        Region sidebarDivider = new Region();
        sidebarDivider.setId("sidebar-resize-handle");
        sidebarDivider.setMinWidth(6);
        sidebarDivider.setPrefWidth(6);
        sidebarDivider.setMaxWidth(6);
        sidebarDivider.setCursor(javafx.scene.Cursor.H_RESIZE);
        Runnable applySidebar = () -> {
            double available = primaryStage.getScene() == null ? 1280 : primaryStage.getScene().getWidth();
            if (queueRail != null && queueRail.isManaged()) {
                double actualQueueWidth = Math.min(queueWidth, Math.max(300, available - 398));
                queueRail.setMinWidth(actualQueueWidth);
                queueRail.setPrefWidth(actualQueueWidth);
                queueRail.setMaxWidth(actualQueueWidth);
            }
            double maximum = available - 326 - (queueRail != null && queueRail.isManaged()
                    ? queueRail.getPrefWidth() + 6 : 0);
            boolean iconOnly = sidebarCollapsed || maximum < 176;
            double width = iconOnly ? 66 : Math.min(sidebarWidth, maximum);
            if (sidebarMotion == null || sidebarMotion.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                sidebarScroll.setMinWidth(width);
                sidebarScroll.setPrefWidth(width);
                sidebarScroll.setMaxWidth(width);
            }
            sidebarIconOnly = iconOnly;
            sidebar.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("collapsed"), iconOnly);
            for (Button nav : navigation) {
                nav.setContentDisplay(iconOnly ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
                stableTooltip(nav, nav.getText(), iconOnly);
                nav.setAlignment(iconOnly ? Pos.CENTER : Pos.CENTER_LEFT);
            }
            focusNav.setContentDisplay(iconOnly ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
            focusNav.setAlignment(iconOnly ? Pos.CENTER : Pos.CENTER_LEFT);
            stableTooltip(focusNav, "专注陪伴", iconOnly);
            diaryNav.setContentDisplay(iconOnly ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
            diaryNav.setAlignment(iconOnly ? Pos.CENTER : Pos.CENTER_LEFT);
            stableTooltip(diaryNav, "最近播放", iconOnly);
            for (javafx.scene.Node node : new javafx.scene.Node[] {navLabel}) {
                node.setVisible(!iconOnly);
                node.setManaged(!iconOnly);
            }
            sidebarPlaylists.setVisible(!iconOnly && playlistNav.getProperties().getOrDefault("expanded", false).equals(true));
            sidebarPlaylists.setManaged(sidebarPlaylists.isVisible());
            toolsSection.setContentDisplay(iconOnly ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
            toolsSection.setAlignment(iconOnly ? Pos.CENTER : Pos.CENTER_LEFT);
            stableTooltip(toolsSection, "设置", true);
            quickSearchButton.setContentDisplay(iconOnly ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
            quickSearchButton.setAlignment(iconOnly ? Pos.CENTER : Pos.CENTER_LEFT);
            if (collapseSidebar.getGraphic() instanceof SVGPath glyph)
                glyph.setContent(iconPath(iconOnly ? "sidebar-open" : "sidebar-close"));
            else collapseSidebar.setGraphic(icon(iconOnly ? "sidebar-open" : "sidebar-close", "action-icon"));
            collapseSidebar.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            stableTooltip(collapseSidebar, iconOnly ? "展开侧栏" : "折叠侧栏", true);
            updatePetPlacement.run();
        };
        collapseSidebar.setOnAction(event -> {
            if (sidebarMotion != null) sidebarMotion.stop();
            double beforeWidth = sidebarScroll.getWidth();
            sidebarCollapsed = !sidebarCollapsed;
            applySidebar.run();
            double targetWidth = sidebarScroll.getPrefWidth();
            if (!MotionPreferences.reduced()) {
                sidebarScroll.setMinWidth(beforeWidth);
                sidebarScroll.setPrefWidth(beforeWidth);
                sidebarScroll.setMaxWidth(beforeWidth);
                sidebarMotion = new Timeline(new KeyFrame(Duration.millis(240),
                        new KeyValue(sidebarScroll.minWidthProperty(), targetWidth, Interpolator.EASE_BOTH),
                        new KeyValue(sidebarScroll.prefWidthProperty(), targetWidth, Interpolator.EASE_BOTH),
                        new KeyValue(sidebarScroll.maxWidthProperty(), targetWidth, Interpolator.EASE_BOTH)));
                sidebarMotion.setOnFinished(done -> applySidebar.run());
                sidebarMotion.play();
            }
            saveAppearance();
        });
        updateSidebarLayout = applySidebar;
        compactSettings.setOnAction(event -> tabs.getSelectionModel().select(settingsTab));
        double[] sidebarDrag = new double[2];
        sidebarDivider.setOnMousePressed(event -> {
            if (sidebarMotion != null) sidebarMotion.stop();
            sidebarDrag[0] = event.getScreenX();
            sidebarDrag[1] = sidebarScroll.getWidth();
        });
        sidebarDivider.setOnMouseDragged(event -> {
            double desired = sidebarDrag[1] + event.getScreenX() - sidebarDrag[0];
            sidebarCollapsed = desired < 120;
            if (!sidebarCollapsed) sidebarWidth = Math.max(176, Math.min(330, desired));
            applySidebar.run();
            event.consume();
        });
        sidebarDivider.setOnMouseReleased(event -> saveAppearance());
        applySidebar.run();

        Label queueTitle = new Label("播放队列");
        queueTitle.getStyleClass().add("section-title");
        queueUndoButton = actionButton("撤销", "queue-undo", this::undoQueueEdit);
        decorateIcon(queueUndoButton, "undo", "action-icon");
        queueUndoButton.setDisable(true);
        Button clearQueueButton = actionButton("清空", "queue-clear", this::clearQueue);
        decorateIcon(clearQueueButton, "trash", "action-icon");
        HBox queueHeading = new HBox(8, queueTitle, queueUndoButton, clearQueueButton);
        queueHeading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(queueTitle, Priority.ALWAYS);
        queueTitle.setMaxWidth(Double.MAX_VALUE);
        Button queueFavorite = favoriteSelectionButton("queue-selected-favorite", queueList);
        MenuButton queueSaveMenu = playlistSelectionMenu("queue-selected-playlist",
                () -> queueList.getSelectionModel().getSelectedItem());
        Button queuePlayNext = playNextSelectionButton("queue-selected-next", queueList);
        FlowPane queueManage = new FlowPane(6, 6, queueFavorite, queueSaveMenu, queuePlayNext);
        queueManage.setId("queue-manage");
        lyricsView.setId("lyrics-list");
        lyricsView.setPlaceholder(new Label("暂无歌词"));
        lyricsView.setPrefHeight(120);
        lyricsView.setMinHeight(90);
        lyricsView.setFixedCellSize(34);
        lyricsView.setCellFactory(ignored -> new ListCell<>() {
            private final LyricMarquee marquee = new LyricMarquee(this,
                    () -> lyricLineProgress(getIndex()));
            {
                lyricMarquees.add(marquee);
                marquee.prefWidthProperty().bind(widthProperty().subtract(16));
                setOnMouseClicked(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.PRIMARY
                            && event.getClickCount() == 1 && event.isStillSincePress()
                            && !event.isShiftDown() && !event.isControlDown()) seekLyricLine(getIndex());
                });
            }
            @Override protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                marquee.setText(empty ? "" : line);
                setGraphic(empty ? null : marquee);
                getStyleClass().remove("current-lyric");
                if (!empty && getIndex() == activeLyricIndex) getStyleClass().add("current-lyric");
                setContextMenu(empty ? null : lyricFragmentMenu(lyricsView, getIndex(), 0));
            }
        });
        lyricsView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Button chooseLyrics = new Button("选择 LRC 歌词");
        chooseLyrics.setId("choose-lyrics");
        chooseLyrics.setOnAction(event -> chooseLyrics(stage));
        Button findLyrics = new Button("搜索歌词");
        findLyrics.setId("search-lyrics");
        findLyrics.setOnAction(event -> {
            Track track = queue.current();
            if (track != null) searchOnlineLyrics(track, lyricQuery.getText(), "", loadVersion);
        });
        lyricQuery.setId("lyrics-query");
        lyricQuery.setPromptText("按曲名搜索候选歌词");
        lyricQuery.setOnAction(event -> findLyrics.fire());
        HBox.setHgrow(lyricQuery, Priority.ALWAYS);
        lyricQuery.setMinWidth(0);
        lyricCandidates.setId("lyrics-candidates");
        lyricCandidates.setPromptText("选择在线歌词候选");
        lyricCandidates.setMaxWidth(Double.MAX_VALUE);
        Button applyLyrics = new Button("使用选中歌词");
        applyLyrics.setId("apply-lyrics");
        applyLyrics.setOnAction(event -> applyOnlineLyrics(lyricCandidates.getValue(), false));
        Button earlierLyrics = new Button("-0.5 秒");
        earlierLyrics.setId("lyrics-earlier");
        earlierLyrics.setOnAction(event -> shiftLyrics(-500));
        Button laterLyrics = new Button("+0.5 秒");
        laterLyrics.setId("lyrics-later");
        laterLyrics.setOnAction(event -> shiftLyrics(500));
        Button fullLyrics = new Button("全文浏览");
        fullLyrics.setId("lyrics-full-text");
        fullLyrics.setOnAction(event -> showFullLyrics());
        lyricsStatus.setId("lyrics-status");
        lyricsStatus.getStyleClass().add("muted");
        currentLyric.setId("current-lyric");
        currentLyric.getStyleClass().add("current-lyric-label");
        installLyricMarquee(currentLyric);
        currentLyric.setWrapText(true);
        currentLyric.setMinWidth(0);
        currentLyric.setMaxWidth(Double.MAX_VALUE);
        VBox matchingTools = new VBox(7, new HBox(6, lyricQuery, findLyrics), lyricCandidates, applyLyrics,
                new FlowPane(8, 6, earlierLyrics, lyricOffsetLabel, laterLyrics,
                        actionButton("歌曲起点", "lyrics-start", () -> showLyricStartDialog(stage)),
                        actionButton("对齐选中句", "lyrics-align", () -> alignLyric(lyricsView, false))));
        ScrollPane matchingScroll = new ScrollPane(matchingTools);
        matchingScroll.getStyleClass().add("playlist-scroll");
        matchingScroll.setFitToWidth(true);
        matchingScroll.setPrefViewportHeight(150);
        matchingScroll.setMinHeight(60);
        matchingScroll.setMaxHeight(180);
        TitledPane matchingSection = new TitledPane("匹配与校准", matchingScroll);
        matchingSection.setExpanded(false);
        matchingSection.setAnimated(false);
        VBox lyricsPane = new VBox(7, new FlowPane(8, 6, new Label("歌词"), chooseLyrics, fullLyrics),
                currentLyric, lyricsView, lyricsStatus, matchingSection);
        lyricsPane.setMinHeight(Region.USE_PREF_SIZE);
        lyricsPane.getStyleClass().add("lyrics-pane");
        VBox queuePane = new VBox(12, queueHeading, trackManagementBar(queueList), queueList, lyricsPane);
        queuePane.getStyleClass().add("queue-pane");
        queuePane.setMinWidth(0);
        queuePane.setMinHeight(Region.USE_COMPUTED_SIZE);
        queueList.setMinHeight(70);
        VBox.setVgrow(queueList, Priority.ALWAYS);
        TitledPane lyricsSection = new TitledPane("歌词与匹配", lyricsPane);
        lyricsSection.setExpanded(true);
        lyricsSection.setAnimated(false);
        lyricsSection.getStyleClass().add("lyrics-section");
        queuePane.getChildren().set(3, lyricsSection);
        ListView<Track> queueRadioList = trackList(radioTracks);
        queueRadioList.setId("queue-radio-list");
        queueRadioList.setPrefHeight(125);
        queueRadioList.setMinHeight(75);
        queueRadioList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                playRadioItem(queueRadioList.getSelectionModel().getSelectedItem());
        });
        Label queueRadioTitle = new Label("电台推荐");
        queueRadioTitle.getStyleClass().add("muted");
        VBox queueRadioPane = new VBox(6, queueRadioTitle, trackManagementBar(queueRadioList), queueRadioList);
        queueRadioPane.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(radioTracks));
        queueRadioPane.managedProperty().bind(queueRadioPane.visibleProperty());
        queuePane.getChildren().add(queueRadioPane);
        ScrollPane queueScroll = new ScrollPane(queuePane);
        queueScroll.getStyleClass().add("playlist-scroll");
        queueScroll.setFitToWidth(true);
        queueScroll.setFitToHeight(true);
        queueScroll.setMinHeight(0);
        queueScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox rightRail = new VBox(queueScroll);
        VBox.setVgrow(queueScroll, Priority.ALWAYS);
        queueRail = rightRail;
        rightRail.setId("right-rail");
        rightRail.setPrefWidth(queueWidth);
        rightRail.setMinWidth(300);
        rightRail.setVisible(false);
        rightRail.setManaged(false);
        VBox.setVgrow(queuePane, Priority.ALWAYS);
        VBox.setVgrow(queueList, Priority.ALWAYS);
        Region queueDivider = new Region();
        queueDivider.setId("queue-resize-handle");
        queueDivider.setMinWidth(6);
        queueDivider.setPrefWidth(6);
        queueDivider.setMaxWidth(6);
        queueDivider.setCursor(javafx.scene.Cursor.H_RESIZE);
        queueDivider.visibleProperty().bind(rightRail.visibleProperty());
        queueDivider.managedProperty().bind(rightRail.managedProperty());
        double[] queueDrag = new double[2];
        queueDivider.setOnMousePressed(event -> {
            queueDrag[0] = event.getScreenX();
            queueDrag[1] = rightRail.getWidth();
        });
        queueDivider.setOnMouseDragged(event -> {
            queueWidth = Math.max(300, Math.min(560, queueDrag[1] + queueDrag[0] - event.getScreenX()));
            applySidebar.run();
            event.consume();
        });
        queueDivider.setOnMouseReleased(event -> saveAppearance());
        javafx.scene.shape.Rectangle railClip = new javafx.scene.shape.Rectangle();
        railClip.widthProperty().bind(rightRail.widthProperty());
        railClip.heightProperty().bind(rightRail.heightProperty());
        rightRail.setClip(railClip);
        HBox content = new HBox(sidebarFrame, sidebarDivider, tabs, queueDivider, rightRail);
        HBox.setHgrow(tabs, Priority.ALWAYS);
        tabs.setMinWidth(320);
        tabs.setMinHeight(0);
        content.setMinHeight(0);
        StackPane contentLayer = new StackPane(content);
        contentLayer.setMinHeight(0);
        javafx.scene.shape.Rectangle contentClip = new javafx.scene.shape.Rectangle();
        contentClip.widthProperty().bind(contentLayer.widthProperty());
        contentClip.heightProperty().bind(contentLayer.heightProperty());
        contentLayer.setClip(contentClip);
        ImageView[] closingRail = new ImageView[1];
        Timeline[] railExit = new Timeline[1];
        Runnable clearRailGhost = () -> {
            if (railExit[0] != null) { railExit[0].stop(); railExit[0] = null; }
            if (closingRail[0] != null) {
                contentLayer.getChildren().remove(closingRail[0]);
                closingRail[0] = null;
            }
        };
        clearSceneOverlays = clearRailGhost;

        Button previous = new Button("上一首");
        decorateIcon(previous, "previous", "transport-icon");
        previous.setId("previous-button");
        previous.setOnAction(event -> advance(-1));
        playButton.getStyleClass().add("accent-button");
        playButton.setDisable(true);
        playButton.setOnAction(event -> {
            if (queue.current() == null || playButton.isDisabled()) return;
            sleepPaused = false;
            if (audio.state() == JavaFxAudioPlayer.State.IDLE
                    || audio.state() == JavaFxAudioPlayer.State.FAILED
                    || audio.state() == JavaFxAudioPlayer.State.ENDED || segmentFinished)
            {
                Double resume = restoredPositionSeconds;
                restoredPositionSeconds = null;
                loadCurrent(true, resume);
            }
            else {
                if (shortFadeEnabled && !practiceLoop.enabled()
                        && (audio.state() == JavaFxAudioPlayer.State.PAUSED
                            || audio.state() == JavaFxAudioPlayer.State.READY)) {
                    fadeInPending = true;
                    applyOutputVolume();
                }
                audio.toggle();
            }
        });
        Button next = new Button("下一首");
        decorateIcon(next, "next", "transport-icon");
        next.setId("next-button");
        next.setOnAction(event -> advance(1));
        Button repeat = new Button("列表循环");
        repeatControl = repeat;
        decorateIcon(repeat, "repeat", "transport-icon");
        repeat.setId("repeat-button");
        repeat.setOnAction(event -> {
            PlayerQueue.RepeatMode mode = switch (queue.repeatMode()) {
                case ALL -> PlayerQueue.RepeatMode.ONE;
                case ONE -> PlayerQueue.RepeatMode.SHUFFLE;
                case SHUFFLE -> PlayerQueue.RepeatMode.OFF;
                case OFF -> PlayerQueue.RepeatMode.ALL;
            };
            queue.setRepeatMode(mode);
            queueUndoButton.setDisable(queueEdits.isEmpty()
                    || !queueEdits.peekLast().after().equals(queue.snapshot()));
            repeat.setText(switch (mode) {
                case ALL -> "列表循环";
                case ONE -> "单曲循环";
                case SHUFFLE -> "随机播放";
                case OFF -> "不循环";
            });
            repeat.setGraphic(icon(switch (mode) {
                case ALL -> "repeat";
                case ONE -> "repeat-one";
                case SHUFFLE -> "shuffle";
                case OFF -> "repeat-off";
            }, "transport-icon"));
            repeat.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"),
                    mode != PlayerQueue.RepeatMode.ALL);
            status.setText("播放方式：" + repeat.getText());
            refreshNextPreparation();
        });
        decorateIcon(favoriteButton, "heart", "action-icon");
        favoriteButton.setId("favorite-current");
        favoriteButton.setOnAction(event -> toggleTrackFavorite(queue.current()));
        Slider volume = new Slider(0, 1, 1);
        volume.setId("volume-slider");
        volume.setPrefWidth(72);
        volume.valueProperty().addListener((ignored, oldValue, value) -> {
            playbackVolume = value.doubleValue();
            applyOutputVolume();
        });
        globalHotkeys = new GlobalHotkeys(action -> Platform.runLater(() -> {
            if (recordingHotkey || isEditingInFocusedWindow()) return;
            switch (action) {
                case PREVIOUS -> advance(-1);
                case TOGGLE -> playButton.fire();
                case NEXT -> advance(1);
                case VOLUME_UP -> volume.setValue(Math.min(1, volume.getValue() + .05));
                case VOLUME_DOWN -> volume.setValue(Math.max(0, volume.getValue() - .05));
            }
        }), detail -> Platform.runLater(() -> status.setText("快捷键：" + detail)));
        applyHotkeys(hotkeyStatus);
        applyAudioPreferences();
        stylePlayButton(playButton);
        cover.setFitWidth(64);
        cover.setFitHeight(64);
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        StackPane coverPane = new StackPane(coverPlaceholder, cover);
        coverPane.setMinSize(64, 64);
        coverPane.setPrefSize(64, 64);
        coverPane.getStyleClass().add("cover-pane");
        coverPane.setMaxSize(64, 64);
        javafx.scene.shape.Rectangle coverClip = new javafx.scene.shape.Rectangle(64, 64);
        coverClip.setArcWidth(20);
        coverClip.setArcHeight(20);
        coverPane.setClip(coverClip);
        Tooltip coverTip = readableTooltip("");
        coverTip.textProperty().bind(coverPlaceholder.accessibleTextProperty());
        Tooltip.install(coverPane, coverTip);
        retryStartupButton = actionButton("重试连接", "retry-startup", () -> beginRuntimeStartup(true));
        retryStartupButton.setVisible(false);
        retryStartupButton.setManaged(false);
        HBox libraryStatusRow = new HBox(8, libraryStatus, retryStartupButton);
        libraryStatusRow.setAlignment(Pos.CENTER_LEFT);
        VBox playbackDetails = new VBox(3, nowPlaying, status, libraryStatusRow);
        playbackDetails.getStyleClass().add("player-track-details");
        playbackDetails.setMinWidth(100);
        playbackDetails.setPrefWidth(235);
        nowPlaying.setTextOverrun(OverrunStyle.ELLIPSIS);
        nowPlaying.setMinWidth(0);
        nowPlaying.setMaxWidth(Double.MAX_VALUE);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        libraryStatus.setMinWidth(0);
        libraryStatus.setMaxWidth(Double.MAX_VALUE);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        libraryStatus.setTextOverrun(OverrunStyle.ELLIPSIS);
        Region repeatBalance = new Region();
        repeatBalance.minWidthProperty().bind(repeat.widthProperty());
        repeatBalance.prefWidthProperty().bind(repeat.widthProperty());
        repeatBalance.maxWidthProperty().bind(repeat.widthProperty());
        HBox transport = new HBox(18, repeat, previous, playButton, next, repeatBalance);
        transport.setAlignment(Pos.CENTER);
        transport.getStyleClass().add("player-transport");
        Region timingSpacer = new Region();
        timingSpacer.minWidthProperty().bind(timing.widthProperty());
        timingSpacer.prefWidthProperty().bind(timing.widthProperty());
        timingSpacer.maxWidthProperty().bind(timing.widthProperty());
        HBox seekRow = new HBox(12, timing, progress, timingSpacer);
        seekRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progress.setMinWidth(0);
        timing.setMinWidth(Region.USE_PREF_SIZE);
        VBox playbackCenter = new VBox(5, transport, seekRow);
        playbackCenter.setAlignment(Pos.CENTER);
        playbackCenter.setMinWidth(235);
        playbackCenter.setPrefWidth(390);
        HBox.setHgrow(playbackCenter, Priority.ALWAYS);
        Button toggleQueue = new Button("队列");
        decorateIcon(toggleQueue, "list", "action-icon");
        toggleQueue.setId("toggle-queue");
        openQueuePanel = () -> {
            if (!rightRail.isVisible()) toggleQueue.fire();
            queueList.requestFocus();
        };
        toggleQueue.setOnAction(event -> {
            boolean showing = !rightRail.isVisible();
            clearRailGhost.run();
            UiMotion.clear(queuePane);
            if (!showing && !MotionPreferences.reduced()
                    && rightRail.getWidth() > 0 && rightRail.getHeight() > 0) {
                var bounds = contentLayer.sceneToLocal(rightRail.localToScene(rightRail.getBoundsInLocal()));
                javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
                parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
                ImageView ghost = new ImageView(rightRail.snapshot(parameters, null));
                ghost.setId("queue-exit-snapshot");
                ghost.setManaged(false);
                ghost.setMouseTransparent(true);
                ghost.setLayoutX(bounds.getMinX());
                ghost.setLayoutY(bounds.getMinY());
                contentLayer.getChildren().add(ghost);
                closingRail[0] = ghost;
                Timeline exit = new Timeline(new KeyFrame(Duration.millis(180),
                        new KeyValue(ghost.opacityProperty(), 0, Interpolator.EASE_IN),
                        new KeyValue(ghost.translateXProperty(), 24, Interpolator.EASE_IN)));
                exit.setOnFinished(done -> {
                    contentLayer.getChildren().remove(ghost);
                    if (closingRail[0] == ghost) closingRail[0] = null;
                    if (railExit[0] == exit) railExit[0] = null;
                });
                railExit[0] = exit;
                exit.play();
            }
            rightRail.setVisible(showing);
            rightRail.setManaged(showing);
            applySidebar.run();
            if (showing) animateEntrance(queuePane, 24, 0);
            toggleQueue.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("active"), showing);
        });
        Label volumeIcon = new Label();
        volumeIcon.setId("volume-toggle");
        volumeIcon.setGraphic(icon("volume", "action-icon"));
        volumeIcon.getStyleClass().add("volume-icon");
        volumeIcon.setCursor(javafx.scene.Cursor.HAND);
        volumeIcon.setFocusTraversable(false);
        Runnable updateVolumeDisplay = () -> {
            boolean muted = playbackVolume <= 0;
            volumeIcon.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("muted"), muted);
            volumeIcon.setGraphic(icon(muted ? "volume-mute" : "volume", "action-icon"));
            String percent = Math.round(playbackVolume * 100) + "%";
            String iconTip = muted ? "已静音（点击恢复）" : "音量：" + percent + "（点击静音，滚轮调节）";
            volumeIcon.setTooltip(readableTooltip(iconTip));
            volume.setTooltip(readableTooltip("音量：" + percent + "（支持滚轮微调）"));
        };
        updateVolumeDisplay.run();
        volume.valueProperty().addListener((ignored, oldValue, value) -> {
            if (value.doubleValue() > 0) unmutedVolume = value.doubleValue();
            updateVolumeDisplay.run();
        });
        volumeIcon.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                if (volume.getValue() > 0) {
                    unmutedVolume = volume.getValue();
                    volume.setValue(0);
                } else {
                    volume.setValue(unmutedVolume);
                }
            }
        });
        javafx.event.EventHandler<javafx.scene.input.ScrollEvent> onVolumeScroll = event -> {
            double delta = event.getDeltaY() > 0 ? 0.05 : event.getDeltaY() < 0 ? -0.05 : 0;
            if (delta != 0) {
                volume.setValue(Math.max(0.0, Math.min(1.0, volume.getValue() + delta)));
            }
            event.consume();
        };
        volume.setOnScroll(onVolumeScroll);
        volumeIcon.setOnScroll(onVolumeScroll);
        Button openPractice = actionButton("精听与练习", "open-practice", this::showPracticeDialog);
        decorateIcon(openPractice, "repeat-one", "action-icon");
        practiceButtons.add(openPractice);
        openPractice.getStyleClass().add("practice-toggle");
        HBox playbackActions = new HBox(11, favoriteButton, openPractice, openImmersive, openMini, toggleQueue,
                volumeIcon, volume);
        playbackActions.getStyleClass().add("player-actions");
        playbackActions.setAlignment(Pos.CENTER_RIGHT);
        HBox trackEntry = new HBox(15, coverPane, playbackDetails);
        trackEntry.setId("player-track-entry");
        trackEntry.getStyleClass().add("player-track-entry");
        trackEntry.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(playbackDetails, Priority.ALWAYS);
        Tooltip.install(trackEntry, readableTooltip("点击展开沉浸播放页"));
        trackEntry.setOnMouseClicked(event -> {
            if (queue.current() != null) tabs.getSelectionModel().select(immersiveTab);
        });
        HBox playbackMain = new HBox(15, trackEntry, playbackCenter, playbackActions);
        playbackMain.setAlignment(Pos.CENTER_LEFT);
        VBox playback = new VBox(playbackMain);
        playback.setId("playback-bar");
        playback.getStyleClass().addAll("playback", "player-card");
        playback.setMinHeight(94);
        playback.setPrefHeight(94);
        playback.setMaxHeight(94);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setId("progress-slider");
        progress.setDisable(true);
        installProgressTrackFill(progress);
        progress.setOnMouseReleased(event -> seekToFraction(progress.getValue()));
        progress.setOnKeyReleased(event -> seekToFraction(progress.getValue()));
        playButton.setId("play-button");
        nowPlaying.setId("now-playing");
        timing.setId("timing");
        status.setId("status");
        nowPlaying.getStyleClass().addAll("section-title", "player-title");
        status.getStyleClass().addAll("muted", "player-subtitle");
        libraryStatus.getStyleClass().addAll("muted", "player-library-status");
        libraryStatus.setId("library-status");

        BorderPane root = new BorderPane();
        root.setTop(header);
        failureNotice.setId("playback-failure-notice");
        failureNotice.setWrapText(true);
        failureNotice.setMinWidth(0);
        failureNotice.setMaxWidth(Double.MAX_VALUE);
        Tooltip failureTooltip = new Tooltip();
        failureTooltip.textProperty().bind(failureNotice.textProperty());
        failureNotice.setTooltip(failureTooltip);
        Button retryAudio = actionButton("重试播放", "retry-playback", () -> {
            retryingPlayback = true;
            loadCurrent(true);
        });
        retryAudio.disableProperty().bind(playButton.disableProperty());
        decorateIcon(retryAudio, "repeat", "action-icon");
        Button replaceResource = actionButton("更换资源", "replace-failed-resource", () -> {
            if (queue.current() != null) showVersions(queue.current());
        });
        decorateIcon(replaceResource, "list", "action-icon");
        HBox failureBar = new HBox(10, failureNotice, retryAudio, replaceResource);
        HBox.setHgrow(failureNotice, Priority.ALWAYS);
        failureBar.setAlignment(Pos.CENTER_LEFT);
        failureBar.getStyleClass().add("playback-failure-bar");
        failureBar.visibleProperty().bind(failureNotice.textProperty().isNotEmpty());
        failureBar.managedProperty().bind(failureBar.visibleProperty());
        VBox center = new VBox(failureBar, contentLayer);
        VBox.setVgrow(contentLayer, Priority.ALWAYS);
        root.setCenter(center);
        root.setBottom(playback);
        StackPane windowLayer = new StackPane(root);
        petEventNotice.setId("pet-event-notice");
        petEventNotice.getStyleClass().add("pet-bubble");
        petEventNotice.setWrapText(true);
        petEventNotice.setManaged(false);
        petEventNotice.setMouseTransparent(true);
        petEventNotice.setVisible(false);
        windowLayer.getChildren().add(petEventNotice);
        petEventTimer.setOnFinished(event -> petEventNotice.setVisible(false));
        pet.setEventNotice(message -> {
            if (closing) return;
            if (desktopPet != null && desktopPet.isShowing()) {
                desktopPet.showNotice(message);
                return;
            }
            if (!stage.isShowing() || stage.isIconified() || pet.getScene() != stage.getScene()) return;
            for (javafx.scene.Node ancestor = pet; ancestor != null; ancestor = ancestor.getParent())
                if (!ancestor.isVisible()) return;
            petEventNotice.setText(message);
            petEventNotice.applyCss();
            double width = Math.min(220, windowLayer.getWidth() - 32);
            double height = petEventNotice.prefHeight(width);
            var location = windowLayer.sceneToLocal(pet.localToScene(pet.getBoundsInLocal()));
            petEventNotice.resizeRelocate(Math.min(Math.max(16, location.getMinX()), windowLayer.getWidth() - width - 16),
                    Math.max(52, Math.min(location.getMinY() - height - 10,
                            windowLayer.getHeight() - playback.getHeight() - height - 12)), width, height);
            petEventNotice.setVisible(true);
            petEventNotice.toFront();
            petEventTimer.playFromStart();
        });
        listening.setOnQualified(play -> Platform.runLater(() -> {
            if (closing) return;
            if (diaryTab.isSelected()) diaryPane.refresh();
            if (homeTab.isSelected()) refreshPersonalHome();
            if (play.revisitedOldFavorite() && loadedTrack != null && !loadedNeutral
                    && loadedTrack.id().equals(play.track().id()))
                notifyPetMusicEvent(PetCompanion.Event.REPLAYED_OLD_FAVORITE, play.track().title());
        }));
        Runnable placeFocusPet = () -> {
            petDock.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focus-quiet"),
                    tabs.getSelectionModel().getSelectedItem() == focusTab);
            if (tabs.getSelectionModel().getSelectedItem() == focusTab) {
                if (petDock.getParent() == sidebar) sidebar.getChildren().remove(petDock);
                if (petDock.getParent() != windowLayer) windowLayer.getChildren().add(petDock);
                petDock.setManaged(false);
                petDock.resizeRelocate(18, Math.max(60, windowLayer.getHeight() - 88), 82, 76);
            } else {
                windowLayer.getChildren().remove(petDock);
                petDock.setManaged(true);
                if (petDock.getParent() != sidebar) sidebar.getChildren().add(petDock);
            }
        };
        artworkTransition = new ArtworkTransition(windowLayer);
        idleControls = new IdlePlaybackControls(
                () -> audio.state() == JavaFxAudioPlayer.State.PLAYING,
                () -> immersiveMenu.isShowing() || isInside(stage.getScene().getFocusOwner(), immersiveActions, lyricCalibration, playback),
                immersiveActions, lyricCalibration, playback);
        playButton.textProperty().addListener((ignored, before, after) -> {
            focusPane.setPlaying("暂停".equals(after));
            idleControls.activity();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) -> {
            if (selected == playlistTab) retryPlaylistResources(List.copyOf(playlistTracks));
            if (selected == favoriteTab) retryPlaylistResources(List.copyOf(favorites));
            petEventTimer.stop();
            petEventNotice.setVisible(false);
            diaryNav.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), selected == diaryTab);
            artworkTransition.cancel();
            long motionVersion = ++sceneMotionVersion;
            ImageView previousCover = before == immersiveTab ? immersiveCover
                    : before == focusTab ? focusPane.coverView() : cover;
            ArtworkTransition.Frame frame = artworkTransition.capture(previousCover);
            if (before != null) UiMotion.clear(before.getContent());
            boolean immersive = selected == immersiveTab;
            boolean focusing = selected == focusTab;
            header.setVisible(!immersive);
            header.setManaged(!immersive);
            sidebarFrame.setVisible(!immersive && !focusing);
            sidebarFrame.setManaged(!immersive && !focusing);
            sidebarDivider.setVisible(!immersive && !focusing);
            sidebarDivider.setManaged(!immersive && !focusing);
            playback.setVisible(!focusing);
            playback.setManaged(!focusing);
            playback.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("immersive"), immersive);
            if (immersive || focusing) {
                clearRailGhost.run();
                UiMotion.clear(queuePane);
                rightRail.setVisible(false);
                rightRail.setManaged(false);
                toggleQueue.pseudoClassStateChanged(
                        javafx.css.PseudoClass.getPseudoClass("active"), false);
            }
            if (selected != null && stage.isShowing())
                animateEntrance(selected.getContent(), 0, frame != null ? 0 : immersive ? 18 : 8);
            idleControls.setActive(immersive);
            pet.setQuiet(focusing);
            placeFocusPet.run();
            if (stage.isShowing() && (immersive || focusing || before == immersiveTab || before == focusTab)) {
                Platform.runLater(() -> {
                    if (motionVersion != sceneMotionVersion || closing) return;
                    windowLayer.applyCss();
                    windowLayer.layout();
                    ImageView destination = immersive ? immersiveCover : focusing ? focusPane.coverView() : cover;
                    artworkTransition.play(frame, destination);
                    if (immersive && activeLyricIndex >= 0) animateLyricScroll(activeLyricIndex);
                });
            }
            updatePetPlacement.run();
        });
        Scene scene = new Scene(windowLayer, 1280, 800);
        SmoothScrolling.install(windowLayer);
        FriendlyTooltips.install(windowLayer);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        javafx.scene.shape.Rectangle windowClip = new javafx.scene.shape.Rectangle();
        windowClip.widthProperty().bind(windowLayer.widthProperty());
        windowClip.heightProperty().bind(windowLayer.heightProperty());
        windowLayer.setClip(windowClip);
        Runnable roundWindow = () -> {
            double arc = stage.isMaximized() || stage.isFullScreen() ? 0 : 24;
            windowClip.setArcWidth(arc);
            windowClip.setArcHeight(arc);
        };
        stage.maximizedProperty().addListener((ignored, before, after) -> roundWindow.run());
        stage.fullScreenProperty().addListener((ignored, before, after) -> roundWindow.run());
        roundWindow.run();
        windowLayer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, event -> idleControls.activity());
        windowLayer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> idleControls.activity());
        windowLayer.addEventFilter(KeyEvent.KEY_PRESSED, event -> idleControls.activity());
        scene.focusOwnerProperty().addListener((ignored, before, after) -> idleControls.activity());
        motionListener = (ignored, before, reduced) -> {
            ++sceneMotionVersion;
            UiMotion.cancelAll();
            artworkTransition.cancel();
            clearRailGhost.run();
            pet.setReducedMotion(reduced);
            if (activeLyricIndex >= 0) animateLyricScroll(activeLyricIndex);
        };
        MotionPreferences.reducedProperty().addListener(motionListener);
        root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (!pet.isQuiet()) pet.onInteraction("interface");
        });
        root.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (!pet.isQuiet()) pet.onInteraction("scroll");
        });
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (!pet.isQuiet()) pet.onInteraction("keyboard");
        });
        root.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, event ->
                Platform.runLater(this::themeOpenTextMenus));
        scene.getStylesheets().add(getClass().getResource("/lumen-theme.css").toExternalForm());
        Runnable updateLayout = () -> {
            artworkTransition.cancel();
            placeFocusPet.run();
            applySidebar.run();
            boolean compact = scene.getWidth() < 1080;
            boolean spacious = scene.getHeight() >= 720;
            windowLayer.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), compact);
            windowLayer.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("short"), !spacious);
            playbackDetails.setPrefWidth(compact ? 130 : 235);
            playbackDetails.setMaxWidth(compact ? 160 : 235);
            playbackMain.setSpacing(compact ? 10 : 15);
            trackEntry.setSpacing(compact ? 10 : 15);
            transport.setSpacing(compact ? 12 : 18);
            playbackActions.setSpacing(compact ? 5 : 11);
            volume.setMinWidth(compact ? 52 : 72);
            volume.setPrefWidth(compact ? 52 : 72);
            volume.setMaxWidth(compact ? 52 : 72);
            homePane.setPadding(compact ? new Insets(16, 18, 14, 18) : new Insets(18, 28, 18, 28));
            searchPane.setPadding(compact ? new Insets(20, 18, 18, 18) : new Insets(30, 32, 24, 32));
            for (VBox page : new VBox[] {localPane, favoritePane, playlistPane})
                page.setPadding(compact ? new Insets(20, 18, 18, 18) : new Insets(30, 32, 24, 32));
            double artworkSize = Math.max(290, Math.min(520,
                    Math.min((scene.getHeight() - 300
                            - (failureBar.isManaged() ? failureBar.prefHeight(scene.getWidth()) : 0)) * 1.6,
                            scene.getWidth() * .43)));
            double artworkHeight = artworkSize / 1.6;
            immersiveArtwork.setMinSize(artworkSize, artworkHeight);
            immersiveArtwork.setPrefSize(artworkSize, artworkHeight);
            immersiveArtwork.setMaxSize(artworkSize, artworkHeight);
            immersiveCover.setFitWidth(artworkSize);
            immersiveCover.setFitHeight(artworkHeight);
            artworkClip.setWidth(artworkSize);
            artworkClip.setHeight(artworkHeight);
            immersiveLeft.setPrefWidth(artworkSize + 20);
            immersiveLeft.setMinWidth(artworkSize + 20);
            immersiveLeft.setMaxWidth(artworkSize + 20);
            immersiveActions.setPrefWrapLength(artworkSize + 22);
            immersiveBody.setSpacing(compact ? 20 : 36);
            immersiveLeft.setSpacing(spacious ? 18 : 12);
            immersiveWords.setSpacing(spacious ? 8 : 4);
        };
        scene.widthProperty().addListener((ignored, before, width) -> updateLayout.run());
        scene.heightProperty().addListener((ignored, before, height) -> updateLayout.run());
        failureNotice.textProperty().addListener((ignored, before, after) -> updateLayout.run());
        updateLayout.run();
        themeController = new ThemeController(scene);
        applyAppearance();
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (recordingHotkey) return;
            if (event.getCode() == KeyCode.ESCAPE && !KeyboardInputGuard.isTextEditing(scene.getFocusOwner())
                    && (tabs.getSelectionModel().getSelectedItem() == immersiveTab
                    || tabs.getSelectionModel().getSelectedItem() == focusTab)) {
                tabs.getSelectionModel().select(lastBrowseTab);
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.K) {
                quickSearchDialog.show();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.Z
                    && !event.isShiftDown()
                    && !KeyboardInputGuard.isTextEditing(scene.getFocusOwner())) {
                if (tabs.getSelectionModel().getSelectedItem() == playlistTab && !playlistUndoButton.isDisabled())
                    undoPlaylistRemoval();
                else undoQueueEdit();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.F) {
                tabs.getSelectionModel().select(searchTab);
                query.requestFocus();
                query.selectAll();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.O) {
                importFiles(stage);
                event.consume();
            } else if (!KeyboardInputGuard.isTextEditing(scene.getFocusOwner())
                    && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                if (event.getCode() == KeyCode.SPACE) {
                    playButton.fire();
                    event.consume();
                } else if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT) {
                    advance(event.getCode() == KeyCode.LEFT ? -1 : 1);
                    event.consume();
                }
            }
        });
        stage.setTitle("Lumen Music");
        stage.setMinWidth(860);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> { stop(); Platform.exit(); });
        WindowResize.install(stage, scene);
        taskbarControls = WindowsTaskbarControls.attach(stage,
                () -> advance(-1), () -> playButton.fire(), () -> advance(1),
                () -> favoriteButton.fire());
        stage.show();

        clock = new Timeline(new KeyFrame(Duration.millis(100), event -> updateTime()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        volumeClock = new Timeline(new KeyFrame(Duration.millis(20), event -> applyOutputVolume()));
        volumeClock.setCycleCount(Timeline.INDEFINITE);
        updateFadeClock();
        refreshFavoriteButton();
        restoreLocalCatalogue();
        restoreLocalState();
        initializeVlcAsync();
        beginRuntimeStartup(false);
        for (String arg : launchArgs) {
            if (arg.startsWith("--open=")) {
                addFiles(List.of(Path.of(arg.substring("--open=".length())).toFile()));
                tabs.getSelectionModel().select(localTab);
            }
        }
    }

    private static void stylePlayButton(Button button) {
        SVGPath glyph = icon("play", "player-play-glyph");
        glyph.contentProperty().bind(javafx.beans.binding.Bindings.when(
                button.textProperty().isEqualTo("暂停"))
                .then(iconPath("pause")).otherwise(iconPath("play")));
        button.setGraphic(glyph);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("player-main-play");
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(button.textProperty());
        configureTooltip(tooltip);
        button.setTooltip(tooltip);
    }

    private static Button windowButton(Stage stage, String kind, String id) {
        Button button = new Button(switch (kind) {
            case "minimize" -> "最小化";
            case "maximize" -> "最大化 / 还原";
            default -> "关闭";
        });
        button.setId(id);
        button.setGraphic(icon(kind, "window-icon"));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("window-control");
        button.setTooltip(readableTooltip(button.getText()));
        button.setOnAction(event -> {
            switch (kind) {
                case "minimize" -> stage.setIconified(true);
                case "maximize" -> stage.setMaximized(!stage.isMaximized());
                default -> stage.fireEvent(new javafx.stage.WindowEvent(
                        stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            }
        });
        if (kind.equals("maximize")) stage.maximizedProperty().addListener((ignored, before, maximized) ->
                button.setGraphic(icon(maximized ? "restore" : "maximize", "window-icon")));
        return button;
    }

    private static SVGPath icon(String name, String styleClass) {
        SVGPath shape = new SVGPath();
        shape.setContent(iconPath(name));
        shape.getStyleClass().addAll("ui-icon", styleClass);
        return shape;
    }

    private static void decorateIcon(Button button, String name, String styleClass) {
        SVGPath glyph = icon(name, styleClass);
        if ("nav-icon".equals(styleClass)) {
            StackPane frame = new StackPane(glyph);
            frame.getStyleClass().add("nav-icon-frame");
            frame.setMinSize(24, 24);
            frame.setPrefSize(24, 24);
            frame.setMaxSize(24, 24);
            frame.setMouseTransparent(true);
            button.setGraphic(frame);
        } else button.setGraphic(glyph);
        if ("transport-icon".equals(styleClass) || "action-icon".equals(styleClass)) {
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.getStyleClass().add("transport-icon".equals(styleClass)
                    ? "transport-button" : "icon-button");
            Tooltip tooltip = new Tooltip();
            tooltip.textProperty().bind(button.textProperty());
            configureTooltip(tooltip);
            button.setTooltip(tooltip);
        } else button.getStyleClass().add("nav-icon-button");
    }

    private static String iconPath(String name) {
        return switch (name) {
            case "trash" -> "M7 3H17V5H22V7H2V5H7Z M5 8H19L18 22H6Z M8 10V20H10V10Z M14 10V20H16V10Z";
            case "versions" -> "M7 2H21V17H19V4H7Z M3 7H17V22H3Z M5 9V20H15V9Z";
            case "tags" -> "M2 3H12L22 13L13 22L2 11Z M5 5V10L13 18L18 13L10 5Z M7 6A2 2 0 1 0 7 10A2 2 0 0 0 7 6Z";
            case "playlist-add" -> "M2 4H16V6H2Z M2 9H16V11H2Z M2 14H10V16H2Z M16 13H18V17H22V19H18V23H16V19H12V17H16Z";
            case "segment" -> "M3 3H5V21H3Z M19 3H21V21H19Z M8 6L17 12L8 18Z";
            case "check-list" -> "M2 5L4 7L8 3L9 5L4 10L1 7Z M11 5H23V7H11Z M2 15L4 17L8 13L9 15L4 20L1 17Z M11 15H23V17H11Z";
            case "minimize" -> "M4 11H20V13H4Z";
            case "maximize" -> "M4 4H20V20H4Z M6 6V18H18V6Z";
            case "restore" -> "M8 3H21V16H18V6H8Z M3 8H16V21H3Z M5 10V19H14V10Z";
            case "close" -> "M5 3L12 10L19 3L21 5L14 12L21 19L19 21L12 14L5 21L3 19L10 12L3 5Z";
            case "collapse" -> "M4 7L12 15L20 7L22 9L12 19L2 9Z";
            case "sidebar-close" -> "M4 3H7V21H4Z M20 5L12 12L20 19L18 21L8 12L18 3Z";
            case "sidebar-open" -> "M4 3H7V21H4Z M10 5L18 12L10 19L12 21L22 12L12 3Z";
            case "settings" -> "M9 2H15L16 6L20 7L22 12L20 17L16 18L15 22H9L8 18L4 17L2 12L4 7L8 6Z M12 8A4 4 0 1 0 12 16A4 4 0 0 0 12 8Z";
            case "focus" -> "M12 2A10 10 0 1 0 12 22A10 10 0 0 0 12 2Z M12 4A8 8 0 1 1 12 20A8 8 0 0 1 12 4Z M11 6H13V11L17 13L16 15L11 12Z";
            case "history" -> "M12 2A10 10 0 1 1 3.6 7H1V2H3V5.3A10 10 0 0 1 12 2Z M12 4A8 8 0 1 0 12 20A8 8 0 0 0 12 4Z M11 6H13V11.4L17 13.7L16 15.4L11 12.5Z";
            case "external" -> "M13 3H21V11H19V6L11 14L9 12L17 5H13Z M3 6H10V8H5V19H16V14H18V21H3Z";
            case "lyrics" -> "M3 4H21V20H3Z M6 7V9H18V7Z M6 11V13H18V11Z M6 15V17H14V15Z";
            case "home" -> "M10 20V14H14V20H19V11H22L12 2L2 11H5V20Z";
            case "search" -> "M10 2A8 8 0 1 0 10 18A8 8 0 0 0 10 2Z M10 5A5 5 0 1 1 10 15A5 5 0 0 1 10 5Z M16 14L22 20L20 22L14 16Z";
            case "music" -> "M12 3V15A4 4 0 1 0 15 19V7H21V3Z";
            case "heart" -> "M12 21L10.5 19.6C5.3 15 2 12 2 8.4C2 5.3 4.4 3 7.4 3C9.2 3 10.8 3.8 12 5.2C13.2 3.8 14.8 3 16.6 3C19.6 3 22 5.3 22 8.4C22 12 18.7 15 13.5 19.6Z";
            case "more" -> "M4 10A2 2 0 1 0 4 14A2 2 0 0 0 4 10Z M12 10A2 2 0 1 0 12 14A2 2 0 0 0 12 10Z M20 10A2 2 0 1 0 20 14A2 2 0 0 0 20 10Z";
            case "list" -> "M3 5H18V7H3Z M3 10H18V12H3Z M3 15H13V17H3Z M16 14L22 18L16 22Z";
            case "disc" -> "M12 2A10 10 0 1 0 12 22A10 10 0 0 0 12 2Z M12 9A3 3 0 1 1 12 15A3 3 0 0 1 12 9Z";
            case "mini" -> "M3 4H21V17H3Z M12 10H20V16H12Z";
            case "sparkle" -> "M12 2L14.3 9.7L22 12L14.3 14.3L12 22L9.7 14.3L2 12L9.7 9.7Z";
            case "cache" -> "M3 4H21V20H3Z M6 7V10H18V7Z M7 13H17V16H7Z";
            case "previous" -> "M5 5H8V19H5Z M19 5V19L9 12Z";
            case "undo" -> "M8 3L2 9L8 15V11H14C17 11 19 13 19 16C19 19 17 21 14 21H12V19H14C16 19 17 18 17 16C17 14 16 13 14 13H8Z";
            case "next" -> "M16 5H19V19H16Z M5 5L15 12L5 19Z";
            case "repeat" -> "M7 5H18V2L22 6L18 10V7H7C5 7 4 8 4 10H2C2 7 4 5 7 5Z M17 19H6V22L2 18L6 14V17H17C19 17 20 16 20 14H22C22 17 20 19 17 19Z";
            case "repeat-one" -> "M7 5H18V2L22 6L18 10V7H7C5 7 4 8 4 10H2C2 7 4 5 7 5Z M17 19H6V22L2 18L6 14V17H17C19 17 20 16 20 14H22C22 17 20 19 17 19Z M11 9H13V15H11Z";
            case "repeat-off" -> "M3 4L20 21L21.5 19.5L4.5 2.5Z M8 5H18V2L22 6L18 10V7H7C5 7 4 8 4 10H2Z M17 19H6V22L2 18L6 14V17H17C19 17 20 16 20 14H22Z";
            case "shuffle" -> "M3 5H7L12 10L17 5H15V3H22V10H20V8H18L13.5 12.5L18 17H20V15H22V22H15V20H17L12 15L7 20H3V18H6L10.5 13.5L6 7H3Z";
            case "volume" -> "M3 9H7L12 4V20L7 15H3Z M15 8L17 6C20 9 20 15 17 18L15 16C17 14 17 10 15 8Z";
            case "volume-mute" -> "M3 9H7L12 4V20L7 15H3Z M16.5 12L19.5 9L21 10.5L18 13.5L21 16.5L19.5 18L16.5 15L13.5 18L12 16.5L15 13.5L12 10.5L13.5 9Z";
            case "pause" -> "M6 4H10V20H6Z M14 4H18V20H14Z";
            case "stop" -> "M6 6H18V18H6Z";
            default -> "M8 5V19L19 12Z";
        };
    }

    private static Tooltip readableTooltip(String title) {
        Tooltip tooltip = new Tooltip(title);
        configureTooltip(tooltip);
        return tooltip;
    }

    private static void configureTooltip(Tooltip tooltip) {
        FriendlyTooltips.configure(tooltip);
    }

    private static void stableTooltip(Control control, String text, boolean enabled) {
        Tooltip tooltip = (Tooltip) control.getProperties().computeIfAbsent("lumen.stable-tooltip",
                ignored -> readableTooltip(text));
        if (!java.util.Objects.equals(tooltip.getText(), text)) tooltip.setText(text);
        if (control.getTooltip() != (enabled ? tooltip : null)) control.setTooltip(enabled ? tooltip : null);
    }

    private Button mirrorButton(Button source, String id) {
        Button button = new Button();
        button.setId(id);
        button.textProperty().bind(source.textProperty());
        button.disableProperty().bind(source.disableProperty());
        button.setOnAction(event -> source.fire());
        if (source == favoriteButton) {
            button.getStyleClass().add("favorite-action");
            javafx.css.PseudoClass active = javafx.css.PseudoClass.getPseudoClass("active");
            button.pseudoClassStateChanged(active, "取消收藏".equals(source.getText()));
            source.textProperty().addListener((ignored, before, text) ->
                    button.pseudoClassStateChanged(active, "取消收藏".equals(text)));
        }
        if (id.endsWith("-play")) stylePlayButton(button);
        return button;
    }

    private static VBox pageHeading(String title, String description) {
        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");
        Label hint = new Label(description);
        hint.getStyleClass().add("page-subtitle");
        hint.setWrapText(true);
        return new VBox(7, heading, hint);
    }

    private static Label artworkPlaceholder() {
        Label placeholder = new Label("♫");
        placeholder.getStyleClass().add("artwork-placeholder");
        placeholder.setAccessibleText("暂无封面");
        return placeholder;
    }

    private static VBox emptyState(String id, String title, String description) {
        Label heading = new Label(title);
        heading.getStyleClass().add("empty-state-title");
        Label hint = new Label(description);
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        hint.setMaxWidth(320);
        VBox state = new VBox(10, artworkPlaceholder(), heading, hint);
        state.setId(id);
        state.getStyleClass().add("empty-state");
        state.setAlignment(Pos.CENTER);
        state.setMaxWidth(340);
        return state;
    }

    private static Button navButton(String label, String id, TabPane tabs, Tab page) {
        Button button = new Button(label);
        button.setId(id);
        button.setOnAction(event -> tabs.getSelectionModel().select(page));
        return button;
    }

    private Button actionButton(String label, String id, Runnable action) {
        Button button = new Button(label);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static void installWindowDrag(javafx.scene.Node handle, Stage stage) {
        final double[] pointer = new double[2];
        handle.setOnMousePressed(event -> {
            pointer[0] = event.getScreenX() - stage.getX();
            pointer[1] = event.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(event -> {
            if (stage.isMaximized()) return;
            stage.setX(event.getScreenX() - pointer[0]);
            stage.setY(event.getScreenY() - pointer[1]);
        });
        handle.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                stage.setMaximized(!stage.isMaximized());
        });
    }

    private void showMini(Stage main) {
        if (miniStage != null) {
            miniStage.toFront();
            return;
        }
        Stage mini = new Stage();
        miniStage = mini;
        mini.initStyle(StageStyle.TRANSPARENT);
        mini.getIcons().add(new Image(getClass().getResource("/icons/lumen-256.png").toExternalForm()));
        mini.setTitle("Lumen Music · 迷你播放器");
        mini.setAlwaysOnTop(true);
        mini.setMinWidth(460);
        mini.setMinHeight(244);
        ImageView artwork = new ImageView();
        artwork.imageProperty().bind(cover.imageProperty());
        artwork.viewportProperty().bind(cover.viewportProperty());
        artwork.setFitWidth(72);
        artwork.setFitHeight(72);
        artwork.setPreserveRatio(true);
        Label miniCoverPlaceholder = artworkPlaceholder();
        miniCoverPlaceholder.visibleProperty().bind(coverPlaceholder.visibleProperty());
        StackPane miniArtwork = new StackPane(miniCoverPlaceholder, artwork);
        miniArtwork.setId("mini-artwork");
        miniArtwork.getStyleClass().add("cover-pane");
        miniArtwork.setMinSize(64, 64);
        miniArtwork.setPrefSize(64, 64);
        miniArtwork.setMaxSize(64, 64);
        Label title = new Label();
        title.setId("mini-now-playing");
        title.textProperty().bind(nowPlaying.textProperty());
        title.setMaxWidth(350);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label miniTiming = new Label();
        miniTiming.textProperty().bind(timing.textProperty());
        Label drag = new Label("LUMEN · 长按空白处移动");
        drag.getStyleClass().add("window-drag-title");
        Button returnButton = new Button("返回主窗口");
        returnButton.setId("mini-return");
        decorateIcon(returnButton, "external", "action-icon");
        returnButton.setOnAction(event -> mini.close());
        HBox titlebar = new HBox(4, drag, new Region(), returnButton,
                windowButton(mini, "minimize", "mini-minimize"),
                windowButton(mini, "maximize", "mini-maximize"),
                windowButton(mini, "close", "mini-close"));
        HBox.setHgrow(titlebar.getChildren().get(1), Priority.ALWAYS);
        titlebar.getStyleClass().add("mini-titlebar");
        HBox miniControls = new HBox(6,
                actionButton("⏮", "mini-previous", () -> advance(-1)),
                mirrorButton(playButton, "mini-play"),
                actionButton("⏭", "mini-next", () -> advance(1)),
                mirrorButton(favoriteButton, "mini-favorite"));
        decorateIcon((Button) miniControls.getChildren().get(0), "previous", "transport-icon");
        decorateIcon((Button) miniControls.getChildren().get(2), "next", "transport-icon");
        decorateIcon((Button) miniControls.getChildren().get(3), "heart", "action-icon");
        Slider miniProgress = new Slider(0, 1, progress.getValue());
        miniProgress.setId("mini-progress-slider");
        miniProgress.valueProperty().bindBidirectional(progress.valueProperty());
        installProgressTrackFill(miniProgress);
        miniProgress.disableProperty().bind(progress.disableProperty());
        miniProgress.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                event -> progress.setValueChanging(true));
        miniProgress.setOnMouseReleased(event -> {
            progress.setValueChanging(false);
            seekToFraction(miniProgress.getValue());
        });
        miniProgress.setOnKeyReleased(event -> seekToFraction(miniProgress.getValue()));
        Label miniArtist = new Label();
        miniArtist.setId("mini-artist");
        miniArtist.textProperty().bind(immersiveTrackArtist.textProperty());
        miniArtist.getStyleClass().add("mini-artist");
        miniArtist.setMinWidth(0);
        miniArtist.setMaxWidth(Double.MAX_VALUE);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        VBox details = new VBox(4, title, miniArtist, miniTiming, miniControls);
        details.setMinWidth(0);
        HBox.setHgrow(details, Priority.ALWAYS);
        HBox content = new HBox(14, miniArtwork, details);
        content.setAlignment(Pos.CENTER_LEFT);
        BorderPane root = new BorderPane();
        root.setTop(titlebar);
        root.setCenter(content);
        Label miniFailure = new Label();
        miniFailure.textProperty().bind(failureNotice.textProperty());
        miniFailure.setTextOverrun(OverrunStyle.ELLIPSIS);
        miniFailure.setMaxWidth(Double.MAX_VALUE);
        Tooltip miniFailureTooltip = readableTooltip("");
        miniFailureTooltip.textProperty().bind(failureNotice.textProperty());
        miniFailure.setTooltip(miniFailureTooltip);
        miniFailure.getStyleClass().add("mini-failure");
        miniFailure.visibleProperty().bind(failureNotice.textProperty().isNotEmpty());
        miniFailure.managedProperty().bind(miniFailure.visibleProperty());
        Label miniLyric = new Label();
        miniLyric.setId("mini-current-lyric");
        miniLyric.getStyleClass().add("mini-current-lyric");
        miniLyric.setMinWidth(0);
        miniLyric.setMaxWidth(Double.MAX_VALUE);
        miniLyric.setMinHeight(30);
        miniLyric.setPrefHeight(30);
        miniLyric.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> activeLyrics.isEmpty() ? "暂无同步歌词" : currentLyric.getText(),
                currentLyric.textProperty(), lyricsStatus.textProperty()));
        installLyricMarquee(miniLyric);
        root.setBottom(new VBox(4, miniLyric, miniProgress, miniFailure));
        root.setPadding(new Insets(10));
        root.getStyleClass().add("mini-player");
        LongPressWindowDrag.install(root, mini);
        javafx.scene.shape.Rectangle miniClip = new javafx.scene.shape.Rectangle();
        miniClip.widthProperty().bind(root.widthProperty());
        miniClip.heightProperty().bind(root.heightProperty());
        root.setClip(miniClip);
        Runnable roundMini = () -> {
            double arc = mini.isMaximized() ? 0 : 24;
            miniClip.setArcWidth(arc);
            miniClip.setArcHeight(arc);
        };
        mini.maximizedProperty().addListener((ignored, before, after) -> roundMini.run());
        roundMini.run();
        Scene scene = new Scene(root, 540, 244, javafx.scene.paint.Color.TRANSPARENT);
        SmoothScrolling.install(root);
        FriendlyTooltips.install(root);
        scene.getStylesheets().add(getClass().getResource("/lumen-theme.css").toExternalForm());
        miniThemeController = new ThemeController(scene);
        applyAppearance();
        mini.setScene(scene);
        WindowResize.install(mini, scene);
        miniTaskbarControls = WindowsTaskbarControls.attach(mini,
                () -> advance(-1), () -> playButton.fire(), () -> advance(1),
                () -> favoriteButton.fire());
        if (miniTaskbarControls != null)
            miniTaskbarControls.setPlaying(audio.state() == JavaFxAudioPlayer.State.PLAYING);
        refreshFavoriteButton();
        mini.setOnHidden(event -> {
            if (miniTaskbarControls != null) miniTaskbarControls.close();
            miniTaskbarControls = null;
            miniThemeController.dispose();
            miniThemeController = null;
            miniStage = null;
            progress.setValueChanging(false);
            miniProgress.valueProperty().unbindBidirectional(progress.valueProperty());
            miniLyric.textProperty().unbind();
            if (!closing) {
                main.show();
                main.toFront();
            }
        });
        mini.show();
        main.hide();
    }

    private ListView<Track> trackList(ObservableList<Track> tracks) {
        return trackList(tracks, false);
    }

    private ListView<Track> trackList(ObservableList<Track> tracks, boolean ranked) {
        var view = new ListView<>(tracks);
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        SmoothListScroll.install(view);
        trackViews.add(view);
        if (ranked) view.getStyleClass().add("recommendation-list");
        view.setPlaceholder(new Label("暂无曲目"));
        view.getStyleClass().add("track-list");
        view.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Track track, boolean empty) {
                super.updateItem(track, empty);
                setText(null);
                if (empty || track == null) {
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                boolean isPlaying = loadedTrack != null && loadedTrack.id().equals(track.id());
                Label artworkPlaceholder = artworkPlaceholder();
                artworkPlaceholder.getStyleClass().add("track-artwork-placeholder");
                StackPane artwork = new StackPane(artworkPlaceholder);
                artwork.getStyleClass().add("track-artwork");
                int artworkSize = ranked ? 56 : 46;
                artwork.setMinSize(artworkSize, artworkSize);
                artwork.setPrefSize(artworkSize, artworkSize);
                artwork.setMaxSize(artworkSize, artworkSize);
                javafx.scene.shape.Rectangle crop = new javafx.scene.shape.Rectangle(artworkSize, artworkSize);
                crop.setArcWidth(9);
                crop.setArcHeight(9);
                artwork.setClip(crop);
                if (track.coverUri() != null) {
                    ImageView image = new ImageView();
                    image.imageProperty().bind(coverImage(track.coverUri()));
                    image.setFitWidth(artworkSize);
                    image.setFitHeight(artworkSize);
                    image.setPreserveRatio(true);
                    image.setSmooth(true);
                    artworkPlaceholder.visibleProperty().bind(image.imageProperty().isNull());
                    artwork.getChildren().add(image);
                }
                StackPane playOverlay = new StackPane();
                playOverlay.getStyleClass().add("track-cover-play-overlay");
                SVGPath playOverlayIcon = icon("play", "track-cover-play-icon");
                playOverlayIcon.contentProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                        () -> iconPath(isPlaying && (playbackUiState.get() == JavaFxAudioPlayer.State.PLAYING
                                || playbackUiState.get() == JavaFxAudioPlayer.State.STALLED) ? "pause" : "play"),
                        playbackUiState));
                playOverlay.getChildren().add(playOverlayIcon);
                playOverlay.setOpacity(0.0);
                artwork.getChildren().add(playOverlay);
                artwork.setCursor(javafx.scene.Cursor.HAND);
                artwork.setOnMouseEntered(e -> playOverlay.setOpacity(1.0));
                artwork.setOnMouseExited(e -> playOverlay.setOpacity(0.0));
                Tooltip artworkTooltip = readableTooltip("");
                artworkTooltip.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                        () -> !isPlaying ? "点击播放此曲目"
                                : playbackUiState.get() == JavaFxAudioPlayer.State.PLAYING
                                        || playbackUiState.get() == JavaFxAudioPlayer.State.STALLED
                                        ? "正在播放 · 点击暂停" : "已暂停 · 点击继续播放",
                        playbackUiState));
                Tooltip.install(artwork, artworkTooltip);
                artwork.setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY) {
                        event.consume();
                        list.getSelectionModel().clearAndSelect(getIndex());
                        if (isPlaying) {
                            playButton.fire();
                        } else {
                            playTrackFromList(list, track, getIndex());
                        }
                    }
                });
                Label title = new Label(songTitle(track));
                title.getStyleClass().add("track-title");
                title.setMinWidth(0);
                title.setTextOverrun(OverrunStyle.ELLIPSIS);
                title.setMaxWidth(Double.MAX_VALUE);
                title.setTooltip(readableTooltip(songTitle(track) + "\n" + performerCaption(track)
                        + "\n" + sourceCaption(track) + "\n" + track.title()));
                String performer = performerCaption(track);
                Label artist = new Label(performer);
                artist.getStyleClass().add("track-artist");
                artist.setMinWidth(0);
                artist.setMaxWidth(Double.MAX_VALUE);
                artist.setTextOverrun(OverrunStyle.ELLIPSIS);
                SVGPath playingWave = icon("music", "track-playing-indicator");
                playingWave.setVisible(isPlaying);
                playingWave.setManaged(isPlaying);
                HBox titleRow = new HBox(5, playingWave, title);
                titleRow.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(title, Priority.ALWAYS);
                VBox details = new VBox(3, titleRow, artist);
                details.setAlignment(Pos.CENTER_LEFT);
                details.setMinWidth(0);
                HBox.setHgrow(details, Priority.ALWAYS);
                String source = switch (track.source()) {
                    case "bilibili" -> "B 站";
                    case "local" -> "本地";
                    case "demo" -> "演示";
                    default -> "在线";
                };
                Label badge = new Label(track.isSegment() ? "片段" : track.partCount() > 1 ? track.partCount() + "P" : source);
                badge.getStyleClass().add("track-badge");
                badge.setTooltip(readableTooltip(sourceCaption(track)));
                Label length = new Label(formatTrackDuration(track.durationSeconds()));
                if (track.isSegment()) length.setTooltip(new Tooltip(
                        LyricTiming.formatStart(track.segmentStartMillis()) + " - "
                                + LyricTiming.formatStart(track.segmentEndMillis())));
                length.getStyleClass().add("track-duration");
                Label views = new Label(formatViewCount(track.viewCount()));
                views.getStyleClass().add("track-views");
                views.setMinWidth(Region.USE_PREF_SIZE);
                views.setVisible(track.viewCount() > 0);
                views.setManaged(views.isVisible());
                boolean unavailable = trackPreferences != null && trackPreferences.isUnavailable(track.id());
                boolean isExcluded = trackPreferences != null && trackPreferences.isExcluded(track.id());
                HBox metadata = new HBox(8, views, badge, length);
                metadata.getStyleClass().add("track-metadata");
                metadata.setAlignment(Pos.CENTER_RIGHT);
                metadata.setMinWidth(Region.USE_PREF_SIZE);
                boolean saved = favorites.stream().anyMatch(item -> item.id().equals(track.id()));
                Button heart = new Button(saved ? "取消收藏" : "收藏");
                heart.getStyleClass().addAll("track-action", "track-favorite");
                heart.setGraphic(icon("heart", "row-icon"));
                heart.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                heart.setTooltip(readableTooltip(heart.getText()));
                heart.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), saved);
                heart.setOnAction(event -> toggleTrackFavorite(track));
                Button more = new Button("更多操作");
                more.getStyleClass().addAll("track-action", "track-more");
                more.setGraphic(icon("more", "row-icon"));
                more.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                more.setTooltip(readableTooltip("更多操作"));
                HBox actions = new HBox(2, heart, more);
                actions.getStyleClass().add("track-row-actions");
                actions.setMinWidth(58);
                actions.setMaxWidth(58);
                for (Button button : new Button[] {heart, more})
                    button.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
                HBox row;
                CheckBox selected = new CheckBox();
                selected.getStyleClass().add("track-select");
                selected.setVisible(Boolean.TRUE.equals(view.getProperties().get("batch-mode")));
                selected.setManaged(selected.isVisible());
                selected.setSelected(list.getSelectionModel().isSelected(getIndex()));
                selected.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, javafx.event.Event::consume);
                selected.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
                selected.setOnAction(event -> {
                    if (selected.isSelected()) list.getSelectionModel().select(getIndex());
                    else list.getSelectionModel().clearSelection(getIndex());
                });
                Label index = new Label(String.format("%02d", getIndex() + 1));
                index.getStyleClass().add("track-index");
                if (ranked) {
                    row = new HBox(10, selected, index, artwork, details, metadata, actions);
                } else row = new HBox(10, selected, artwork, details, metadata, actions);
                Runnable fitRow = () -> {
                    boolean narrow = row.getWidth() < 520;
                    metadata.setVisible(!narrow);
                    metadata.setManaged(!narrow);
                    if (!narrow) {
                        boolean showViews = row.getWidth() >= 620 && track.viewCount() > 0;
                        views.setVisible(showViews);
                        views.setManaged(showViews);
                    }
                    index.setVisible(!narrow);
                    index.setManaged(!narrow);
                    int size = narrow ? 34 : artworkSize;
                    artwork.setMinSize(size, size);
                    artwork.setPrefSize(size, size);
                    artwork.setMaxSize(size, size);
                    if (artwork.getClip() instanceof javafx.scene.shape.Rectangle cropRect) {
                        cropRect.setWidth(size);
                        cropRect.setHeight(size);
                    }
                    for (javafx.scene.Node child : artwork.getChildren()) {
                        if (child instanceof ImageView image) {
                            image.setFitWidth(size);
                            image.setFitHeight(size);
                        }
                    }

                    String suffix = narrow && track.durationSeconds() > 0
                            ? " · " + formatTrackDuration(track.durationSeconds()) : "";
                    String origin = list.getItems() == queued && getIndex() < queue.sources().size()
                            ? switch (queue.sourceAt(getIndex())) {
                                case MANUAL -> "手动安排 · ";
                                case RADIO -> "电台推荐 · ";
                                case CURRENT_LIST -> "歌单后续 · ";
                            } : "";
                    artist.setText(origin + performer + suffix + (unavailable ? " · 失效" : "")
                            + (isExcluded ? " · 不想听" : ""));
                    if (unavailable) artist.setTooltip(new Tooltip(trackPreferences.failureReason(track.id())));
                    row.setSpacing(narrow ? 7 : 10);
                    row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), narrow);
                };
                row.widthProperty().addListener((ignored, before, after) -> fitRow.run());
                fitRow.run();
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("track-row");
                row.prefWidthProperty().bind(list.widthProperty().subtract(20));
                row.setMinWidth(0);
                row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("unavailable"), unavailable);
                row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), isPlaying);
                setGraphic(row);
                ContextMenu menu = new ContextMenu();
                populateTrackMenu(menu, list, track, getIndex());
                menu.setOnShowing(event -> {
                    if (!list.getSelectionModel().isSelected(getIndex()))
                        list.getSelectionModel().clearAndSelect(getIndex());
                    populateTrackMenu(menu, list, track, getIndex());
                    themePopup(menu);
                });
                menu.setOnShown(event -> themePopup(menu));
                more.setOnAction(event -> menu.show(more, Side.BOTTOM, 0, 3));
                setContextMenu(menu);
            }
        });
        return view;
    }

    private HBox trackManagementBar(ListView<Track> list) {
        ToggleButton manage = new ToggleButton("批量管理", icon("check-list", "row-icon"));
        manage.setId(list.getId() + "-batch-mode");
        manage.getStyleClass().add("batch-toggle");
        CheckBox all = new CheckBox("全选");
        all.setId(list.getId() + "-select-all");
        Label count = new Label();
        count.setId(list.getId() + "-selection-count");
        Button more = actionButton("批量操作", list.getId() + "-batch-more", () -> { });
        decorateIcon(more, "more", "action-icon");
        HBox selection = new HBox(10, all, count, more);
        selection.setAlignment(Pos.CENTER_LEFT);
        selection.visibleProperty().bind(manage.selectedProperty());
        selection.managedProperty().bind(manage.selectedProperty());
        HBox bar = new HBox(12, manage, selection);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("track-batch-bar");
        Runnable update = () -> {
            int size = list.getSelectionModel().getSelectedIndices().size();
            count.setText("已选 " + size + " 首");
            all.setSelected(size > 0 && size == list.getItems().size());
            all.setIndeterminate(size > 0 && size < list.getItems().size());
            more.setDisable(size == 0);
        };
        PauseTransition refresh = new PauseTransition(Duration.millis(16));
        refresh.setOnFinished(event -> { if (manage.isSelected()) list.refresh(); });
        list.getSelectionModel().getSelectedIndices().addListener((javafx.collections.ListChangeListener<Integer>) change -> {
            update.run(); refresh.playFromStart();
        });
        manage.setOnAction(event -> {
            list.getProperties().put("batch-mode", manage.isSelected());
            if (!manage.isSelected()) list.getSelectionModel().clearSelection();
            list.refresh(); update.run();
        });
        all.setOnAction(event -> {
            if (all.isSelected()) list.getSelectionModel().selectAll();
            else list.getSelectionModel().clearSelection();
            update.run();
        });
        more.setOnAction(event -> batchTrackMenu(List.copyOf(list.getSelectionModel().getSelectedItems()), list)
                .show(more, Side.BOTTOM, 0, 3));
        update.run();
        return bar;
    }

    private boolean isFavorite(Track track) {
        return favorites.stream().anyMatch(item -> item.id().equals(track.id()));
    }

    private ContextMenu diaryTrackMenu(Track track, List<Track> selected) {
        ListView<Track> source = new ListView<>(FXCollections.observableArrayList(track));
        ContextMenu menu = new ContextMenu();
        populateTrackMenu(menu, source, track, 0);
        if (selected.size() > 1) {
            menu.getItems().add(new SeparatorMenuItem());
            Menu batch = new Menu("已选 " + selected.size() + " 首的批量操作");
            ContextMenu operations = batchTrackMenu(selected, null);
            List<MenuItem> choices = List.copyOf(operations.getItems());
            operations.getItems().clear();
            batch.getItems().setAll(choices);
            menu.getItems().add(batch);
        }
        menu.setOnShowing(event -> themePopup(menu));
        menu.setOnShown(event -> themePopup(menu));
        return menu;
    }

    private ContextMenu batchTrackMenu(List<Track> selection, ListView<Track> list) {
        List<Track> selected = List.copyOf(selection);
        ContextMenu menu = new ContextMenu();
        menu.getItems().add(trackMenuItem("收藏所选 · " + selected.size() + " 首", "heart", "batch-menu-favorite",
                () -> batchLibrary(selected, track -> libraryClient.favorite(track), "已批量收藏")));
        menu.getItems().add(trackMenuItem("取消所选收藏", "heart", "batch-menu-unfavorite",
                () -> unfavoriteSelection(selected)));
        Menu add = new Menu("加入歌单", icon("playlist-add", "menu-icon"));
        add.setId("batch-menu-playlist");
        for (var playlist : playlists) {
            MenuItem target = new MenuItem(playlist.name());
            target.setOnAction(event -> batchLibrary(selected, track -> libraryClient.addToPlaylist(playlist.id(), track),
                    "已加入歌单“" + playlist.name() + "”"));
            add.getItems().add(target);
        }
        add.setDisable(add.getItems().isEmpty());
        menu.getItems().add(add);
        menu.getItems().add(trackMenuItem("下一首播放", "next", "batch-menu-next", () -> arrangeTracks(selected, true, list != null && list.getItems() == queued)));
        menu.getItems().add(trackMenuItem("稍后播放 · 添加到队尾", "list", "batch-menu-enqueue", () -> arrangeTracks(selected, false, list != null && list.getItems() == queued)));
        if (list != null && (list.getItems() == playlistTracks || list.getItems() == favorites
                || list.getItems() == queued || list.getItems() == radioTracks
                || "local-list".equals(list.getId()) || list.getItems() == searchResults
                || list.getItems() == recommendations)) {
            Runnable remove;
            String label;
            if (list.getItems() == playlistTracks && playlistPicker.getValue() != null) {
                var playlist = playlistPicker.getValue();
                remove = () -> removePlaylistTracks(playlist, selected); label = "从歌单移除所选";
            } else if (list.getItems() == favorites) {
                remove = () -> unfavoriteSelection(selected); label = "移除所选收藏";
            } else if (list.getItems() == queued) {
                var indices = List.copyOf(list.getSelectionModel().getSelectedIndices());
                remove = () -> removeQueueTracks(indices); label = "从队列移除所选";
            } else if (list.getItems() == radioTracks) {
                remove = () -> selected.forEach(this::removeRadioItem); label = "移出本次电台";
            } else if ("local-list".equals(list.getId())) {
                remove = () -> removeLocalTracks(selected); label = "从本地列表移除（不删除文件）";
            } else {
                remove = () -> { list.getItems().removeAll(selected); status.setText("已从此列表移除 · " + selected.size() + " 首"); };
                label = list.getItems() == localTracks ? "从本地列表移除（不删除文件）" : "从此列表移除所选";
            }
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(trackMenuItem(label, "trash", "batch-menu-remove", remove));
        }
        menu.setOnShowing(event -> themePopup(menu));
        menu.setOnShown(event -> themePopup(menu));
        return menu;
    }

    @FunctionalInterface private interface TrackWrite { LibraryClient.Snapshot apply(Track track) throws Exception; }

    private void batchLibrary(List<Track> selected, TrackWrite write, String success) {
        if (selected.isEmpty()) return;
        libraryStatus.setText("正在批量更新…");
        libraryTasks.submit(() -> {
            int completed = 0;
            Exception failure = null;
            LibraryClient.Snapshot snapshot = null;
            for (Track track : selected) {
                try { snapshot = write.apply(track); completed++; }
                catch (Exception error) { failure = error; break; }
            }
            if (failure != null) try { snapshot = libraryClient.load(); } catch (Exception ignored) { }
            VersionCatalog updatedCatalog = null;
            try { updatedCatalog = versionClient.load(); } catch (Exception ignored) { }
            VersionCatalog catalog = updatedCatalog;
            LibraryClient.Snapshot result = snapshot;
            String text = failure == null ? success + " · " + completed + " 首"
                    : "已完成 " + completed + " 首；其余未完成：" + message(failure);
            Platform.runLater(() -> {
                if (closing) return;
                if (catalog != null) versionCatalog = catalog;
                if (result != null) applyLibrary(result);
                libraryStatus.setText(text); status.setText(text);
            });
        });
    }

    private void playTrackFromList(ListView<Track> list, Track track, int index) {
        if (index < 0 || index >= list.getItems().size()) return;
        if (list.getItems() == radioTracks) playRadioItem(track);
        else if (list.getItems() == searchResults) selectSearchResult(index);
        else if (list.getItems() == queued) { queue.selectIndex(index); loadCurrent(true); }
        else select(list.getItems(), index);
    }

    private void populateTrackMenu(ContextMenu menu, ListView<Track> list, Track track, int rowIndex) {
        menu.getItems().clear();
        int selectedCount = selectedTracks(list, rowIndex).size();
        String batchLabel = selectedCount > 1 ? "（" + selectedCount + " 首）" : "";
        menu.getItems().add(trackMenuItem("播放", "play", "track-menu-play", () -> {
            int index = rowIndex;
            if (index < 0) return;
            playTrackFromList(list, track, index);
        }));
        menu.getItems().add(trackMenuItem("下一首播放" + batchLabel, "list", "track-menu-next", () ->
                arrangeTracks(selectedTracks(list, rowIndex), true, list.getItems() == queued)));
        menu.getItems().add(trackMenuItem("添加到队尾" + batchLabel, "list", "track-menu-enqueue", () ->
                arrangeTracks(selectedTracks(list, rowIndex), false, list.getItems() == queued)));
        if ("local".equals(track.source())) {
            menu.getItems().add(trackMenuItem("打开所在文件夹", "folder", "track-menu-local-folder", () -> locateLocalTrack(track)));
            if ("local-list".equals(list.getId()))
                menu.getItems().add(trackMenuItem("从本地列表移除" + batchLabel, "trash", "track-menu-local-remove",
                        () -> removeLocalTracks(selectedTracks(list, rowIndex))));
        }
        boolean saved = favorites.stream().anyMatch(item -> item.id().equals(track.id()));
        menu.getItems().add(trackMenuItem(selectedCount > 1 ? "收藏所选" + batchLabel : saved ? "取消收藏" : "收藏",
                "heart", "track-menu-favorite", () -> {
                    if (selectedCount > 1) batchLibrary(selectedTracks(list, rowIndex), item -> libraryClient.favorite(item), "已批量收藏");
                    else toggleTrackFavorite(track);
                }));
        if (selectedCount > 1) menu.getItems().add(trackMenuItem("取消所选收藏" + batchLabel, "heart", "track-menu-unfavorite",
                () -> unfavoriteSelection(selectedTracks(list, rowIndex))));
        Menu add = new Menu("加入歌单");
        add.setId("track-menu-playlist");
        add.setGraphic(icon("list", "menu-icon"));
        for (LibraryClient.Playlist playlist : playlists) {
            MenuItem target = new MenuItem(playlist.name());
            target.setOnAction(event -> batchLibrary(selectedTracks(list, rowIndex), item -> libraryClient.addToPlaylist(playlist.id(), item),
                    "已加入歌单“" + playlist.name() + "”"));
            add.getItems().add(target);
        }
        if (add.getItems().isEmpty()) {
            MenuItem empty = new MenuItem("请先新建歌单");
            empty.setDisable(true);
            add.getItems().add(empty);
        }
        add.setOnShowing(event -> {
            if (!add.getItems().isEmpty()) themePopup(add.getItems().get(0).getParentPopup());
        });
        menu.getItems().add(add);
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(trackMenuItem("从此曲开启电台", "sparkle", "track-menu-radio",
                () -> showRadio(List.of(track), "")));
        menu.getItems().add(trackMenuItem("暂时听腻了", "repeat-off", "track-menu-tired",
                () -> radioFeedback(track, "tired")));
        menu.getItems().add(trackMenuItem("少推荐这个版本", "repeat-off", "track-menu-less-version",
                () -> radioFeedback(track, "version")));
        if (list.getItems() == radioTracks)
            menu.getItems().add(trackMenuItem("移出本次电台", "close", "track-menu-radio-remove",
                    () -> removeRadioItem(track)));
        menu.getItems().add(trackMenuItem("歌曲版本", "list", "track-menu-versions", () -> showVersions(track)));
        menu.getItems().add(trackMenuItem("私人备注", "list", "track-menu-diary-note", () -> {
            openDiaryScene.run();
            diaryPane.editTrackNote(track);
        }));
        boolean inLibrary = saved || playlists.stream().anyMatch(item -> item.tracks().stream()
                .anyMatch(resource -> resource.id().equals(track.id())))
                || versionCatalog.findByResourceId(track.id()).isPresent();
        if (inLibrary) menu.getItems().add(trackMenuItem("编辑标签", "list", "track-menu-tags",
                () -> showSmartLibrary(track.id())));
        if (versionCatalog.findByResourceId(track.id()).isPresent())
            menu.getItems().add(trackMenuItem("播放此资源", "play", "track-menu-resource", () -> playResource(track)));
        menu.getItems().add(trackMenuItem(track.isSegment() ? "编辑片段" : "保存为片段歌曲", "list",
                track.isSegment() ? "track-menu-segment-edit" : "track-menu-segment",
                () -> showSegmentDialog(track)));
        menu.getItems().add(trackMenuItem("合集批量整理", "list", "track-menu-segment-batch",
                () -> showSegmentBatch(track)));
        if (track.isSegment()) menu.getItems().add(trackMenuItem("删除片段", "close",
                "track-menu-segment-delete", () -> deleteSegment(track)));
        int position = rowIndex;
        if (list.getItems() == favorites || list.getItems() == playlistTracks || list.getItems() == queued) {
            MenuItem up = trackMenuItem("上移", "previous", "track-menu-up", () -> {
                list.getSelectionModel().clearAndSelect(rowIndex);
                if (list.getItems() == favorites) moveFavorite(list, -1);
                else if (list.getItems() == playlistTracks) movePlaylistTrack(list, -1);
                else editQueue(() -> queue.reorder(rowIndex, rowIndex - 1), "播放队列顺序已调整");
            });
            up.setDisable(position <= 0);
            MenuItem down = trackMenuItem("下移", "next", "track-menu-down", () -> {
                list.getSelectionModel().clearAndSelect(rowIndex);
                if (list.getItems() == favorites) moveFavorite(list, 1);
                else if (list.getItems() == playlistTracks) movePlaylistTrack(list, 1);
                else editQueue(() -> queue.reorder(rowIndex, rowIndex + 1), "播放队列顺序已调整");
            });
            down.setDisable(position < 0 || position >= list.getItems().size() - 1);
            menu.getItems().addAll(up, down);
        }
        if (track.bvid() != null) {
            menu.getItems().add(trackMenuItem("复制 B 站链接", "external", "track-menu-copy", () -> {
                ClipboardContent content = new ClipboardContent();
                content.putString("https://www.bilibili.com/video/" + track.bvid() + "?p=" + track.page());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            }));
        }
        if (list.getItems() == playlistTracks && playlistPicker.getValue() != null) {
            LibraryClient.Playlist playlist = playlistPicker.getValue();
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(trackMenuItem("从歌单移除" + batchLabel, "close", "track-menu-remove", () ->
                    removePlaylistTracks(playlist, selectedTracks(list, rowIndex))));
        }
        if (list.getItems() == queued) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(trackMenuItem("从队列移除" + batchLabel, "close", "track-menu-queue-remove", () -> {
                List<Integer> indices = list.getSelectionModel().isSelected(rowIndex)
                        ? List.copyOf(list.getSelectionModel().getSelectedIndices()) : List.of(rowIndex);
                removeQueueTracks(indices);
            }));
        }
        if (!track.isSegment() && track.partCount() > 1) {
            boolean excluded = trackPreferences != null && trackPreferences.isExcluded(track.id());
            menu.getItems().add(trackMenuItem(excluded ? "恢复参与播放" : "不想听这个分 P", "repeat-off",
                    "track-menu-exclude", () -> setExcluded(track, !excluded)));
        }
        if (trackPreferences != null && trackPreferences.isUnavailable(track.id()))
            menu.getItems().add(trackMenuItem("重试播放", "repeat", "track-menu-retry", () -> select(List.of(track), 0)));
    }

    private static MenuItem trackMenuItem(String title, String glyph, String id, Runnable action) {
        MenuItem item = new MenuItem(title, icon(glyph, "menu-icon"));
        item.setId(id);
        item.setOnAction(event -> action.run());
        return item;
    }

    private String homeRadioHint() {
        var input = primaryStage.getScene().lookup("#home-query");
        return input instanceof TextField field ? field.getText().strip() : "";
    }

    private void continueHomePlayback() {
        if (queue.current() == null || playButton.isDisabled()) return;
        if (audio.state() != JavaFxAudioPlayer.State.PLAYING
                && audio.state() != JavaFxAudioPlayer.State.STALLED) playButton.fire();
    }

    private void updateHomeSession() {
        if (personalHome == null || audio == null) return;
        Track track = queue.current();
        boolean playing = audio.state() == JavaFxAudioPlayer.State.PLAYING
                || audio.state() == JavaFxAudioPlayer.State.STALLED;
        double position = restoredPositionSeconds != null ? restoredPositionSeconds
                : pendingResumeSeconds != null ? pendingResumeSeconds : seconds(playbackPosition());
        String origin = queueListeningContext == null ? "当前队列"
                : "歌单「" + queueListeningContext.playlistName() + "」";
        double duration = track == null ? 0 : track.durationSeconds();
        if (track != null && loadedTrack != null && track.id().equals(loadedTrack.id())
                && restoredPositionSeconds == null) duration = Math.max(duration, seconds(playbackDuration()));
        String detail = track == null ? "" : performerCaption(track) + "\n" + origin + " · "
                + queue.tracks().size() + " 首 · " + format(Duration.seconds(position)) + " / "
                + (duration > 0 ? format(Duration.seconds(duration)) : "待加载时长");
        String key = (track == null ? "" : track.id() + songTitle(track)) + "\n" + detail
                + "\n" + playing + "\n" + playButton.isDisabled();
        if (key.equals(homeSessionKey)) return;
        homeSessionKey = key;
        personalHome.setSession(track, detail, !playButton.isDisabled(), playing);
    }

    private void openHomePin(HomeClient.Pin pin) {
        if ("playlist".equals(pin.kind())) openHomePlaylist.accept(pin.id());
        else {
            showSmartLibrary(null);
            smartDialog.selectPlaylist(pin.id());
        }
    }

    private void refreshPersonalHome() {
        if (closing || personalHome == null || homePreferencesSaving) return;
        long request = ++homeRequestVersion;
        personalHome.setFeedback("");
        personalHome.setPlaylists(List.copyOf(playlists), homeOrganization);
        updateHomeSession();
        readHomeData(request, homeClient::load, personalHome::setPreferences);
        readHomeData(request, smartClient::load, state -> {
            homeOrganization = state;
            personalHome.setPlaylists(List.copyOf(playlists), state);
        });
        readHomeData(request, diaryClient::load, personalHome::setDiary);
    }

    private <T> void readHomeData(long request, Callable<T> read,
                                 java.util.function.Consumer<T> apply) {
        CompletableFuture.supplyAsync(() -> {
            try { return read.call(); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((value, error) -> Platform.runLater(() -> {
            if (closing || request != homeRequestVersion) return;
            if (error != null) personalHome.setFeedback("首页信息暂不可用：" + message(error)
                    + "。已有内容仍可使用，重新打开首页可重试。");
            else apply.accept(value);
        }));
    }

    private void saveHomePreferences(HomeClient.Preferences draft) {
        if (closing || homePreferencesSaving) return;
        homePreferencesSaving = true;
        ++homeRequestVersion;
        libraryTasks.submit(() -> {
            try {
                HomeClient.Preferences saved = homeClient.save(draft);
                Platform.runLater(() -> {
                    if (closing) return;
                    homePreferencesSaving = false;
                    personalHome.preferenceSaveSucceeded(saved);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closing) return;
                    homePreferencesSaving = false;
                    personalHome.preferenceSaveFailed("保存失败：" + message(error) + "。草稿已保留。");
                });
            }
        });
    }

    private void showRadio(List<Track> seeds, String hint) {
        if (radioDialog != null) radioDialog.close();
        Dialog<Void> dialog = new Dialog<>();
        radioDialog = dialog;
        dialog.initOwner(primaryStage);
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.setTitle("私人电台");
        dialog.setResizable(true);
        dialog.getDialogPane().setId("radio-dialog");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        themeSegmentDialog(dialog.getDialogPane());
        TextField hintInput = new TextField(hint.isBlank() && radio.active() ? radio.hint() : hint);
        hintInput.setId("radio-hint");
        hintInput.setPromptText("此刻想听什么");
        hintInput.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= 160 ? change : null));
        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("熟悉一些", "多发现一些"));
        mode.setId("radio-mode");
        mode.setValue(radio.active() && radio.mode().equals("discover") ? "多发现一些" : "熟悉一些");
        CheckBox neutral = new CheckBox("本次不影响口味");
        neutral.setId("radio-neutral");
        neutral.setSelected(radio.active() && radio.neutral());
        CheckBox auto = new CheckBox("自动续播");
        auto.setId("radio-auto");
        auto.setSelected(!radio.active() || radio.autoContinue());
        auto.setOnAction(event -> {
            radio.setAutoContinue(auto.isSelected());
            if (!auto.isSelected()) radioContinuationPending = false;
            radioStatus.setText(auto.isSelected() ? "自动续播已开启" : "自动续播已关闭");
        });
        List<Track> startSeeds = seeds.isEmpty() && radio.active() ? radio.seeds() : List.copyOf(seeds);
        Button start = actionButton("开启电台", "radio-start", () -> {
            startRadio(startSeeds, hintInput.getText().strip(),
                    mode.getValue().equals("多发现一些") ? "discover" : "familiar", neutral.isSelected());
            radio.setAutoContinue(auto.isSelected());
        });
        decorateIcon(start, "play", "action-icon");
        Button refresh = actionButton("刷新候选", "radio-refresh", () -> fetchRadio(false));
        decorateIcon(refresh, "repeat", "action-icon");
        Button stop = actionButton("停止电台", "radio-stop", this::stopRadio);
        decorateIcon(stop, "repeat-off", "action-icon");
        FlowPane controls = new FlowPane(8, 8, mode, neutral, auto, start, refresh, stop);
        Label seedTitle = new Label(startSeeds.isEmpty() ? "你的私人电台"
                : startSeeds.size() == 1 ? "从「" + songTitle(startSeeds.get(0)) + "」出发"
                : "来自所选歌单 · " + startSeeds.size() + " 首");
        seedTitle.setWrapText(true);
        seedTitle.getStyleClass().add("section-title");
        radioStatus.setId("radio-status");
        radioStatus.setWrapText(true);
        radioStatus.getStyleClass().add("muted");
        ListView<Track> list = trackList(radioTracks);
        list.setId("radio-list");
        list.setPlaceholder(new Label("暂无待播候选"));
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && list.getSelectionModel().getSelectedItem() != null)
                playRadioItem(list.getSelectionModel().getSelectedItem());
        });
        Label reason = new Label();
        reason.setId("radio-reason");
        reason.setWrapText(true);
        reason.getStyleClass().add("muted");
        list.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) ->
                reason.setText(selected == null ? "" : radio.preview().stream()
                        .filter(item -> item.track().id().equals(selected.id())).map(RadioClient.Item::reason)
                        .findFirst().orElse("")));
        Button play = actionButton("播放选中", "radio-play", () -> {
            if (list.getSelectionModel().getSelectedItem() != null)
                playRadioItem(list.getSelectionModel().getSelectedItem());
        });
        decorateIcon(play, "play", "action-icon");
        Button remove = actionButton("移出本次", "radio-remove", () -> {
            if (list.getSelectionModel().getSelectedItem() != null)
                removeRadioItem(list.getSelectionModel().getSelectedItem());
        });
        decorateIcon(remove, "close", "action-icon");
        Button feedback = actionButton("口味反馈", "radio-feedback-open", this::showRadioFeedback);
        decorateIcon(feedback, "list", "action-icon");
        VBox content = new VBox(12, seedTitle, hintInput, controls, radioStatus, trackManagementBar(list), list, reason,
                new FlowPane(8, 8, play, remove, feedback));
        content.setPadding(new Insets(14));
        content.setPrefSize(640, 570);
        content.setMinSize(360, 400);
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.setOnHidden(event -> {
            trackViews.remove(list);
            if (radioDialog == dialog) radioDialog = null;
        });
        dialog.show();
        setRadioBusy(radioLoading);
    }

    private void startRadio(List<Track> seeds, String hint, String mode, boolean neutral) {
        if (nextPreloader != null) nextPreloader.cancel();
        ++collectionVersion;
        ++radioVersion;
        radioLoading = false;
        if (neutral) finishListening();
        radio.start(seeds, hint, mode, neutral);
        loadedNeutral = neutral;
        radioContinuationPending = false;
        radioTracks.clear();
        if (queue.current() != null) radio.recordPlayed(queue.current());
        fetchRadio(queue.current() == null);
    }

    private void setRadioBusy(boolean busy) {
        if (radioDialog == null) return;
        for (String id : List.of("radio-start", "radio-refresh")) {
            var control = radioDialog.getDialogPane().lookup("#" + id);
            if (control != null) control.setDisable(busy);
        }
        var start = radioDialog.getDialogPane().lookup("#radio-start");
        if (start instanceof Button button) button.setText(busy ? "寻找中…" : "开启电台");
    }

    private void fetchRadio(boolean continueWhenReady) {
        if (closing || !radio.active()) { radioStatus.setText("请先开启电台"); return; }
        if (continueWhenReady) { radioContinuationPending = true; radioWaitingLoad = loadVersion; }
        if (radioLoading) return;
        long version = ++radioVersion;
        List<Track> seeds = radio.seeds();
        String hint = radio.hint();
        String mode = radio.mode();
        boolean neutral = radio.neutral();
        var excluded = new java.util.LinkedHashSet<>(radio.seenIds());
        queue.tracks().forEach(track -> excluded.add(track.id()));
        if (trackPreferences != null) excluded.addAll(trackPreferences.excludedIds());
        radioLoading = true;
        setRadioBusy(true);
        radioStatus.setText("正在寻找下一批音乐…");
        CompletableFuture.supplyAsync(() -> {
            try { return radioClient.generate(seeds, hint, mode, neutral, excluded); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, radioTasks).whenComplete((result, error) -> Platform.runLater(() -> {
            if (closing || version != radioVersion || !radio.active()) return;
            radioLoading = false;
            setRadioBusy(false);
            if (error != null) {
                radioStatus.setText("电台暂不可用：" + message(error));
                radioContinuationPending = false;
                return;
            }
            radio.accept(result);
            syncRadioPreview();
            radioStatus.setText(result.message().isBlank()
                    ? (radioTracks.isEmpty() ? "候选不足，暂无可继续播放的歌曲" : "待播候选 · " + radioTracks.size() + " 首")
                    : result.message());
            if (radioContinuationPending && radioWaitingLoad == loadVersion && radio.autoContinue()) {
                radioContinuationPending = false;
                RadioClient.Item next = nextRadioItem();
                if (next != null) playRadioTrack(next.track());
                else status.setText("电台候选不足，播放已结束");
            }
        }));
    }

    private void syncRadioPreview() {
        radioTracks.setAll(radio.preview().stream().map(RadioClient.Item::track).toList());
    }

    private RadioClient.Item nextRadioItem() {
        RadioClient.Item item;
        while ((item = radio.popNext()) != null) {
            if (trackPreferences == null || !trackPreferences.isExcluded(item.track().id())) {
                syncRadioPreview(); return item;
            }
        }
        syncRadioPreview();
        return null;
    }

    private void continueRadio() {
        finishListening();
        audio.stop();
        if (!radio.autoContinue()) { status.setText("已安排歌曲播放完毕，电台自动续播已关闭"); return; }
        RadioClient.Item next = nextRadioItem();
        if (next != null) playRadioTrack(next.track());
        else fetchRadio(true);
    }

    private void playRadioTrack(Track track) {
        if (queue.current() == null) queue.select(List.of(track), 0, PlayerQueue.Source.RADIO);
        else { queue.playNext(track, PlayerQueue.Source.RADIO); queue.advanceWithoutWrap(); }
        updateQueuePreferences();
        queued.setAll(queue.tracks());
        loadCurrent(true);
    }

    private void playRadioItem(Track track) {
        RadioClient.Item item = radio.take(track.id());
        if (item == null) return;
        syncRadioPreview();
        playRadioTrack(item.track());
    }

    private void removeRadioItem(Track track) {
        radio.remove(track.id());
        syncRadioPreview();
        refreshNextPreparation();
        radioStatus.setText("已移出本次电台：" + track.title());
    }

    private void stopRadio() {
        if (nextPreloader != null) nextPreloader.cancel();
        ++radioVersion;
        radio.stop();
        radioLoading = false;
        radioContinuationPending = false;
        radioTracks.clear();
        setRadioBusy(false);
        radioStatus.setText("电台已停止");
    }

    private void radioFeedback(Track track, String kind) {
        if (radio.active() && radio.neutral()) {
            removeRadioItem(track);
            radioStatus.setText("仅移出本次电台，未保存口味反馈");
            return;
        }
        radioStatus.setText("正在保存口味反馈…");
        CompletableFuture.runAsync(() -> {
            try { radioClient.feedback(track, kind); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, radioTasks).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (closing) return;
            if (error != null) radioStatus.setText("反馈保存失败：" + message(error));
            else {
                removeRadioItem(track);
                status.setText(kind.equals("tired") ? "七天内少听这首歌，可在电台反馈中恢复" : "已降低此版本的推荐权重，可恢复");
                radioStatus.setText(status.getText());
            }
        }));
    }

    private void postRadioSkip(Track track) {
        if (radio.neutral()) return;
        radioTasks.submit(() -> {
            try { radioClient.feedback(track, "skip"); }
            catch (Exception error) { System.err.println("Radio skip feedback: " + error.getMessage()); }
        });
    }

    private void showRadioFeedback() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("口味反馈");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        themeSegmentDialog(dialog.getDialogPane());
        ListView<RadioClient.Feedback> list = new ListView<>();
        list.setId("radio-feedback-list");
        list.setPlaceholder(new Label("暂无口味反馈"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(RadioClient.Feedback item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                String kind = switch (item.kind()) {
                    case "tired" -> "暂时听腻了";
                    case "version" -> "少推荐这个版本";
                    default -> "跳过反馈 · 弱信号";
                };
                setText(item.track().title() + "\n" + kind);
            }
        });
        Label feedbackStatus = new Label("正在加载…");
        feedbackStatus.setWrapText(true);
        Button restore = actionButton("恢复选中", "radio-feedback-restore", () -> {
            var selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            feedbackStatus.setText("正在恢复…");
            CompletableFuture.supplyAsync(() -> {
                try { return radioClient.restore(selected.track().id()); }
                catch (Exception error) { throw new RuntimeException(error); }
            }, radioTasks).whenComplete((entries, error) -> Platform.runLater(() -> {
                if (closing || !dialog.isShowing()) return;
                if (error == null) {
                    radio.restoreRemoved(selected.track().id());
                    list.getItems().setAll(entries);
                    feedbackStatus.setText("已恢复，下次刷新生效");
                }
                else feedbackStatus.setText("恢复失败：" + message(error));
            }));
        });
        decorateIcon(restore, "repeat", "action-icon");
        VBox content = new VBox(12, list, feedbackStatus, restore);
        content.setPadding(new Insets(14));
        content.setPrefSize(460, 400);
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.show();
        CompletableFuture.supplyAsync(() -> {
            try { return radioClient.feedback(); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, radioTasks).whenComplete((entries, error) -> Platform.runLater(() -> {
            if (closing || !dialog.isShowing()) return;
            if (error == null) { list.getItems().setAll(entries); feedbackStatus.setText("已保存的口味反馈"); }
            else feedbackStatus.setText("加载失败：" + message(error));
        }));
    }

    private String songTitle(Track track) {
        return versionCatalog.findByResourceId(track.id()).map(VersionCatalog.Song::title).orElse(track.title());
    }

    private String performerCaption(Track track) {
        return versionCatalog.versionByResourceId(track.id()).map(version -> {
            String performer = version.performer() == null ? "" : version.performer().strip();
            String label = version.label() == null ? "" : version.label().strip();
            String identity = performer.isEmpty() ? authorCaption(track) : performer;
            return label.isEmpty() ? identity : identity + " · " + label;
        }).orElseGet(() -> authorCaption(track));
    }

    private static String authorCaption(Track track) {
        String author = track.artist() == null ? "" : track.artist().strip();
        if ("bilibili".equals(track.source())) return author.isEmpty() ? "B 站音源" : "UP 主：" + author;
        return author.isEmpty() ? ("local".equals(track.source()) ? "本地音频" : "未知演唱者") : author;
    }

    private static String sourceCaption(Track track) {
        String source = switch (track.source()) {
            case "bilibili" -> "B 站";
            case "local" -> "本地音频";
            case "demo" -> "演示音源";
            default -> "在线音源";
        };
        if (track.bvid() != null) source += " · " + track.bvid() + " · P" + track.page();
        if (track.isSegment()) source += " · 片段 " + LyricTiming.formatStart(track.segmentStartMillis())
                + "–" + LyricTiming.formatStart(track.segmentEndMillis());
        return source;
    }

    private void playResource(Track track) {
        clearListeningContext();
        stopRadio();
        queue.select(List.of(track), 0);
        updateQueuePreferences();
        queued.setAll(queue.tracks());
        loadCurrent(true);
    }

    private void playLyricFavorite(Track track, long positionMillis) {
        if (track == null || positionMillis < 0) return;
        stopRadio();
        clearListeningContext();
        clearPracticeLoop();
        queue.select(List.of(track), 0);
        updateQueuePreferences();
        queued.setAll(queue.tracks());
        loadCurrent(true, positionMillis / 1000.0);
    }

    private void seekLyricLine(int index) {
        if (index < 0 || index >= activeLyrics.size() || lyricMediaVersion != loadVersion
                || segmentSeeking || loadedTrack == null) return;
        try {
            long position = LyricTiming.mediaMillis(activeLyrics.get(index).millis(),
                    lyricStartMillis, lyricOffsetMillis);
            double total = seconds(playbackDuration());
            if (total <= 0) { status.setText("请等待音频时长就绪"); return; }
            if (position < 0 || position >= total * 1000) {
                status.setText("这句歌词不在当前曲目范围内");
                return;
            }
            pendingResumeSeconds = null;
            seekToFraction(position / (total * 1000));
            status.setText("已定位到 " + LyricTiming.formatStart(position));
        } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
    }

    private long practiceDurationMillis() {
        return Math.round(seconds(playbackDuration()) * 1000);
    }

    private void markPracticeA() {
        try {
            practiceLoop.markStart(Math.round(seconds(playbackPosition()) * 1000), practiceDurationMillis());
            practiceSeekTarget = null;
            refreshPracticeControls(true);
        } catch (IllegalArgumentException error) { practiceMessage(error.getMessage()); }
    }

    private void markPracticeB() {
        try {
            practiceLoop.markEnd(Math.round(seconds(playbackPosition()) * 1000), practiceDurationMillis());
            practiceSeekTarget = null;
            refreshPracticeControls(true);
            seekPracticeStart();
        } catch (IllegalArgumentException error) { practiceMessage(error.getMessage()); }
    }

    private void clearPracticeLoop() {
        practiceLoop.clear();
        practiceSeekTarget = null;
        refreshPracticeControls(true);
    }

    private void practiceMessage(String message) {
        if (practiceFeedback != null) practiceFeedback.setText(message);
        status.setText(message);
    }

    private void refreshPracticeControls(boolean fields) {
        String range = practiceLoop.complete() ? "A " + LyricTiming.formatStart(practiceLoop.startMillis())
                + "  –  B " + LyricTiming.formatStart(practiceLoop.endMillis()) : "尚未设置循环范围";
        practiceSummary.setText(range + (practiceLoop.enabled() ? " · 循环中" : ""));
        for (Button button : practiceButtons) {
            button.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"),
                    practiceLoop.enabled() || playbackRate != 1.0);
            button.setTooltip(new Tooltip("精听与练习"
                    + (practiceLoop.complete() ? " · " + range + (practiceLoop.enabled() ? " · 循环中" : " · 已暂停") : "")
                    + String.format(java.util.Locale.ROOT, " · %s×", playbackRate)));
        }
        if (practiceDialog == null) return;
        if (fields) {
            practiceStart.setText(practiceLoop.startMillis() < 0 ? ""
                    : LyricTiming.formatStart(practiceLoop.startMillis()));
            practiceEnd.setText(practiceLoop.endMillis() < 0 ? ""
                    : LyricTiming.formatStart(practiceLoop.endMillis()));
        }
        practiceEnabled.setSelected(practiceLoop.enabled());
        practiceSave.setDisable(!practiceLoop.complete() || loadedTrack == null);
        if (!java.util.Objects.equals(practiceRate.getValue(), playbackRate)) practiceRate.setValue(playbackRate);
    }

    private boolean commitPracticeRange() {
        if (practiceDialog == null) return false;
        try {
            long start = LyricTiming.parseStart(practiceStart.getText());
            long end = LyricTiming.parseStart(practiceEnd.getText());
            practiceLoop.configure(start, end, practiceDurationMillis());
            practiceSeekTarget = null;
            refreshPracticeControls(false);
            practiceFeedback.setText("");
            return true;
        } catch (IllegalArgumentException error) {
            practiceMessage(error.getMessage());
            return false;
        }
    }

    private void setPracticeRate(double rate) {
        if (!Double.isFinite(rate) || rate == playbackRate) return;
        boolean playing = audio.state() == JavaFxAudioPlayer.State.PLAYING;
        if (listeningActive) listening.observe(seconds(playbackPosition()), playing, false, playbackRate);
        if (audio.setPlaybackRate(rate)) {
            playbackRate = audio.playbackRate();
            if (listeningActive) listening.observe(seconds(playbackPosition()), playing, false, playbackRate);
            practiceMessage(String.format(java.util.Locale.ROOT, "播放速度 %.2f× · %s", playbackRate,
                    audio == streamingAudio ? "VLC 保持音调" : "JavaFX 音调由引擎处理"));
        } else practiceMessage("当前音源无法使用这个播放速度");
        refreshPracticeControls(false);
    }

    private void showPracticeDialog() {
        if (practiceDialog != null) { practiceDialog.getDialogPane().getScene().getWindow().requestFocus(); return; }
        practiceDialog = new Dialog<>();
        practiceDialog.initOwner(primaryStage);
        practiceDialog.initModality(javafx.stage.Modality.NONE);
        practiceDialog.setTitle("精听与练习");
        practiceDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        themeSegmentDialog(practiceDialog.getDialogPane());
        practiceStart = new TextField();
        practiceStart.setId("practice-start");
        practiceStart.setPromptText("分:秒.毫秒");
        practiceEnd = new TextField();
        practiceEnd.setId("practice-end");
        practiceEnd.setPromptText("分:秒.毫秒");
        Button markA = actionButton("标记 A", "practice-mark-a", this::markPracticeA);
        Button markB = actionButton("标记 B", "practice-mark-b", this::markPracticeB);
        markA.setTooltip(new Tooltip("将当前播放位置设为循环起点"));
        markB.setTooltip(new Tooltip("将当前播放位置设为循环终点"));
        practiceEnabled = new CheckBox("A–B 循环");
        practiceEnabled.setId("practice-enabled");
        practiceEnabled.setOnAction(event -> {
            boolean enable = practiceEnabled.isSelected();
            if (enable && !commitPracticeRange()) { refreshPracticeControls(false); return; }
            practiceLoop.setEnabled(enable);
            practiceSeekTarget = null;
            refreshPracticeControls(false);
            if (enable) seekPracticeStart();
        });
        Button clear = actionButton("清除标记", "practice-clear", this::clearPracticeLoop);
        decorateIcon(clear, "close", "action-icon");
        practiceStart.setOnAction(event -> commitPracticeRange());
        practiceEnd.setOnAction(event -> commitPracticeRange());
        for (TextField input : List.of(practiceStart, practiceEnd)) {
            input.setPrefColumnCount(12);
            input.setMinWidth(0);
            input.focusedProperty().addListener((ignored, before, focused) -> {
                if (!focused && practiceDialog != null && practiceDialog.isShowing()
                        && !practiceStart.getText().isBlank() && !practiceEnd.getText().isBlank())
                    commitPracticeRange();
            });
        }
        practiceRate = new ComboBox<>(FXCollections.observableArrayList(0.75, 1.0, 1.25, 1.5));
        practiceRate.setId("practice-rate");
        practiceRate.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Double value) {
                return value == null ? "" : String.format(java.util.Locale.ROOT, "%s×", value);
            }
            @Override public Double fromString(String value) { return Double.valueOf(value.replace("×", "")); }
        });
        practiceRate.setValue(playbackRate);
        practiceRate.setOnAction(event -> { if (practiceRate.getValue() != null) setPracticeRate(practiceRate.getValue()); });
        practiceFeedback = new Label();
        practiceFeedback.setId("practice-feedback");
        practiceFeedback.setWrapText(true);
        practiceFeedback.getStyleClass().add("muted");
        practiceSave = actionButton("保存为片段歌曲", "practice-save-segment", this::savePracticeSegment);
        decorateIcon(practiceSave, "list", "action-icon");
        GridPane marks = new GridPane();
        marks.setHgap(10);
        marks.setVgap(12);
        ColumnConstraints nameColumn = new ColumnConstraints();
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        marks.getColumnConstraints().addAll(nameColumn, inputColumn);
        HBox aRow = new HBox(8, practiceStart, markA);
        HBox bRow = new HBox(8, practiceEnd, markB);
        HBox.setHgrow(practiceStart, Priority.ALWAYS);
        HBox.setHgrow(practiceEnd, Priority.ALWAYS);
        marks.addRow(0, new Label("A"), aRow);
        marks.addRow(1, new Label("B"), bRow);
        FlowPane actions = new FlowPane(10, 8, practiceEnabled, clear, practiceSave);
        HBox rateRow = new HBox(12, new Label("播放速度"), practiceRate);
        rateRow.setAlignment(Pos.CENTER_LEFT);
        practiceSummary.getStyleClass().add("practice-summary");
        VBox content = new VBox(16, marks, actions, practiceSummary, new Separator(), rateRow, practiceFeedback);
        content.getStyleClass().add("practice-content");
        content.setPrefWidth(430);
        practiceDialog.getDialogPane().setContent(content);
        practiceDialog.setOnHidden(event -> {
            practiceDialog = null;
            practiceFeedback = null;
        });
        refreshPracticeControls(true);
        practiceDialog.show();
    }

    private void savePracticeSegment() {
        if (loadedTrack == null || !practiceLoop.complete()) return;
        Track track = loadedTrack;
        long start = practiceLoop.sourceStart(track), end = practiceLoop.sourceEnd(track);
        if (practiceDialog != null) practiceDialog.close();
        showSegmentDialog(track, start, end);
    }

    private void seekPracticeStart() {
        if (!practiceLoop.enabled() || segmentSeeking || practiceDurationMillis() <= 0) return;
        practiceSeekTarget = practiceLoop.startMillis();
        practiceSeekStartedNanos = System.nanoTime();
        seekToFraction(practiceSeekTarget / (double) practiceDurationMillis(), true);
    }

    private boolean updatePracticeLoop() {
        if (!practiceLoop.enabled() || segmentSeeking || pendingResumeSeconds != null) return false;
        long current = Math.round(seconds(playbackPosition()) * 1000);
        if (practiceSeekTarget != null) {
            // A coarse native clock may first report near B in a very short, accelerated loop.
            if (current >= practiceLoop.startMillis() && current < practiceLoop.endMillis())
                practiceSeekTarget = null;
            else if (System.nanoTime() - practiceSeekStartedNanos > TimeUnit.SECONDS.toNanos(5)) {
                practiceLoop.setEnabled(false);
                practiceSeekTarget = null;
                refreshPracticeControls(false);
                practiceMessage("音源未能定位到 A 点，循环已暂停");
            }
            return true;
        }
        if (audio.state() != JavaFxAudioPlayer.State.PLAYING) return false;
        long end = practiceLoop.endMillis();
        if (current >= end || current < practiceLoop.startMillis()) {
            seekPracticeStart();
            return true;
        }
        return false;
    }

    private void showVersions(Track track) {
        libraryStatus.setText("正在载入歌曲版本…");
        libraryTasks.submit(() -> {
            try {
                VersionCatalog catalog = versionClient.load();
                Platform.runLater(() -> {
                    versionCatalog = catalog;
                    var resources = new ArrayList<Track>();
                    resources.addAll(favorites);
                    playlists.forEach(playlist -> resources.addAll(playlist.tracks()));
                    resources.addAll(localTracks);
                    resources.addAll(searchResults);
                    resources.addAll(recommendations);
                    resources.addAll(queued);
                    new VersionManagerDialog(primaryStage, track, resources, catalog, versionClient,
                            client, libraryTasks, result -> {
                                replaceQueuedResources(versionCatalog, result);
                                versionCatalog = result;
                                refreshTrackViews();
                                runLibrary(libraryClient::load, "歌曲版本已更新");
                            }, this::playResource, this::showVersions, this::themeSegmentDialog).show();
                    libraryStatus.setText("歌曲版本已载入");
                });
            } catch (Exception error) {
                Platform.runLater(() -> libraryStatus.setText("歌曲版本载入失败：" + message(error)));
            }
        });
    }

    private void showVersionLibrary() {
        libraryTasks.submit(() -> {
            try {
                VersionCatalog catalog = versionClient.load();
                Platform.runLater(() -> {
                    versionCatalog = catalog;
                    if (catalog.songs().isEmpty()) libraryStatus.setText("暂无已整理歌曲");
                    else catalog.songs().get(0).preferredTrack().ifPresent(this::showVersions);
                });
            } catch (Exception error) {
                Platform.runLater(() -> libraryStatus.setText("歌曲版本载入失败：" + message(error)));
            }
        });
    }

    private void showSmartLibrary(String trackId) {
        if (smartDialog == null || !smartDialog.isShowing()) {
            smartDialog = new SmartPlaylistDialog(primaryStage, smartClient, libraryTasks, tracks -> {
                if (persistentCache == null) throw new IllegalStateException("音频缓存状态不可用");
                try { return persistentCache.cachedTrackIds(tracks); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            }, () -> List.copyOf(playlists), (tracks, index) -> {
                if (index < 0 || index >= tracks.size()) return;
                clearListeningContext();
                stopRadio();
                // Rules select resources (for example a live version), so preserve those exact matches.
                queue.select(List.copyOf(tracks), index);
                updateQueuePreferences();
                queued.setAll(queue.tracks());
                loadCurrent(true);
            }, this::showVersions, () -> runLibrary(libraryClient::load, "曲库整理已更新"),
                    this::themeSegmentDialog);
            smartDialog.setTrackManagement(this::trackManagementBar);
        }
        if (trackId == null) smartDialog.show();
        else smartDialog.showForTrack(trackId);
    }

    private void replaceQueuedResources(VersionCatalog previous, VersionCatalog replacement) {
        var changes = new java.util.HashMap<String, Track>();
        for (var song : previous.songs()) for (var version : song.versions()) {
            replacement.songs().stream().flatMap(item -> item.versions().stream())
                    .filter(item -> item.id().equals(version.id()) && !item.track().id().equals(version.track().id()))
                    .findFirst().ifPresent(item -> changes.put(version.track().id(), item.track()));
        }
        if (changes.isEmpty()) return;
        List<Track> resources = new ArrayList<>(queue.tracks());
        for (int index = 0; index < resources.size(); index++) {
            if (index == queue.index() && loadedTrack != null) continue;
            Track updated = changes.get(resources.get(index).id());
            if (updated != null) resources.set(index, updated);
        }
        queue.restore(resources, queue.index(), queue.sources());
        updateQueuePreferences();
        queued.setAll(queue.tracks());
        savePlaybackState();
    }

    private record SegmentDestination(String label, String playlistId) {
        @Override public String toString() { return label; }
    }

    private static final class SegmentPreview {
        final PlayerQueue.Snapshot queueSnapshot;
        final List<Track> tracks;
        final int index;
        final PlayerQueue.RepeatMode mode;
        final double position;
        final boolean playing;
        final boolean loaded;
        long version = -1;
        SegmentPreview(PlayerQueue.Snapshot snapshot,
                       double position, boolean playing, boolean loaded) {
            this.queueSnapshot = snapshot;
            this.tracks = snapshot.tracks();
            this.index = snapshot.index();
            this.mode = snapshot.repeatMode();
            this.position = position;
            this.playing = playing;
            this.loaded = loaded;
        }
    }

    private void showSegmentDialog(Track track) {
        showSegmentDialog(track, null, null);
    }

    private void showSegmentBatch(Track track) {
        if (segmentBatchDialog != null && segmentBatchDialog.isShowing()) return;
        Track source = track.audioSource();
        if (loadedTrack != null && loadedTrack.audioSource().id().equals(source.id())) {
            int actual = (int) Math.ceil(seconds(audio.duration()));
            if (actual > 0) source = new Track(source.id(), source.title(), source.artist(), actual,
                    source.source(), source.mediaUri(), source.bvid(), source.page(),
                    source.coverUri(), source.partCount(), source.viewCount());
        }
        SegmentPreview[] context = {null};
        Runnable restore = () -> { if (!closing && context[0] != null) restoreSegmentPreview(context[0]); };
        segmentBatchDialog = new SegmentBatchDialog(primaryStage, source, libraryClient, libraryTasks, result -> {
            if (closing) return;
            applyLibrary(result.snapshot());
            String notice = "已保存 " + result.savedCount() + " 个片段"
                    + (result.skippedCount() > 0 ? "，跳过 " + result.skippedCount() + " 个重复片段" : "");
            libraryStatus.setText(notice); status.setText(notice);
        }, chosen -> {
            if (context[0] == null || segmentPreview != context[0] || context[0].version != loadVersion) {
                context[0] = new SegmentPreview(queue.snapshot(),
                        restoredPositionSeconds == null ? seconds(playbackPosition()) : restoredPositionSeconds,
                        audio.state() == JavaFxAudioPlayer.State.PLAYING,
                        audio.state() != JavaFxAudioPlayer.State.IDLE && restoredPositionSeconds == null);
            }
            finishListening(); segmentPreview = context[0];
            queue.select(List.of(chosen), 0); queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
            queued.setAll(queue.tracks()); context[0].version = loadVersion + 1;
            loadCurrent(true);
        }, restore, this::themeSegmentDialog, name -> icon(name, "action-icon"));
        segmentBatchDialog.show();
    }

    private void showSegmentDialog(Track track, Long startOverride, Long endOverride) {
        boolean editing = track.isSegment() && startOverride == null;
        Track source = track.audioSource();
        if (!editing && loadedTrack != null && loadedTrack.audioSource().id().equals(source.id())) {
            int actual = (int) Math.ceil(seconds(audio.duration()));
            if (actual > 0) source = new Track(source.id(), source.title(), source.artist(), actual,
                    source.source(), source.mediaUri(), source.bvid(), source.page(),
                    source.coverUri(), source.partCount(), source.viewCount());
        }
        Track origin = source;
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle(editing ? "编辑片段歌曲" : "保存片段歌曲");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        themeSegmentDialog(dialog.getDialogPane());
        TextField title = new TextField(track.title());
        title.setId("segment-title");
        TextField artist = new TextField(track.artist());
        artist.setId("segment-artist");
        long startAt = startOverride != null ? startOverride : editing ? track.segmentStartMillis() : currentSourceMillis(origin);
        long endAt = endOverride != null ? endOverride : editing ? track.segmentEndMillis() : origin.durationSeconds() > 0
                ? Math.min(origin.durationSeconds() * 1000L, startAt + 30000) : startAt + 30000;
        if (endAt <= startAt) startAt = Math.max(0, endAt - 30000);
        TextField start = new TextField(LyricTiming.formatStart(startAt));
        start.setId("segment-start");
        TextField end = new TextField(LyricTiming.formatStart(endAt));
        end.setId("segment-end");
        Label message = new Label();
        message.setId("segment-message");
        message.setWrapText(true);
        message.getStyleClass().add("muted");
        Button startCurrent = actionButton("取当前时间", "segment-start-current", () -> {
            if (isCurrentSource(origin)) start.setText(LyricTiming.formatStart(currentSourceMillis(origin)));
            else message.setText("请先播放这个音源，或直接输入起点");
        });
        Button endCurrent = actionButton("取当前时间", "segment-end-current", () -> {
            if (isCurrentSource(origin)) end.setText(LyricTiming.formatStart(currentSourceMillis(origin)));
            else message.setText("请先播放这个音源，或直接输入终点");
        });
        ComboBox<SegmentDestination> destination = new ComboBox<>();
        destination.setId("segment-destination");
        destination.getItems().add(new SegmentDestination("我的收藏", null));
        for (LibraryClient.Playlist playlist : playlists)
            destination.getItems().add(new SegmentDestination(playlist.name(), playlist.id()));
        destination.getSelectionModel().selectFirst();
        destination.setMaxWidth(Double.MAX_VALUE);
        destination.setDisable(editing);
        SegmentPreview context = new SegmentPreview(queue.snapshot(),
                restoredPositionSeconds == null ? seconds(playbackPosition()) : restoredPositionSeconds,
                audio.state() == JavaFxAudioPlayer.State.PLAYING,
                audio.state() != JavaFxAudioPlayer.State.IDLE && restoredPositionSeconds == null);
        Supplier<Track> candidate = () -> {
            long from = LyricTiming.parseStart(start.getText());
            long to = LyricTiming.parseStart(end.getText());
            String performer = artist.getText().isBlank() ? origin.artist() : artist.getText();
            return editing ? track.withSegment(title.getText(), performer, from, to)
                    : Track.segment(origin, title.getText(), performer, from, to);
        };
        Button preview = actionButton("试听", "segment-preview", () -> {
            try {
                Track chosen = candidate.get();
                finishListening();
                segmentPreview = context;
                queue.select(List.of(chosen), 0);
                queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
                queued.setAll(queue.tracks());
                context.version = loadVersion + 1;
                loadCurrent(true);
                message.setText("正在试听：" + chosen.title());
            } catch (IllegalArgumentException error) { message.setText(error.getMessage()); }
        });
        decorateIcon(preview, "play", "action-icon");
        Button save = new Button(editing ? "保存修改" : "保存歌曲");
        save.setId("segment-save");
        save.getStyleClass().add("accent-button");
        save.setOnAction(event -> {
            try {
                Track chosen = candidate.get();
                SegmentDestination target = destination.getValue();
                save.setDisable(true);
                preview.setDisable(true);
                message.setText("正在保存…");
                libraryTasks.submit(() -> {
                    try {
                        LibraryClient.Snapshot snapshot = editing ? libraryClient.updateSegment(chosen)
                                : target.playlistId() == null ? libraryClient.favorite(chosen)
                                : libraryClient.addToPlaylist(target.playlistId(), chosen);
                        Platform.runLater(() -> {
                            restoreSegmentPreview(context);
                            applyLibrary(snapshot);
                            if (editing) replaceSegmentInQueue(chosen);
                            libraryStatus.setText(editing ? "片段已更新" : "片段歌曲已保存");
                            status.setText(libraryStatus.getText());
                            dialog.close();
                        });
                    } catch (Exception error) {
                        Platform.runLater(() -> {
                            save.setDisable(false);
                            preview.setDisable(false);
                            message.setText("保存失败：" + message(error));
                        });
                    }
                });
            } catch (IllegalArgumentException error) { message.setText(error.getMessage()); }
        });
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(12);
        ColumnConstraints names = new ColumnConstraints();
        names.setMinWidth(56);
        ColumnConstraints inputs = new ColumnConstraints();
        inputs.setHgrow(Priority.ALWAYS);
        fields.getColumnConstraints().addAll(names, inputs);
        fields.addRow(0, new Label("歌曲名"), title);
        fields.addRow(1, new Label("歌手"), artist);
        fields.addRow(2, new Label("起点"), new HBox(8, start, startCurrent));
        fields.addRow(3, new Label("终点"), new HBox(8, end, endCurrent));
        HBox.setHgrow(start, Priority.ALWAYS);
        HBox.setHgrow(end, Priority.ALWAYS);
        fields.addRow(4, new Label(editing ? "范围" : "保存到"), editing
                ? new Label("同步更新收藏与歌单中的这个片段") : destination);
        Label sourceName = new Label(origin.bvid() == null ? origin.title()
                : origin.bvid() + " · P" + origin.page());
        sourceName.setWrapText(true);
        sourceName.getStyleClass().add("muted");
        HBox actions = new HBox(10, preview, save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(14, sourceName, fields, actions, message);
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.setOnHidden(event -> { if (!closing) restoreSegmentPreview(context); });
        dialog.show();
    }

    private boolean isCurrentSource(Track source) {
        return loadedTrack != null && loadedTrack.audioSource().id().equals(source.id())
                && lyricMediaVersion == loadVersion;
    }

    private long currentSourceMillis(Track source) {
        return isCurrentSource(source) ? Math.max(0, (long) audio.position().toMillis()) : 0;
    }

    private void themeSegmentDialog(DialogPane pane) {
        SmoothScrolling.install(pane);
        FriendlyTooltips.install(pane);
        pane.getStylesheets().add(getClass().getResource("/lumen-theme.css").toExternalForm());
        pane.getStyleClass().add(themeMode.name().toLowerCase(java.util.Locale.ROOT) + "-theme");
        if (pane.getProperties().putIfAbsent("lumen.text-menu-theme", true) == null)
            pane.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    event -> Platform.runLater(this::themeOpenTextMenus));
    }

    private void restoreSegmentPreview(SegmentPreview context) {
        if (segmentPreview != context) return;
        segmentPreview = null;
        if (context.version != loadVersion) return;
        finishListening();
        queue.restore(context.queueSnapshot);
        queued.setAll(queue.tracks());
        context.version = -1;
        if (queue.current() != null && context.loaded) loadCurrent(context.playing, context.position);
        else {
            ++loadVersion;
            lyricMediaVersion = -1;
            loadedTrack = null;
            cancelPendingOnline();
            audio.stop();
            restoredPositionSeconds = queue.current() == null ? null : context.position;
            refreshFavoriteButton();
        }
    }

    private void replaceSegmentInQueue(Track updated) {
        boolean current = queue.current() != null && queue.current().id().equals(updated.id());
        double position = seconds(playbackPosition());
        boolean playing = audio.state() == JavaFxAudioPlayer.State.PLAYING;
        queue.replaceById(updated);
        queued.setAll(queue.tracks());
        if (current) loadCurrent(playing, Math.min(position,
                (updated.segmentEndMillis() - updated.segmentStartMillis()) / 1000.0));
    }

    private void deleteSegment(Track track) {
        if (!confirmRemoval("删除片段歌曲", "删除“" + track.title()
                + "”的片段记录及收藏、歌单引用？\n原始音源仍保留，此操作不能撤回。")) return;
        runLibrary(() -> libraryClient.deleteSegment(track.id()), "片段已删除", () -> {
            boolean current = queue.current() != null && queue.current().id().equals(track.id());
            if (current) finishListening();
            queue.removeById(track.id());
            queued.setAll(queue.tracks());
            if (current) {
                ++loadVersion;
                lyricMediaVersion = -1;
                loadedTrack = null;
                segmentFinished = false;
                cancelPendingOnline();
                audio.stop();
                restoredPositionSeconds = queue.current() == null ? null : 0.0;
                refreshFavoriteButton();
            }
        });
    }

    private static void animateEntrance(javafx.scene.Node node, double x, double y) {
        UiMotion.entrance(node, x, y);
    }

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Node... ancestors) {
        for (javafx.scene.Node current = node; current != null; current = current.getParent())
            for (javafx.scene.Node ancestor : ancestors) if (current == ancestor) return true;
        return false;
    }

    private void pauseForSleep() {
        sleepPaused = true;
        currentAutoplay = false;
        stopRadio();
        if (audio.state() == JavaFxAudioPlayer.State.PLAYING
                || audio.state() == JavaFxAudioPlayer.State.STALLED) audio.toggle();
        status.setText("睡眠定时已结束，音乐已暂停");
    }

    private static String formatTrackDuration(int seconds) {
        if (seconds <= 0) return "";
        int hours = seconds / 3600;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String formatViewCount(long count) {
        if (count <= 0) return "";
        if (count >= 100000000) return String.format(java.util.Locale.ROOT, "%.1f 亿播放", count / 100000000.0);
        if (count >= 10000) return String.format(java.util.Locale.ROOT, "%.1f 万播放", count / 10000.0);
        return count + " 播放";
    }

    private void beginRuntimeStartup(boolean retry) {
        if (closing) return;
        libraryStatus.setText("正在连接个人曲库…本地音乐可直接导入和播放");
        retryStartupButton.setDisable(true);
        (retry ? LumenLauncher.retryRuntime() : LumenLauncher.startRuntimeAsync())
                .whenComplete((result, error) -> Platform.runLater(() -> {
            if (closing) return;
            boolean available = error == null && result.serviceAvailable();
            retryStartupButton.setDisable(false);
            retryStartupButton.setVisible(!available);
            retryStartupButton.setManaged(!available);
            if (!available) {
                libraryStatus.setText("个人曲库连接失败；本地音乐仍可使用，请重试连接");
                searchStatus.setText("服务尚未连接");
                return;
            }
            if (!serviceInitialized) {
                serviceInitialized = true;
                search("", "全部");
                runLibrary(libraryClient::load, "个人曲库已载入");
                diaryPane.refresh();
                if (recommendations.isEmpty()) loadInitialPopular();
                updateHomeSession();
            }
        }));
    }

    private void initializeVlcAsync() {
        vlcReady = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            uk.co.caprica.vlcj.factory.MediaPlayerFactory factory;
            try { factory = VlcAudioPlayer.prepareFactory(); }
            catch (RuntimeException | LinkageError error) {
                recordDiagnostic("VLC_START_ERROR");
                vlcReady.complete(false);
                return;
            }
            pendingVlcFactory.set(factory);
            if (closing) {
                if (pendingVlcFactory.compareAndSet(factory, null)) factory.release();
                vlcReady.complete(false);
                return;
            }
            Platform.runLater(() -> {
                if (!pendingVlcFactory.compareAndSet(factory, null)) { vlcReady.complete(false); return; }
                if (closing) { factory.release(); vlcReady.complete(false); return; }
                try {
                    streamingAudio = new VlcAudioPlayer(this::onPlaybackState, this::onAudioError, factory);
                    streamingAudio.setNormalizationEnabled(normalizationEnabled);
                    vlcReady.complete(true);
                } catch (RuntimeException | LinkageError error) {
                    factory.release();
                    recordDiagnostic("VLC_START_ERROR");
                    vlcReady.complete(false);
                }
            });
        }, "lumen-audio-start");
        worker.start();
    }

    private void importFiles(Stage stage) {
        var chooser = new FileChooser();
        chooser.setTitle("选择本地音频");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "音频文件", "*.mp3", "*.flac", "*.m4a", "*.mp4", "*.wav", "*.aiff", "*.aif", "*.ogg", "*.opus"));
        var files = chooser.showOpenMultipleDialog(stage);
        if (files != null) addFiles(files);
    }

    private void importFolder(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("导入音乐文件夹（包含子文件夹）");
        File folder = chooser.showDialog(stage);
        if (folder != null) addFiles(List.of(folder));
    }

    private void addFiles(List<File> files) {
        List<Path> selections = files.stream().map(File::toPath).toList();
        localCatalogueReady.thenRun(() -> Platform.runLater(() -> importLocalPaths(selections, false)));
    }

    private void importLocalPaths(List<Path> paths, boolean refresh) {
        if (closing) return;
        if (localImportBusy) { localStatus.setText("正在导入，请完成后再试"); return; }
        localImportBusy = true;
        localStatus.setText(refresh ? "正在刷新封面、歌词和标签…" : "正在扫描音乐文件夹并读取标签…");
        List<Track> before = List.copyOf(localTracks);
        CompletableFuture.supplyAsync(() -> LocalAudioImporter.scan(paths,
                refresh ? List.of() : before, dataDirectory()), localTasks)
                .whenComplete((result, error) -> Platform.runLater(() -> {
            localImportBusy = false;
            if (closing) return;
            if (error != null) { localStatus.setText("导入失败：" + message(error)); return; }
            for (var imported : result.imported()) {
                Track track = imported.track();
                localLyrics.put(track.id(), imported.lyrics());
                if (refresh) {
                    for (int i = 0; i < localTracks.size(); i++)
                        if (track.mediaUri().equals(localTracks.get(i).mediaUri())) { localTracks.set(i, track); break; }
                } else localTracks.add(track);
            }
            persistLocalCatalogue();
            localStatus.setText((refresh ? "已刷新 " : "已导入 ") + result.imported().size()
                    + " 首 · 重复 " + result.duplicates() + " · 无法读取 " + result.failures()
                    + " · 共 " + localTracks.size() + " 首");
            Track current = loadedTrack;
            if (current != null && "local".equals(current.source())) {
                localTracks.stream().filter(track -> track.mediaUri().equals(current.mediaUri())).findFirst()
                        .ifPresent(track -> {
                            showCover(track, loadVersion);
                            loadLyrics(track, loadVersion);
                        });
            }
        }));
    }

    private void restoreLocalCatalogue() {
        LocalMusicStore store = new LocalMusicStore(dataDirectory().resolve("local-music.xml"));
        CompletableFuture.supplyAsync(() -> {
            try { localCatalogueExisted = store.exists(); return store.read(); }
            catch (IOException error) { throw new RuntimeException(error); }
        }, localTasks).whenComplete((saved, error) -> Platform.runLater(() -> {
            if (closing) { localCatalogueReady.complete(null); return; }
            if (error == null) {
                localMusicStore = store;
                localLyrics.putAll(saved.lyrics());
                localTracks.setAll(saved.tracks());
                if (!localCatalogueExisted) {
                    List<Track> old = queue.tracks().stream().filter(track -> "local".equals(track.source())
                            && track.mediaUri() != null && !track.isSegment()).toList();
                    localTracks.addAll(old);
                    if (!old.isEmpty()) persistLocalCatalogue();
                }
                localStatus.setText("共 " + localTracks.size() + " 首 · 支持导入文件夹及拖入文件/文件夹");
            } else localStatus.setText("本地列表读取失败，原文件已保留：" + message(error));
            localCatalogueReady.complete(null);
        }));
    }

    private void persistLocalCatalogue() {
        if (localMusicStore == null) return;
        List<Track> tracks = List.copyOf(localTracks);
        java.util.Map<String, String> lyrics = new java.util.HashMap<>();
        for (Track track : tracks) if (localLyrics.containsKey(track.id())) lyrics.put(track.id(), localLyrics.get(track.id()));
        LocalMusicStore.State snapshot = new LocalMusicStore.State(tracks, lyrics);
        localTasks.submit(() -> {
            try { localMusicStore.save(snapshot); }
            catch (IOException error) { Platform.runLater(() -> {
                if (!closing) localStatus.setText("本地列表未能保存：" + message(error));
            }); }
        });
    }

    private void refreshLocalMetadata() {
        localMetadataReads.clear();
        localCatalogueReady.thenRun(() -> Platform.runLater(() -> importLocalPaths(localTracks.stream()
                .map(Track::mediaUri).filter(java.util.Objects::nonNull).map(Path::of).toList(), true)));
    }

    private void removeLocalTracks(List<Track> selected) {
        if (selected.isEmpty() || !confirmRemoval("移除本地音乐", "从本地列表移除 "
                + selected.size() + " 首音乐？\n原音频文件、收藏和歌单仍保留。")) return;
        localTracks.removeAll(selected);
        persistLocalCatalogue();
        localStatus.setText("已从本地列表移除 " + selected.size() + " 首 · 共 " + localTracks.size() + " 首");
    }

    private static String localParent(Track track) {
        try { return Path.of(track.mediaUri()).getParent().toString(); }
        catch (RuntimeException error) { return ""; }
    }

    private void refreshLocalFolders() {
        String before = localFolder.getValue();
        List<String> folders = new ArrayList<>();
        folders.add("全部文件夹");
        folders.addAll(localTracks.stream().map(LumenFxApp::localParent).filter(path -> !path.isBlank())
                .distinct().sorted().toList());
        if (!localFolder.getItems().equals(folders)) {
            localFolder.getItems().setAll(folders);
            localFolder.setValue(folders.contains(before) ? before : "全部文件夹");
        }
        filterLocalMusic();
    }

    private void updateTonearmBadge(JavaFxAudioPlayer.State state) {
        boolean playing = state == JavaFxAudioPlayer.State.PLAYING;
        immersiveTonearmBadge.setText(switch (state) {
            case PLAYING -> "◉ 唱针已落盘 · 演奏中";
            case PAUSED -> "○ 唱针已抬起 · 已暂停";
            case LOADING -> "○ 唱针待落盘 · 加载中";
            case STALLED -> "○ 唱针已抬起 · 缓冲中";
            case FAILED -> "○ 唱针已抬起 · 播放失败";
            case ENDED -> "○ 唱针已抬起 · 已结束";
            default -> "○ 唱针已抬起 · 待机";
        });
        immersiveTonearmBadge.getStyleClass().removeAll("badge-idle", "badge-playing");
        immersiveTonearmBadge.getStyleClass().add(playing ? "badge-playing" : "badge-idle");
    }

    private void filterLocalMusic() {
        String query = localSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String folder = localFolder.getValue();
        visibleLocalTracks.setPredicate(track -> (folder == null || folder.equals("全部文件夹")
                || folder.equals(localParent(track))) && (query.isBlank()
                || (track.title() + " " + track.artist() + " " + localFileName(track))
                        .toLowerCase(java.util.Locale.ROOT).contains(query)));
        if (localFilterCount != null) {
            if (!query.isBlank() || (folder != null && !folder.equals("全部文件夹"))) {
                localFilterCount.setText("显示 " + visibleLocalTracks.size() + " / " + localTracks.size() + " 首");
                localFilterCount.setVisible(true);
                localFilterCount.setManaged(true);
            } else {
                localFilterCount.setVisible(false);
                localFilterCount.setManaged(false);
            }
        }
    }

    private static String localFileName(Track track) {
        try { return Path.of(track.mediaUri()).getFileName().toString(); }
        catch (RuntimeException error) { return ""; }
    }

    private void locateLocalTrack(Track track) {
        try {
            Path path = Path.of(track.mediaUri());
            Path folder = path.getParent();
            if (!Files.isDirectory(folder)) { localStatus.setText("原文件夹已移动或不存在"); return; }
            getHostServices().showDocument(folder.toUri().toString());
        } catch (RuntimeException error) { localStatus.setText("无法打开所在文件夹：" + message(error)); }
    }

    private void select(List<Track> source, int index) {
        if (index < 0) return;
        stopRadio();
        queue.select(source.stream().map(versionCatalog::preferredTrack).toList(), index);
        LibraryClient.Playlist origin = source == playlistTracks ? playlistPicker.getValue() : null;
        queueListeningContext = origin == null ? null : new ListeningReporter.Context(origin.id(), origin.name());
        playlistQueueResources = origin == null ? java.util.Set.of() : queue.tracks().stream()
                .map(Track::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        updateQueuePreferences();
        queued.setAll(queue.tracks());
        loadCurrent(true);
    }

    private void selectSearchResult(int index) {
        if (index < 0 || index >= searchResults.size()) return;
        Track track = searchResults.get(index);
        if (versionCatalog.findByResourceId(track.id()).isPresent()) {
            select(searchResults, index);
            return;
        }
        List<Track> resolved = MusicClient.completeCollection(searchResults, track);
        if (!resolved.isEmpty()) select(resolved, resolved.indexOf(track));
        else if (track.partCount() > 1) selectCollection(track);
        else select(searchResults, index);
    }

    private void selectCollection(Track track) {
        if (track.bvid() == null) {
            status.setText("该合集缺少 BV 身份，无法展开分 P");
            return;
        }
        long request = ++collectionVersion;
        status.setText("正在展开合集分 P…");
        CompletableFuture.supplyAsync(() -> {
            try { return client.resolveCollection(track.bvid()); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((parts, error) -> Platform.runLater(() -> {
            if (request != collectionVersion) return;
            if (error != null) {
                showPlaybackFailure(track, PlaybackFailure.from(error));
                return;
            }
            if (parts.isEmpty()) {
                status.setText("该合集没有可播放的分 P");
                return;
            }
            int selected = 0;
            for (int index = 0; index < parts.size(); index++) {
                if (parts.get(index).page() == track.page()) { selected = index; break; }
            }
            queue.select(parts, selected);
            clearListeningContext();
            stopRadio();
            updateQueuePreferences();
            queued.setAll(queue.tracks());
            loadCurrent(true);
            showCollectionParts(parts);
        }));
    }

    private void advance(int step) {
        if (queue.current() == null) return;
        if (radio.active() && step > 0) {
            Track skipped = queue.current();
            if (!loadedNeutral) postRadioSkip(skipped);
            if (queue.advanceWithoutWrap() != null) loadCurrent(true);
            else continueRadio();
            return;
        }
        if (queue.move(step) == null) {
            finishListening();
            audio.stop();
            status.setText("队列中的曲目均已标记为不想听");
            return;
        }
        loadCurrent(true);
    }

    private void showCollectionParts(List<Track> parts) {
        if (collectionStage != null) collectionStage.close();
        Stage dialog = new Stage();
        collectionStage = dialog;
        dialog.initOwner(primaryStage);
        dialog.setTitle("分 P 列表");
        ObservableList<Track> items = FXCollections.observableArrayList(parts);
        ListView<Track> list = trackList(items);
        list.setId("collection-parts-list");
        list.getSelectionModel().select(Math.max(0, parts.indexOf(queue.current())));
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                select(parts, list.getSelectionModel().getSelectedIndex());
        });
        Button play = actionButton("播放选中分 P", "collection-play", () ->
                select(parts, list.getSelectionModel().getSelectedIndex()));
        Button dislike = new Button("不想听");
        dislike.setId("collection-exclude");
        dislike.setOnAction(event -> {
            Track selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                setExcluded(selected, trackPreferences == null || !trackPreferences.isExcluded(selected.id()));
                dislike.setText(trackPreferences != null && trackPreferences.isExcluded(selected.id())
                        ? "恢复参与播放" : "不想听");
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) -> {
            play.setDisable(selected == null);
            dislike.setDisable(selected == null);
            dislike.setText(selected != null && trackPreferences != null && trackPreferences.isExcluded(selected.id())
                    ? "恢复参与播放" : "不想听");
        });
        if (list.getSelectionModel().getSelectedItem() != null && trackPreferences != null
                && trackPreferences.isExcluded(list.getSelectionModel().getSelectedItem().id()))
            dislike.setText("恢复参与播放");
        Label count = new Label(parts.size() + " 个分 P");
        Label feedback = new Label();
        feedback.textProperty().bind(status.textProperty());
        feedback.setWrapText(true);
        VBox content = new VBox(10, count, trackManagementBar(list), list, new HBox(8, play, dislike), feedback);
        content.setPadding(new Insets(18));
        VBox.setVgrow(list, Priority.ALWAYS);
        Scene scene = new Scene(content, 680, 460);
        SmoothScrolling.install(content);
        FriendlyTooltips.install(content);
        scene.getStylesheets().add(getClass().getResource("/lumen-theme.css").toExternalForm());
        scene.getRoot().getStyleClass().add(themeMode == ThemeController.Mode.LIGHT ? "light-theme" : "dark-theme");
        dialog.setScene(scene);
        dialog.setOnHidden(event -> {
            trackViews.remove(list);
            if (collectionStage == dialog) collectionStage = null;
        });
        dialog.show();
    }

    private void setExcluded(Track track, boolean excluded) {
        if (trackPreferences == null) { status.setText("曲目偏好存储不可用"); return; }
        try {
            trackPreferences.setExcluded(track.id(), excluded);
            updateQueuePreferences();
            refreshTrackViews();
            status.setText((excluded ? "已标记不想听：" : "已恢复参与播放：") + track.title());
        } catch (IOException error) { status.setText("曲目偏好保存失败：" + error.getMessage()); }
    }

    private void updateQueuePreferences() {
        if (trackPreferences != null) queue.setExcludedIds(trackPreferences.excludedIds());
    }

    private void refreshTrackViews() { trackViews.forEach(ListView::refresh); }

    private void cleanupUnavailable(LibraryClient.Playlist playlist) {
        if (trackPreferences == null) return;
        List<Track> source = playlist == null ? List.copyOf(favorites) : playlist.tracks();
        List<Track> invalid = source.stream().filter(track -> trackPreferences.isUnavailable(track.id())).toList();
        if (invalid.isEmpty()) { libraryStatus.setText("没有已确认失效的曲目"); return; }
        if (!confirmRemoval("清理失效曲目", "从“" + (playlist == null ? "我的收藏" : playlist.name())
                + "”移除 " + invalid.size() + " 条已确认失效的记录？\n不删除音频文件，此操作不能撤回。")) return;
        runLibrary(() -> {
            LibraryClient.Snapshot result = null;
            for (Track track : invalid) result = playlist == null
                    ? libraryClient.unfavorite(track.id())
                    : libraryClient.removeFromPlaylist(playlist.id(), track.id());
            return result;
        }, "已清理 " + invalid.size() + " 首失效曲目");
    }

    private void cleanupAllUnavailable() {
        if (trackPreferences == null) return;
        List<Track> invalidFavorites = favorites.stream().filter(track -> trackPreferences.isUnavailable(track.id())).toList();
        List<LibraryClient.Playlist> currentPlaylists = List.copyOf(playlists);
        long invalidCount = invalidFavorites.size() + currentPlaylists.stream().mapToLong(playlist ->
                playlist.tracks().stream().filter(track -> trackPreferences.isUnavailable(track.id())).count()).sum();
        if (invalidCount == 0) { libraryStatus.setText("没有已确认失效的曲目"); return; }
        if (!confirmRemoval("清理全部失效曲目", "从收藏和歌单移除 " + invalidCount
                + " 条已确认失效的记录？\n不删除音频文件，此操作不能撤回。")) return;
        runLibrary(() -> {
            LibraryClient.Snapshot result = null;
            for (Track track : invalidFavorites) result = libraryClient.unfavorite(track.id());
            for (LibraryClient.Playlist playlist : currentPlaylists)
                for (Track track : playlist.tracks()) if (trackPreferences.isUnavailable(track.id()))
                    result = libraryClient.removeFromPlaylist(playlist.id(), track.id());
            return result;
        }, "已从收藏和歌单清理 " + invalidCount + " 条失效曲目");
    }

    private static <T> void enableDragReorder(ListView<T> list,
            java.util.function.Function<T, String> identity,
            java.util.function.BiConsumer<Integer, Integer> move) {
        var previousFactory = list.getCellFactory();
        list.setCellFactory(view -> {
            ListCell<T> cell = previousFactory == null ? new ListCell<>() {
                @Override protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.toString());
                }
            } : previousFactory.call(view);
            String prefix = "lumen-reorder:" + list.getId() + ":";
            cell.setOnDragDetected(event -> {
                if (cell.isEmpty()) return;
                var board = cell.startDragAndDrop(TransferMode.MOVE);
                var content = new ClipboardContent();
                content.putString(prefix + cell.getIndex() + ":" + identity.apply(cell.getItem()));
                board.setContent(content);
                event.consume();
            });
            cell.setOnDragOver(event -> {
                if (!cell.isEmpty() && event.getDragboard().hasString()
                        && event.getDragboard().getString().startsWith(prefix))
                    event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            });
            cell.setOnDragDropped(event -> {
                boolean accepted = false;
                String value = event.getDragboard().hasString() ? event.getDragboard().getString() : "";
                if (!cell.isEmpty() && value.startsWith(prefix)) {
                    String rest = value.substring(prefix.length());
                    int separator = rest.indexOf(':');
                    if (separator > 0) {
                        try {
                            int from = Integer.parseInt(rest.substring(0, separator));
                            int to = cell.getIndex();
                            if (from >= 0 && from < list.getItems().size()
                                    && to >= 0 && to < list.getItems().size()
                                    && identity.apply(list.getItems().get(from)).equals(rest.substring(separator + 1))) {
                                if (from != to) move.accept(from, to);
                                accepted = true;
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
                event.setDropCompleted(accepted);
                event.consume();
            });
            return cell;
        });
    }

    private void moveFavorite(ListView<Track> list, int step) {
        int from = list.getSelectionModel().getSelectedIndex();
        int to = from + step;
        if (from >= 0 && to >= 0 && to < favorites.size())
            runLibrary(() -> libraryClient.moveFavorite(from, to), "收藏顺序已更新",
                    () -> list.getSelectionModel().select(to));
    }

    private void movePlaylistTrack(ListView<Track> list, int step) {
        var playlist = playlistPicker.getValue();
        int from = list.getSelectionModel().getSelectedIndex();
        int to = from + step;
        if (playlist != null && from >= 0 && to >= 0 && to < playlistTracks.size())
            runLibrary(() -> libraryClient.moveInPlaylist(playlist.id(), from, to), "歌单顺序已更新",
                    () -> list.getSelectionModel().select(to));
    }

    private void askPlaylistName(String title, String prompt, String initial,
                                 java.util.function.Consumer<String> onName) {
        var dialog = new TextInputDialog(initial == null ? "" : initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        dialog.showAndWait().ifPresent(value -> {
            String name = value.strip();
            if (name.isEmpty()) libraryStatus.setText("歌单名称不能为空");
            else onName.accept(name);
        });
    }

    private void importPublicFavorite() {
        var dialog = new TextInputDialog();
        dialog.setTitle("导入 B 站公开收藏夹");
        dialog.initOwner(primaryStage);
        dialog.setHeaderText("粘贴公开收藏夹链接\n每次最多导入 200 首有效去重曲目，生成独立歌单");
        dialog.setContentText("链接：");
        dialog.showAndWait().ifPresent(value -> {
            String url = value.strip();
            if (url.isEmpty()) return;
            libraryStatus.setText("正在读取公开收藏夹并核对曲目…");
            libraryTasks.submit(() -> {
                try {
                    var imported = libraryClient.importPublicFavorite(url);
                    Platform.runLater(() -> {
                        applyLibrary(imported.snapshot());
                        if (!playlists.isEmpty()) playlistPicker.setValue(playlists.get(playlists.size() - 1));
                        libraryStatus.setText("已导入“" + imported.name() + "” "
                                + imported.importedCount() + " 首；跳过失效 "
                                + imported.skippedCount() + " 首、重复 "
                                + imported.duplicateCount() + " 首"
                                + (imported.truncated() ? "；已达 200 首上限，收藏夹还有未导入内容" : ""));
                    });
                } catch (Exception error) {
                    Platform.runLater(() -> libraryStatus.setText("收藏夹导入失败：" + message(error)));
                }
            });
        });
    }

    private void exportBackup(Stage stage) {
        var chooser = new FileChooser();
        chooser.setTitle("保存 Lumen Music 数据备份");
        chooser.setInitialFileName("Lumen-Music-备份.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 备份", "*.zip"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        libraryStatus.setText("正在导出数据备份…");
        libraryTasks.submit(() -> {
            try {
                var preview = backupClient.export(file.toPath());
                Platform.runLater(() -> libraryStatus.setText("备份已保存："
                        + preview.favorites() + " 首收藏、" + preview.playlists() + " 个歌单、"
                        + preview.history() + " 条听歌记录"));
            } catch (Exception error) {
                Platform.runLater(() -> libraryStatus.setText("备份导出失败：" + message(error)));
            }
        });
    }

    private void restoreBackup(Stage stage) {
        var chooser = new FileChooser();
        chooser.setTitle("选择 Lumen Music 数据备份");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 备份", "*.zip"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        libraryStatus.setText("正在检查备份内容…");
        libraryTasks.submit(() -> {
            try {
                var preview = backupClient.inspect(file.toPath());
                Platform.runLater(() -> {
                    if (!DestructiveConfirmation.confirm(stage, "恢复数据备份", "备份已通过完整性检查，包含 " + preview.favorites() + " 首收藏、"
                            + preview.playlists() + " 个歌单、" + preview.history()
                            + " 条听歌记录。\n文件：" + preview.members()
                            + "\n恢复将替换备份中包含的当前数据，此操作不能撤回。", this::themeSegmentDialog)) {
                        libraryStatus.setText("已取消恢复");
                        return;
                    }
                    if (audio.state() == JavaFxAudioPlayer.State.PLAYING
                            || audio.state() == JavaFxAudioPlayer.State.STALLED) audio.toggle();
                    finishListening();
                    libraryStatus.setText("正在恢复备份…");
                    libraryTasks.submit(() -> {
                        try {
                            var restored = backupClient.restore(file.toPath());
                            var snapshot = libraryClient.load();
                            Platform.runLater(() -> {
                                applyLibrary(snapshot);
                                refreshRestoredBackground();
                                recommendationStatus.setText("数据已恢复");
                                libraryStatus.setText("已恢复 " + restored.favorites()
                                        + " 首收藏、" + restored.playlists() + " 个歌单");
                            });
                        } catch (Exception error) {
                            Platform.runLater(() -> libraryStatus.setText(
                                    "备份恢复失败：" + message(error)));
                        }
                    });
                });
            } catch (Exception error) {
                Platform.runLater(() -> libraryStatus.setText("备份检查失败：" + message(error)));
            }
        });
    }

    private void runLibrary(Callable<LibraryClient.Snapshot> operation, String success) {
        runLibrary(operation, success, () -> { });
    }

    private void runLibrary(Callable<LibraryClient.Snapshot> operation, String success,
                            Runnable afterApply) {
        libraryStatus.setText("正在更新个人曲库…");
        libraryTasks.submit(() -> {
            try {
                var snapshot = operation.call();
                VersionCatalog catalog;
                try { catalog = versionClient.load(); }
                catch (Exception unavailable) { catalog = new VersionCatalog(List.of()); }
                VersionCatalog refreshed = catalog;
                Platform.runLater(() -> {
                    if (closing) return;
                    Track newlySaved = "已收藏".equals(success) ? snapshot.favorites().stream()
                            .filter(track -> favorites.stream().noneMatch(old -> old.id().equals(track.id())))
                            .findFirst().orElse(null) : null;
                    versionCatalog = refreshed;
                    applyLibrary(snapshot);
                    afterApply.run();
                    if (newlySaved != null) notifyPetMusicEvent(PetCompanion.Event.NEW_FAVORITE, newlySaved.title());
                    if (diaryTab.isSelected()) diaryPane.refresh();
                    libraryStatus.setText(success);
                });
            } catch (Exception error) {
                String detail = error instanceof java.net.ConnectException
                        ? "曲库服务未连接；本地播放仍可用"
                        : error.getMessage() == null || error.getMessage().isBlank()
                                ? error.getClass().getSimpleName() : error.getMessage();
                Platform.runLater(() -> { recordDiagnostic("LIBRARY_ERROR"); libraryStatus.setText("个人曲库操作失败：" + detail); });
            }
        });
    }

    private void applyLibrary(LibraryClient.Snapshot snapshot) {
        String selectedId = playlistPicker.getValue() == null ? null : playlistPicker.getValue().id();
        ListView<Track> favoriteView = trackViews.stream().filter(view -> view.getItems() == favorites)
                .findFirst().orElse(null);
        ListBrowseState favoriteBrowse = favoriteView == null ? null : ListBrowseState.capture(favoriteView, 0);
        if (!favorites.equals(snapshot.favorites())) favorites.setAll(snapshot.favorites());
        playlists.setAll(snapshot.playlists());
        LibraryClient.Playlist selected = playlists.stream()
                .filter(item -> item.id().equals(selectedId)).findFirst()
                .orElse(playlists.isEmpty() ? null : playlists.get(0));
        playlistPicker.setValue(selected);
        playlistOrderList.getSelectionModel().select(selected);
        showPlaylistTracks();
        if (favoriteBrowse != null) favoriteBrowse.restore(favoriteView, () -> !closing);
        refreshFavoriteButton();
        refreshTrackViews();
        if (smartDialog != null && smartDialog.isShowing()) smartDialog.refresh();
        refreshPersonalHome();
    }

    private void showPlaylistTracks() {
        if (playlistTrackView != null && displayedPlaylistId != null)
            playlistBrowseStates.put(displayedPlaylistId, ListBrowseState.capture(playlistTrackView,
                    playlistPageScroll == null ? 0 : playlistPageScroll.getVvalue()));
        LibraryClient.Playlist selected = playlistPicker.getValue();
        String nextId = selected == null ? null : selected.id();
        List<Track> nextTracks = selected == null ? List.of() : selected.tracks();
        boolean samePage = java.util.Objects.equals(displayedPlaylistId, nextId);
        if (!samePage) retryPlaylistResources(nextTracks);
        displayedPlaylistId = nextId;
        if (samePage && playlistTracks.equals(nextTracks)) return;
        playlistTracks.setAll(nextTracks);
        if (playlistTrackView == null) return;
        ListBrowseState saved = playlistBrowseStates.get(nextId);
        if (saved != null) {
            saved.restore(playlistTrackView, () -> java.util.Objects.equals(displayedPlaylistId, nextId));
            if (playlistPageScroll != null) playlistPageScroll.setVvalue(saved.pagePosition);
        } else {
            playlistTrackView.getSelectionModel().clearSelection();
            playlistTrackView.scrollTo(0);
            if (playlistPageScroll != null) playlistPageScroll.setVvalue(0);
        }
    }

    private void refreshFavoriteButton() {
        if (diaryPane != null) diaryPane.refreshTrackActions();
        Track current = queue.current();
        favoriteButton.setDisable(current == null);
        boolean saved = current != null
                && favorites.stream().anyMatch(item -> item.id().equals(current.id()));
        favoriteButton.setText(saved ? "取消收藏" : "收藏当前");
        favoriteButton.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("active"), saved);
        if (taskbarControls != null) taskbarControls.setFavorite(saved);
        if (miniTaskbarControls != null) miniTaskbarControls.setFavorite(saved);
        for (var entry : managedFavoriteButtons.entrySet()) {
            Track selected = entry.getValue().get();
            boolean selectedSaved = selected != null
                    && favorites.stream().anyMatch(item -> item.id().equals(selected.id()));
            entry.getKey().setDisable(selected == null);
            entry.getKey().setText(selectedSaved ? "♥ 已收藏" : "♡ 收藏");
            entry.getKey().pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("active"), selectedSaved);
        }
    }

    /** Locate matches without filtering rows or changing the playback queue. */
    private static void installTrackLocator(TextField input, Label count, ListView<Track> view,
            javafx.collections.ObservableList<Track> tracks) {
        TrackLocator locator = new TrackLocator();
        Runnable refresh = () -> {
            locator.setItems(List.copyOf(tracks));
            locator.setQuery(input.getText());
            boolean searching = !input.getText().isBlank();
            count.setVisible(searching);
            count.setManaged(searching);
            count.setText(locator.matches().isEmpty() ? "无匹配" : "找到 " + locator.matches().size() + " 首");
            if (searching) {
                view.getSelectionModel().clearSelection();
                int index = locator.currentIndex();
                if (index >= 0) {
                    view.scrollTo(index);
                    view.getSelectionModel().clearAndSelect(index);
                }
            }
            input.setDisable(tracks.isEmpty());
        };
        input.textProperty().addListener((ignored, before, after) -> refresh.run());
        tracks.addListener((javafx.collections.ListChangeListener<Track>) change -> refresh.run());
        input.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && !locator.matches().isEmpty()) {
                int index = locator.nextIndex();
                view.scrollTo(index);
                view.getSelectionModel().clearAndSelect(index);
                count.setText("找到 " + locator.matches().size() + " 首 (" + (locator.cursor() + 1)
                        + "/" + locator.matches().size() + ")");
                event.consume();
            }
        });
        refresh.run();
    }

    private static TitledPane settingsSection(String title, String id, javafx.scene.Node content) {
        return settingsSection(title, id, content, (Label) null);
    }

    private static TitledPane settingsSection(String title, String id, javafx.scene.Node content, String summaryText) {
        return settingsSection(title, id, content, summaryText == null ? null : new Label(summaryText));
    }

    private static TitledPane settingsSection(String title, String id, javafx.scene.Node content, Label summaryBadge) {
        TitledPane section = new TitledPane(title, content);
        section.setId(id);
        section.setExpanded(false);
        section.setAnimated(false);
        section.getStyleClass().add("settings-section");
        if (summaryBadge != null) {
            summaryBadge.getStyleClass().add("settings-summary-badge");
            summaryBadge.setMinWidth(0);
            summaryBadge.setMaxWidth(220);
            summaryBadge.setTextOverrun(OverrunStyle.ELLIPSIS);
            summaryBadge.visibleProperty().bind(section.expandedProperty().not());
            summaryBadge.managedProperty().bind(summaryBadge.visibleProperty());
            section.setGraphic(summaryBadge);
            section.setContentDisplay(ContentDisplay.RIGHT);
        }
        return section;
    }

    private VBox createHotkeySettings() {
        hotkeySettingsStore = new HotkeySettingsStore(dataDirectory().resolve("hotkeys.properties"));
        try { hotkeyBindings.putAll(hotkeySettingsStore.load()); }
        catch (IOException error) {
            hotkeyBindings.putAll(GlobalHotkeys.defaults());
            status.setText("快捷键读取失败：" + error.getMessage());
        }
        VBox content = new VBox(8);
        java.util.Map<GlobalHotkeys.Action, Button> recordButtons =
                new java.util.EnumMap<>(GlobalHotkeys.Action.class);
        Label feedback = new Label();
        feedback.setId("hotkey-settings-status");
        hotkeyStatus = feedback;
        feedback.setWrapText(true);
        for (GlobalHotkeys.Action action : GlobalHotkeys.Action.values()) {
            Label name = new Label(switch (action) {
                case PREVIOUS -> "上一首";
                case TOGGLE -> "播放 / 暂停";
                case NEXT -> "下一首";
                case VOLUME_UP -> "音量增大";
                case VOLUME_DOWN -> "音量减小";
            });
            Button record = new Button(hotkeyText(action));
            record.setId("hotkey-record-" + action.name().toLowerCase(java.util.Locale.ROOT));
            record.setMaxWidth(Double.MAX_VALUE);
            Tooltip recordTooltip = new Tooltip();
            recordTooltip.textProperty().bind(record.textProperty());
            record.setTooltip(recordTooltip);
            recordButtons.put(action, record);
            Button clear = new Button("清除");
            decorateIcon(clear, "close", "action-icon");
            clear.setTooltip(new Tooltip("清除此快捷键"));
            clear.setOnAction(event -> {
                hotkeyBindings.remove(action);
                record.setText(hotkeyText(action));
                applyHotkeys(feedback);
            });
            record.setOnAction(event -> {
                recordingHotkey = true;
                globalHotkeys.apply(java.util.Map.of());
                record.setText("按下组合键…");
                record.requestFocus();
                feedback.setText("");
                record.setOnKeyPressed(key -> {
                    key.consume();
                    if (key.getCode() == KeyCode.ESCAPE) {
                        record.setOnKeyPressed(null);
                        recordingHotkey = false;
                        record.setText(hotkeyText(action));
                        applyHotkeys(feedback);
                        return;
                    }
                    var captured = GlobalHotkeys.fromKeyEvent(key);
                    if (captured.isEmpty() && !key.getCode().isModifierKey())
                        feedback.setText("请选择带 Ctrl / Alt / Shift 的组合键或功能键");
                    captured.ifPresent(combination -> {
                        hotkeyBindings.put(action, combination);
                        record.setText(combination.displayName());
                        record.setOnKeyPressed(null);
                        recordingHotkey = false;
                        applyHotkeys(feedback);
                    });
                });
            });
            record.focusedProperty().addListener((ignored, before, focused) -> {
                if (!focused && record.getOnKeyPressed() != null) {
                    record.setOnKeyPressed(null);
                    recordingHotkey = false;
                    record.setText(hotkeyText(action));
                    applyHotkeys(feedback);
                }
            });
            HBox row = new HBox(5, record, clear);
            HBox.setHgrow(record, Priority.ALWAYS);
            content.getChildren().add(new VBox(3, name, row));
        }
        Button defaults = actionButton("恢复默认", "hotkey-reset", () -> {
            hotkeyBindings.clear();
            hotkeyBindings.putAll(GlobalHotkeys.defaults());
            recordButtons.forEach((action, button) -> button.setText(hotkeyText(action)));
            applyHotkeys(feedback);
        });
        content.getChildren().addAll(defaults, feedback);
        return content;
    }

    private String hotkeyText(GlobalHotkeys.Action action) {
        GlobalHotkeys.Combination value = hotkeyBindings.get(action);
        return value == null ? "未设置" : value.displayName();
    }

    private void applyHotkeys(Label feedback) {
        if (globalHotkeys == null) return;
        try { hotkeySettingsStore.save(hotkeyBindings); }
        catch (IOException error) {
            if (feedback != null) feedback.setText("保存失败：" + error.getMessage());
            return;
        }
        globalHotkeys.apply(java.util.Map.copyOf(hotkeyBindings)).whenComplete((failures, error) ->
                Platform.runLater(() -> {
                    if (feedback == null) {
                        if (error != null) status.setText("快捷键注册失败：" + message(error));
                        else if (!failures.isEmpty()) status.setText("部分快捷键被占用，请在设置中更换组合键");
                    } else feedback.setText(error != null ? "注册失败：" + message(error)
                            : failures.isEmpty() ? "已保存" : String.join("；", failures.values()));
                }));
    }

    private void applyAudioPreferences() {
        for (AudioPlayer player : new AudioPlayer[] {localAudio, streamingAudio})
            if (player != null) player.setNormalizationEnabled(normalizationEnabled);
        if (audio != null) normalizationStatus.setText(audio.normalizationStatus());
        if (normalizationEnabled && streamingAudio != null && audio == localAudio && queue.current() != null
                && audio.state() != JavaFxAudioPlayer.State.IDLE) {
            boolean playing = audio.state() == JavaFxAudioPlayer.State.PLAYING;
            double position = seconds(playbackPosition());
            loadCurrent(playing, position);
        }
    }

    private void toggleTrackFavorite(Track track) {
        if (track == null) { libraryStatus.setText("请先选择曲目"); return; }
        if (favorites.stream().anyMatch(item -> item.id().equals(track.id())))
            runLibrary(() -> libraryClient.unfavorite(track.id()), "已取消收藏",
                    () -> pet.onFavoriteChanged(false));
        else runLibrary(() -> libraryClient.favorite(track), "已收藏");
    }

    private Button favoriteSelectionButton(String id, ListView<Track> list) {
        Button button = new Button("♡ 收藏");
        button.setId(id);
        button.setDisable(true);
        button.setOnAction(event -> toggleTrackFavorite(list.getSelectionModel().getSelectedItem()));
        managedFavoriteButtons.put(button, () -> list.getSelectionModel().getSelectedItem());
        list.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) ->
                refreshFavoriteButton());
        return button;
    }

    private MenuButton playlistSelectionMenu(String id, Supplier<Track> selectedTrack) {
        MenuButton button = new MenuButton("加入歌单");
        button.setId(id);
        button.getStyleClass().add("playlist-selection-menu");
        button.setOnShowing(event -> {
            button.getItems().clear();
            Track track = selectedTrack.get();
            if (track == null || playlists.isEmpty()) {
                MenuItem empty = new MenuItem(track == null ? "请先选择曲目" : "请先新建歌单");
                empty.setDisable(true);
                button.getItems().add(empty);
                themePlaylistPopup(button);
                return;
            }
            for (LibraryClient.Playlist playlist : playlists) {
                MenuItem item = new MenuItem(playlist.name());
                item.setOnAction(action -> runLibrary(
                        () -> libraryClient.addToPlaylist(playlist.id(), track),
                        "已加入歌单“" + playlist.name() + "”"));
                button.getItems().add(item);
            }
            themePlaylistPopup(button);
        });
        button.setOnShown(event -> themePlaylistPopup(button));
        return button;
    }

    private void themePlaylistPopup(MenuButton button) {
        if (button.getItems().isEmpty()) return;
        themePopup(button.getItems().get(0).getParentPopup());
    }

    private void themePopup(ContextMenu popup) {
        if (popup == null) return;
        var popupRoot = popup.getScene().getRoot();
        popupRoot.getStyleClass().removeAll("dark-theme", "light-theme", "background-theme",
                "dynamic-theme", "lumen-theme");
        popup.getStyleClass().removeAll("dark-theme", "light-theme", "background-theme",
                "dynamic-theme", "lumen-theme");
        String themeClass = switch (themeMode) {
            case DARK -> "dark-theme";
            case LIGHT -> "light-theme";
            case BACKGROUND -> "background-theme";
            case DYNAMIC -> "dynamic-theme";
            case LUMEN -> "lumen-theme";
        };
        popupRoot.getStyleClass().add(themeClass);
        popup.getStyleClass().add(themeClass);
        if (popup.getSkin() != null) {
            var content = popup.getSkin().getNode();
            content.getStyleClass().removeAll("dark-theme", "light-theme", "background-theme",
                    "dynamic-theme", "lumen-theme");
            content.getStyleClass().add(themeClass);
        }
        String stylesheet = getClass().getResource("/lumen-theme.css").toExternalForm();
        if (!popup.getScene().getStylesheets().contains(stylesheet))
            popup.getScene().getStylesheets().add(stylesheet);
        if (themeController != null) popupRoot.setStyle(themeController.paletteStyle());
        popupRoot.applyCss();
    }

    private void themeOpenTextMenus() {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (!(window instanceof javafx.stage.PopupWindow) || window.getScene() == null) continue;
            if (window instanceof Tooltip tooltip) {
                FriendlyTooltips.refreshTheme(tooltip);
                continue;
            }
            if (window instanceof ContextMenu menu) {
                themePopup(menu);
                continue;
            }
            var root = window.getScene().getRoot();
            if (root.lookup("#desktop-pet-current-lyric") != null
                    || root.lookup("#desktop-pet-event-notice") != null) continue;
            root.getStyleClass().removeAll("dark-theme", "light-theme", "background-theme", "dynamic-theme", "lumen-theme");
            root.getStyleClass().add(themeMode.name().toLowerCase(java.util.Locale.ROOT) + "-theme");
            if (themeController != null) root.setStyle(themeController.paletteStyle());
            root.applyCss();
        }
    }

    private Button playNextSelectionButton(String id, ListView<Track> list) {
        Button button = new Button("下一首播放");
        button.setId(id);
        button.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener((ignored, before, selected) ->
                button.setDisable(selected == null));
        button.setOnAction(event -> {
            Track track = list.getSelectionModel().getSelectedItem();
            playNextTrack(track);
        });
        return button;
    }

    private void playNextTrack(Track track) {
        if (track == null) return;
        arrangeTracks(List.of(track), true, false);
    }

    private static List<Track> selectedTracks(ListView<Track> list, int row) {
        if (list.getSelectionModel().isSelected(row))
            return list.getSelectionModel().getSelectedIndices().stream().sorted()
                    .map(index -> list.getItems().get(index)).toList();
        return row < 0 || row >= list.getItems().size() ? List.of() : List.of(list.getItems().get(row));
    }

    private void arrangeTracks(List<Track> selected, boolean next, boolean exactResources) {
        if (selected.isEmpty()) return;
        List<Track> tracks = exactResources ? List.copyOf(selected)
                : selected.stream().map(versionCatalog::preferredTrack).toList();
        boolean empty = queue.current() == null;
        editQueue(() -> {
            if (empty) queue.enqueue(tracks);
            else if (next) {
                for (int i = tracks.size() - 1; i >= 0; i--) queue.playNext(tracks.get(i));
            } else queue.enqueue(tracks);
        }, next ? "已安排下一首 · " + tracks.size() + " 首" : "已添加到队尾 · " + tracks.size() + " 首");
        if (empty && queue.current() != null) loadCurrent(true);
    }

    private record QueueEdit(PlayerQueue.Snapshot before, PlayerQueue.Snapshot after,
                             double position, boolean playing, boolean changedAudio) { }

    private void editQueue(Runnable operation, String success) {
        var before = queue.snapshot();
        if (!queueEdits.isEmpty() && !queueEdits.peekLast().after().equals(before)) queueEdits.clear();
        double position = restoredPositionSeconds == null ? seconds(playbackPosition()) : restoredPositionSeconds;
        boolean playing = audio != null && audio.state() == JavaFxAudioPlayer.State.PLAYING;
        long previousLoad = loadVersion;
        operation.run();
        var after = queue.snapshot();
        if (!before.equals(after)) {
            queueEdits.addLast(new QueueEdit(before, after, position, playing, previousLoad != loadVersion));
            while (queueEdits.size() > 20) queueEdits.removeFirst();
        }
        queued.setAll(queue.tracks());
        if (queueUndoButton != null) queueUndoButton.setDisable(queueEdits.isEmpty());
        refreshNextPreparation();
        status.setText(success);
    }

    private void removeQueueTracks(List<Integer> indices) {
        editQueue(() -> {
            if (queue.removeIndices(indices)) {
                if (queue.current() == null) { stopRadio(); stopEmptyQueue(); }
                else loadCurrent(audio.state() == JavaFxAudioPlayer.State.PLAYING);
            }
        }, "已从队列移除 · " + indices.size() + " 首");
    }

    private void clearQueue() {
        if (queue.tracks().isEmpty()) return;
        editQueue(() -> {
            stopRadio();
            queue.clear();
            stopEmptyQueue();
        }, "播放队列已清空，可撤销恢复");
    }

    private void undoQueueEdit() {
        if (queueEdits.isEmpty()) { status.setText("没有可撤销的队列操作"); return; }
        QueueEdit edit = queueEdits.peekLast();
        if (!queue.snapshot().equals(edit.after())) {
            queueEdits.clear();
            queueUndoButton.setDisable(true);
            status.setText("播放安排已变化，之前的队列操作不能撤销");
            return;
        }
        Track current = queue.current();
        queueEdits.removeLast();
        queue.restore(edit.before());
        queued.setAll(queue.tracks());
        updateQueuePreferences();
        if (edit.changedAudio() || !java.util.Objects.equals(current, queue.current())) {
            if (queue.current() == null) stopEmptyQueue();
            else loadCurrent(edit.playing(), edit.position());
        }
        queueUndoButton.setDisable(queueEdits.isEmpty());
        refreshNextPreparation();
        status.setText("队列操作已撤销");
    }

    private void stopEmptyQueue() {
        finishListening();
        ++loadVersion;
        cancelPendingOnline();
        if (nextPreloader != null) nextPreloader.cancel();
        preparedToRetain = null;
        trackToCache = null;
        pendingResumeSeconds = null;
        restoredPositionSeconds = null;
        loadedTrack = null;
        clearPracticeLoop();
        lyricMediaVersion = -1;
        segmentFinished = false;
        audio.stop();
        activeLyrics = List.of();
        lyricsView.getItems().clear();
        immersiveLyrics.getItems().clear();
        currentLyric.setText("♪");
        nowPlaying.setText("未选择曲目");
        immersiveTrackTitle.setText("还没有播放歌曲");
        immersiveTrackArtist.setText("");
        immersiveTrackSource.setText("");
        immersiveSourceButton.setDisable(true);
        cover.setImage(null);
        coverPlaceholder.setVisible(true);
        failureNotice.setText("");
        progress.setValue(0);
        timing.setText("00:00 / 00:00");
        playButton.setDisable(true);
        refreshFavoriteButton();
        refreshTrackViews();
    }

    private void removePlaylistTracks(LibraryClient.Playlist playlist, List<Track> tracks) {
        if (playlistEditing || tracks.isEmpty()) return;
        playlistEditing = true;
        libraryStatus.setText("正在移出歌单…");
        libraryTasks.submit(() -> {
            try {
                var result = libraryClient.removeTracksFromPlaylist(playlist.id(), tracks.stream().map(Track::id).toList());
                Platform.runLater(() -> {
                    playlistEditing = false;
                    applyLibrary(result.snapshot());
                    playlistUndoId = playlist.id();
                    playlistUndoToken = result.undoToken();
                    playlistUndoButton.setDisable(playlistUndoToken == null || playlistUndoToken.isBlank());
                    libraryStatus.setText("已从“" + playlist.name() + "”移除 " + tracks.size() + " 首，可撤销");
                });
            } catch (Exception error) {
                Platform.runLater(() -> { playlistEditing = false; libraryStatus.setText("移除失败：" + message(error)); });
            }
        });
    }

    private void undoPlaylistRemoval() {
        if (playlistEditing || playlistUndoToken == null) return;
        String id = playlistUndoId, token = playlistUndoToken;
        playlistEditing = true;
        libraryStatus.setText("正在撤销移除…");
        libraryTasks.submit(() -> {
            try {
                var snapshot = libraryClient.undoPlaylistRemoval(id, token);
                Platform.runLater(() -> {
                    playlistEditing = false;
                    playlistUndoToken = null;
                    playlistUndoButton.setDisable(true);
                    applyLibrary(snapshot);
                    libraryStatus.setText("歌单移除已撤销，原顺序已恢复");
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    playlistEditing = false;
                    libraryStatus.setText("撤销失败：" + message(error));
                });
            }
        });
    }

    private boolean isEditingInFocusedWindow() {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows())
            if (window.isFocused() && window.getScene() != null
                    && KeyboardInputGuard.isTextEditing(window.getScene().getFocusOwner())) return true;
        return false;
    }

    private void loadCurrent(boolean autoplay) { loadCurrent(autoplay, null); }

    private void loadCurrent(boolean autoplay, Double resumeAt) {
        if (autoplay) sleepPaused = false;
        if (queueUndoButton != null) queueUndoButton.setDisable(queueEdits.isEmpty()
                || !queueEdits.peekLast().after().equals(queue.snapshot()));
        radioContinuationPending = false;
        collectionVersion++;
        seekVersion++;
        pendingLocalSeek = null;
        restoredPositionSeconds = null;
        pendingResumeSeconds = resumeAt;
        Track track = queue.current();
        if (track == null) return;
        URI prepared = track.mediaUri() == null && "bilibili".equals(track.source()) && preloadEnabled
                ? nextPreloader.take(track, streamingAudio != null) : null;
        nextPreloader.cancel();
        if (loadedTrack == null || !loadedTrack.id().equals(track.id())) clearPracticeLoop();
        if (!openingRetry) {
            openingRetries = 0;
            currentPlaybackStarted = false;
        }
        openingRetry = false;
        currentAutoplay = autoplay;
        onlineStreamOpening = false;
        preparedToRetain = null;
        trackToCache = null;
        fadeInPending = shortFadeEnabled && autoplay && !practiceLoop.enabled();
        playbackEnvelope.start(false, System.nanoTime());
        failureNotice.setText(retryingPlayback ? "正在重试：" + track.title() : "");
        retryingPlayback = false;
        if (pet != null) pet.onTrackChanged(track.title());
        finishListening();
        loadedNeutral = radio.active() && radio.neutral();
        if (radio.active() && segmentPreview == null) {
            radio.recordPlayed(track);
            syncRadioPreview();
        }
        loadedTrack = track;
        segmentFinished = false;
        segmentSeeking = track.isSegment();
        segmentSeekStartedNanos = System.nanoTime();
        lastSegmentSeekNanos = 0;
        if (track.isSegment() && pendingResumeSeconds == null) pendingResumeSeconds = 0.0;
        refreshFavoriteButton();
        refreshTrackViews();
        long version = ++loadVersion;
        lyricMediaVersion = -1;
        cancelPendingOnline();
        showCover(track, version);
        loadLyrics(track, version);
        nowPlaying.setText("准备播放：" + track);
        immersiveTrackTitle.setText(songTitle(track));
        immersiveTrackArtist.setText(performerCaption(track));
        immersiveTrackSource.setText(sourceCaption(track));
        if (immersiveSourceButton != null) immersiveSourceButton.setDisable(track.bvid() == null);
        URI mediaUri = track.mediaUri();
        if (mediaUri == null && "demo".equals(track.source())) {
            try { mediaUri = demoAudio.resolve(track.audioSource().id()); }
            catch (Exception error) {
                audio.stop();
                playButton.setDisable(true);
                status.setText("演示音频生成失败：" + error.getMessage());
                return;
            }
        }
        if (mediaUri == null && "bilibili".equals(track.source())) {
            audio.stop();
            playButton.setDisable(true);
            status.setText("正在连接在线音频：" + track.title());
            if (prepared != null) {
                playOnlineUri(track, prepared, autoplay, version, "file".equalsIgnoreCase(prepared.getScheme()), true, null);
                return;
            }
            var cancellation = new MusicClient.DownloadCancellation();
            pendingCancellation = cancellation;
            MusicClient.PreparationListener feedback = (phase, attempt) -> Platform.runLater(() -> {
                if (closing || version != loadVersion || cancellation != pendingCancellation) return;
                String label = switch (phase) {
                    case CHECKING -> "正在连接音频源";
                    case DOWNLOADING -> "正在准备完整音频";
                    case RETRYING -> "连接暂时中断，正在重试（" + attempt + "/2）";
                };
                status.setText(label + " · " + track.title());
                if (phase == MusicClient.PreparationStage.RETRYING) failureNotice.setText(label + " · " + track.title());
            });
            pendingOnline = onlineDownloads.submit(() -> {
                try {
                    URI cached = null;
                    String cacheIssue = null;
                    if (persistentCache != null) {
                        try { cached = persistentCache.lookup(track).orElse(null); }
                        catch (IOException error) { cacheIssue = "缓存读取失败，改为在线播放：" + error.getMessage(); }
                    }
                    URI uri = cached != null && streamingAudio != null ? cached : streamingAudio == null
                            ? onlineAudio.resolve(track, cancellation, feedback) : client.checkStream(track, cancellation, feedback);
                    boolean cacheHit = cached != null;
                    String cacheWarning = cacheIssue;
                    Platform.runLater(() -> {
                        if (version != loadVersion) return;
                        playOnlineUri(track, uri, autoplay, version, cacheHit, false, cacheWarning);
                    });
                } catch (Exception error) {
                    Platform.runLater(() -> {
                        if (version != loadVersion) return;
                        nowPlaying.setText("已选中：" + track);
                        showPlaybackFailure(track, PlaybackFailure.from(error));
                        playButton.setDisable(false);
                    });
                }
            });
            return;
        }
        if (mediaUri == null) {
            audio.stop();
            nowPlaying.setText("已选中：" + track);
            showPlaybackFailure(track, PlaybackFailure.forCode("AUDIO_UNAVAILABLE"));
            playButton.setDisable(true);
            return;
        }
        if ("file".equalsIgnoreCase(mediaUri.getScheme()) && !Files.isRegularFile(Path.of(mediaUri))) {
            audio.stop();
            showPlaybackFailure(track, PlaybackFailure.forCode("LOCAL_FILE_MISSING"));
            playButton.setDisable(false);
            return;
        }
        status.setText("正在加载" + ("demo".equals(track.source()) ? "演示合成音频" : "音频")
                + "：" + track.title());
        playButton.setDisable(false);
        String fileName = mediaUri.getPath() == null ? "" : mediaUri.getPath().toLowerCase(java.util.Locale.ROOT);
        boolean needsVlc = fileName.endsWith(".flac") || fileName.endsWith(".ogg") || fileName.endsWith(".opus");
        if (needsVlc && streamingAudio == null && vlcReady != null && !vlcReady.isDone()) {
            status.setText("音频引擎准备中…界面可以继续操作");
            vlcReady.thenAccept(ready -> Platform.runLater(() -> {
                if (!closing && version == loadVersion) {
                    if (ready) loadCurrent(autoplay, resumeAt);
                    else showPlaybackFailure(track, PlaybackFailure.forCode("AUDIO_UNAVAILABLE"));
                }
            }));
            return;
        }
        switchAudio((normalizationEnabled || needsVlc) && streamingAudio != null ? streamingAudio : localAudio);
        loadAudioRange(mediaUri, autoplay);
        lyricMediaVersion = version;
    }

    private void playOnlineUri(Track track, URI uri, boolean autoplay, long version,
                               boolean cacheHit, boolean prepared, String warning) {
        if (closing || version != loadVersion) return;
        boolean completeFile = "file".equalsIgnoreCase(uri.getScheme());
        if (prepared && completeFile) preparedToRetain = uri;
        else if (!cacheHit && streamingAudio != null) trackToCache = track;
        switchAudio(streamingAudio != null ? streamingAudio : localAudio);
        onlineStreamOpening = !completeFile && streamingAudio != null;
        loadAudioRange(uri, autoplay);
        lyricMediaVersion = version;
        if (prepared) status.setText("已使用提前准备的音频");
        else if (cacheHit) status.setText("正在播放缓存音频");
        else if (warning != null) status.setText(warning);
    }

    private void refreshNextPreparation() {
        if (nextPreloader == null) return;
        if (closing || !preloadEnabled || segmentPreview != null || practiceLoop.enabled() || sleepPaused
                || loadedTrack == null || !loadedTrack.equals(queue.current()) || audio == null
                || (audio.state() != JavaFxAudioPlayer.State.PLAYING
                    && audio.state() != JavaFxAudioPlayer.State.PAUSED)) {
            nextPreloader.cancel();
            return;
        }
        boolean repeatCurrent = queue.repeatMode() == PlayerQueue.RepeatMode.ONE
                && (trackPreferences == null || !trackPreferences.isExcluded(queue.current().id()));
        Track next = radio.active()
                ? (repeatCurrent ? queue.current() : queue.peekWithoutWrap())
                : queue.peekAfterEnd();
        if (next == null && radio.active() && radio.autoContinue()) {
            next = radio.preview().stream().map(RadioClient.Item::track)
                    .filter(track -> trackPreferences == null || !trackPreferences.isExcluded(track.id()))
                    .findFirst().orElse(null);
        }
        if (next == null || next.mediaUri() != null || !"bilibili".equals(next.source())
                || next.audioSource().id().equals(loadedTrack.audioSource().id())) nextPreloader.cancel();
        else nextPreloader.request(next, streamingAudio != null);
    }

    private void updateFadeClock() {
        if (volumeClock == null) return;
        if (shortFadeEnabled) volumeClock.play();
        else volumeClock.stop();
    }

    private void applyOutputVolume() {
        if (audio == null) return;
        if (audio.state() != JavaFxAudioPlayer.State.PLAYING) playbackEnvelope.resetPositionClock();
        double gain = 1;
        if (segmentSeeking || segmentFinished || (shortFadeEnabled && fadeInPending)) gain = 0;
        else if (shortFadeEnabled && audio.state() == JavaFxAudioPlayer.State.PLAYING) {
            Duration total = playbackDuration();
            double duration = total.isUnknown() || total.isIndefinite() ? Double.NaN : total.toMillis();
            gain = playbackEnvelope.gainAtPosition(System.nanoTime(), playbackPosition().toMillis(), duration,
                    playbackRate, !practiceLoop.enabled() && segmentPreview == null);
        }
        if (Math.abs(outputVolume - playbackVolume) > .001 || Math.abs(outputEnvelope - gain) > .001) {
            outputVolume = playbackVolume;
            outputEnvelope = gain;
            audio.setOutputGain(playbackVolume, gain);
        }
    }

    private void switchAudio(AudioPlayer replacement) {
        if (audio == replacement) return;
        audio.stop();
        audio = replacement;
        outputVolume = -1;
        outputEnvelope = -1;
        applyOutputVolume();
        audio.setNormalizationEnabled(normalizationEnabled);
        audio.setPlaybackRate(playbackRate);
    }

    private void loadAudioRange(URI uri, boolean autoplay) {
        autoplay = autoplay && !sleepPaused;
        Track track = loadedTrack;
        segmentSeekStartedNanos = System.nanoTime();
        lastSegmentSeekNanos = 0;
        outputVolume = -1;
        applyOutputVolume();
        audio.setPlaybackRate(playbackRate);
        if (track != null && track.isSegment())
            audio.loadRange(uri, autoplay, track.segmentStartMillis() / 1000.0, track.segmentEndMillis() / 1000.0);
        else audio.load(uri, autoplay);
    }

    private Duration playbackPosition() {
        return SegmentTimeline.position(loadedTrack == null ? queue.current() : loadedTrack, audio.position());
    }

    private Duration playbackDuration() {
        return SegmentTimeline.duration(loadedTrack == null ? queue.current() : loadedTrack, audio.duration());
    }

    private void cacheOnlineInBackground(Track track, long version) {
        var cancellation = new MusicClient.DownloadCancellation();
        pendingCancellation = cancellation;
        pendingOnline = onlineDownloads.submit(() -> {
            try { onlineAudio.resolve(track, cancellation); }
            catch (Exception error) {
                if (version == loadVersion && cancellation == pendingCancellation)
                    System.err.println("音频缓存未完成：" + error.getMessage());
            }
        });
    }

    private void retainPreparedInBackground(Track track, URI uri, long version) {
        if (track == null || persistentCache == null || !persistentCache.enabled()) return;
        var cancellation = new MusicClient.DownloadCancellation();
        pendingCancellation = cancellation;
        pendingOnline = onlineDownloads.submit(() -> {
            try { onlineAudio.retainPrepared(track, uri, cancellation); }
            catch (Exception error) {
                if (version == loadVersion && cancellation == pendingCancellation)
                    System.err.println("音频缓存未完成：" + error.getMessage());
            }
        });
    }

    private void cancelPendingOnline() {
        if (pendingOnline != null) pendingOnline.cancel(true);
        if (pendingCancellation != null) pendingCancellation.cancel();
        pendingOnline = null;
        pendingCancellation = null;
    }

    private void onPlaybackState(JavaFxAudioPlayer.State state) {
        if (closing) return;
        playbackUiState.set(state);
        if (state == JavaFxAudioPlayer.State.PLAYING && !segmentSeeking
                && (fadeInPending || previousPlaybackState == JavaFxAudioPlayer.State.PAUSED)) {
            fadeInPending = false;
            playbackEnvelope.start(shortFadeEnabled && !practiceLoop.enabled(), System.nanoTime());
        }
        previousPlaybackState = state;
        applyOutputVolume();
        if (audio.playbackRate() != playbackRate) {
            playbackRate = audio.playbackRate();
            refreshPracticeControls(false);
        }
        if (sleepPaused && state == JavaFxAudioPlayer.State.PLAYING) {
            audio.toggle();
            return;
        }
        if (segmentFinished && state == JavaFxAudioPlayer.State.PAUSED) return;
        if (state == JavaFxAudioPlayer.State.READY || state == JavaFxAudioPlayer.State.PLAYING) {
            failureNotice.setText("");
            Track current = queue.current();
            if (current != null && trackPreferences != null && trackPreferences.isUnavailable(current.id())) {
                try { trackPreferences.clearUnavailable(current.id()); refreshTrackViews(); }
                catch (IOException error) { status.setText("失效标记更新失败：" + error.getMessage()); }
            }
        }
        if (state == JavaFxAudioPlayer.State.READY || state == JavaFxAudioPlayer.State.PLAYING)
            applyPendingResume();
        if (state == JavaFxAudioPlayer.State.READY && audio == localAudio && pendingLocalSeek != null) {
            double target = pendingLocalSeek;
            pendingLocalSeek = null;
            seekToFraction(target);
        }
        if (taskbarControls != null) taskbarControls.setPlaying(state == JavaFxAudioPlayer.State.PLAYING);
        if (miniTaskbarControls != null) miniTaskbarControls.setPlaying(state == JavaFxAudioPlayer.State.PLAYING);
        if (pet != null && (state == JavaFxAudioPlayer.State.PLAYING
                || state == JavaFxAudioPlayer.State.PAUSED
                || state == JavaFxAudioPlayer.State.IDLE
                || state == JavaFxAudioPlayer.State.ENDED
                || state == JavaFxAudioPlayer.State.FAILED))
            pet.onPlaybackState(state == JavaFxAudioPlayer.State.PLAYING);
        playButton.setText(state == JavaFxAudioPlayer.State.PLAYING
                || state == JavaFxAudioPlayer.State.STALLED ? "暂停" : "播放");
        playButton.setDisable(state == JavaFxAudioPlayer.State.LOADING || queue.current() == null);
        if (state == JavaFxAudioPlayer.State.PLAYING)
        {
            currentPlaybackStarted = true;
            openingRetries = 0;
            if (preparedToRetain != null) {
                URI complete = preparedToRetain;
                preparedToRetain = null;
                retainPreparedInBackground(queue.current(), complete, loadVersion);
            } else if (trackToCache != null) {
                Track played = trackToCache;
                trackToCache = null;
                if (persistentCache != null && persistentCache.enabled()) cacheOnlineInBackground(played, loadVersion);
            }
            if (!listeningActive && !loadedNeutral && queue.current() != null && !segmentSeeking && segmentPreview == null) {
                var total = playbackDuration();
                int actualDuration = total.isUnknown() || total.isIndefinite() ? 0
                        : Math.max(1, (int) Math.round(total.toSeconds()));
                listeningActive = listening.start(queue.current(), actualDuration,
                        seconds(playbackPosition()), listeningContext(), playbackRate);
            } else if (listeningActive) listening.observe(seconds(playbackPosition()), true, false);
            if (queue.current() != null) nowPlaying.setText("正在播放：" + queue.current());
            status.setText(queue.current() != null && "demo".equals(queue.current().source())
                    ? "正在播放演示合成音频" : "正在播放");
        }
        else if (state == JavaFxAudioPlayer.State.PAUSED) {
            if (listeningActive) listening.observe(seconds(playbackPosition()), false, false);
            if (queue.current() != null) nowPlaying.setText("已暂停：" + queue.current());
            status.setText("已暂停");
        }
        else if (state == JavaFxAudioPlayer.State.ENDED) {
            completePlayback();
        }
        else if (state == JavaFxAudioPlayer.State.LOADING) status.setText("正在加载音频…");
        else if (state == JavaFxAudioPlayer.State.STALLED) {
            if (listeningActive) listening.observe(seconds(playbackPosition()), false, false);
            status.setText("网络缓冲中…");
        }
        else if (state == JavaFxAudioPlayer.State.FAILED) {
            finishListening();
            progress.setDisable(true);
        }
        if (state == JavaFxAudioPlayer.State.IDLE) {
            progress.setValue(0);
            progress.setDisable(true);
            timing.setText("00:00 / 00:00");
        }
    }

    private void onAudioError(String detail) {
        if (closing) return;
        if (detail != null && (detail.startsWith("VLC 当前音频不支持") || detail.startsWith("JavaFX 无法设置播放速度"))) {
            practiceMessage(detail);
            return;
        }
        String message = detail == null || detail.isBlank() ? "未能打开音频" : detail;
        boolean unsupported = message.toLowerCase(java.util.Locale.ROOT).contains("not supported")
                || message.toLowerCase(java.util.Locale.ROOT).contains("unsupported");
        Track track = queue.current();
        if (!unsupported && track != null && "bilibili".equals(track.source()) && onlineStreamOpening
                && audio == streamingAudio && !currentPlaybackStarted && openingRetries < 1) {
            ++openingRetries;
            long expected = loadVersion;
            failureNotice.setText("首次打开音频失败，正在重新准备（1/1） · " + track.title());
            PauseTransition retry = new PauseTransition(Duration.millis(400));
            retry.setOnFinished(event -> {
                if (closing || expected != loadVersion || queue.current() == null
                        || !queue.current().id().equals(track.id())) return;
                openingRetry = true;
                retryingPlayback = true;
                loadCurrent(currentAutoplay, pendingResumeSeconds);
            });
            retry.play();
            return;
        }
        showPlaybackFailure(queue.current(), new PlaybackFailure(unsupported ? "PLAYBACK_FORMAT_UNSUPPORTED" : "PLAYBACK_ERROR",
                unsupported ? "播放器不支持该音频格式" : "播放器无法打开音频：" + message, false, false));
    }

    private void showPlaybackFailure(Track track, PlaybackFailure failure) {
        recordDiagnostic(failure.code());
        String text = failure.displayMessage() + (track == null ? "" : " · " + track.title());
        failureNotice.setText(text);
        status.setText(failure.displayMessage());
        if (track != null && trackPreferences != null && failure.permanent()) {
            try { trackPreferences.markUnavailable(track.id(), failure.displayMessage()); refreshTrackViews(); }
            catch (IOException error) { failureNotice.setText(text + "；失效标记保存失败：" + error.getMessage()); }
        }
    }

    private void updateTime() {
        updateHomeSession();
        refreshNextPreparation();
        applyOutputVolume();
        long minute = System.currentTimeMillis() / 60_000;
        if (minute != lastNightMinute) {
            lastNightMinute = minute;
            int hour = java.time.LocalTime.now().getHour();
            if (hour >= 22 || hour < 7) {
                petEventTimer.stop();
                petEventNotice.setVisible(false);
            }
            pet.setNightMode(hour >= 22 || hour < 7);
        }
        updateLyrics();
        normalizationStatus.setText(audio.normalizationStatus());
        if (audio.state() == JavaFxAudioPlayer.State.IDLE
                || audio.state() == JavaFxAudioPlayer.State.LOADING
                || audio.state() == JavaFxAudioPlayer.State.FAILED) return;
        applyPendingResume();
        if (updatePracticeLoop()) return;
        Track currentTrack = loadedTrack;
        if (currentTrack != null && currentTrack.isSegment() && !segmentSeeking && !segmentFinished
                && audio.state() == JavaFxAudioPlayer.State.PLAYING
                && audio.position().toMillis() >= currentTrack.segmentEndMillis()) {
            audio.setVolume(0);
            outputVolume = 0;
            audio.toggle();
            completePlayback();
            return;
        }
        var total = playbackDuration();
        var current = playbackPosition();
        if (!listeningActive && !loadedNeutral && !segmentSeeking && !segmentFinished && segmentPreview == null
                && queue.current() != null && audio.state() == JavaFxAudioPlayer.State.PLAYING) {
            int knownDuration = total.isUnknown() || total.isIndefinite() || total.toMillis() <= 0
                    ? 0 : Math.max(1, (int) Math.ceil(total.toSeconds()));
            listeningActive = listening.start(queue.current(), knownDuration, seconds(current), listeningContext(), playbackRate);
        }
        if (listeningActive && audio.state() == JavaFxAudioPlayer.State.PLAYING
                && System.nanoTime() - lastListeningSampleNanos >= TimeUnit.SECONDS.toNanos(1)) {
            listening.observe(seconds(current), true, false);
            lastListeningSampleNanos = System.nanoTime();
        }
        if (total.isUnknown() || total.isIndefinite() || total.toMillis() <= 0) return;
        progress.setDisable(false);
        if (!progress.isValueChanging())
            progress.setValue(Math.min(1, current.toMillis() / total.toMillis()));
        timing.setText(format(current) + " / " + format(total));
    }

    private void seekToFraction(double fraction) {
        seekToFraction(fraction, false);
    }

    private void seekToFraction(double fraction, boolean loopSeek) {
        if (!Double.isFinite(fraction)) return;
        playbackEnvelope.resetPositionClock();
        var duration = playbackDuration();
        if (!loopSeek && practiceLoop.outside(Math.round(fraction * seconds(duration) * 1000))) {
            practiceLoop.setEnabled(false);
            practiceSeekTarget = null;
            refreshPracticeControls(false);
        }
        if (listeningActive) listening.observe(seconds(playbackPosition()),
                audio.state() == JavaFxAudioPlayer.State.PLAYING, false);
        audio.seekFraction(SegmentTimeline.sourceFraction(loadedTrack, fraction, audio.duration()));
        var total = playbackDuration();
        if (audio == streamingAudio && !total.isUnknown() && !total.isIndefinite()) {
            long expectedLoad = loadVersion;
            long expectedSeek = ++seekVersion;
            double targetSeconds = Math.max(0, Math.min(1, fraction)) * total.toSeconds();
            PauseTransition check = new PauseTransition(Duration.seconds(4));
            check.setOnFinished(event -> {
                if (expectedLoad != loadVersion || expectedSeek != seekVersion || audio != streamingAudio)
                    return;
                double actual = seconds(playbackPosition());
                if (Math.abs(actual - targetSeconds) > 12)
                    fallbackSeekToLocal(fraction, expectedLoad);
            });
            check.play();
        }
        if (listeningActive && !total.isUnknown() && !total.isIndefinite())
            listening.observe(Math.max(0, Math.min(1, fraction)) * total.toSeconds(),
                    audio.state() == JavaFxAudioPlayer.State.PLAYING, true);
    }

    private void fallbackSeekToLocal(double fraction, long version) {
        Track track = queue.current();
        if (track == null || !"bilibili".equals(track.source())) return;
        double targetSeconds = Math.max(0, Math.min(1, fraction)) * seconds(playbackDuration());
        // 暂停时刻意拖动不应该变成播放，回退到完整文件后沿用拖动前的播放状态。
        boolean resumePlaying = audio.state() == JavaFxAudioPlayer.State.PLAYING
                || audio.state() == JavaFxAudioPlayer.State.STALLED;
        finishListening();
        pendingResumeSeconds = targetSeconds;
        audio.stop();
        cancelPendingOnline();
        status.setText("该音轨需准备完整文件才能拖动，正在加载…");
        playButton.setDisable(true);
        var cancellation = new MusicClient.DownloadCancellation();
        pendingCancellation = cancellation;
        pendingOnline = onlineDownloads.submit(() -> {
            try {
                URI local = onlineAudio.resolve(track, cancellation);
                Platform.runLater(() -> {
                    if (closing || version != loadVersion) return;
                    switchAudio(localAudio);
                    pendingLocalSeek = fraction;
                    segmentSeeking = track.isSegment();
                    segmentSeekStartedNanos = System.nanoTime();
                    pendingResumeSeconds = targetSeconds;
                    loadAudioRange(local, resumePlaying);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closing && version == loadVersion) status.setText("音频进度恢复失败：" + error.getMessage());
                });
            }
        });
    }

    private static double seconds(Duration value) {
        return value.isUnknown() || value.isIndefinite() ? 0 : Math.max(0, value.toSeconds());
    }

    private void finishListening() {
        if (!listeningActive) return;
        listening.finish(seconds(playbackPosition()));
        listeningActive = false;
    }

    private ListeningReporter.Context listeningContext() {
        Track track = queue.current();
        return queueListeningContext != null && track != null && playlistQueueResources.contains(track.id())
                && queue.sourceAt(queue.index()) == PlayerQueue.Source.CURRENT_LIST ? queueListeningContext : null;
    }

    private void clearListeningContext() {
        queueListeningContext = null;
        playlistQueueResources = java.util.Set.of();
    }

    private void completePlayback() {
        if (segmentPreview != null && segmentPreview.version == loadVersion) {
            restoreSegmentPreview(segmentPreview);
            return;
        }
        if (practiceLoop.enabled()) {
            seekPracticeStart();
            if (audio.state() == JavaFxAudioPlayer.State.ENDED) audio.toggle();
            return;
        }
        if (endedLoadVersion == loadVersion) return;
        endedLoadVersion = loadVersion;
        segmentFinished = loadedTrack != null && loadedTrack.isSegment();
        if (listeningActive && segmentFinished) {
            listening.finish(seconds(playbackDuration()));
            listeningActive = false;
        } else finishListening();
        boolean repeatCurrent = queue.repeatMode() == PlayerQueue.RepeatMode.ONE && queue.current() != null
                && (trackPreferences == null || !trackPreferences.isExcluded(queue.current().id()));
        Track next = radio.active()
                ? (repeatCurrent ? queue.current() : queue.advanceWithoutWrap())
                : queue.advanceAfterEnd();
        if (next == null && radio.active()) {
            continueRadio();
            return;
        }
        if (next == null) {
            if (queue.current() != null) nowPlaying.setText("播放结束：" + queue.current());
            status.setText("播放队列已结束");
            playButton.setText("播放");
        } else loadCurrent(true);
    }

    private String format(Duration duration) {
        long seconds = Math.max(0, (long) duration.toSeconds());
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private void search(String query, String selection) {
        long version = ++searchVersion;
        long playbackAtSearch = loadVersion;
        boolean direct = isBvInput(query);
        String kind = direct ? "all" : switch (selection) {
            case "单曲" -> "single";
            case "合集" -> "collection";
            default -> "all";
        };
        searchStatus.setText(direct ? "正在解析 BV 视频…" : "正在搜索曲库…");
        CompletableFuture.supplyAsync(() -> {
            try { return client.searchDetailed(query, kind); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((found, error) -> Platform.runLater(() -> {
            if (version != searchVersion) return;
            if (error != null) {
                recordDiagnostic("SEARCH_ERROR");
                searchResults.clear();
                Throwable cause = error.getCause() == null ? error : error.getCause();
                searchStatus.setText("搜索失败：" + message(cause));
            }
            else {
                searchResults.setAll(found.tracks());
                String unresolved = found.unresolvedCount() > 0
                        ? "；另有 " + found.unresolvedCount() + " 条无法核实分 P" : "";
                searchStatus.setText(found.tracks().isEmpty()
                        ? (query.isBlank() ? "输入关键词开始搜索"
                                : "未找到已核实的曲目" + unresolved + "，请尝试其他关键词")
                        : direct ? "已解析 " + found.tracks().size() + " 个分 P"
                        : "找到 " + found.tracks().size() + " 首" + unresolved + "；搜索不会改变当前队列");
                if (direct && !found.tracks().isEmpty() && playbackAtSearch == loadVersion) {
                    int first = 0;
                    while (first < found.tracks().size() && trackPreferences != null
                            && trackPreferences.isExcluded(found.tracks().get(first).id())) first++;
                    if (first < found.tracks().size()) select(found.tracks(), first);
                    else {
                        finishListening();
                        ++loadVersion;
                        cancelPendingOnline();
                        queue.select(found.tracks(), 0);
                        updateQueuePreferences();
                        queued.setAll(queue.tracks());
                        audio.stop();
                        status.setText("该视频的分 P 均已标记为不想听");
                    }
                    showCollectionParts(found.tracks());
                }
            }
        }));
    }

    private static boolean isBvInput(String query) {
        String value = query.strip();
        return value.matches("BV[0-9A-Za-z]{10}")
                || value.matches("https?://(?:(?:www|m)\\.)?bilibili\\.com/video/BV[0-9A-Za-z]{10}(?:[/?#].*)?");
    }

    private void loadRecommendations(String hint) {
        if (closing || generateRecommendationsButton.isDisabled()) return;
        long version = ++recommendationVersion;
        generateRecommendationsButton.setText("生成中…");
        generateRecommendationsButton.setDisable(true);
        refreshRecommendationsButton.setDisable(true);
        recommendationStatus.setText("正在寻找音乐…");
        CompletableFuture.supplyAsync(() -> {
            try { return client.recommendations(hint); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (closing || version != recommendationVersion) return;
            generateRecommendationsButton.setText("生成推荐");
            generateRecommendationsButton.setDisable(false);
            refreshRecommendationsButton.setDisable(false);
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                recommendationStatus.setText("推荐暂不可用：" + message(cause));
                return;
            }
            showRecommendations(result);
            if (recommendationStore != null) {
                try { recommendationStore.save(result); }
                catch (IOException failure) {
                    recommendationStatus.setText("推荐已生成，保存失败：" + failure.getMessage());
                }
            }
        }));
    }

    private void showRecommendations(MusicClient.RecommendationResult result) {
        recommendations.setAll(result.tracks());
        String source = switch (result.tasteSource()) {
            case "popular" -> "B站音乐热门榜";
            case "ai" -> "AI 口味推荐";
            default -> "口味推荐";
        };
        recommendationStatus.setText(result.tracks().isEmpty() ? "暂无推荐"
                : result.tracks().size() + " 首 · " + source);
    }

    private void restoreLocalState() {
        recommendationStore = new RecommendationStore(dataDirectory().resolve("recommendations.xml"));
        try {
            var saved = recommendationStore.read();
            if (saved.isPresent()) showRecommendations(saved.get());
            else if (serviceInitialized) loadInitialPopular();
        } catch (IOException error) {
            recommendationStore = null;
            recommendationStatus.setText(error.getMessage());
        }
        playbackStateStore = new PlaybackStateStore(dataDirectory().resolve("playback-state.xml"));
        try {
            var saved = playbackStateStore.read();
            if (saved.isPresent() && !saved.get().tracks().isEmpty()) {
                var state = saved.get();
                queue.restore(state.tracks(), state.index(), state.sources());
                queue.setRepeatMode(state.repeatMode());
                queued.setAll(queue.tracks());
                Track current = queue.current();
                if (current == null) return;
                restoredPositionSeconds = state.positionSeconds();
                nowPlaying.setText("已暂停：" + current);
                immersiveTrackTitle.setText(songTitle(current));
                immersiveTrackArtist.setText(performerCaption(current));
                immersiveTrackSource.setText(sourceCaption(current));
                immersiveSourceButton.setDisable(current.bvid() == null);
                showCover(current, loadVersion);
                playButton.setDisable(false);
                timing.setText(format(Duration.seconds(state.positionSeconds())) + " / "
                        + format(Duration.seconds(current.durationSeconds())));
                progress.setValue(current.durationSeconds() > 0
                        ? Math.min(1, state.positionSeconds() / current.durationSeconds()) : 0);
                repeatControl.setText(switch (state.repeatMode()) {
                    case ALL -> "列表循环";
                    case ONE -> "单曲循环";
                    case SHUFFLE -> "随机播放";
                    case OFF -> "不循环";
                });
                repeatControl.setGraphic(icon(switch (state.repeatMode()) {
                    case ALL -> "repeat";
                    case ONE -> "repeat-one";
                    case SHUFFLE -> "shuffle";
                    case OFF -> "repeat-off";
                }, "transport-icon"));
                status.setText("已恢复上次播放位置");
                refreshFavoriteButton();
            }
        } catch (IOException error) {
            playbackStateStore = null;
            status.setText(error.getMessage());
        }
    }

    private void loadInitialPopular() {
        CompletableFuture.supplyAsync(() -> {
            try { return aiSettingsClient.read(); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((settings, error) -> Platform.runLater(() -> {
            if (closing || recommendationVersion != 0) return;
            if (error != null) recommendationStatus.setText("推荐暂不可用：" + message(error));
            else if (!settings.enabled() || !settings.keyConfigured()) loadRecommendations("");
            else recommendationStatus.setText("暂无推荐");
        }));
    }

    private void applyPendingResume() {
        if (pendingResumeSeconds == null) return;
        double duration = seconds(playbackDuration());
        if (duration <= 0) return;
        double target = Math.min(pendingResumeSeconds, Math.max(0, duration - 0.1));
        if (loadedTrack != null && loadedTrack.isSegment()) {
            double sourceDuration = seconds(audio.duration());
            if (sourceDuration <= 0) return;
            if (loadedTrack.segmentEndMillis() > sourceDuration * 1000 + 250) {
                pendingResumeSeconds = null;
                segmentSeeking = false;
                audio.stop();
                showPlaybackFailure(loadedTrack, new PlaybackFailure("SEGMENT_RANGE_INVALID",
                        "片段范围超出当前音源时长，请编辑起止时间", false, false));
                return;
            }
            double absoluteTarget = loadedTrack.segmentStartMillis() / 1000.0 + target;
            if (Math.abs(seconds(audio.position()) - absoluteTarget) <= 0.45) {
                pendingResumeSeconds = null;
                segmentSeeking = false;
                if (fadeInPending) {
                    fadeInPending = false;
                    playbackEnvelope.start(shortFadeEnabled, System.nanoTime());
                }
                applyOutputVolume();
                return;
            }
            long now = System.nanoTime();
            if (now - segmentSeekStartedNanos > TimeUnit.SECONDS.toNanos(6)) {
                pendingResumeSeconds = null;
                if (audio == streamingAudio && "bilibili".equals(loadedTrack.source())) {
                    fallbackSeekToLocal(target / duration, loadVersion);
                } else {
                    segmentSeeking = false;
                    audio.stop();
                    showPlaybackFailure(loadedTrack, new PlaybackFailure("SEGMENT_SEEK_FAILED",
                            "音源无法定位到片段起点，请重试或调整起点", false, false));
                }
                return;
            }
            if (now - lastSegmentSeekNanos >= TimeUnit.MILLISECONDS.toNanos(350)) {
                lastSegmentSeekNanos = now;
                audio.seekFraction(absoluteTarget / sourceDuration);
            }
            return;
        }
        pendingResumeSeconds = null;
        seekToFraction(target / duration);
    }

    private void savePlaybackState() {
        if (playbackStateStore == null) return;
        if (segmentPreview != null && segmentPreview.version == loadVersion) {
            try { playbackStateStore.save(new PlaybackStateStore.State(segmentPreview.tracks,
                    segmentPreview.index, segmentPreview.mode, segmentPreview.position,
                    segmentPreview.queueSnapshot.sources())); }
            catch (IOException error) { System.err.println("播放状态保存失败：" + error.getMessage()); }
            return;
        }
        double position = restoredPositionSeconds != null ? restoredPositionSeconds
                : pendingResumeSeconds != null ? pendingResumeSeconds : seconds(playbackPosition());
        List<Track> snapshot = new ArrayList<>(queue.tracks());
        if (queue.current() != null && restoredPositionSeconds == null) {
            Track current = queue.current();
            int duration = (int) Math.round(seconds(audio.duration()));
            if (duration > 0 && !current.isSegment()) snapshot.set(queue.index(), new Track(current.id(), current.title(),
                    current.artist(), duration, current.source(), current.mediaUri(), current.bvid(),
                    current.page(), current.coverUri(), current.partCount(), current.viewCount()));
        }
        try { playbackStateStore.save(new PlaybackStateStore.State(snapshot, queue.index(),
                queue.repeatMode(), position, queue.sources())); }
        catch (IOException error) { System.err.println("播放状态保存失败：" + error.getMessage()); }
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause instanceof RuntimeException) cause = cause.getCause();
        if (cause instanceof java.net.ConnectException) return "曲库服务未连接";
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private void showCover(Track track, long version) {
        cover.setImage(null);
        cover.setViewport(null);
        if (themeController != null) themeController.setCoverImage(null);
        if (miniThemeController != null) miniThemeController.setCoverImage(null);
        coverPlaceholder.setAccessibleText("暂无封面，音乐仍可正常播放");
        coverPlaceholder.setVisible(true);
        URI uri = track.coverUri();
        if (uri == null && "local".equals(track.source()) && track.mediaUri() != null) {
            URI savedCover = localTracks.stream().filter(item -> track.mediaUri().equals(item.mediaUri()))
                    .map(Track::coverUri).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (savedCover != null) { showLocalCover(savedCover, version); return; }
            localMetadata(track).whenComplete((metadata, error) -> Platform.runLater(() -> {
                if (!closing && version == loadVersion && error == null && metadata.coverUri() != null)
                    showLocalCover(metadata.coverUri(), version);
            }));
            return;
        }
        if (uri == null) return;
        failedCovers.remove(uri);
        coverPlaceholder.setAccessibleText("封面加载中，音乐仍可正常播放");
        var property = coverImage(uri);
        if (property.get() != null) applyCover(property.get());
        else observeCurrentCover(property, version);
    }

    private CompletableFuture<LocalAudioMetadata.Metadata> localMetadata(Track track) {
        return localMetadataReads.computeIfAbsent(track.mediaUri(), uri -> CompletableFuture.supplyAsync(() -> {
            try { return LocalAudioMetadata.read(Path.of(uri), dataDirectory()); }
            catch (IOException error) { throw new RuntimeException(error); }
        }, localTasks));
    }

    private void showLocalCover(URI uri, long version) {
        failedCovers.remove(uri);
        var property = coverImage(uri);
        if (property.get() != null) applyCover(property.get());
        else observeCurrentCover(property, version);
    }

    private void observeCurrentCover(javafx.beans.property.ObjectProperty<Image> property, long version) {
        javafx.beans.value.ChangeListener<Image> listener = new javafx.beans.value.ChangeListener<>() {
            @Override public void changed(javafx.beans.value.ObservableValue<? extends Image> observable, Image before, Image after) {
                property.removeListener(this);
                if (!closing && version == loadVersion && after != null) applyCover(after);
            }
        };
        property.addListener(listener);
    }

    private javafx.beans.property.ObjectProperty<Image> coverImage(URI uri) {
        if (!coverThumbnails.containsKey(uri) && coverThumbnails.size() >= 96) {
            URI oldest = coverThumbnails.keySet().iterator().next();
            coverThumbnails.remove(oldest);
            failedCovers.remove(oldest);
        }
        var property = coverThumbnails.computeIfAbsent(uri, ignored -> new javafx.beans.property.SimpleObjectProperty<>());
        if (property.get() != null || coverCache == null || failedCovers.contains(uri) || !loadingCovers.add(uri)) return property;
        coverCache.load(uri).whenComplete((bytes, error) -> Platform.runLater(() -> {
            loadingCovers.remove(uri);
            if (closing) return;
            if (error == null) {
                Image image = new Image(new java.io.ByteArrayInputStream(bytes), 640, 640, true, true);
                if (!image.isError()) {
                    property.set(image);
                    var currentProperty = coverThumbnails.get(uri);
                    if (currentProperty != null && currentProperty != property) currentProperty.set(image);
                    if (loadedTrack != null && uri.equals(loadedTrack.coverUri())) applyCover(image);
                } else {
                    failedCovers.add(uri);
                    loadingCovers.add(uri);
                    coverCache.invalidate(uri).whenComplete((ignored, failure) -> Platform.runLater(() -> loadingCovers.remove(uri)));
                }
            } else failedCovers.add(uri);
            if (property.get() == null && loadedTrack != null && uri.equals(loadedTrack.coverUri())) {
                coverPlaceholder.setAccessibleText("封面暂不可用，重新打开歌单将重试");
                coverPlaceholder.setVisible(true);
            }
        }));
        return property;
    }

    private void retryPlaylistResources(List<Track> tracks) {
        // Invisible rows stay lazy; reopening retries only covers previously requested by a visible row.
        for (Track track : tracks) if (track.coverUri() != null && coverThumbnails.containsKey(track.coverUri())) {
            failedCovers.remove(track.coverUri());
            coverImage(track.coverUri());
        }
        Track current = queue.current();
        if (current == null || tracks.stream().noneMatch(track -> track.id().equals(current.id()))) return;
        if (cover.getImage() == null && current.coverUri() != null) showCover(current, loadVersion);
        if (activeLyrics.isEmpty()) loadLyrics(current, loadVersion);
    }

    private void applyCover(Image image) {
        if (image.isError()) {
            coverPlaceholder.setAccessibleText("封面暂不可用，重新打开歌单将重试");
            coverPlaceholder.setVisible(true);
        } else {
            cover.setImage(image);
            cover.setViewport(null);
            coverPlaceholder.setAccessibleText("封面完整展示");
            if (themeController != null) themeController.setCoverImage(image);
            if (miniThemeController != null) miniThemeController.setCoverImage(image);
            coverPlaceholder.setVisible(false);
        }
    }

    private void loadLyrics(Track track, long version) {
        ++lyricsReadVersion;
        lyricRecoveryNotice = "";
        lyricOffsetMillis = 0;
        lyricStartMillis = 0;
        manualLyricSelection = null;
        lyricOffsetLabel.setText("偏移 0.0 秒");
        lyricCandidates.getItems().clear();
        lyricCandidates.setValue(null);
        lyricQuery.setText(track.title());
        setLyrics(List.of(), "暂无歌词，可选择 LRC 文件");
        if (lyricSelectionStore != null) {
            try {
                var saved = lyricSelectionStore.load(track);
                if (saved.isPresent()) {
                    LyricsSelectionStore.Selection selection = saved.get();
                    List<Lyrics.Line> lines = Lyrics.parse(selection.lrc());
                    if (!lines.isEmpty()) {
                        manualLyricSelection = selection;
                        lyricOffsetMillis = selection.offsetMillis();
                        lyricStartMillis = selection.startMillis();
                        updateLyricTimingLabel();
                        setLyrics(lines, (selection.origin() == LyricsSelectionStore.Origin.MANUAL
                                ? "已恢复手动匹配：" : "本地歌词：")
                                + selection.title() + " · " + selection.artist());
                        return;
                    }
                }
            } catch (IOException error) {
                lyricRecoveryNotice = "歌词记录读取失败，原件已保留；可重新匹配";
                recordDiagnostic("LYRICS_ERROR");
                showLyricsStatus(lyricRecoveryNotice);
            }
        }
        if ("local".equals(track.source()) && track.mediaUri() != null) {
            try {
                Path audioPath = Path.of(track.mediaUri());
                String filename = audioPath.getFileName().toString();
                int dot = filename.lastIndexOf('.');
                Path sidecar = audioPath.resolveSibling((dot < 0 ? filename : filename.substring(0, dot)) + ".lrc");
                if (Files.isRegularFile(sidecar)) {
                    readLyrics(sidecar, version, true);
                    return;
                }
            } catch (Exception ignored) { }
            String embedded = localLyrics.get(track.id());
            if (embedded != null && !embedded.isBlank()) { applyEmbeddedLyrics(embedded); return; }
            long request = lyricsReadVersion;
            showLyricsStatus("正在读取内嵌歌词…");
            localMetadata(track).whenComplete((metadata, error) -> Platform.runLater(() -> {
                if (closing || version != loadVersion || request != lyricsReadVersion) return;
                if (error == null && !metadata.lyrics().isBlank()) {
                    localLyrics.put(track.id(), metadata.lyrics());
                    applyEmbeddedLyrics(metadata.lyrics());
                } else if (serviceInitialized) searchOnlineLyrics(track, track.title(), track.artist(), version);
                else showLyricsStatus("暂无本地歌词，可选择 LRC 文件");
            }));
            return;
        }
        if (!"demo".equals(track.source())) searchOnlineLyrics(track, track.title(), track.artist(), version);
    }

    private void chooseLyrics(Stage stage) {
        if (queue.current() == null) {
            showLyricsStatus("请先选择曲目");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 LRC 歌词");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("LRC 歌词", "*.lrc"));
        File file = chooser.showOpenDialog(stage);
        if (file != null) readLyrics(file.toPath(), loadVersion);
    }

    private void readLyrics(Path path, long version) { readLyrics(path, version, false); }

    private void readLyrics(Path path, long version, boolean automatic) {
        long request = ++lyricsReadVersion;
        showLyricsStatus("正在读取歌词…");
        CompletableFuture.supplyAsync(() -> {
            try { return Files.readString(path, StandardCharsets.UTF_8); }
            catch (IOException error) { throw new RuntimeException(error); }
        }).whenComplete((lrc, error) -> Platform.runLater(() -> {
            if (version != loadVersion || request != lyricsReadVersion) return;
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                showLyricsStatus("歌词读取失败：" + cause.getMessage());
            } else {
                Track track = queue.current();
                String content = automatic && track != null && track.isSegment()
                        ? SegmentTimeline.sourceLyrics(lrc, track) : lrc;
                List<Lyrics.Line> lines = Lyrics.parse(content);
                if (lines.isEmpty()) showLyricsStatus("未找到带时间标记的歌词");
                else {
                    setLyrics(lines, "歌词：" + path.getFileName());
                    if (track != null) {
                        manualLyricSelection = new LyricsSelectionStore.Selection(track.title(),
                                track.artist(), content, (int) lyricOffsetMillis, automatic
                                ? LyricsSelectionStore.Origin.AUTOMATIC : LyricsSelectionStore.Origin.MANUAL, lyricStartMillis);
                        saveManualLyrics();
                    }
                }
            }
        }));
    }

    private void searchOnlineLyrics(Track track, String title, String artist, long version) {
        if (title == null || title.isBlank()) {
            showLyricsStatus("请输入曲名搜索歌词");
            return;
        }
        long request = ++lyricsReadVersion;
        showLyricsStatus("正在查找歌词候选…");
        CompletableFuture.supplyAsync(() -> {
            try { return lyricsClient.search(title, artist, track.durationSeconds()); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (version != loadVersion || request != lyricsReadVersion) return;
            if (error != null) {
                showLyricsStatus("歌词查找失败：" + message(error));
                return;
            }
            lyricCandidates.getItems().setAll(result.candidates());
            if (result.candidates().isEmpty()) {
                showLyricsStatus("未找到可用的同步歌词，可选择本地 LRC");
                return;
            }
            LyricsClient.Candidate automatic = result.autoId() == null ? null
                    : result.candidates().stream().filter(item -> item.id() == result.autoId()).findFirst().orElse(null);
            if (automatic != null) applyOnlineLyrics(automatic, true);
            else showLyricsStatus("找到 " + result.candidates().size() + " 个候选；匹配不确定，请手动确认");
        }));
    }

    private void applyOnlineLyrics(LyricsClient.Candidate candidate, boolean automatic) {
        applyOnlineLyrics(candidate, automatic, false);
    }

    private void applyOnlineLyrics(LyricsClient.Candidate candidate, boolean automatic, boolean startConfirmed) {
        if (candidate == null) {
            showLyricsStatus("请先选择歌词候选");
            return;
        }
        List<Lyrics.Line> lines = Lyrics.parse(candidate.lrc());
        if (lines.isEmpty()) {
            showLyricsStatus("该候选没有有效时间标记，请选择其他歌词");
            return;
        }
        ++lyricsReadVersion;
        Track track = queue.current();
        long end = lines.get(lines.size() - 1).millis();
        if (!automatic && !startConfirmed && track != null
                && track.durationSeconds() > Math.max(candidate.durationSeconds(), end / 1000) + 60
                && LyricTiming.relativeMillis(lyricMediaMillis(), lyricStartMillis, lyricOffsetMillis) > end + 10000) {
            showLyricMatchDialog(primaryStage, candidate);
            return;
        }
        lyricCandidates.setValue(candidate);
        setLyrics(lines, (automatic ? "自动匹配：" : "手动选择：")
                + candidate.title() + " · " + candidate.artist());
        manualLyricSelection = new LyricsSelectionStore.Selection(candidate.title(),
                candidate.artist(), candidate.lrc(), (int) lyricOffsetMillis, automatic
                ? LyricsSelectionStore.Origin.AUTOMATIC : LyricsSelectionStore.Origin.MANUAL, lyricStartMillis);
        saveManualLyrics();
    }

    private void shiftLyrics(long milliseconds) {
        lyricOffsetMillis = Math.max(-60_000, Math.min(60_000, lyricOffsetMillis + milliseconds));
        updateLyricTimingLabel();
        updateLyrics();
        if (manualLyricSelection != null) {
            manualLyricSelection = manualLyricSelection.withOffsetMillis((int) lyricOffsetMillis);
            saveManualLyrics();
        }
    }

    private void updateLyricTimingLabel() {
        String offset = String.format(java.util.Locale.ROOT, "偏移 %+.1f 秒", lyricOffsetMillis / 1000.0);
        lyricOffsetLabel.setText(lyricStartMillis == 0 ? offset
                : "起点 " + LyricTiming.formatStart(lyricStartMillis) + " · " + offset);
    }

    private long lyricMediaMillis() {
        return lyricMediaVersion == loadVersion ? Math.max(0, (long) playbackPosition().toMillis()) : 0;
    }

    private void setLyricStartMillis(long start) {
        if (start < 0) throw new IllegalArgumentException("歌曲起点不能为负数");
        lyricStartMillis = start;
        updateLyricTimingLabel();
        updateLyrics();
        if (manualLyricSelection != null) {
            manualLyricSelection = manualLyricSelection.withStartMillis(start);
            saveManualLyrics();
        }
    }

    private void showLyricStartDialog(Stage owner) {
        if (queue.current() == null) return;
        long version = loadVersion;
        TextInputDialog dialog = new TextInputDialog(LyricTiming.formatStart(lyricStartMillis));
        dialog.initOwner(owner);
        dialog.setTitle("歌曲起点");
        dialog.setHeaderText(null);
        dialog.setContentText("视频时间（分:秒 / 时:分:秒）");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/lumen-theme.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add(themeMode.name().toLowerCase(java.util.Locale.ROOT) + "-theme");
        dialog.showAndWait().ifPresent(value -> {
            if (version != loadVersion) { showLyricsStatus("曲目已变化，请重新设置起点"); return; }
            try { setLyricStartMillis(LyricTiming.parseStart(value)); }
            catch (IllegalArgumentException error) { showLyricsStatus(error.getMessage()); }
        });
    }

    private void alignLyric(ListView<String> view, boolean immersive) {
        int index = view.getSelectionModel().getSelectedIndex() - (immersive ? immersiveLyricPadding : 0);
        if (index < 0 || index >= activeLyrics.size()) {
            showLyricsStatus("请先选中正在听到的歌词句");
            return;
        }
        long start = lyricMediaMillis() - activeLyrics.get(index).millis();
        lyricOffsetMillis = Math.min(0, start);
        setLyricStartMillis(Math.max(0, start));
        if (manualLyricSelection != null) {
            manualLyricSelection = manualLyricSelection.withOffsetMillis((int) lyricOffsetMillis);
            saveManualLyrics();
        }
    }

    private void saveManualLyrics() {
        if (lyricSelectionStore == null || queue.current() == null || manualLyricSelection == null) {
            showLyricsStatus("歌词已应用，但本地缓存不可用");
            return;
        }
        Track track = queue.current();
        long version = loadVersion;
        LyricsSelectionStore.Selection selection = manualLyricSelection;
        lyricWrites.execute(() -> {
            try { lyricSelectionStore.save(track, selection); }
            catch (IOException error) { Platform.runLater(() -> {
                recordDiagnostic("LYRICS_ERROR");
                if (!closing && version == loadVersion) {
                    lyricRecoveryNotice = "歌词已应用但保存失败，原件已保留；可重试";
                    showLyricsStatus(lyricRecoveryNotice);
                }
            }); }
        });
    }

    private void showLyricMatchDialog(Stage owner) {
        showLyricMatchDialog(owner, null);
    }

    private void showLyricMatchDialog(Stage owner, LyricsClient.Candidate preset) {
        Track track = queue.current();
        if (track == null) return;
        long dialogVersion = loadVersion;
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("手动匹配歌词");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextField title = new TextField(preset == null ? track.title() : preset.title());
        title.setId("lyric-match-title");
        title.setPromptText("歌曲名，例如：晴天");
        TextField artist = new TextField(preset == null ? "" : preset.artist());
        artist.setPromptText("歌手（可留空）");
        ComboBox<LyricsClient.Candidate> matches = new ComboBox<>();
        matches.setId("lyric-match-candidates");
        matches.setMaxWidth(Double.MAX_VALUE);
        matches.setPromptText("选择正确的同步歌词");
        Label message = new Label("输入曲名后查找；选中候选再应用");
        if (preset != null) {
            matches.getItems().setAll(preset);
            matches.setValue(preset);
            message.setText("当前视频进度超出这首歌词，请确认这首歌在视频中的起点");
        }
        message.setWrapText(true);
        Button search = new Button("查找歌词");
        search.setId("lyric-match-search");
        Button apply = new Button("使用选中歌词");
        TextField songStart = new TextField(LyricTiming.formatStart(lyricStartMillis));
        songStart.setId("lyric-match-start");
        songStart.setPrefColumnCount(12);
        Button useProgress = new Button("当前进度");
        useProgress.setId("lyric-match-use-progress");
        useProgress.setOnAction(event -> songStart.setText(LyricTiming.formatStart(lyricMediaMillis())));
        apply.setId("lyric-match-apply");
        apply.disableProperty().bind(matches.valueProperty().isNull());
        apply.setOnAction(event -> {
            if (dialogVersion != loadVersion) {
                showLyricsStatus("曲目已变化，请重新匹配歌词");
                dialog.close();
                return;
            }
            try {
                long start = LyricTiming.parseStart(songStart.getText());
                if (Lyrics.parse(matches.getValue().lrc()).isEmpty()) {
                    message.setText("该候选没有有效时间标记");
                    return;
                }
                lyricStartMillis = start;
                lyricOffsetMillis = 0;
                updateLyricTimingLabel();
            } catch (IllegalArgumentException error) { message.setText(error.getMessage()); return; }
            applyOnlineLyrics(matches.getValue(), false, true);
            dialog.close();
        });
        search.setOnAction(event -> {
            if (dialogVersion != loadVersion) { message.setText("曲目已变化，请关闭后重新匹配"); return; }
            String query = title.getText().trim();
            String singer = artist.getText().trim();
            if (query.isEmpty()) { message.setText("请输入歌曲名"); return; }
            search.setDisable(true);
            message.setText("正在查找同步歌词…");
            matches.getItems().clear();
            long request = ++lyricsReadVersion;
            long currentLoad = loadVersion;
            CompletableFuture.supplyAsync(() -> {
                try { return lyricsClient.search(query, singer, track.durationSeconds()); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).whenComplete((result, error) -> Platform.runLater(() -> {
                if (!dialog.isShowing() || request != lyricsReadVersion || currentLoad != loadVersion) return;
                search.setDisable(false);
                if (error != null) message.setText("歌词查找失败：" + message(error));
                else {
                    matches.getItems().setAll(result.candidates());
                    message.setText(result.candidates().isEmpty() ? "没有找到同步歌词，请换一个曲名"
                            : "找到 " + result.candidates().size() + " 个候选，请核对曲名与歌手");
                }
            }));
        });
        title.setOnAction(event -> search.fire());
        VBox content = new VBox(10, new Label("歌曲名"), title,
                new Label("歌手"), artist, search, matches,
                new HBox(8, new Label("歌曲起点"), songStart, useProgress), apply, message);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        SmoothScrolling.install(dialog.getDialogPane());
        FriendlyTooltips.install(dialog.getDialogPane());
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/lumen-theme.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add(switch (themeMode) {
            case DARK -> "dark-theme";
            case LIGHT -> "light-theme";
            case BACKGROUND -> "background-theme";
            case DYNAMIC -> "dynamic-theme";
            case LUMEN -> "lumen-theme";
        });
        dialog.show();
        if (preset == null) search.fire();
    }

    private void showFullLyrics() {
        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("歌词全文");
        dialog.setHeaderText(queue.current() == null ? "暂无曲目" : queue.current().title());
        TextArea text = new TextArea(!plainLyrics.isBlank() ? plainLyrics : activeLyrics.isEmpty() ? "暂无歌词" :
                String.join("\n", activeLyrics.stream().map(Lyrics.Line::text).toList()));
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(580, 430);
        dialog.getDialogPane().setContent(text);
        SmoothScrolling.install(text);
        dialog.show();
    }

    private void notifyPetMusicEvent(PetCompanion.Event event, String detail) {
        if (closing || pet == null || !pet.isVisible()) return;
        if (desktopPet != null && desktopPet.isShowing()) {
            pet.onMusicEvent(event, detail);
            return;
        }
        if (primaryStage == null || !primaryStage.isShowing() || primaryStage.isIconified()
                || pet.getScene() != primaryStage.getScene()) return;
        for (javafx.scene.Node ancestor = pet; ancestor != null; ancestor = ancestor.getParent())
            if (!ancestor.isVisible()) return;
        pet.onMusicEvent(event, detail);
    }

    private ContextMenu lyricFragmentMenu(ListView<String> list, int row, int padding) {
        ContextMenu menu = new ContextMenu();
        Track track = loadedTrack;
        List<Lyrics.Line> lines = activeLyrics;
        long start = lyricStartMillis, offset = lyricOffsetMillis;
        MenuItem save = trackMenuItem("收藏歌词片段", "heart", "lyric-menu-save", () -> {
            if (track == null || row - padding < 0 || row - padding >= lines.size()) return;
            List<Integer> indices = list.getSelectionModel().getSelectedIndices().stream()
                    .map(index -> index - padding).filter(index -> index >= 0 && index < lines.size()).sorted().toList();
            if (indices.isEmpty()) indices = List.of(row - padding);
            Lyrics.Line first = lines.get(indices.get(0));
            long position = Math.max(0, first.millis() + start + offset);
            String text = String.join("\n", indices.stream().map(index -> lines.get(index).text()).toList());
            diaryPane.saveLyricFavorite(track, position, text);
        });
        menu.getItems().add(save);
        menu.getItems().add(trackMenuItem("歌词收藏", "list", "lyric-menu-open", () -> {
            openDiaryScene.run();
            diaryPane.showLyricsTab();
        }));
        menu.setOnShowing(event -> {
            if (!list.getSelectionModel().isSelected(row)) list.getSelectionModel().clearAndSelect(row);
            save.setDisable(track == null || row - padding < 0 || row - padding >= lines.size());
            themePopup(menu);
        });
        menu.setOnShown(event -> themePopup(menu));
        return menu;
    }

    private void showLyricsStatus(String message) {
        lyricsStatus.setText(lyricRecoveryNotice.isBlank() || message.contains(lyricRecoveryNotice)
                ? message : message + " · " + lyricRecoveryNotice);
    }

    private void setLyrics(List<Lyrics.Line> lines, String message) {
        plainLyrics = "";
        activeLyrics = lines;
        activeLyricIndex = -2;
        lyricsView.getItems().setAll(lines.stream().map(Lyrics.Line::text).toList());
        rebuildImmersiveLyrics();
        showLyricsStatus(message);
        updateLyrics();
    }

    private void applyEmbeddedLyrics(String content) {
        List<Lyrics.Line> timed = Lyrics.parse(content);
        if (!timed.isEmpty()) { setLyrics(timed, "内嵌歌词"); return; }
        setLyrics(List.of(), "内嵌歌词 · 未包含时间戳，可浏览全文");
        plainLyrics = content.strip();
        lyricsView.getItems().setAll(plainLyrics.lines().toList());
        rebuildImmersiveLyrics();
    }

    private void installLyricMarquee(Label label) {
        LyricMarquee marquee = new LyricMarquee(label, () -> lyricLineProgress(activeLyricIndex));
        marquee.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0, label.widthProperty().subtract(2)));
        label.setGraphic(marquee);
        label.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        label.textProperty().addListener((ignored, before, after) -> marquee.setText(after));
        marquee.setText(label.getText());
        lyricMarquees.add(marquee);
    }

    private double lyricLineProgress(int index) {
        if (index < 0 || index != activeLyricIndex || index >= activeLyrics.size()) return Double.NaN;
        long start = activeLyrics.get(index).millis();
        long end = index + 1 < activeLyrics.size() ? activeLyrics.get(index + 1).millis() : start + 10_000;
        long current = LyricTiming.relativeMillis(lyricMediaMillis(), lyricStartMillis, lyricOffsetMillis);
        return (current - start) / (double) Math.max(1, end - start);
    }

    private void updateLyrics() {
        int index = Lyrics.currentIndex(activeLyrics,
                LyricTiming.relativeMillis(lyricMediaMillis(), lyricStartMillis, lyricOffsetMillis));
        if (index == activeLyricIndex) {
            updateDesktopLyric();
            lyricMarquees.forEach(LyricMarquee::updatePosition);
            return;
        }
        activeLyricIndex = index;
        currentLyric.setText(index >= 0 ? activeLyrics.get(index).text() : "♪");
        updateDesktopLyric();
        lyricsView.refresh();
        immersiveLyrics.refresh();
        if (index >= 0) SmoothListScroll.install(lyricsView).centerOn(index, true);
        if (index >= 0) animateLyricScroll(index);
        lyricMarquees.forEach(LyricMarquee::updatePosition);
    }

    private void updateDesktopLyric() {
        if (desktopPet != null) desktopPet.setLyric(activeLyricIndex >= 0
                && activeLyricIndex < activeLyrics.size() ? activeLyrics.get(activeLyricIndex).text() : "",
                !closing && audio != null && audio.state() == JavaFxAudioPlayer.State.PLAYING);
    }

    private boolean confirmRemoval(String title, String detail) {
        return DestructiveConfirmation.confirm(primaryStage, title, detail, this::themeSegmentDialog);
    }

    private void unfavoriteSelection(List<Track> tracks) {
        List<Track> selected = tracks.stream().filter(track -> favorites.stream()
                .anyMatch(saved -> saved.id().equals(track.id()))).distinct().toList();
        if (selected.isEmpty()) { libraryStatus.setText("所选曲目尚未收藏"); return; }
        if (!confirmRemoval("取消所选收藏", "取消 " + selected.size()
                + " 首曲目的收藏？\n歌单和音频仍保留，原收藏日期无法撤回。")) return;
        batchLibrary(selected, track -> libraryClient.unfavorite(track.id()), "已批量取消收藏");
    }

    private void applyLyricSize(LyricSize size) {
        this.currentLyricSize = size;
        lyricSizeGroup.getToggles().stream().filter(toggle -> toggle.getUserData() == size)
                .findFirst().ifPresent(lyricSizeGroup::selectToggle);
        for (LyricSize s : LyricSize.values()) {
            immersiveLyrics.getStyleClass().remove(s.styleClass);
        }
        immersiveLyrics.getStyleClass().add(size.styleClass);
        immersiveLyrics.setFixedCellSize(size.cellHeight);
        if (lyricSizeButton != null) {
            lyricSizeButton.setText(size.shortLabel);
            lyricSizeButton.setTooltip(readableTooltip("歌词字号：" + size.label + "（点击切换）"));
        }
        rebuildImmersiveLyrics();
        if (activeLyricIndex >= 0) animateLyricScroll(activeLyricIndex);
    }

    private void rebuildImmersiveLyrics() {
        double cellSize = immersiveLyrics.getFixedCellSize() > 0 ? immersiveLyrics.getFixedCellSize() : 58;
        immersiveLyricPadding = Math.max(1, (int) Math.ceil(immersiveLyrics.getHeight() / (cellSize * 2)));
        if (activeLyrics.isEmpty()) {
            immersiveLyrics.getItems().setAll(plainLyrics.isBlank() ? List.of() : plainLyrics.lines().toList());
            return;
        }
        List<String> display = new ArrayList<>();
        for (int i = 0; i < immersiveLyricPadding; i++) display.add("");
        display.addAll(activeLyrics.stream().map(Lyrics.Line::text).toList());
        for (int i = 0; i < immersiveLyricPadding; i++) display.add("");
        immersiveLyrics.getItems().setAll(display);
        if (activeLyricIndex >= 0) animateLyricScroll(activeLyricIndex);
    }

    private void animateLyricScroll(int index) {
        Platform.runLater(() -> {
            if (index != activeLyricIndex || closing) return;
            SmoothListScroll.install(immersiveLyrics).centerOn(immersiveLyricPadding + index,
                    immersiveLyrics.getScene() != null && immersiveLyrics.isVisible());
        });
    }

    private String themeLabel() {
        return "主题：" + switch (themeMode) {
            case DARK -> "深色";
            case LIGHT -> "浅色";
            case BACKGROUND -> "背景图";
            case DYNAMIC -> "动态";
            case LUMEN -> "Lumen";
        };
    }

    private void showAiSettings(Stage stage) {
        AiSettingsDialog.show(stage, aiSettingsClient,
                () -> recommendationStatus.setText("AI 设置已保存"));
    }

    private void showCacheSettings(Stage stage) {
        if (persistentCache == null) {
            status.setText("内部音频缓存不可用，请检查本地数据目录");
            return;
        }
        CacheSettingsDialog.show(stage, persistentCache);
    }

    private void chooseBackground(Stage stage, Button themeButton) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择背景图");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        try { setBackgroundImage(file.toPath()); }
        catch (IOException error) {
            status.setText("背景图保存失败：" + error.getMessage());
            return;
        }
        themeButton.setText(themeLabel());
    }

    void setBackgroundImage(Path selected) throws IOException {
        try {
            if (Files.size(selected) > 32L * 1024 * 1024)
                throw new IOException("背景图不能超过 32 MiB");
            String name = selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String suffix = name.substring(name.lastIndexOf('.') + 1);
            if (!List.of("png", "jpg", "jpeg", "webp").contains(suffix))
                throw new IOException("背景图格式不支持");
            Image candidateImage = new Image(selected.toUri().toString(), false);
            if (candidateImage.isError()) throw new IOException("无法读取所选背景图");
            Path stored = dataDirectory().resolve("background." + suffix);
            Files.createDirectories(stored.getParent());
            if (!selected.equals(stored)) Files.copy(selected, stored,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            backgroundUri = stored.toUri();
        } catch (java.nio.file.InvalidPathException error) { throw new IOException(error); }
        themeMode = ThemeController.Mode.BACKGROUND;
        applyAppearance();
        saveAppearance();
    }

    private void applyAppearance() {
        updateThemeSwatches();
        if (backgroundControls != null) {
            boolean visible = themeMode == ThemeController.Mode.BACKGROUND;
            backgroundControls.setVisible(visible);
            backgroundControls.setManaged(visible);
        }
        for (ThemeController controller : new ThemeController[] { themeController, miniThemeController }) {
            if (controller == null) continue;
            controller.setAccent(accent);
            controller.setBackgroundUri(backgroundUri);
            controller.setMode(themeMode);
            controller.setCoverImage(cover.getImage());
        }
    }

    private TitledPane createVersionSettings() {
        BuildInfo build = BuildInfo.current();
        Label version = new Label("Lumen Music v" + build.version());
        version.setId("settings-app-version"); version.getStyleClass().add("section-title");
        version.setWrapText(true);
        Label identifier = new Label("构建 " + build.buildId());
        identifier.setId("settings-build-id"); identifier.getStyleClass().add("muted");
        identifier.setWrapText(true);
        TextArea changes = new TextArea(BuildInfo.releaseNotes());
        changes.setId("settings-release-notes"); changes.setEditable(false); changes.setWrapText(true);
        changes.setPrefRowCount(7); changes.setMinHeight(120); changes.setMaxHeight(200);
        Button select = new Button("选择本地更新包", icon("repeat", "action-icon"));
        select.setId("settings-select-update"); select.setTooltip(new Tooltip("选择 Lumen Music 更新 ZIP"));
        select.setDisable(LocalUpdatePackage.installationRoot().isEmpty());
        select.setOnAction(event -> selectLocalUpdate(select));
        Button export = new Button("导出诊断信息", icon("external", "action-icon"));
        export.setId("settings-export-diagnostics"); export.setTooltip(new Tooltip("导出诊断 ZIP"));
        export.setDisable(diagnosticLog == null);
        export.setOnAction(event -> exportDiagnostics(export));
        updateStatus.setId("settings-update-status"); updateStatus.getStyleClass().add("muted");
        updateStatus.setWrapText(true);
        try {
            LocalUpdatePackage.latestResult(dataDirectory().resolve("updates")).ifPresent(result -> {
                String notice = switch (result.status()) {
                    case "success", "already_current" -> "上次更新完成：v" + result.toVersion();
                    case "rolled_back" -> "上次更新失败，原程序已恢复";
                    case "rollback_failed" -> "上次更新未能完整恢复，请保留备份";
                    default -> "上次更新未应用，原程序未改动";
                };
                String detail = !java.util.Set.of("success", "already_current").contains(result.status())
                        && result.message() != null && !result.message().isBlank() ? "\n" + result.message() : "";
                updateStatus.setText(notice + detail + (result.backupPath() == null
                        ? "" : "\n备份：" + result.backupPath()));
            });
        } catch (IOException error) { updateStatus.setText("上次更新结果暂不可读，记录已保留"); }
        FlowPane actions = new FlowPane(10, 10, select, export);
        return settingsSection("版本与反馈", "settings-version", new VBox(10, version, identifier, changes, actions, updateStatus), "v" + build.version() + " · 内测候选");
    }

    private void selectLocalUpdate(Button button) {
        if (updateBusy) return;
        var install = LocalUpdatePackage.installationRoot();
        if (install.isEmpty()) { updateStatus.setText("当前运行方式不是安装版"); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Lumen Music 更新包");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Lumen Music 更新包", "*.zip"));
        File chosen = chooser.showOpenDialog(primaryStage);
        if (chosen == null) return;
        updateBusy = true; button.setDisable(true); updateStatus.setText("正在检查更新包…");
        libraryTasks.submit(() -> {
            try {
                var inspected = LocalUpdatePackage.inspect(chosen.toPath(), install.get(), BuildInfo.current().version());
                var prepared = LocalUpdatePackage.stage(inspected, dataDirectory().resolve("updates"));
                Platform.runLater(() -> {
                    if (closing) return;
                    updateBusy = false; button.setDisable(false);
                    showUpdateConfirmation(prepared, button);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closing) return;
                    updateBusy = false; button.setDisable(false); recordDiagnostic("UPDATE_ERROR");
                    updateStatus.setText("更新包未应用：" + message(error));
                });
            }
        });
    }

    private void showUpdateConfirmation(LocalUpdatePackage.Prepared prepared, Button select) {
        var manifest = prepared.inspection().manifest();
        updateConfirm = new Dialog<>();
        updateConfirm.initOwner(primaryStage); updateConfirm.setTitle("确认更新");
        DialogPane pane = updateConfirm.getDialogPane(); pane.setId("local-update-confirm");
        Label summary = new Label("v" + manifest.fromVersion() + " → v" + manifest.toVersion()
                + "\n构建 " + manifest.buildId() + "\n\n退出播放器后更新并重新打开。个人数据保留。更新失败时尝试恢复原程序。");
        summary.setWrapText(true); summary.setMaxWidth(440); pane.setContent(summary);
        ButtonType apply = new ButtonType("更新并重启", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(apply, cancel);
        Button applyButton = (Button) pane.lookupButton(apply); applyButton.setId("local-update-apply");
        pane.lookupButton(cancel).setId("local-update-cancel");
        themeSegmentDialog(pane);
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); if (updateBusy) return;
            updateBusy = true; applyButton.setDisable(true); pane.lookupButton(cancel).setDisable(true); select.setDisable(true);
            updateStatus.setText("正在启动更新器…");
            libraryTasks.submit(() -> {
                try {
                    LocalUpdatePackage.launch(prepared, ProcessHandle.current().pid(), true);
                    Platform.runLater(() -> { stop(); Platform.exit(); });
                } catch (Exception error) {
                    Platform.runLater(() -> {
                        if (closing) return;
                        updateBusy = false; applyButton.setDisable(false); pane.lookupButton(cancel).setDisable(false); select.setDisable(false);
                        recordDiagnostic("UPDATE_ERROR"); updateStatus.setText("更新器未启动：" + message(error));
                    });
                }
            });
        });
        updateConfirm.setOnHidden(event -> { if (!closing && !updateBusy) updateStatus.setText("已取消更新，程序未改动"); });
        updateConfirm.show();
    }

    private void exportDiagnostics(Button button) {
        if (diagnosticLog == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("导出诊断信息");
        chooser.setInitialFileName("Lumen-Music-Diagnostics-" + java.time.LocalDate.now() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 压缩包", "*.zip"));
        File chosen = chooser.showSaveDialog(primaryStage);
        if (chosen == null) return;
        var snapshot = new DiagnosticLog.Snapshot(audio == streamingAudio ? DiagnosticLog.Engine.VLC : DiagnosticLog.Engine.JAVA_FX,
                audio.state(), normalizationEnabled, preloadEnabled, shortFadeEnabled, MotionPreferences.reduced());
        button.setDisable(true); updateStatus.setText("正在导出诊断信息…");
        libraryTasks.submit(() -> {
            try {
                diagnosticLog.export(chosen.toPath(), snapshot);
                Platform.runLater(() -> { if (!closing) { button.setDisable(false); updateStatus.setText("诊断信息已导出"); } });
            } catch (IOException error) {
                Platform.runLater(() -> { if (!closing) { button.setDisable(false); updateStatus.setText("诊断信息导出失败：" + message(error)); } });
            }
        });
    }

    private void recordDiagnostic(String code) {
        if (diagnosticLog == null) return;
        try { diagnosticLog.record(code); }
        catch (IOException error) { System.err.println("Diagnostic record is unavailable"); }
    }

    private static Path preferencesPath() {
        String configured = System.getenv("LUMEN_PREFERENCES_PATH");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank())
            return Path.of(appData, "Lumen Music", "preferences.properties");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return config.resolve("lumen-music").resolve("preferences.properties");
    }

    private static Path dataDirectory() {
        String configured = System.getenv("LUMEN_LIBRARY_PATH");
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath().getParent();
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "Lumen Music");
        String xdg = System.getenv("XDG_DATA_HOME");
        return Path.of(xdg != null && !xdg.isBlank() ? xdg
                : Path.of(System.getProperty("user.home"), ".local", "share").toString(), "Lumen Music");
    }

    private void refreshRestoredBackground() {
        try {
            Path newest = null;
            for (String suffix : List.of("png", "jpg", "jpeg", "webp")) {
                Path candidate = dataDirectory().resolve("background." + suffix);
                if (Files.isRegularFile(candidate) && (newest == null
                        || Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(newest)) > 0))
                    newest = candidate;
            }
            if (newest != null) {
                backgroundUri = newest.toUri();
                applyAppearance();
                saveAppearance();
            }
        } catch (IOException error) {
            status.setText("背景图恢复后读取失败：" + error.getMessage());
        }
    }

    private void readAppearance() {
        Properties properties = new Properties();
        try {
            appearancePreferences = new AppearancePreferencesStore(preferencesPath());
            AppearancePreferencesStore.LoadResult loaded = appearancePreferences.load();
            properties = loaded.properties();
            if (!loaded.warning().isBlank()) {
                appearanceRecoveryNotice.setText(loaded.recoveryFile() != null
                        ? "外观设置读取失败，已保留恢复副本并使用默认设置"
                        : "外观设置读取失败，原件已保留；暂不能保存设置");
                status.setText(appearanceRecoveryNotice.getText());
                recordDiagnostic("PREFERENCES_ERROR");
            }
            try { themeMode = ThemeController.Mode.valueOf(
                    properties.getProperty("theme", "dark").toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { themeMode = ThemeController.Mode.DARK; }
            try { accent = ThemeController.Accent.valueOf(properties.getProperty("accent", "PINK")); }
            catch (IllegalArgumentException ignored) { accent = ThemeController.Accent.BLUE; }
            try { petSpecies = PetCompanion.Species.valueOf(properties.getProperty("pet", "BLACK_CAT")); }
            catch (IllegalArgumentException ignored) { petSpecies = PetCompanion.Species.BLACK_CAT; }
            sidebarCollapsed = Boolean.parseBoolean(properties.getProperty("sidebar.collapsed", "false"));
            petOnDesktopImmersive = Boolean.parseBoolean(properties.getProperty("pet.desktop.immersive", "true"));
            petOnDesktopMinimized = Boolean.parseBoolean(properties.getProperty("pet.desktop.minimized", "false"));
            try { petDesktopX = Double.parseDouble(properties.getProperty("pet.desktop.x", "NaN")); petDesktopY = Double.parseDouble(properties.getProperty("pet.desktop.y", "NaN")); }
            catch (NumberFormatException ignored) { petDesktopX = petDesktopY = Double.NaN; }
            normalizationEnabled = Boolean.parseBoolean(properties.getProperty("audio.normalization", "false"));
            preloadEnabled = Boolean.parseBoolean(properties.getProperty("audio.preload", "true"));
            shortFadeEnabled = Boolean.parseBoolean(properties.getProperty("audio.shortFade", "false"));
            try { sidebarWidth = Math.max(176, Math.min(330, Double.parseDouble(properties.getProperty("sidebar.width", "228")))); }
            catch (NumberFormatException ignored) { sidebarWidth = 228; }
            try { queueWidth = Math.max(300, Math.min(560, Double.parseDouble(properties.getProperty("queue.width", "360")))); }
            catch (NumberFormatException ignored) { queueWidth = 360; }
            String path = properties.getProperty("background", "");
            if (!path.isBlank() && Files.isRegularFile(Path.of(path)))
                backgroundUri = Path.of(path).toUri();
        } catch (java.nio.file.InvalidPathException error) {
            appearanceRecoveryNotice.setText("外观设置路径无效，已使用默认设置");
            status.setText(appearanceRecoveryNotice.getText());
            recordDiagnostic("PREFERENCES_ERROR");
        }
        MotionPreferences.setReduced(Boolean.parseBoolean(properties.getProperty("motion.reduced", "false")));
    }

    private void saveAppearance() {
        try {
            if (appearancePreferences == null) appearancePreferences = new AppearancePreferencesStore(preferencesPath());
            Properties properties = new Properties();
            properties.setProperty("theme", themeMode.name().toLowerCase(java.util.Locale.ROOT));
            properties.setProperty("accent", accent.name());
            properties.setProperty("pet", petSpecies.name());
            properties.setProperty("pet.desktop.immersive", Boolean.toString(petOnDesktopImmersive));
            properties.setProperty("pet.desktop.minimized", Boolean.toString(petOnDesktopMinimized));
            if (desktopPet != null) { petDesktopX = desktopPet.getX(); petDesktopY = desktopPet.getY(); }
            if (Double.isFinite(petDesktopX) && Double.isFinite(petDesktopY)) {
                properties.setProperty("pet.desktop.x", Double.toString(petDesktopX));
                properties.setProperty("pet.desktop.y", Double.toString(petDesktopY));
            }
            properties.setProperty("sidebar.collapsed", Boolean.toString(sidebarCollapsed));
            properties.setProperty("sidebar.width", Double.toString(sidebarWidth));
            properties.setProperty("queue.width", Double.toString(queueWidth));
            properties.setProperty("audio.normalization", Boolean.toString(normalizationEnabled));
            properties.setProperty("audio.preload", Boolean.toString(preloadEnabled));
            properties.setProperty("audio.shortFade", Boolean.toString(shortFadeEnabled));
            properties.setProperty("motion.reduced", Boolean.toString(MotionPreferences.reduced()));
            if (backgroundUri != null) properties.setProperty("background", Path.of(backgroundUri).toString());
            appearancePreferences.save(properties);
            appearanceRecoveryNotice.setText("");
        } catch (IOException | java.nio.file.InvalidPathException error) {
            appearanceRecoveryNotice.setText("外观设置保存失败，原件已保留；可重试");
            status.setText(appearanceRecoveryNotice.getText());
            recordDiagnostic("PREFERENCES_ERROR");
        }
    }

    @Override public void stop() {
        if (closing) return;
        closing = true;
        savePlaybackState();
        finishListening();
        ++loadVersion;
        ++searchVersion;
        ++collectionVersion;
        ++lyricsReadVersion;
        ++homeRequestVersion;
        cancelPendingOnline();
        if (clock != null) clock.stop();
        if (volumeClock != null) volumeClock.stop();
        if (sidebarMotion != null) sidebarMotion.stop();
        var pendingFactory = pendingVlcFactory.getAndSet(null);
        if (vlcReady != null) vlcReady.complete(false);
        saveAppearance();
        if (motionListener != null) MotionPreferences.reducedProperty().removeListener(motionListener);
        UiMotion.cancelAll();
        clearSceneOverlays.run();
        if (artworkTransition != null) artworkTransition.close();
        if (idleControls != null) idleControls.close();
        if (focusPane != null) focusPane.close();
        if (desktopPet != null) desktopPet.close();
        if (miniStage != null) miniStage.close();
        ++recommendationVersion;
        ++radioVersion;
        if (radioDialog != null) radioDialog.close();
        if (practiceDialog != null) practiceDialog.close();
        if (segmentBatchDialog != null) segmentBatchDialog.close();
        if (updateConfirm != null) updateConfirm.close();
        if (quickSearchDialog != null) quickSearchDialog.close();
        if (personalHome != null) personalHome.close();
        if (playlistPresentation != null) playlistPresentation.close();
        if (smartDialog != null) smartDialog.close();
        if (diaryPane != null) diaryPane.close();
        petEventTimer.stop();
        petEventNotice.setVisible(false);
        radioTasks.shutdownNow();
        if (nextPreloader != null) nextPreloader.close();
        onlineDownloads.shutdownNow();
        libraryTasks.shutdownNow();
        localTasks.shutdown();
        lyricWrites.shutdown();
        if (coverCache != null) coverCache.close();
        if (themeController != null) themeController.dispose();
        if (miniThemeController != null) miniThemeController.dispose();
        if (pet != null) pet.dispose();
        if (taskbarControls != null) taskbarControls.close();
        if (miniTaskbarControls != null) miniTaskbarControls.close();
        if (globalHotkeys != null) globalHotkeys.close();
        if (audio != null) audio.stop();
        if (localAudio != null) localAudio.close();
        if (streamingAudio != null) streamingAudio.close();
        // Keep the JVM alive for bounded final writes, without waiting on the FX thread.
        Thread shutdown = new Thread(() -> {
            Throwable failure = null;
            try {
                listening.close();
                LumenLauncher.closeRuntime();
                boolean saved = awaitShutdownTask(localTasks) & awaitShutdownTask(lyricWrites);
                boolean downloadsStopped = awaitShutdownTask(onlineDownloads);
                if (!saved || !downloadsStopped) {
                    failure = new IOException("部分后台任务未能在退出时完成");
                }
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                try {
                    LumenLauncher.closeRuntime();
                    if (onlineAudio != null) onlineAudio.close();
                    demoAudio.close();
                    if (pendingFactory != null) pendingFactory.release();
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                }
            }
            if (failure == null) shutdownComplete.complete(null);
            else {
                recordDiagnostic("SHUTDOWN_ERROR");
                shutdownComplete.completeExceptionally(failure);
            }
        }, "lumen-shutdown");
        shutdown.setDaemon(false);
        shutdown.start();
    }

    private static boolean awaitShutdownTask(ExecutorService tasks) {
        try {
            if (tasks.awaitTermination(2, TimeUnit.SECONDS)) return true;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        tasks.shutdownNow();
        return false;
    }

    private void updateThemeSwatches() {
        for (Button swatch : themeSwatchButtons) {
            ThemeController.Mode mode = (ThemeController.Mode) swatch.getUserData();
            boolean active = mode == themeMode;
            swatch.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), active);
        }
    }

    private static void installProgressTrackFill(Slider slider) {
        Runnable update = () -> updateProgressTrackFill(slider, slider.getValue());
        slider.valueProperty().addListener((obs, oldVal, newVal) -> update.run());
        slider.skinProperty().addListener((obs, oldVal, newVal) -> Platform.runLater(update));
        slider.sceneProperty().addListener((obs, oldVal, newVal) -> Platform.runLater(update));
    }

    private static void updateProgressTrackFill(Slider slider, double fraction) {
        javafx.scene.Node trackNode = slider.lookup(".track");
        if (trackNode instanceof Region track) {
            double percent = Math.max(0.0, Math.min(100.0, fraction * 100.0));
            track.setStyle(String.format(java.util.Locale.ROOT,
                "-fx-background-color: linear-gradient(to right, -lumen-accent 0%%, -lumen-accent %.2f%%, -lumen-divider %.2f%%, -lumen-divider 100%%);",
                percent, percent));
        }
    }

    public static void main(String[] args) { launch(args); }
}
