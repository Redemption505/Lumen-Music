package com.lumenmusic;

import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackSegmentTest {
    private static Track source() {
        return new Track("bilibili:BV1xx411c7mD:p1", "原曲", "原作者", 120,
                "bilibili", URI.create("https://example.test/audio"), "BV1xx411c7mD", 1,
                URI.create("https://example.test/cover"), 2, 123);
    }

    @Test void createsIndependentSegmentsWithOriginalAudioIdentity() {
        Track original = source();
        Track first = Track.segment(original, "片段一", "作者一", 1_001, 3_002);
        Track second = Track.segment(first, "片段二", "作者二", 4_000, 4_001);

        assertTrue(first.isSegment());
        assertTrue(second.isSegment());
        assertTrue(first.id().startsWith("segment:"));
        assertNotEquals(first.id(), second.id());
        assertEquals(original.id(), second.sourceTrackId());
        assertEquals(original.id(), first.audioSource().id());
        assertEquals(original.durationSeconds(), first.audioSource().durationSeconds());
        assertEquals(original.mediaUri(), first.audioSource().mediaUri());
        assertEquals(original.coverUri(), first.audioSource().coverUri());
        assertEquals(3, first.durationSeconds());
        assertEquals(1, second.durationSeconds());
        assertFalse(second.audioSource().isSegment());
    }

    @Test void editingKeepsSegmentIdAndChecksOriginalBoundary() {
        Track segment = Track.segment(source(), "初始", "作者", 1_000, 2_000);
        Track edited = segment.withSegment("修改", "新作者", 119_001, 120_000);
        assertEquals(segment.id(), edited.id());
        assertEquals(segment.sourceTrackId(), edited.sourceTrackId());
        assertEquals(1, edited.durationSeconds());
        assertThrows(IllegalArgumentException.class, () -> Track.segment(source(), "x", "y", -1, 2_000));
        assertThrows(IllegalArgumentException.class, () -> Track.segment(source(), "x", "y", 2_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> Track.segment(source(), "x", "y", 0, 120_001));
        Track unknown = new Track("unknown", "原曲", "原作者", 0, "local");
        Track fromUnknown = Track.segment(unknown, "片段", "", 0, 1_000);
        assertEquals(0, fromUnknown.sourceDurationSeconds());
        assertEquals("原作者", fromUnknown.artist());
        assertEquals("unknown", fromUnknown.audioSource().id());
        assertThrows(IllegalStateException.class, () -> source().withSegment("x", "y", 0, 1_000));
    }

    @Test void validatesTitlesAndKeepsArtistWhenEditLeavesItBlank() {
        Track segment = Track.segment(source(), "初始", "自定义作者", 0, 1_000);
        Track edited = segment.withSegment(" 修改 ", " ", 1_000, 2_000);
        assertEquals("修改", edited.title());
        assertEquals("自定义作者", edited.artist());
        assertEquals("片段歌名不能为空", assertThrows(IllegalArgumentException.class,
                () -> Track.segment(source(), " ", "歌手", 0, 1_000)).getMessage());
        assertEquals("片段结束时间超出音源时长", assertThrows(IllegalArgumentException.class,
                () -> Track.segment(source(), "片段", "歌手", 0, 120_001)).getMessage());
        assertEquals("原音源时长不能为负", assertThrows(IllegalArgumentException.class,
                () -> new Track("segment:bad", "片段", "歌手", 1, "local", null,
                        null, 0, null, 0, 0, "source", 0, 1_000, -1)).getMessage());
    }

    @Test void oldConstructorsRemainOrdinary() {
        Track old = source();
        assertFalse(old.isSegment());
        assertNull(old.sourceTrackId());
        assertEquals(0, old.segmentStartMillis());
        assertSame(old, old.audioSource());
    }
}
