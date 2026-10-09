package com.lumenmusic;

import com.lumenmusic.audio.AudioPlayer;
import com.lumenmusic.audio.JavaFxAudioPlayer;
import com.lumenmusic.audio.VlcAudioPlayer;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Real JavaFX/VLC transport and practice controls on an isolated service/profile. */
public final class PracticePlaybackSmoke extends Application {
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String phase = "startup";
    private LumenFxApp app;
    private Stage stage;
    private Path root;
    private LibraryClient library;
    private Track source;
    private Track segment;
    private Track second;
    private String playlistId;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(270_000); } catch (InterruptedException ignored) { return; }
            if (finished.compareAndSet(false, true)) {
                System.err.println("FAIL practice watchdog: " + phase);
                System.exit(1);
            }
        }, "practice-smoke-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        Thread driver = new Thread(() -> {
            try { exercise(); finish(0); }
            catch (Throwable error) {
                System.err.println("FAIL practice phase: " + phase);
                error.printStackTrace(System.err);
                finish(1);
            }
        }, "practice-smoke-driver");
        driver.setDaemon(true); driver.start();
    }

    private void exercise() throws Exception {
        prepare();
        fx(() -> {
            app = new LumenFxApp(); app.startUi(stage, List.of());
            stage.setWidth(1280); stage.setHeight(800);
        });
        System.out.println("OUTPUT SCALE: " + fxValue(() -> stage.getOutputScaleX())
                + " x " + fxValue(() -> stage.getOutputScaleY()));
        await(15_000, () -> fxValue(() -> picker().getItems().stream()
                .anyMatch(item -> playlistId.equals(item.id()))), "fixture playlist did not load");
        fx(() -> {
            button("nav-playlists").fire();
            picker().setValue(picker().getItems().stream().filter(item -> playlistId.equals(item.id()))
                    .findFirst().orElseThrow());
            ListView<Track> list = list("playlist-tracks");
            list.getSelectionModel().clearAndSelect(0);
            list.fireEvent(click(2, false, false, MouseButton.PRIMARY));
        });
        awaitPlaying(source);
        check(fxValue(() -> audio() instanceof JavaFxAudioPlayer), "local fixture did not use real JavaFX");
        if (Boolean.getBoolean("lumen.practice.smoke.edgeOnly")) {
            endpointEdgeChecks();
            return;
        }
        if (Boolean.getBoolean("lumen.practice.smoke.followupOnly")) {
            followupChecks();
            return;
        }
        if (!Boolean.getBoolean("lumen.scene.smoke.visualOnly")) {
            lyricChecks();
            loopChecks();
            diaryChecks();
            saveChecks();
            rateChecks(false);
            rateChecks(true);
            vlcEndLoopChecks();
        }
        phase = "five themes and two window sizes";
        pausePlayback();
        themeMatrix();
        System.out.println("PASS five themes at 860x600/1280x800 with practice and immersive controls");
    }

    private void prepare() throws Exception {
        root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        check(root != null && root.toString().contains("practice-smoke"), "isolated practice profile required");
        for (String key : List.of("LOCALAPPDATA", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH")) {
            Path path = Path.of(required(key)).toAbsolutePath().normalize();
            check(path.equals(root) || root.equals(path.getParent()), key + " outside isolated profile");
        }
        check(System.getenv("OPENAI_API_KEY") == null && System.getenv("LUMEN_AI_API_KEY") == null,
                "API keys entered isolated smoke");
        URI service = URI.create(required("LUMEN_SERVICE_URL"));
        check("http".equals(service.getScheme()) && "127.0.0.1".equals(service.getHost()) && service.getPort() > 0,
                "isolated loopback service required");
        library = new LibraryClient(service);
        var playlist = library.load().playlists().stream().filter(item -> "Practice acceptance".equals(item.name()))
                .findFirst().orElseThrow();
        playlistId = playlist.id(); source = playlist.tracks().get(0);
        segment = playlist.tracks().get(1); second = playlist.tracks().get(2);
        check(segment.isSegment() && segment.segmentStartMillis() == 8000, "offset fixture missing");
        LyricsSelectionStore store = new LyricsSelectionStore(LyricsSelectionStore.defaultDirectory());
        String lrc = "[00:00.00]First line\n[00:04.00]Calibrated line\n[00:10.00]Last line";
        store.save(source, new LyricsSelectionStore.Selection(source.title(), source.artist(), lrc,
                750, LyricsSelectionStore.Origin.MANUAL, 3000));
        store.save(segment, new LyricsSelectionStore.Selection(segment.title(), segment.artist(), lrc,
                -500, LyricsSelectionStore.Origin.MANUAL, 1000));
        store.save(second, new LyricsSelectionStore.Selection(second.title(), second.artist(), lrc, 0));
    }

    private void followupChecks() throws Exception {
        phase = "right rail paused calibrated lyric cell";
        pausePlayback();
        fx(() -> {
            button("toggle-queue").fire();
            javafx.scene.control.TitledPane lyricsSection = (javafx.scene.control.TitledPane)
                    stage.getScene().getRoot().lookup(".lyrics-section");
            check(lyricsSection != null, "right rail lyric section missing");
            lyricsSection.setExpanded(true); layout();
        });
        pause(400);
        fx(() -> {
            ListView<String> lyrics = lyricList("lyrics-list");
            lyrics.scrollTo(1); lyrics.applyCss(); lyrics.layout();
            ListCell<?> cell = lyrics.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast).filter(item -> item.getIndex() == 1 && !item.isEmpty())
                    .findFirst().orElseThrow(() -> new AssertionError("right rail lyric row not rendered"));
            check(cell.isVisible() && cell.localToScene(cell.getLayoutBounds()).getHeight() > 0,
                    "right rail lyric row hidden");
            cell.fireEvent(click(1, false, false, MouseButton.PRIMARY));
        });
        awaitPosition(7.75, .55);
        fx(() -> {
            check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "right rail lyric click resumed playback");
            screenshot(stage.getScene(), "followup-right-rail-paused.png");
            button("toggle-queue").fire(); button("open-practice").fire();
            DialogPane pane = dialog("practice-start");
            @SuppressWarnings("unchecked") ComboBox<Double> rates = control(pane, "practice-rate", ComboBox.class);
            rates.setValue(1.5); close(pane);
        });
        System.out.println("PASS actual right rail lyric cell seeks calibrated media while preserving pause");

        Track shortTrack = Track.segment(source, "Followup complete 6s", "Fixture", 10000, 16000);
        Track loopTrack = Track.segment(source, "Followup looping 6s", "Fixture", 20000, 26000);
        library.favorite(shortTrack); library.favorite(loopTrack);
        phase = "1.5x six-second completion qualifies actual listening through HTTP";
        select(shortTrack);
        fx(() -> check(Math.abs(audio().playbackRate() - 1.5) < .01, "changing track reset playback speed"));
        await(8000, () -> fxValue(() -> field("segmentFinished", Boolean.class)), "six-second fast segment did not complete");
        await(6000, () -> historyEntry(shortTrack) != null, "completed fast segment never reached service history");
        HistoryObservation completed = historyEntry(shortTrack);
        check(completed.playCount() == 1 && completed.seconds() >= 3.6 && completed.seconds() <= 4.5,
                "fast short track credited source duration instead of actual listening: " + completed);
        System.out.println("HISTORY complete 6s at 1.5x: " + completed);

        phase = "1.5x loop uses one actual listening session and one qualified entry";
        select(loopTrack); pausePlayback();
        fx(() -> button("open-practice").fire());
        DialogPane pane = fxValue(() -> dialog("practice-start"));
        setRange(pane, "0:00.000", "0:06.000");
        fx(() -> {
            CheckBox enabled = control(pane, "practice-enabled", CheckBox.class);
            if (!enabled.isSelected()) enabled.fire();
            close(pane);
        });
        awaitPosition(20, .55);
        String session = fxValue(this::listeningSessionId);
        check(session != null, "loop listening session did not start");
        long load = fxValue(() -> field("loadVersion", Long.class));
        fx(() -> button("play-button").fire());
        long deadline = System.nanoTime() + 9_500_000_000L;
        double previous = -1; int wraps = 0;
        while (System.nanoTime() < deadline) {
            double current = fxValue(this::relative);
            if (previous - current > 2) wraps++;
            previous = current;
            fx(() -> {
                check(session.equals(listeningSessionId()), "AB wrap started another listening session");
                check(field("loadVersion", Long.class) == load, "AB wrap reloaded media");
            });
            pause(100);
        }
        check(wraps >= 2, "fast six-second loop did not wrap twice");
        pausePlayback();
        await(6000, () -> historyEntry(loopTrack) != null, "fast looping segment never reached service history");
        HistoryObservation looped = historyEntry(loopTrack);
        check(looped.playCount() == 1 && looped.seconds() >= 3.6 && looped.seconds() <= 4.5,
                "loop duplicated qualified play or credited media instead of actual duration: " + looped);
        System.out.println("HISTORY 9.5s AB at 1.5x: " + looped + "; wraps=" + wraps + "; session=" + session);
        fx(() -> {
            button("open-practice").fire(); screenshot(dialog("practice-start").getScene(), "followup-loop-1.5x.png");
            close(dialog("practice-start"));
        });
        select(second);
        fx(() -> {
            check(Math.abs(audio().playbackRate() - 1.5) < .01 && Math.abs(field("playbackRate", Double.class) - 1.5) < .01,
                    "track change did not retain 1.5x");
            check(!loopEnabled() && loopMillis("startMillis") == -1 && loopMillis("endMillis") == -1,
                    "track change retained loop markers");
            app.stop(); stage.hide();
            stage = new Stage(); app = new LumenFxApp(); app.startUi(stage, List.of());
            stage.setWidth(1280); stage.setHeight(800);
            check(Math.abs(field("playbackRate", Double.class) - 1) < .01 && Math.abs(audio().playbackRate() - 1) < .01,
                    "restart restored temporary playback speed");
            check(!loopEnabled() && loopMillis("startMillis") == -1 && loopMillis("endMillis") == -1,
                    "restart restored temporary AB marks");
        });
        pause(800);
        fx(() -> {
            check(audio().state() != JavaFxAudioPlayer.State.PLAYING, "restart automatically played media");
            screenshot(stage.getScene(), "followup-restart-defaults.png");
        });
        check(historyEntry(shortTrack).playCount() == 1 && historyEntry(loopTrack).playCount() == 1,
                "closing/restarting duplicated short-track listening records");
        System.out.println("PASS HTTP history confirms actual fast listening and one AB session; changing tracks retains speed; restart is paused at 1x with empty AB");
    }

    private record HistoryObservation(long playCount, double seconds) { }

    private void endpointEdgeChecks() throws Exception {
        Track tail = Track.segment(source, "Endpoint 500ms acceptance", "Fixture", 10000, 16000);
        library.favorite(tail);
        for (boolean vlc : Boolean.getBoolean("lumen.practice.smoke.endpointVlcOnly")
                ? new boolean[] {true} : new boolean[] {false, true}) {
            phase = (vlc ? "VLC" : "JavaFX") + " terminal 500ms at 1.5x";
            pausePlayback();
            fx(() -> {
                button("sidebar-settings").fire();
                CheckBox normalize = (CheckBox) node("audio-normalization-enabled");
                if (normalize.isSelected() != vlc) normalize.fire();
                queue().select(List.of(tail, second), 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
                invoke("loadCurrent", new Class<?>[] {boolean.class}, true);
            });
            awaitPlaying(tail); pausePlayback();
            fx(() -> {
                check(vlc ? audio() instanceof VlcAudioPlayer : audio() instanceof JavaFxAudioPlayer,
                        "endpoint acceptance used the wrong native backend");
                button("open-practice").fire();
                DialogPane pane = dialog("practice-start");
                @SuppressWarnings("unchecked") ComboBox<Double> rates = control(pane, "practice-rate", ComboBox.class);
                rates.setValue(1.5);
                check(Math.abs(audio().playbackRate() - 1.5) < .01 && Math.abs(field("playbackRate", Double.class) - 1.5) < .01,
                        "terminal loop did not adopt 1.5x");
            });
            DialogPane pane = fxValue(() -> dialog("practice-start"));
            setRange(pane, "0:05.500", "0:06.000");
            fx(() -> {
                CheckBox enabled = control(pane, "practice-enabled", CheckBox.class);
                if (!enabled.isSelected()) enabled.fire();
                close(pane);
            });
            awaitPosition(15.5, .12);
            await(4000, () -> fxValue(() -> field("practiceSeekTarget", Object.class) == null
                    && !field("segmentSeeking", Boolean.class) && field("pendingResumeSeconds", Object.class) == null),
                    "initial endpoint-loop seek did not settle");
            fx(() -> assertNoEarlyEndpointSeek(tail));
            String session = fxValue(this::listeningSessionId);
            check(session != null, "endpoint loop has no listening session");
            long load = fxValue(() -> field("loadVersion", Long.class));
            PlayerQueue.Snapshot queue = fxValue(() -> queue().snapshot());
            EndpointProbe probe = new EndpointProbe();
            fx(() -> probe.observe(audio()));
            fx(() -> button("play-button").fire());
            long deadline = System.nanoTime() + 6_500_000_000L;
            long lastConfirmed = System.nanoTime();
            double previous = -1, maximum = -1;
            int wraps = 0, confirmedReturns = 0, lastConfirmedNativeEnd = 0;
            java.util.ArrayList<String> samples = new java.util.ArrayList<>();
            samples.add("elapsedSeconds,state,sourceSeconds,relativeSeconds,seekTarget,confirmed,durationSeconds,loadVersion,queueIndex,sessionId,nativeEnded,segmentSeeking,pendingResumeSeconds,rate");
            long started = System.nanoTime();
            try {
                while (System.nanoTime() < deadline) {
                    EndpointSample sample = fxValue(() -> {
                        probe.observe(audio());
                        check(tail.id().equals(field("loadedTrack", Track.class).id()) && queue().snapshot().equals(queue),
                                "terminal loop advanced or changed the queue");
                        check(field("loadVersion", Long.class) == load, "terminal loop reloaded the track");
                        check(session.equals(listeningSessionId()), "terminal loop restarted the listening session");
                        check(loopEnabled(), "terminal 500ms loop disabled itself");
                        JavaFxAudioPlayer.State state = audio().state();
                        Long target = field("practiceSeekTarget", Long.class);
                        double raw = physical();
                        boolean confirmed = state == JavaFxAudioPlayer.State.PLAYING && target == null
                                && !field("segmentSeeking", Boolean.class) && field("pendingResumeSeconds", Object.class) == null;
                        int nativeEnds = probe.ends.get();
                        String evidence = String.format(java.util.Locale.ROOT, "%.3f,%d,%d,%s,%d,%s,%s,%.2f",
                                audio().duration().toSeconds(), field("loadVersion", Long.class), queue().index(), listeningSessionId(),
                                nativeEnds, field("segmentSeeking", Boolean.class), field("pendingResumeSeconds", Object.class),
                                audio().playbackRate());
                        return new EndpointSample(state, raw, relative(), target, confirmed, evidence, nativeEnds);
                    });
                    samples.add(String.format(java.util.Locale.ROOT, "%.3f,%s,%.3f,%.3f,%s,%s,%s",
                            (System.nanoTime() - started) / 1_000_000_000.0, sample.state(), sample.sourceSeconds(),
                            sample.position(), sample.target(), sample.confirmed(), sample.evidence()));
                    double position = sample.position();
                    if (sample.confirmed()) {
                        lastConfirmed = System.nanoTime();
                        check(position >= 5.38 && position <= 6.02, "terminal loop escaped 5.5..6s range: " + position);
                        maximum = Math.max(maximum, position);
                        if (previous - position > .15) wraps++;
                        if (sample.nativeEnded() > lastConfirmedNativeEnd && position >= 5.5 && position < 6.0) {
                            confirmedReturns++;
                            lastConfirmedNativeEnd = sample.nativeEnded();
                        }
                        previous = position;
                    } else {
                        check(System.nanoTime() - lastConfirmed < 5_000_000_000L,
                                "terminal loop did not return to confirmed playable media within five seconds");
                    }
                    pause(20);
                }
            } finally {
                Files.write(root.resolve("endpoint-500ms-" + (vlc ? "vlc" : "javafx") + "-samples.csv"), samples);
            }
            System.out.println("ENDPOINT " + (vlc ? "VLC" : "JavaFX") + " wraps=" + wraps + ", maximum=" + maximum
                    + ", samples=" + (samples.size() - 1) + ", nativeEnded=" + probe.ends.get() + ", confirmedReturns=" + confirmedReturns);
            check(confirmedReturns >= 3, "terminal 500ms loop did not return to stable legal playback after three native ends: " + confirmedReturns);
            check(maximum >= 5.85, "terminal loop truncated the last 150ms: maximum=" + maximum);
            fx(() -> {
                button("open-practice").fire();
                screenshot(dialog("practice-start").getScene(), "endpoint-500ms-" + (vlc ? "vlc" : "javafx") + ".png");
                control(dialog("practice-start"), "practice-clear", Button.class).fire();
                close(dialog("practice-start"));
            });
            awaitPlaying(second);
            fx(() -> check(field("loadVersion", Long.class) == load + 1 && queue().index() == 1,
                    "clearing terminal loop did not advance exactly once"));
            System.out.println("PASS " + (vlc ? "VLC" : "JavaFX") + " B-150ms does not seek; terminal 500ms at 1.5x wraps="
                    + confirmedReturns + " confirmed returns, positionDrops=" + wraps + ", maximum=" + maximum
                    + "; queue/load/session preserved; clearing advances once");
        }
    }

    private record EndpointSample(JavaFxAudioPlayer.State state, double sourceSeconds, double position,
                                  Long target, boolean confirmed, String evidence, int nativeEnded) { }

    private static final class EndpointProbe {
        private Object observedPlayer;
        private final java.util.concurrent.atomic.AtomicInteger ends = new java.util.concurrent.atomic.AtomicInteger();
        private void observe(AudioPlayer audio) throws ReflectiveOperationException {
            Field field = audio.getClass().getDeclaredField("player"); field.setAccessible(true);
            Object player = field.get(audio);
            if (player == null || player == observedPlayer) return;
            observedPlayer = player;
            if (player instanceof uk.co.caprica.vlcj.player.base.MediaPlayer nativePlayer) {
                nativePlayer.events().addMediaPlayerEventListener(new uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
                    @Override public void finished(uk.co.caprica.vlcj.player.base.MediaPlayer ignored) { ends.incrementAndGet(); }
                });
            } else if (player instanceof javafx.scene.media.MediaPlayer nativePlayer) {
                Runnable existing = nativePlayer.getOnEndOfMedia();
                nativePlayer.setOnEndOfMedia(() -> { ends.incrementAndGet(); if (existing != null) existing.run(); });
            }
        }
    }

    private void assertNoEarlyEndpointSeek(Track tail) throws Exception {
        AudioPlayer nativePlayer = audio();
        java.util.concurrent.atomic.AtomicInteger seeks = new java.util.concurrent.atomic.AtomicInteger();
        AudioPlayer probe = (AudioPlayer) Proxy.newProxyInstance(AudioPlayer.class.getClassLoader(),
                new Class<?>[] {AudioPlayer.class}, (ignored, method, args) -> {
                    if ("position".equals(method.getName()))
                        return javafx.util.Duration.millis(tail.segmentStartMillis() + 5850);
                    if ("state".equals(method.getName())) return JavaFxAudioPlayer.State.PLAYING;
                    if ("seekFraction".equals(method.getName())) { seeks.incrementAndGet(); return null; }
                    return method.invoke(nativePlayer, args);
                });
        setField("audio", probe);
        try {
            check(!(boolean) invoke("updatePracticeLoop", new Class<?>[0]), "B-150ms initiated an early loop seek");
            check(seeks.get() == 0 && field("practiceSeekTarget", Object.class) == null,
                    "B-150ms changed the pending loop seek");
            System.out.println("PASS deterministic B-150ms has no loop seek at 1.5x");
        } finally { setField("audio", nativePlayer); }
    }

    private final HttpClient historyHttp = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    private HistoryObservation historyEntry(Track track) throws Exception {
        URI endpoint = URI.create(required("LUMEN_SERVICE_URL")).resolve("/api/history");
        var response = historyHttp.send(HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        check(response.statusCode() == 200, "history HTTP " + response.statusCode());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element history = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        check("history".equals(history.getTagName()), "history response format");
        var entries = history.getElementsByTagName("entry");
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            Element stored = (Element) entry.getElementsByTagName("track").item(0);
            if (track.id().equals(stored.getAttribute("id")))
                return new HistoryObservation(Long.parseLong(entry.getAttribute("playCount")),
                        Double.parseDouble(entry.getAttribute("totalQualifiedSeconds")));
        }
        return null;
    }

    private String listeningSessionId() {
        try {
            Object reporter = field("listening", ListeningReporter.class);
            Field current = ListeningReporter.class.getDeclaredField("current"); current.setAccessible(true);
            Object session = current.get(reporter);
            if (session == null) return null;
            Method id = session.getClass().getDeclaredMethod("id"); id.setAccessible(true);
            return (String) id.invoke(session);
        } catch (ReflectiveOperationException error) { throw new AssertionError("listening session", error); }
    }

    @SuppressWarnings("unchecked") private ListView<String> lyricList(String id) { return (ListView<String>) node(id); }

    private void lyricChecks() throws Exception {
        phase = "paused calibrated lyric click";
        pausePlayback();
        fx(() -> { button("open-immersive").fire(); layout(); });
        clickLyric(1, false, false, MouseButton.PRIMARY);
        awaitPosition(7.75, .55);
        fx(() -> check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "lyric click resumed paused playback"));
        double before = fxValue(this::physical);
        clickLyric(2, true, false, MouseButton.PRIMARY);
        clickLyric(0, false, true, MouseButton.PRIMARY);
        clickLyric(2, false, false, MouseButton.SECONDARY);
        pause(450);
        fx(() -> check(Math.abs(physical() - before) < .25, "selection/context gesture sought playback"));
        select(segment);
        pausePlayback();
        clickLyric(1, false, false, MouseButton.PRIMARY);
        awaitPosition(12.5, .55);
        fx(() -> {
            check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "segment lyric click resumed playback");
            check(Math.abs(relative() - 4.5) < .55, "segment lyric target was not relative to segment start");
            button("immersive-collapse").fire();
        });
        System.out.println("PASS paused lyric seek inverts start/offset calibration; segment clock and selection gestures");
    }

    private void loopChecks() throws Exception {
        phase = "mark A/B and actual looping";
        select(source); pausePlayback();
        seekRelative(2);
        fx(() -> button("open-practice").fire());
        DialogPane pane = fxValue(() -> dialog("practice-start"));
        fx(() -> control(pane, "practice-mark-a", Button.class).fire());
        seekRelative(3.8);
        fx(() -> control(pane, "practice-mark-b", Button.class).fire());
        fx(() -> check(Math.abs(loopMillis("startMillis") - 2000) < 550
                && Math.abs(loopMillis("endMillis") - 3800) < 550, "mark buttons did not capture relative positions"));
        setRange(pane, "0:02.000", "0:03.800");
        setRange(pane, "0:02.000", "0:02.200");
        fx(() -> check(loopMillis("startMillis") == 2000 && loopMillis("endMillis") == 3800,
                "sub-500ms input replaced the valid loop range"));
        setRange(pane, "0:04.000", "0:03.000");
        fx(() -> check(loopMillis("startMillis") == 2000 && loopMillis("endMillis") == 3800,
                "reversed input replaced the valid loop range"));
        setRange(pane, "0:02.000", "0:03.800");
        fx(() -> {
            CheckBox enabled = control(pane, "practice-enabled", CheckBox.class);
            if (!enabled.isSelected()) enabled.fire();
            close(pane); button("play-button").fire();
        });
        int resets = observeLoop(6500, 2, 3.8);
        check(resets >= 2, "real AB playback did not repeat twice: " + resets);
        fx(() -> {
            button("open-practice").fire();
            control(dialog("practice-start"), "practice-clear", Button.class).fire();
            check(!loopEnabled() && loopMillis("startMillis") == -1 && loopMillis("endMillis") == -1,
                    "clear left loop markers enabled");
            close(dialog("practice-start"));
        });
        await(4000, () -> fxValue(() -> relative() > 4.3), "cleared loop kept repeating");
        pausePlayback();
        fx(() -> button("open-practice").fire());
        DialogPane again = fxValue(() -> dialog("practice-start"));
        setRange(again, "0:02.000", "0:03.800");
        fx(() -> close(again));
        select(second);
        fx(() -> check(!loopEnabled() && loopMillis("startMillis") == -1 && loopMillis("endMillis") == -1,
                "changing tracks retained AB markers"));
        select(segment); pausePlayback();
        fx(() -> button("open-practice").fire());
        DialogPane offset = fxValue(() -> dialog("practice-start"));
        setRange(offset, "0:01.000", "0:02.800");
        fx(() -> {
            CheckBox enabled = control(offset, "practice-enabled", CheckBox.class);
            if (!enabled.isSelected()) enabled.fire();
            close(offset);
        });
        seekRelative(1);
        fx(() -> button("play-button").fire());
        check(observeLoop(4900, 1, 2.8) >= 2, "offset segment AB did not loop twice");
        fx(() -> invoke("clearPracticeLoop", new Class<?>[0]));
        System.out.println("PASS actual AB repeats on full/offset tracks; clear and changing tracks reset markers");
    }

    private void diaryChecks() throws Exception {
        phase = "diary favorite plays at saved relative position";
        select(second);
        fx(() -> button("nav-diary").fire());
        await(8000, () -> fxValue(() -> ((Label) node("diary-feedback")).getText().equals("已更新")),
                "diary did not load");
        fx(() -> {
            field("diaryPane", DiaryPane.class).showLyricsTab(); layout();
            Parent row = (Parent) stage.getScene().getRoot().lookupAll("#diary-lyric-row").stream()
                    .findFirst().orElseThrow(() -> new AssertionError("favorite row missing"));
            row.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getTooltip() != null && "从这句播放".equals(button.getTooltip().getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("favorite play button missing")).fire();
        });
        awaitPlaying(segment);
        await(5000, () -> fxValue(() -> relative() >= 5.7 && relative() < 8.5), "favorite did not seek to saved 6s position");
        fx(() -> check(physical() >= 13.7 && physical() < 16.5, "favorite lost segment source offset"));
        System.out.println("PASS actual diary favorite UI resumes at saved segment-relative position");
    }

    private void saveChecks() throws Exception {
        phase = "save loop as a new source-relative segment";
        pausePlayback();
        fx(() -> { button("nav-playlists").fire(); button("open-practice").fire(); });
        DialogPane pane = fxValue(() -> dialog("practice-start"));
        setRange(pane, "0:01.000", "0:02.500");
        fx(() -> control(pane, "practice-save-segment", Button.class).fire());
        DialogPane editor = fxValue(() -> dialog("segment-start"));
        fx(() -> {
            check(LyricTiming.parseStart(control(editor, "segment-start", TextField.class).getText()) == 9000
                    && LyricTiming.parseStart(control(editor, "segment-end", TextField.class).getText()) == 10500,
                    "save did not convert relative loop to source range");
            control(editor, "segment-title", TextField.class).setText("Saved practice excerpt");
            @SuppressWarnings("unchecked") ComboBox<Object> destination = control(editor, "segment-destination", ComboBox.class);
            destination.getSelectionModel().selectFirst();
            control(editor, "segment-save", Button.class).fire();
        });
        await(8000, () -> library.load().favorites().stream()
                .anyMatch(track -> "Saved practice excerpt".equals(track.title())), "loop excerpt was not saved to service");
        var saved = library.load().favorites().stream().filter(track -> "Saved practice excerpt".equals(track.title()))
                .findFirst().orElseThrow();
        check(!saved.id().equals(segment.id()) && source.id().equals(saved.sourceTrackId())
                && saved.segmentStartMillis() == 9000 && saved.segmentEndMillis() == 10500,
                "loop save changed existing segment or lost original source range");
        var original = library.load().playlists().stream().filter(item -> playlistId.equals(item.id()))
                .findFirst().orElseThrow().tracks().get(1);
        check(original.equals(segment), "saving practice edited the original segment");
        fx(() -> { if (findDialog("practice-start") != null) close(dialog("practice-start")); });
        System.out.println("PASS loop save creates a new excerpt and preserves the existing offset segment");
    }

    private void rateChecks(boolean vlc) throws Exception {
        phase = (vlc ? "VLC" : "JavaFX") + " real playback rate";
        fx(() -> {
            button("sidebar-settings").fire();
            CheckBox normalize = (CheckBox) node("audio-normalization-enabled");
            if (normalize.isSelected() != vlc) normalize.fire();
        });
        select(source); pausePlayback();
        check(fxValue(() -> vlc ? audio() instanceof VlcAudioPlayer : audio() instanceof JavaFxAudioPlayer),
                "wrong actual audio backend for rate acceptance");
        fx(() -> button("open-practice").fire());
        DialogPane pane = fxValue(() -> dialog("practice-start"));
        for (double rate : new double[] {.75, 1.0, 1.25, 1.5}) {
            fx(() -> {
                @SuppressWarnings("unchecked") ComboBox<Double> rates = control(pane, "practice-rate", ComboBox.class);
                check(rates.getItems().contains(rate), "missing speed choice " + rate);
                rates.setValue(rate);
                check(audio().state() == JavaFxAudioPlayer.State.PAUSED, "rate change resumed paused media");
                check(Math.abs(audio().playbackRate() - rate) < .01, "backend rejected rate " + rate);
            });
            seekRelative(10);
            fx(() -> button("play-button").fire());
            await(3000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PLAYING), "rate playback did not resume");
            pause(400);
            double before = fxValue(this::physical);
            long start = System.nanoTime(); pause(3000);
            double after = fxValue(this::physical);
            double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
            double measured = (after - before) / elapsed;
            check(Math.abs(measured - rate) < .23, "actual media advancement differs from speed " + rate + ": " + measured);
            System.out.println("RATE " + (vlc ? "VLC" : "JavaFX") + " requested=" + rate + " measured=" + measured);
            pausePlayback();
        }
        fx(() -> {
            @SuppressWarnings("unchecked") ComboBox<Double> rates = control(pane, "practice-rate", ComboBox.class);
            rates.setValue(1.0); close(pane);
        });
        System.out.println("PASS " + (vlc ? "VLC" : "JavaFX") + " actual .75x/1x/1.25x/1.5x advancement and paused speed changes");
    }

    private void vlcEndLoopChecks() throws Exception {
        phase = "VLC AB at the segment end precedes queue advance";
        fx(() -> {
            queue().select(List.of(segment, second), 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true);
        });
        awaitPlaying(segment); pausePlayback();
        check(fxValue(() -> audio() instanceof VlcAudioPlayer), "endpoint loop did not use real VLC");
        fx(() -> button("open-practice").fire());
        DialogPane pane = fxValue(() -> dialog("practice-start"));
        setRange(pane, "0:18.000", "0:20.000");
        fx(() -> {
            CheckBox enabled = control(pane, "practice-enabled", CheckBox.class);
            if (!enabled.isSelected()) enabled.fire();
            close(pane);
        });
        awaitPosition(26, .55);
        long load = fxValue(() -> field("loadVersion", Long.class));
        fx(() -> button("play-button").fire());
        check(observeLoop(6800, 18, 20) >= 2, "VLC endpoint AB did not repeat twice");
        fx(() -> {
            check(queue().index() == 0 && segment.id().equals(field("loadedTrack", Track.class).id())
                    && field("loadVersion", Long.class) == load, "VLC endpoint AB advanced or reloaded the queue");
            invoke("clearPracticeLoop", new Class<?>[0]);
        });
        awaitPlaying(second);
        System.out.println("PASS real VLC AB at segment end wins over queue advance; clearing then advances normally");
    }

    private void themeMatrix() throws Exception {
        for (ThemeController.Mode mode : ThemeController.Mode.values()) {
            fx(() -> {
                for (int i = 0; i < ThemeController.Mode.values().length && field("themeMode", ThemeController.Mode.class) != mode; i++)
                    button("theme-button").fire();
                check(field("themeMode", ThemeController.Mode.class) == mode, "theme did not change");
            });
            for (int[] size : new int[][] {{860, 600}, {1280, 800}}) {
                fx(() -> {
                    stage.setWidth(size[0]); stage.setHeight(size[1]);
                    button("nav-playlists").fire(); button("open-practice").fire();
                });
                pause(400);
                fx(() -> {
                    layout(); fits(node("open-practice"));
                    DialogPane pane = dialog("practice-start");
                    String theme = mode.name().toLowerCase() + "-theme";
                    check(pane.getStyleClass().contains(theme) || pane.getScene().getRoot().getStyleClass().contains(theme),
                            "practice dialog did not inherit theme " + mode);
                    for (String id : List.of("practice-start", "practice-end", "practice-mark-a", "practice-mark-b",
                            "practice-enabled", "practice-clear", "practice-rate", "practice-save-segment"))
                        fits(control(pane, id, Node.class));
                    screenshot(pane.getScene(), mode.name().toLowerCase() + "-practice-" + size[0] + "x" + size[1] + ".png");
                    close(pane); button("open-immersive").fire();
                });
                pause(450);
                fx(() -> {
                    layout(); fits(node("immersive-practice")); fits(node("playback-bar"));
                    screenshot(stage.getScene(), mode.name().toLowerCase() + "-immersive-" + size[0] + "x" + size[1] + ".png");
                    button("immersive-practice").fire();
                    check(findDialog("practice-start") != null, "immersive practice button did not open controls");
                    close(dialog("practice-start")); button("immersive-collapse").fire();
                });
            }
        }
    }

    private int observeLoop(long millis, double start, double end) throws Exception {
        int resets = 0; double prior = -1;
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline) {
            double position = fxValue(this::relative);
            check(position >= start - .65 && position <= end + .7, "loop escaped range: " + position);
            if (prior - position > .75) resets++;
            prior = position; pause(90);
        }
        return resets;
    }

    private void setRange(DialogPane pane, String start, String end) throws Exception {
        fx(() -> {
            TextField a = control(pane, "practice-start", TextField.class);
            TextField b = control(pane, "practice-end", TextField.class);
            a.setText(start); b.setText(end);
            a.fireEvent(new ActionEvent()); b.fireEvent(new ActionEvent());
        });
    }

    private void clickLyric(int index, boolean ctrl, boolean shift, MouseButton button) throws Exception {
        fx(() -> {
            @SuppressWarnings("unchecked") ListView<String> list = (ListView<String>) node("immersive-lyrics");
            int row = index + field("immersiveLyricPadding", Integer.class);
            list.scrollTo(row); list.applyCss(); list.layout();
            ListCell<?> cell = list.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast).filter(item -> item.getIndex() == row && !item.isEmpty())
                    .findFirst().orElseThrow(() -> new AssertionError("lyric row not rendered: " + row));
            cell.fireEvent(click(1, ctrl, shift, button));
        });
    }

    private static MouseEvent click(int count, boolean ctrl, boolean shift, MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 8, 8, 8, 8, button, count,
                shift, ctrl, false, false, button == MouseButton.PRIMARY, false,
                button == MouseButton.SECONDARY, true, button == MouseButton.SECONDARY, true, null);
    }

    private void select(Track track) throws Exception {
        fx(() -> {
            queue().select(List.of(track), 0); queue().setRepeatMode(PlayerQueue.RepeatMode.OFF);
            invoke("loadCurrent", new Class<?>[] {boolean.class}, true);
        });
        awaitPlaying(track);
    }
    private void pausePlayback() throws Exception {
        fx(() -> { if (audio().state() == JavaFxAudioPlayer.State.PLAYING) button("play-button").fire(); });
        await(3000, () -> fxValue(() -> audio().state() == JavaFxAudioPlayer.State.PAUSED), "media did not pause");
    }
    private void seekRelative(double position) throws Exception {
        fx(() -> invoke("seekToFraction", new Class<?>[] {double.class}, position / duration()));
        Track track = fxValue(() -> field("loadedTrack", Track.class));
        awaitPosition(position + (track.isSegment() ? track.segmentStartMillis() / 1000.0 : 0), .55);
    }
    private void awaitPosition(double seconds, double tolerance) throws Exception {
        await(6000, () -> fxValue(() -> Math.abs(physical() - seconds) < tolerance), "media did not seek to " + seconds);
    }
    private void awaitPlaying(Track track) throws Exception {
        await(15000, () -> fxValue(() -> track.id().equals(field("loadedTrack", Track.class).id())
                && audio().state() == JavaFxAudioPlayer.State.PLAYING && relative() > .1), "media did not play " + track.id());
    }
    private long loopMillis(String name) {
        Object loop = field("practiceLoop", Object.class);
        try {
            Method method = loop.getClass().getDeclaredMethod(name); method.setAccessible(true);
            return (long) method.invoke(loop);
        }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private boolean loopEnabled() {
        Object loop = field("practiceLoop", Object.class);
        try {
            Method method = loop.getClass().getDeclaredMethod("enabled"); method.setAccessible(true);
            return (boolean) method.invoke(loop);
        }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private double physical() { return audio().position().toSeconds(); }
    private double relative() { return ((javafx.util.Duration) invoke("playbackPosition", new Class<?>[0])).toSeconds(); }
    private double duration() { return ((javafx.util.Duration) invoke("playbackDuration", new Class<?>[0])).toSeconds(); }
    private AudioPlayer audio() { return field("audio", AudioPlayer.class); }
    private PlayerQueue queue() { return field("queue", PlayerQueue.class); }
    private Node node(String id) { Node n = stage.getScene().lookup("#" + id); check(n != null, "missing UI #" + id); return n; }
    private Button button(String id) { return (Button) node(id); }
    @SuppressWarnings("unchecked") private ListView<Track> list(String id) { return (ListView<Track>) node(id); }
    @SuppressWarnings("unchecked") private ComboBox<LibraryClient.Playlist> picker() { return (ComboBox<LibraryClient.Playlist>) node("playlist-picker"); }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private DialogPane findDialog(String id) {
        return Window.getWindows().stream().filter(Window::isShowing).filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                .filter(pane -> pane.lookup("#" + id) != null).findFirst().orElse(null);
    }
    private DialogPane dialog(String id) { DialogPane pane = findDialog(id); check(pane != null, "missing dialog #" + id); return pane; }
    private static <T> T control(Parent pane, String id, Class<T> type) {
        Node node = pane.lookup("#" + id); check(node != null, "missing control #" + id); return type.cast(node);
    }
    private void close(DialogPane pane) {
        Button close = (Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE);
        check(close != null, "dialog close button missing"); close.fire();
    }
    private void fits(Node node) {
        check(node.isVisible() && node.isManaged(), "hidden/unmanaged " + node.getId());
        Scene scene = node.getScene(); scene.getRoot().applyCss(); scene.getRoot().layout();
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        check(bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1
                && bounds.getMinY() >= -1 && bounds.getMaxY() <= scene.getHeight() + 1,
                "overflow #" + node.getId() + ": " + bounds);
    }
    private void screenshot(Scene scene, String name) {
        try {
            WritableImage shot = scene.snapshot(null);
            BufferedImage png = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
                png.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Path output = root.resolve(name);
            check(ImageIO.write(png, "png", output.toFile()) && Files.size(output) > 1000, "screenshot not saved");
            System.out.println("SCREENSHOT: " + output);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try { Method m = LumenFxApp.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(app, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private <T> T field(String name, Class<T> type) {
        try { Field f = LumenFxApp.class.getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(app)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private void setField(String name, Object value) throws ReflectiveOperationException {
        Field field = LumenFxApp.class.getDeclaredField(name); field.setAccessible(true); field.set(app, value);
    }
    private static String required(String key) { String v = System.getenv(key); check(v != null && !v.isBlank(), "missing " + key); return v; }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private void await(long millis, CheckedBoolean condition, String message) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < end) { if (condition.get()) return; pause(70); }
        throw new AssertionError(message + "; phase=" + phase);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        try { fx(() -> { if (app != null) app.stop(); for (Window window : List.copyOf(Window.getWindows())) window.hide(); }); }
        catch (Exception error) { error.printStackTrace(System.err); }
        System.exit(code);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
