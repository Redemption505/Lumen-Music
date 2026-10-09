package com.lumenmusic.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutputGainTest {
    private static long millis(long value) { return value * 1_000_000; }

    @Test void sliderJumpSettlesWithoutFurtherUiEventsAndCanBeRetargetedContinuously() {
        OutputGain output = new OutputGain();
        output.setImmediate(.8);
        output.set(.2, 1, 0);
        assertEquals(.8, output.valueAt(0), 1e-12);
        double midDrag = output.valueAt(millis(60));
        assertTrue(midDrag > .2 && midDrag < .8);
        output.set(.6, 1, millis(60));
        assertEquals(midDrag, output.valueAt(millis(60)), 1e-12);
        assertEquals(.6, output.valueAt(millis(210)), 1e-12);
        assertTrue(output.settled(millis(210)));
    }

    @Test void playbackFadeDoesNotRestartOrDelayUserVolumeRamp() {
        OutputGain output = new OutputGain();
        output.setImmediate(.8);
        output.set(.4, 1, 0);
        double beforeFade = output.valueAt(millis(75));
        output.set(.4, .25, millis(75));
        assertEquals(beforeFade * .25, output.valueAt(millis(75)), 1e-12);
        output.set(.4, 0, millis(80));
        assertEquals(0, output.valueAt(millis(80)));
        output.set(.4, 1, millis(150));
        assertEquals(.4, output.valueAt(millis(150)), 1e-12);
        assertTrue(output.settled(millis(150)));
    }

    @Test void muteAndImmediateOverridesCancelOldRampAndIgnoreInvalidValues() {
        OutputGain output = new OutputGain();
        output.set(.3, 1, 0);
        output.set(0, 1, millis(20));
        assertEquals(0, output.valueAt(millis(20)));
        assertEquals(0, output.valueAt(millis(200)));
        output.set(.6, 1, millis(200));
        output.setImmediate(.1);
        assertEquals(.1, output.valueAt(millis(500)));
        output.set(Double.NaN, 1, millis(500));
        output.set(.8, Double.POSITIVE_INFINITY, millis(500));
        assertEquals(.1, output.valueAt(millis(500)));
    }

    @Test void completedMeasurementAndDisablingNormalizationDoNotCreateGainSteps() {
        GainRamp normalization = new GainRamp(0);
        normalization.moveTo(-12, millis(20_000), 1500);
        assertEquals(0, normalization.valueAt(millis(20_000)));
        double previous = 0;
        for (long time = 20_016; time < 21_500; time += 16) {
            double gain = normalization.valueAt(millis(time));
            assertTrue(gain <= previous);
            assertTrue(previous - gain < .2, "Audible gain must not jump at a playback pulse");
            previous = gain;
        }
        assertEquals(-12, normalization.valueAt(millis(21_500)));
        normalization.moveTo(0, millis(21_500), 1500);
        assertEquals(-12, normalization.valueAt(millis(21_500)));
        assertEquals(0, normalization.valueAt(millis(23_000)));
        normalization.reset(0);
        assertEquals(0, normalization.valueAt(millis(23_500)));
    }
}
