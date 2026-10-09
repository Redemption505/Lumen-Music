package com.lumenmusic.audio;

import java.util.function.DoubleConsumer;
import javafx.animation.AnimationTimer;

/** Advances volume even when the UI has stopped delivering slider changes. */
final class FxVolumeSmoother {
    private final OutputGain gain = new OutputGain();
    private final DoubleConsumer output;
    private final AnimationTimer clock = new AnimationTimer() {
        @Override public void handle(long now) {
            output.accept(gain.valueAt(now));
            if (gain.settled(now)) stop();
        }
    };

    FxVolumeSmoother(DoubleConsumer output) { this.output = output; }

    void setImmediate(double value) {
        clock.stop();
        gain.setImmediate(value);
        output.accept(current());
    }

    void set(double volume, double envelope) {
        long now = System.nanoTime();
        gain.set(volume, envelope, now);
        output.accept(gain.valueAt(now));
        if (!gain.settled(now)) clock.start();
    }

    double current() { return gain.valueAt(System.nanoTime()); }
    void stop() { clock.stop(); }
}
