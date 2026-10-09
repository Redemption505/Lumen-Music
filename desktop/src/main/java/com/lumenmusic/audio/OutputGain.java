package com.lumenmusic.audio;

/** User volume is smoothed independently of the short playback envelope. */
final class OutputGain {
    static final long VOLUME_RAMP_MILLIS = 150;
    private final GainRamp userVolume = new GainRamp(1);
    private double envelope = 1;

    void setImmediate(double value) {
        if (!Double.isFinite(value)) return;
        userVolume.reset(clamp(value));
        envelope = 1;
    }

    void set(double volume, double envelopeGain, long nowNanos) {
        if (!Double.isFinite(volume) || !Double.isFinite(envelopeGain)) return;
        volume = clamp(volume);
        envelope = clamp(envelopeGain);
        if (volume == 0) userVolume.reset(0);
        else userVolume.moveTo(volume, nowNanos, VOLUME_RAMP_MILLIS);
    }

    double valueAt(long nowNanos) { return userVolume.valueAt(nowNanos) * envelope; }
    boolean settled(long nowNanos) { return userVolume.settled(nowNanos); }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
