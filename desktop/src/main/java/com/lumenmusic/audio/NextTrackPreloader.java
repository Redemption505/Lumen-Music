package com.lumenmusic.audio;

import com.lumenmusic.MusicClient;
import com.lumenmusic.MusicClient.DownloadCancellation;
import com.lumenmusic.Track;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One cancellable speculative source. It never starts playback or stores an unplayed download. */
public final class NextTrackPreloader implements AutoCloseable {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final long READY_NANOS = Duration.ofMinutes(2).toNanos();
    private final MusicClient client;
    private final OnlineAudioCache session;
    private final PersistentAudioCache persistent;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumen-next-audio");
        thread.setDaemon(true);
        return thread;
    });
    private Preparation current;
    private boolean closed;

    private static final class Preparation {
        final Track source;
        final boolean streaming;
        final boolean cacheEnabled;
        final long cacheGeneration;
        final long maxBytes;
        final DownloadCancellation cancellation = new DownloadCancellation();
        Future<?> task;
        URI ready;
        Path owned;
        long readyAt;
        long readySize;
        Preparation(Track source, boolean streaming, PersistentAudioCache cache) {
            this.source = source;
            this.streaming = streaming;
            synchronized (cache == null ? this : cache) {
                cacheEnabled = cache != null && cache.enabled();
                cacheGeneration = cache == null ? 0 : cache.generation();
                maxBytes = streaming && cacheEnabled ? Math.min(MAX_BYTES, cache.maxBytes()) : MAX_BYTES;
            }
        }
    }

    public NextTrackPreloader(MusicClient client, OnlineAudioCache session, PersistentAudioCache persistent) {
        this.client = Objects.requireNonNull(client);
        this.session = Objects.requireNonNull(session);
        this.persistent = persistent;
    }

    public synchronized void request(Track track, boolean streaming) {
        if (closed) return;
        if (!eligible(track)) { cancel(); return; }
        Track source = track.audioSource();
        if (matches(current, source, streaming) && settingsMatch(current)
                && (current.ready == null || valid(current))) return;
        cancel();
        Preparation preparation = new Preparation(source, streaming, persistent);
        current = preparation;
        preparation.task = worker.submit(() -> prepare(preparation));
    }

    private void prepare(Preparation preparation) {
        Path detached = null;
        try {
            preparation.cancellation.check();
            URI uri = null;
            if (persistent != null) {
                try { uri = persistent.peek(preparation.source).orElse(null); }
                catch (IOException ignored) { }
            }
            if (uri != null && !preparation.streaming) {
                URI existingSession = session.lookup(preparation.source);
                if (existingSession != null) uri = existingSession;
                else {
                    detached = session.prepareCached(uri, preparation.cancellation);
                    uri = detached.toUri();
                }
            }
            if (uri == null) uri = session.lookup(preparation.source);
            if (uri == null) {
                if (preparation.streaming && !preparation.cacheEnabled)
                    uri = client.checkStream(preparation.source, preparation.cancellation);
                else {
                    detached = session.prepare(preparation.source, preparation.cancellation, preparation.maxBytes);
                    uri = detached.toUri();
                }
            }
            long size = "file".equalsIgnoreCase(uri.getScheme()) ? Files.size(Path.of(uri)) : 0;
            synchronized (this) {
                preparation.cancellation.check();
                if (closed || current != preparation || !settingsMatch(preparation)) return;
                preparation.owned = detached;
                preparation.ready = uri;
                preparation.readyAt = System.nanoTime();
                preparation.readySize = size;
                detached = null;
            }
        } catch (Exception ignored) {
            // Speculation never changes foreground failure state; normal playback can still retry.
        } finally {
            delete(detached);
        }
    }

    /** Observation does not consume a ready result or cancel an in-flight request. */
    public synchronized boolean isReady(Track track, boolean streaming) {
        return eligible(track) && matches(current, track.audioSource(), streaming) && valid(current);
    }

    /** Nonblocking handoff. Any unfinished or obsolete preparation is cancelled before foreground work. */
    public synchronized URI take(Track track, boolean streaming) {
        Preparation preparation = current;
        if (!eligible(track) || !matches(preparation, track.audioSource(), streaming) || !valid(preparation)) {
            cancel();
            return null;
        }
        try {
            URI uri = preparation.owned == null ? preparation.ready
                    : session.adoptPrepared(track, preparation.owned);
            preparation.owned = null;
            current = null;
            return uri;
        } catch (IOException ignored) {
            cancel();
            return null;
        }
    }

    public synchronized void cancel() {
        Preparation preparation = current;
        current = null;
        if (preparation == null) return;
        preparation.cancellation.cancel();
        if (preparation.task != null) preparation.task.cancel(true);
        delete(preparation.owned);
        preparation.owned = null;
    }

    private boolean settingsMatch(Preparation preparation) {
        if (persistent == null) return true;
        synchronized (persistent) {
            return preparation.cacheEnabled == persistent.enabled()
                    && preparation.cacheGeneration == persistent.generation()
                    && (!preparation.streaming || !preparation.cacheEnabled
                        || preparation.maxBytes == Math.min(MAX_BYTES, persistent.maxBytes()));
        }
    }

    private boolean valid(Preparation preparation) {
        if (preparation == null || preparation.ready == null || !settingsMatch(preparation)
                || System.nanoTime() - preparation.readyAt > READY_NANOS) return false;
        if (!"file".equalsIgnoreCase(preparation.ready.getScheme())) return true;
        try {
            Path path = Path.of(preparation.ready);
            return Files.isRegularFile(path) && preparation.readySize > 0
                    && Files.size(path) == preparation.readySize
                    && (preparation.owned == null || Files.size(path) <= preparation.maxBytes);
        } catch (IOException error) { return false; }
    }

    private static boolean matches(Preparation preparation, Track source, boolean streaming) {
        return preparation != null && preparation.streaming == streaming
                && preparation.source.id().equals(source.id())
                && Objects.equals(preparation.source.bvid(), source.bvid())
                && preparation.source.page() == source.page();
    }

    private static boolean eligible(Track track) {
        return track != null && track.mediaUri() == null && "bilibili".equals(track.source())
                && track.bvid() != null && track.page() > 0;
    }

    private static void delete(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) { path.toFile().deleteOnExit(); }
    }

    @Override public synchronized void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
