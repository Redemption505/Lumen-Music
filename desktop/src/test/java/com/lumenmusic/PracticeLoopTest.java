package com.lumenmusic;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PracticeLoopTest {
    @Test void markingEndEnablesLoopAndMovingAInvalidatesAnEarlierB() {
        var loop = new PracticeLoop();
        assertThrows(IllegalArgumentException.class, () -> loop.markEnd(3000, 10000));
        loop.markStart(2000, 10000);
        loop.markEnd(4000, 10000);
        assertTrue(loop.enabled());
        assertFalse(loop.outside(2500));
        assertTrue(loop.outside(4000));
        loop.markStart(3800, 10000);
        assertEquals(-1, loop.endMillis());
        assertFalse(loop.enabled());
    }

    @Test void invalidEditPreservesThePreviousValidLoop() {
        var loop = new PracticeLoop();
        loop.configure(1500, 4000, 5000);
        loop.setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> loop.configure(3000, 3200, 5000));
        assertThrows(IllegalArgumentException.class, () -> loop.configure(1000, 5001, 5000));
        assertThrows(IllegalArgumentException.class, () -> loop.configure(-1, 4000, 5000));
        assertEquals(1500, loop.startMillis());
        assertEquals(4000, loop.endMillis());
        assertTrue(loop.enabled());
    }

    @Test void pauseRetainsMarksAndClearRemovesThem() {
        var loop = new PracticeLoop();
        loop.configure(0, 500, 1000);
        loop.setEnabled(true);
        loop.setEnabled(false);
        assertTrue(loop.complete());
        loop.clear();
        assertFalse(loop.complete());
        assertFalse(loop.enabled());
        assertThrows(IllegalArgumentException.class, () -> loop.setEnabled(true));
        assertThrows(IllegalArgumentException.class, () -> loop.markStart(900, 1000));
    }

    @Test void nestedSegmentMarksBecomeSourceBoundariesWithoutChangingTheOldSegment() {
        var source = new Track("source", "Source", "Artist", 60, "local", URI.create("file:///fixture.wav"));
        var segment = Track.segment(source, "Song", "Artist", 10000, 30000);
        var loop = new PracticeLoop();
        loop.configure(2000, 6000, 20000);
        var result = Track.segment(segment.audioSource(), "Practice", "Artist",
                loop.sourceStart(segment), loop.sourceEnd(segment));
        assertEquals(12000, result.segmentStartMillis());
        assertEquals(16000, result.segmentEndMillis());
        assertEquals(source.id(), result.sourceTrackId());
        assertEquals(10000, segment.segmentStartMillis());
        assertEquals(30000, segment.segmentEndMillis());
    }

    @Test void lyricJumpInvertsBothOriginAndPositiveOrNegativeCalibration() {
        assertEquals(7750, LyricTiming.mediaMillis(4000, 3000, 750));
        assertEquals(4500, LyricTiming.mediaMillis(4000, 1000, -500));
        for (long offset : new long[] {-500, 0, 750})
            assertEquals(4000, LyricTiming.relativeMillis(LyricTiming.mediaMillis(4000, 1000, offset), 1000, offset));
        assertEquals(-500, LyricTiming.mediaMillis(0, 0, -500));
        assertThrows(IllegalArgumentException.class, () -> LyricTiming.mediaMillis(Long.MAX_VALUE, 1, 0));
    }
}
