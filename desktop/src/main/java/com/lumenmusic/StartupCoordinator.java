package com.lumenmusic;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs startup away from JavaFX and owns even resources returned after cancellation. */
final class StartupCoordinator<T extends AutoCloseable> implements AutoCloseable {
    private final Callable<T> factory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumen-runtime-start");
        // Keep the JVM alive until an interrupted startup has released its child process.
        thread.setDaemon(false);
        return thread;
    });
    private CompletableFuture<T> ready;
    private Future<?> task;
    private T owned;
    private boolean closed;

    StartupCoordinator(Callable<T> factory) { this.factory = Objects.requireNonNull(factory); }

    synchronized CompletableFuture<T> start() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("启动已取消"));
        if (ready != null && !ready.isCompletedExceptionally()) return ready;
        CompletableFuture<T> attempt = new CompletableFuture<>();
        ready = attempt;
        task = executor.submit(() -> {
            T resource = null;
            try {
                resource = Objects.requireNonNull(factory.call());
                synchronized (this) {
                    if (!closed && ready == attempt && !attempt.isCancelled()) {
                        owned = resource;
                        resource = null;
                        attempt.complete(owned);
                    }
                }
            } catch (Throwable error) {
                attempt.completeExceptionally(error);
            } finally {
                release(resource);
            }
        });
        return attempt;
    }

    @Override public void close() {
        T resource;
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (ready != null) ready.cancel(false);
            if (task != null) task.cancel(true);
            resource = owned;
            owned = null;
            executor.shutdownNow();
        }
        // Process termination may wait for a child; closing a window must not wait for it.
        if (resource != null) {
            Thread cleanup = new Thread(() -> release(resource), "lumen-runtime-close");
            cleanup.setDaemon(false);
            cleanup.start();
        }
    }

    private static void release(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); }
        catch (Exception ignored) { }
    }
}
