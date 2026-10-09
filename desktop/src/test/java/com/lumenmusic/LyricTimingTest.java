package com.lumenmusic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricTimingTest {
    @Test void laterSongUsesItsOwnClockAndCanSeekBackBeforeItsStart() {
        var lines = Lyrics.parse("[00:00]First\n[00:05]Second\n[00:10]Third");
        long start = LyricTiming.parseStart("18:00");
        assertEquals(-1, Lyrics.currentIndex(lines, LyricTiming.relativeMillis(1000, start, 0)));
        assertEquals(0, Lyrics.currentIndex(lines, LyricTiming.relativeMillis(start, start, 0)));
        assertEquals(1, Lyrics.currentIndex(lines, LyricTiming.relativeMillis(start + 5000, start, 0)));
        assertEquals(0, Lyrics.currentIndex(lines, LyricTiming.relativeMillis(start + 5000, start, 500)));
    }

    @Test void independentPartResetsToZeroWithoutSubtractingPreviousParts() {
        assertEquals(5000, LyricTiming.relativeMillis(5000, 0, 0));
        assertEquals(-1, LyricTiming.relativeMillis(0, 0, 500));
        assertEquals(500, LyricTiming.relativeMillis(0, 0, -500));
    }

    @Test void startInputRoundTripsWithoutLosingSubseconds() {
        assertEquals(3723456, LyricTiming.parseStart("1:02:03.456"));
        assertEquals(3723456, LyricTiming.parseStart(LyricTiming.formatStart(3723456)));
        assertThrows(IllegalArgumentException.class, () -> LyricTiming.parseStart("18:99"));
        assertThrows(IllegalArgumentException.class, () -> LyricTiming.parseStart("-1:00"));
        assertThrows(IllegalArgumentException.class, () -> LyricTiming.parseStart("999999999999999999:00"));
    }
}
