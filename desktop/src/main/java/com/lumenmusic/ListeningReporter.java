package com.lumenmusic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Sends ordered playback observations without blocking the JavaFX application thread. */
public final class ListeningReporter implements AutoCloseable {
    public record Context(String playlistId, String playlistName) {
        public Context {
            if (playlistId == null || playlistId.isBlank() || playlistName == null || playlistName.isBlank())
                throw new IllegalArgumentException("Playlist identity and name are required");
        }
    }
    public record QualifiedPlay(Track track, boolean revisitedOldFavorite) { }
    private record Session(String id, Track track, Context context, AtomicBoolean notified, long startedNanos) { }

    private final URI endpoint;
    private final boolean localEndpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lumen-listening");
        thread.setDaemon(true);
        return thread;
    });
    private Session current;
    private double playbackRate = 1.0;
    private double lastPositionSeconds;
    private volatile Consumer<QualifiedPlay> onQualified = ignored -> { };

    public ListeningReporter(URI base) {
        endpoint = base.resolve("/api/listening");
        localEndpoint = "127.0.0.1".equalsIgnoreCase(endpoint.getHost())
                || "localhost".equalsIgnoreCase(endpoint.getHost())
                || "[::1]".equalsIgnoreCase(endpoint.getHost()) || "::1".equalsIgnoreCase(endpoint.getHost());
    }

    public void setOnQualified(Consumer<QualifiedPlay> listener) {
        onQualified = java.util.Objects.requireNonNull(listener);
    }

    public boolean start(Track track, int actualDurationSeconds, double positionSeconds) {
        return start(track, actualDurationSeconds, positionSeconds, null);
    }

    public boolean start(Track track, int actualDurationSeconds, double positionSeconds, Context context) {
        return start(track, actualDurationSeconds, positionSeconds, context, 1.0);
    }

    public boolean start(Track track, int actualDurationSeconds, double positionSeconds,
                         Context context, double rate) {
        requireRate(rate);
        if (current != null) finish(lastPositionSeconds);
        int duration = track.durationSeconds() > 0 ? track.durationSeconds() : actualDurationSeconds;
        if (duration <= 0 || !localEndpoint) return false;
        Track observed = new Track(track.id(), track.title(), track.artist(), duration, track.source(),
                track.mediaUri(), track.bvid(), track.page(), track.coverUri(), track.partCount(),
                track.viewCount(), track.sourceTrackId(), track.segmentStartMillis(),
                track.segmentEndMillis(), track.sourceDurationSeconds());
        current = new Session(UUID.randomUUID().toString(), observed, context, new AtomicBoolean(), System.nanoTime());
        observe(positionSeconds, true, false, rate);
        return true;
    }

    public void observe(double positionSeconds, boolean playing, boolean seeked) {
        observe(positionSeconds, playing, seeked, playbackRate);
    }

    public void observe(double positionSeconds, boolean playing, boolean seeked, double rate) {
        requireRate(rate);
        if (!Double.isFinite(positionSeconds) || positionSeconds < 0) return;
        Session session = current;
        if (session != null) {
            playbackRate = rate;
            lastPositionSeconds = positionSeconds;
            enqueue(session, positionSeconds, playing, seeked, false, rate);
        }
    }

    public void finish(double positionSeconds) {
        Session session = current;
        current = null;
        if (session != null) enqueue(session, positionSeconds, false, false, true, playbackRate);
    }

    private static void requireRate(double rate) {
        if (!Double.isFinite(rate) || rate <= 0)
            throw new IllegalArgumentException("Playback rate must be finite and positive");
    }

    private void enqueue(Session session, double positionSeconds, boolean playing,
                         boolean seeked, boolean finished, double rate) {
        if (!Double.isFinite(positionSeconds) || positionSeconds < 0) return;
        double elapsedSeconds = Math.max(0, (System.nanoTime() - session.startedNanos()) / 1_000_000_000.0);
        String body = "<sample sessionId=\"" + session.id() + "\" positionSeconds=\""
                + positionSeconds + "\" playing=\"" + playing + "\" seeked=\"" + seeked
                + "\" finished=\"" + finished
                + "\" playbackRate=\"" + rate
                + "\" elapsedSeconds=\"" + elapsedSeconds
                + (session.context() == null ? "" : "\" playlistId=\"" + xml(session.context().playlistId())
                        + "\" playlistName=\"" + xml(session.context().playlistName()))
                + "\"><track id=\"" + xml(session.track().id())
                + "\" title=\"" + xml(session.track().title()) + "\" artist=\""
                + xml(session.track().artist()) + "\" durationSeconds=\""
                + session.track().durationSeconds() + "\" source=\"" + xml(session.track().source())
                + "\" bvid=\"" + xml(session.track().bvid()) + "\" page=\""
                + session.track().page() + "\" mediaUri=\""
                + xml(session.track().mediaUri() == null ? null : session.track().mediaUri().toString())
                + "\" coverUri=\""
                + xml(session.track().coverUri() == null ? null : session.track().coverUri().toString())
                + "\" partCount=\"" + (session.track().partCount() < 1 ? "" : session.track().partCount())
                + (localEndpoint && session.track().isSegment() ? "\" sourceTrackId=\""
                        + xml(session.track().sourceTrackId()) + "\" segmentStartMillis=\""
                        + session.track().segmentStartMillis() + "\" segmentEndMillis=\""
                        + session.track().segmentEndMillis() + "\" sourceDurationSeconds=\""
                        + session.track().sourceDurationSeconds() : "")
                + "\"/></sample>";
        writer.submit(() -> {
            try {
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/xml; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200)
                    System.err.println("听歌记录请求失败：HTTP " + response.statusCode());
                else {
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    var result = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()))
                            .getDocumentElement();
                    if ("listening".equals(result.getTagName()) && "true".equals(result.getAttribute("justRecorded"))
                            && session.notified().compareAndSet(false, true))
                        onQualified.accept(new QualifiedPlay(session.track(),
                                "true".equals(result.getAttribute("revisitOldFavorite"))));
                }
            } catch (Exception error) {
                System.err.println("听歌记录暂不可用：" + error.getMessage());
            }
        });
    }

    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException error) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
