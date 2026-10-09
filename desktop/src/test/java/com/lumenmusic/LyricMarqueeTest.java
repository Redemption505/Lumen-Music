package com.lumenmusic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricMarqueeTest {
    @Test void holdsBothEndsAndRevealsTheEntireLongLine() {
        assertEquals(0, LyricMarquee.offset(500, -.1));
        assertEquals(0, LyricMarquee.offset(500, .08));
        assertEquals(250, LyricMarquee.offset(500, .5), .001);
        assertEquals(500, LyricMarquee.offset(500, .92), .001);
        assertEquals(500, LyricMarquee.offset(500, 2));
    }
    @Test void shortAndInactiveLinesDoNotMove() {
        assertEquals(0, LyricMarquee.offset(0, .5));
        assertEquals(0, LyricMarquee.offset(-5, .5));
        assertEquals(0, LyricMarquee.offset(500, Double.NaN));
    }
}
