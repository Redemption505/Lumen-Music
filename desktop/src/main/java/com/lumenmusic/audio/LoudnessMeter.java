package com.lumenmusic.audio;

import java.util.ArrayList;
import java.util.List;

/** Stereo 48 kHz K-weighted PCM measurement with absolute and relative loudness gates. */
public final class LoudnessMeter {
    public static final int SAMPLE_RATE = 48_000, CHANNELS = 2;
    public static final double TARGET_LUFS = -18, MAX_GAIN_DB = 12;
    public static final double MAX_BOOST_DB = 7.9;
    private static final int STEP_FRAMES = SAMPLE_RATE / 10;
    private final Filter[] shelves = { Filter.shelf(), Filter.shelf() };
    private final Filter[] highpasses = { Filter.highpass(), Filter.highpass() };
    private final List<Double> windows = new ArrayList<>();
    private final double[] steps = new double[4];
    private int stepFrames, completedSteps;
    private long frames;
    private double stepEnergy, peak;

    public record Result(double integratedLufs, double gainDb, double peak, long frames) {
        public double linearGain() { return Math.pow(10, gainDb / 20); }
    }

    public synchronized void accept(float[] interleaved) {
        if (interleaved.length % CHANNELS != 0) throw new IllegalArgumentException("Stereo PCM required");
        for (int offset = 0; offset < interleaved.length; offset += CHANNELS) {
            double energy = 0;
            for (int channel = 0; channel < CHANNELS; channel++) {
                double sample = interleaved[offset + channel];
                if (!Double.isFinite(sample)) sample = 0;
                peak = Math.max(peak, Math.abs(sample));
                double weighted = highpasses[channel].apply(shelves[channel].apply(sample));
                energy += weighted * weighted;
            }
            stepEnergy += energy;
            ++frames;
            if (++stepFrames == STEP_FRAMES) {
                steps[completedSteps % 4] = stepEnergy / STEP_FRAMES;
                ++completedSteps;
                if (completedSteps >= 4) {
                    double sum = 0;
                    for (double value : steps) sum += value;
                    windows.add(sum / 4);
                }
                stepFrames = 0;
                stepEnergy = 0;
            }
        }
    }

    public synchronized long frames() { return frames; }

    public synchronized Result result() {
        double absoluteSum = 0;
        int absoluteCount = 0;
        for (double value : windows) if (lufs(value) > -70) { absoluteSum += value; ++absoluteCount; }
        if (absoluteCount == 0) throw new IllegalStateException("音频过短或没有可测量的声音");
        double relativeGate = lufs(absoluteSum / absoluteCount) - 10;
        double gatedSum = 0;
        int gatedCount = 0;
        for (double value : windows) if (lufs(value) > -70 && lufs(value) > relativeGate) {
            gatedSum += value;
            ++gatedCount;
        }
        double loudness = lufs(gatedSum / gatedCount);
        double gain = Math.max(-MAX_GAIN_DB, Math.min(MAX_BOOST_DB, TARGET_LUFS - loudness));
        // Sample-peak headroom prevents boosting measured PCM peaks into clipping.
        if (peak > 0) gain = Math.min(gain, -1 - 20 * Math.log10(peak));
        return new Result(loudness, Math.max(-MAX_GAIN_DB, gain), peak, frames);
    }

    private static double lufs(double energy) { return -.691 + 10 * Math.log10(energy); }

    private static final class Filter {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;
        private Filter(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2;
        }
        static Filter shelf() {
            return new Filter(1.53512485958697, -2.69169618940638, 1.19839281085285,
                    -1.69065929318241, .73248077421585);
        }
        static Filter highpass() { return new Filter(1, -2, 1, -1.99004745483398, .99007225036621); }
        double apply(double sample) {
            double value = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = sample; y2 = y1; y1 = value;
            return value;
        }
    }
}
