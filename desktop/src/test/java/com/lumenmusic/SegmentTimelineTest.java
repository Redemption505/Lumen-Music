package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;

class SegmentTimelineTest {
    private final Track source = new Track("video", "Long video", "Artist", 3600, "bilibili");
    private final Track clip = Track.segment(source, "Song", "Artist", 18 * 60000, 18 * 60000 + 6100);

    @Test void physicalMediaClockMapsToIndependentFractionalRange() {
        assertEquals(0, SegmentTimeline.position(clip, Duration.seconds(1)).toMillis());
        assertEquals(1100, SegmentTimeline.position(clip, Duration.millis(18 * 60000 + 1100)).toMillis());
        assertEquals(6100, SegmentTimeline.position(clip, Duration.hours(1)).toMillis());
        assertEquals(6100, SegmentTimeline.duration(clip, Duration.hours(1)).toMillis());
        assertEquals((18 * 60 + 3.05) / 3600,
                SegmentTimeline.sourceFraction(clip, .5, Duration.hours(1)), .00000001);
        assertEquals(.5, SegmentTimeline.sourceFraction(source, .5, Duration.hours(1)));
    }

    @Test void wholeSourceSidecarCanBeCroppedWithoutLosingCurrentLine() {
        String lrc = "[17:59.000]first\n[18:01.000]<18:01.000>second\n[18:07.000]outside";
        assertEquals(List.of(new Lyrics.Line(0, "first"), new Lyrics.Line(1000, "second")),
                Lyrics.parse(SegmentTimeline.sourceLyrics(lrc, clip)));
    }

    @Test void editingAndRemovingClipDoesNotDisturbOtherQueueItems() {
        Track next = Track.segment(source, "Next", "Artist", 2000, 4000);
        PlayerQueue queue = new PlayerQueue();
        queue.select(List.of(clip, next), 0);
        Track updated = clip.withSegment("Updated", "Artist", 18 * 60000, 18 * 60000 + 5000);
        queue.replaceById(updated);
        assertSame(updated, queue.current());
        queue.removeById(updated.id());
        assertSame(next, queue.current());
        queue.removeById(next.id());
        assertNull(queue.current());
        assertEquals(-1, queue.index());
    }
}
