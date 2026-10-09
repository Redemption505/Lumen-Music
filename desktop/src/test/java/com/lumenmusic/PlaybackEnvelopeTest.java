package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlaybackEnvelopeTest {
    @Test void disabledNeverChangesGain() {
        var envelope = new PlaybackEnvelope();
        envelope.start(false, 0);
        assertEquals(1, envelope.gain(0, 0, true));
        assertEquals(1, envelope.gain(50_000_000, 20, true));
    }

    @Test void smoothStartAndNaturalTailHaveStableBounds() {
        var envelope = new PlaybackEnvelope();
        envelope.start(true, 1_000_000_000);
        assertEquals(0, envelope.gain(1_000_000_000, 1000, true));
        assertEquals(.5, envelope.gain(1_100_000_000, 1000, true), 1e-9);
        assertEquals(1, envelope.gain(1_200_000_000, 1000, true));
        assertEquals(.5, envelope.gain(2_000_000_000, 100, true), 1e-9);
        assertEquals(0, envelope.gain(2_000_000_000, -10, true));
    }

    @Test void practiceLoopAndUnknownDurationDoNotFadeOut() {
        var envelope = new PlaybackEnvelope();
        envelope.start(true, 0);
        assertEquals(1, envelope.gain(500_000_000, 0, false));
        assertEquals(1, envelope.gain(500_000_000, Double.NaN, true));
        assertEquals(1, envelope.gain(500_000_000, Double.POSITIVE_INFINITY, true));
    }

    @Test void restartReplacesPreviousFade() {
        var envelope = new PlaybackEnvelope();
        envelope.start(true, 0);
        envelope.start(true, 500_000_000);
        assertEquals(0, envelope.gain(500_000_000, 1000, true));
        assertEquals(1, envelope.gain(700_000_000, 1000, true));
    }

    @Test void coarseNativeClockStillProducesTailGainWithoutMovingMedia() {
        var envelope = new PlaybackEnvelope();
        envelope.start(true, 0);
        assertEquals(1, envelope.gainAtPosition(2_000_000_000, 31_656, 32_000, 1.5, true));
        double gain = envelope.gainAtPosition(2_100_000_000L, 31_656, 32_000, 1.5, true);
        assertTrue(gain > 0 && gain < 1);
        assertTrue(envelope.gainAtPosition(2_220_000_000L, 31_656, 32_000, 1.5, true) < .02);
        assertEquals(1, envelope.gainAtPosition(2_250_000_000L, 2_000, 32_000, 1.5, true));
    }

    @Test void clockResetAndRateChangeDoNotKeepAnOldTailEstimate() {
        var envelope = new PlaybackEnvelope();
        envelope.start(true, 0);
        envelope.gainAtPosition(2_000_000_000, 31_656, 32_000, 1.5, true);
        envelope.gainAtPosition(2_220_000_000L, 31_656, 32_000, 1.5, true);
        envelope.resetPositionClock();
        assertEquals(1, envelope.gainAtPosition(3_000_000_000L, 31_656, 32_000, 1.5, true));
        assertEquals(1, envelope.gainAtPosition(3_220_000_000L, 31_656, 32_000, .75, true));
        assertEquals(1, envelope.gainAtPosition(4_000_000_000L, 31_990, 32_000, .75, false));
    }
}
