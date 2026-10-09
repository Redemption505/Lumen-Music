package com.lumenmusic;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** A countdown measured from a monotonic clock. Call tick() to observe completion. */
public final class FocusSession {
    public enum State { IDLE, RUNNING, PAUSED, COMPLETED }

    private final LongSupplier clock;
    private final Runnable onComplete;
    private Duration duration;
    private long remainingNanos;
    private long startedAt;
    private State state = State.IDLE;

    public FocusSession(Duration duration, Runnable onComplete) {
        this(duration, System::nanoTime, onComplete);
    }

    public FocusSession(Duration duration, LongSupplier clock, Runnable onComplete) {
        this.clock = Objects.requireNonNull(clock);
        this.onComplete = Objects.requireNonNull(onComplete);
        setDuration(duration);
    }

    public State state() { return state; }
    public Duration duration() { return duration; }

    /** Changing the preset begins a fresh, idle countdown. */
    public void setDuration(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration must be positive");
        this.duration = duration;
        remainingNanos = duration.toNanos();
        state = State.IDLE;
    }

    public void start() {
        if (state == State.RUNNING) return;
        if (state == State.IDLE || state == State.COMPLETED) remainingNanos = duration.toNanos();
        startedAt = clock.getAsLong();
        state = State.RUNNING;
    }

    public void pause() {
        if (state != State.RUNNING) return;
        long left = remainingNanos();
        if (state == State.COMPLETED) return;
        remainingNanos = left;
        state = State.PAUSED;
    }

    public void cancel() {
        remainingNanos = duration.toNanos();
        state = State.IDLE;
    }

    public Duration remaining() { return Duration.ofNanos(remainingNanos()); }

    public void tick() { remainingNanos(); }

    private long remainingNanos() {
        if (state != State.RUNNING) return remainingNanos;
        long elapsed = Math.max(0, clock.getAsLong() - startedAt);
        if (elapsed < remainingNanos) return remainingNanos - elapsed;
        remainingNanos = 0;
        state = State.COMPLETED;
        onComplete.run();
        return 0;
    }
}
