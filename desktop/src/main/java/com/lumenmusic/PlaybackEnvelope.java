package com.lumenmusic;

/** A short gain envelope, separate from user volume and loudness normalization. */
public final class PlaybackEnvelope {
    public static final long FADE_MILLIS = 200;
    private boolean enabled;
    private long startedNanos;
    private double reportedMillis = Double.NaN;
    private double estimatedMillis;
    private double observedRate;
    private long observedNanos;

    public void start(boolean enabled, long nowNanos) {
        this.enabled = enabled;
        startedNanos = nowNanos;
        resetPositionClock();
    }

    public void resetPositionClock() { reportedMillis = Double.NaN; }

    /** Interpolation affects gain only, never the player's progress or completion clock. */
    public double gainAtPosition(long nowNanos, double positionMillis, double durationMillis,
                                 double rate, boolean fadeOut) {
        if (!enabled) return 1;
        if (!Double.isFinite(positionMillis) || !Double.isFinite(rate) || rate <= 0)
            return gain(nowNanos, Double.NaN, false);
        double elapsed = Math.max(0, (nowNanos - observedNanos) / 1_000_000.0);
        if (Double.isNaN(reportedMillis) || positionMillis < reportedMillis || rate != observedRate) {
            estimatedMillis = positionMillis;
            observedNanos = nowNanos;
        } else if (positionMillis != reportedMillis) {
            estimatedMillis = Math.min(positionMillis + 500 * rate,
                    Math.max(positionMillis, estimatedMillis + elapsed * rate));
            observedNanos = nowNanos;
        }
        reportedMillis = positionMillis;
        observedRate = rate;
        double estimate = Math.min(positionMillis + 500 * rate,
                estimatedMillis + Math.max(0, (nowNanos - observedNanos) / 1_000_000.0) * rate);
        return gain(nowNanos, (durationMillis - estimate) / rate, fadeOut);
    }

    public double gain(long nowNanos, double remainingMillis, boolean fadeOut) {
        if (!enabled) return 1;
        double elapsed = Math.max(0, (nowNanos - startedNanos) / 1_000_000.0);
        double in = smooth(elapsed / FADE_MILLIS);
        double out = fadeOut && Double.isFinite(remainingMillis)
                ? smooth(remainingMillis / FADE_MILLIS) : 1;
        return Math.min(in, out);
    }

    private static double smooth(double fraction) {
        double value = Math.max(0, Math.min(1, fraction));
        return value * value * (3 - 2 * value);
    }
}
