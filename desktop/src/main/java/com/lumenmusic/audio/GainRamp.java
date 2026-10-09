package com.lumenmusic.audio;

/** A retargetable gain ramp driven by a monotonic clock. */
final class GainRamp {
    private double from, target;
    private long startedNanos, durationNanos;

    GainRamp(double initial) { reset(initial); }

    void reset(double value) {
        from = target = value;
        durationNanos = 0;
    }

    void moveTo(double value, long nowNanos, long millis) {
        if (!Double.isFinite(value) || value == target) return;
        from = valueAt(nowNanos);
        target = value;
        startedNanos = nowNanos;
        durationNanos = Math.max(0, millis) * 1_000_000;
    }

    double valueAt(long nowNanos) {
        if (durationNanos == 0) return target;
        double fraction = Math.max(0, Math.min(1, (nowNanos - startedNanos) / (double) durationNanos));
        double smooth = fraction * fraction * (3 - 2 * fraction);
        return from + (target - from) * smooth;
    }

    boolean settled(long nowNanos) {
        return durationNanos == 0 || nowNanos - startedNanos >= durationNanos;
    }
}
