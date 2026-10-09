package com.lumenmusic.audio;

import com.sun.jna.Pointer;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;

/** Decodes a separate silent VLC stream; it does not intercept the audible player's output. */
public final class LoudnessAnalyzer implements AutoCloseable {
    public static final int SAMPLE_SECONDS = 20;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumen-loudness-analysis");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    // Reused between measurements; creation and final release are confined to the worker.
    private MediaPlayerFactory factory;
    private final Set<CompletableFuture<LoudnessMeter.Result>> pending = ConcurrentHashMap.newKeySet();

    public synchronized CompletableFuture<LoudnessMeter.Result> analyze(URI uri) {
        Objects.requireNonNull(uri);
        CompletableFuture<LoudnessMeter.Result> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Loudness analyzer is closed"));
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            worker.execute(() -> {
                try { result.complete(measure(uri, Duration.ofMinutes(2), result)); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) { result.completeExceptionally(error); }
        return result;
    }

    private LoudnessMeter.Result measure(URI uri, Duration timeout, CompletableFuture<?> cancellation) throws Exception {
        if (closed || cancellation.isCancelled()) throw new CancellationException();
        if (factory == null) {
            VlcAudioPlayer.configureNativePath();
            factory = new MediaPlayerFactory("--no-video", "--no-metadata-network-access", "--quiet");
        }
        MediaPlayer decoder = null;
        try {
            decoder = factory.mediaPlayers().newMediaPlayer();
            LoudnessMeter meter = new LoudnessMeter();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicBoolean samplingComplete = new AtomicBoolean();
            decoder.audio().callback("S16N", LoudnessMeter.SAMPLE_RATE, LoudnessMeter.CHANNELS,
                    new AudioCallbackAdapter() {
                        @Override public void play(MediaPlayer mediaPlayer, Pointer samples, int count, long pts) {
                            if (closed || cancellation.isCancelled() || samplingComplete.get()) return;
                            try {
                                long remaining = (long) LoudnessMeter.SAMPLE_RATE * SAMPLE_SECONDS - meter.frames();
                                int frames = (int) Math.min(count, remaining);
                                short[] decoded = samples.getShortArray(0, Math.multiplyExact(frames, LoudnessMeter.CHANNELS));
                                float[] pcm = new float[decoded.length];
                                for (int i = 0; i < decoded.length; i++) pcm[i] = decoded[i] / 32768f;
                                meter.accept(pcm);
                                if (meter.frames() >= (long) LoudnessMeter.SAMPLE_RATE * SAMPLE_SECONDS) {
                                    samplingComplete.set(true);
                                    completed.countDown();
                                }
                            }
                            catch (Throwable error) { failure.compareAndSet(null, error.toString()); completed.countDown(); }
                        }
                    });
            decoder.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void finished(MediaPlayer player) { completed.countDown(); }
                @Override public void error(MediaPlayer player) {
                    failure.compareAndSet(null, "VLC 无法解码曲目进行响度测量");
                    completed.countDown();
                }
            });
            if (!decoder.media().play(uri.toString(), ":no-video", ":clock-synchro=0", ":audio-time-stretch=0"))
                throw new IllegalStateException("VLC 无法打开曲目进行响度测量");
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!completed.await(100, TimeUnit.MILLISECONDS)) {
                if (closed || cancellation.isCancelled() || Thread.currentThread().isInterrupted())
                    throw new CancellationException();
                if (System.nanoTime() >= deadline) throw new IllegalStateException("响度测量超时");
            }
            if (failure.get() != null) throw new IllegalStateException(failure.get());
            if (closed || cancellation.isCancelled()) throw new CancellationException();
            return meter.result();
        } finally {
            if (decoder != null) {
                try { decoder.controls().stop(); }
                finally { decoder.release(); }
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (CompletableFuture<?> result : pending) result.cancel(true);
        worker.execute(() -> {
            if (factory != null) {
                try { factory.release(); }
                finally { factory = null; }
            }
        });
        worker.shutdown();
    }
}
