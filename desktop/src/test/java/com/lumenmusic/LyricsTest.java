package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class LyricsTest {
    @Test void parsesMultipleStampsFractionsAndOffsetInTimeOrder() {
        var lines = Lyrics.parse("[ar:Artist]\n[offset:-250]\n[00:02.50][00:01.5] 重复 \n[00:03.005]末句");
        assertEquals(List.of(new Lyrics.Line(1250, "重复"),
                new Lyrics.Line(2250, "重复"), new Lyrics.Line(2755, "末句")), lines);
        assertEquals(-1, Lyrics.currentIndex(lines, 1249));
        assertEquals(0, Lyrics.currentIndex(lines, 1250));
        assertEquals(2, Lyrics.currentIndex(lines, 3000));
    }

    @Test void ignoresMetadataEmptyAndInvalidTimestampsAndClampsNegativeOffset() {
        var lines = Lyrics.parse("[ti:Title]\n[00:60.00]bad\n[00:00.10]\n[offset:-500]\n[00:00.20]first");
        assertEquals(List.of(new Lyrics.Line(0, "first")), lines);
        assertEquals(-1, Lyrics.currentIndex(List.of(), 100));
        assertEquals(List.of(), Lyrics.parse(null));
    }

    @Test void stripsEnhancedLrcWordTimesButKeepsLineTimeAndOtherAngleText() {
        var lines = Lyrics.parse("[00:41.00]<00:41.548>你<00:41.900>是否<00:42.12>明白<注释>\n"
                + "[00:43.00]下一句<00:60.000><00:44><tag>");
        assertEquals(List.of(new Lyrics.Line(41000, "你是否明白<注释>"),
                new Lyrics.Line(43000, "下一句<00:60.000><00:44><tag>")), lines);
    }

    @Test void usesFirstWordTimeWhenEnhancedLrcHasNoLineStamp() {
        var lines = Lyrics.parse("[offset:-250]\n<00:41.548>美<00:41.900>人<00:42.100>鱼\n"
                + "<invalid>not timed\n<00:43.000><00:43.200>回声");
        assertEquals(List.of(new Lyrics.Line(41298, "美人鱼"),
                new Lyrics.Line(42750, "回声")), lines);
    }
}
