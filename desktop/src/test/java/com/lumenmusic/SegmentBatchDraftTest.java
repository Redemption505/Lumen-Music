package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SegmentBatchDraftTest {
    private static final Track SOURCE = new Track("video:p2", "合集", "作者", 7200, "bilibili",
            null, "BV1example", 2);

    @Test void parsesCommonTimestampListsAndPreservesOrderAndLineNumbers() {
        var rows = SegmentBatchDraft.parse("\n1. [00:00] 第一首\n2、03:04.5 — 05:06.250 | 第二首\n"
                + "3) 01:00:00.123～01:01:00.1：第三首", SOURCE);
        assertEquals(3, rows.size());
        assertEquals(List.of("第一首", "第二首", "第三首"), rows.stream().map(SegmentBatchDraft.Row::title).toList());
        assertEquals(List.of(2, 3, 4), rows.stream().map(SegmentBatchDraft.Row::line).toList());
        assertEquals("3:04.500", rows.get(0).end());
        assertEquals("05:06.250", rows.get(1).end());
        assertEquals("01:01:00.1", rows.get(2).end());
        assertTrue(rows.stream().allMatch(row -> row.selected() && row.artist().equals(SOURCE.artist())));
        var validated = SegmentBatchDraft.validate(SOURCE, rows);
        assertTrue(validated.stream().allMatch(value -> value.track() != null && value.message().isEmpty()));
        assertEquals(3_600_123, validated.get(2).track().segmentStartMillis());
        assertEquals(3_660_100, validated.get(2).track().segmentEndMillis());
    }

    @Test void supportsExplicitRangeSeparatorsAndKeepsArtistTextInTitle() {
        for (String separator : List.of("-", "–", "—", "－", "~", "～", "至")) {
            var rows = SegmentBatchDraft.parse("[00:01.01]" + separator + "[00:02.001] | 歌名 - 歌手", SOURCE);
            Track track = SegmentBatchDraft.validate(SOURCE, rows).get(0).track();
            assertNotNull(track, separator);
            assertEquals(1_010, track.segmentStartMillis());
            assertEquals(2_001, track.segmentEndMillis());
            assertEquals("歌名 - 歌手", track.title());
            assertEquals(SOURCE.artist(), track.artist());
        }
    }

    @Test void knownTailIsInferredButUnknownTailRequiresAnExplicitEnd() {
        var unknown = new Track("unknown", "未知时长", "作者", 0, "test");
        var rows = SegmentBatchDraft.parse("00:00 第一首\n01:00 最后一首", unknown);
        assertEquals("1:00.000", rows.get(0).end());
        assertEquals("", rows.get(1).end());
        var values = SegmentBatchDraft.validate(unknown, rows);
        assertNotNull(values.get(0).track());
        assertNull(values.get(1).track());
        assertTrue(values.get(1).message().contains("终点未知"));
        var edited = copy(rows.get(1), "最后一首", "01:00", "02:30", true);
        assertNotNull(SegmentBatchDraft.validate(unknown, List.of(edited)).get(0).track());
        var known = SegmentBatchDraft.parse("00:00 第一首\n01:00 最后一首", SOURCE);
        assertEquals("120:00.000", known.get(1).end());
    }

    @Test void badAdjacentLineIsRetainedAndCannotBeSkippedDuringInference() {
        var rows = SegmentBatchDraft.parse("00:00 第一首\n无时间的坏行\n02:00 第三首", SOURCE);
        assertEquals(3, rows.size());
        assertEquals("", rows.get(0).end());
        assertEquals("", rows.get(1).start());
        assertEquals("无时间的坏行", rows.get(1).title());
        var unchecked = copy(rows.get(1), rows.get(1).title(), "", "", false);
        var values = SegmentBatchDraft.validate(SOURCE, List.of(rows.get(0), unchecked, rows.get(2)));
        assertNull(values.get(0).track());
        assertTrue(values.get(0).message().contains("终点未知"));
        assertNull(values.get(1).track());
        assertNotNull(values.get(2).track());
    }

    @Test void uncheckedBadRowDoesNotBlockExplicitValidRows() {
        var rows = SegmentBatchDraft.parse("00:00-00:30 第一首\n坏行\n01:00-01:30 第三首", SOURCE);
        var unchecked = copy(rows.get(1), rows.get(1).title(), "", "", false);
        var values = SegmentBatchDraft.validate(SOURCE, List.of(rows.get(0), unchecked, rows.get(2)));
        assertNotNull(values.get(0).track());
        assertNull(values.get(1).track());
        assertNotNull(values.get(2).track());
    }

    @Test void validationPreservesIdentityAndMetadataAcrossEditingAndRetry() {
        var row = SegmentBatchDraft.parse("00:01-00:03 歌曲", SOURCE).get(0);
        var edited = new SegmentBatchDraft.Row(row.id(), row.line(), "新标题", "新歌手", "00:02", "00:04", true);
        Track first = SegmentBatchDraft.validate(SOURCE, List.of(edited)).get(0).track();
        Track retry = SegmentBatchDraft.validate(SOURCE, List.of(edited)).get(0).track();
        assertEquals(first, retry);
        assertEquals(row.id(), first.id());
        assertEquals("新标题", first.title());
        assertEquals("新歌手", first.artist());
        assertEquals(SOURCE.id(), first.audioSource().id());
        assertEquals(SOURCE.bvid(), first.bvid());
        assertEquals(SOURCE.page(), first.page());
        assertEquals(2, first.durationSeconds());
    }

    @Test void segmentInputUsesOriginalSourceAndSharedCacheIdentity() {
        var local = new Track("local:album", "原音源", "演奏者", 60, "local", URI.create("file:/album.wav"));
        var part = Track.segment(local, "已有片段", "演奏者", 10_000, 20_000);
        var rows = SegmentBatchDraft.parse("00:30-00:40 新片段", part);
        Track track = SegmentBatchDraft.validate(part, rows).get(0).track();
        assertNotNull(track);
        assertEquals(local.id(), track.sourceTrackId());
        assertEquals(local.mediaUri(), track.mediaUri());
        assertEquals(60, track.sourceDurationSeconds());
    }

    @Test void rejectsMalformedClockValuesAndOverflowWithoutLosingRows() {
        for (String time : List.of("00:60", "1:2", "00:00.1234", "99999999999999999999:00")) {
            var rows = SegmentBatchDraft.parse(time + "-02:00 歌曲", SOURCE);
            var value = SegmentBatchDraft.validate(SOURCE, rows).get(0);
            assertNull(value.track(), time);
            assertTrue(value.message().contains("起点"), time);
        }
    }

    @Test void rejectsEmptyTitleInvalidRangeAndKnownSourceBoundary() {
        for (String line : List.of("00:00-00:30", "00:02-00:01 歌曲", "120:00-121:00 歌曲")) {
            var value = SegmentBatchDraft.validate(SOURCE, SegmentBatchDraft.parse(line, SOURCE)).get(0);
            assertNull(value.track(), line);
            assertFalse(value.message().isBlank());
        }
    }

    @Test void malformedExplicitEndRemainsAnErrorAndDoesNotBecomeAnInferredEnd() {
        var rows = SegmentBatchDraft.parse("00:00 第一首\n01:00-02:xx 坏终点\n03:00 最后一首", SOURCE);
        assertEquals("", rows.get(0).end());
        assertEquals("02:xx", rows.get(1).end());
        var values = SegmentBatchDraft.validate(SOURCE, rows);
        assertNull(values.get(0).track());
        assertNull(values.get(1).track());
        assertTrue(values.get(1).message().contains("终点格式"));
        assertNotNull(values.get(2).track());
        for (String end : List.of("xx:00", "-01:00", "02:00.")) {
            var malformed = SegmentBatchDraft.parse("00:00-" + end + " 歌曲", SOURCE);
            assertEquals(end, malformed.get(0).end());
            assertNull(SegmentBatchDraft.validate(SOURCE, malformed).get(0).track());
        }
    }

    @Test void outOfBoundsAdjacentStartCannotSupplyAnInferredEnd() {
        var rows = SegmentBatchDraft.parse("00:00 第一首\n121:00 越界项", SOURCE);
        assertEquals("", rows.get(0).end());
        assertNull(SegmentBatchDraft.validate(SOURCE, rows).get(0).track());
    }

    @Test void outOfOrderAndSameStartsRemainInOriginalOrderAndAreRejected() {
        var reversed = SegmentBatchDraft.parse("02:00-02:30 第二首\n00:00-00:30 第一首", SOURCE);
        assertEquals("第二首", reversed.get(0).title());
        assertTrue(SegmentBatchDraft.validate(SOURCE, reversed).get(1).message().contains("顺序"));
        var same = SegmentBatchDraft.parse("00:00-00:30 第一首\n00:00-00:40 第二首", SOURCE);
        assertNull(SegmentBatchDraft.validate(SOURCE, same).get(1).track());
    }

    @Test void duplicateRangesAndSelectedOverlapAreRejectedButTouchingBoundariesAreAllowed() {
        var duplicates = SegmentBatchDraft.validate(SOURCE,
                SegmentBatchDraft.parse("00:00-00:30 第一首\n00:00-00:30 另一标题", SOURCE));
        assertTrue(duplicates.stream().allMatch(value -> value.track() == null && value.message().contains("重复")));
        var overlapRows = SegmentBatchDraft.parse("00:00-00:30 第一首\n00:20-00:40 第二首", SOURCE);
        assertTrue(SegmentBatchDraft.validate(SOURCE, overlapRows).stream()
                .allMatch(value -> value.track() == null && value.message().contains("重叠")));
        var unchecked = copy(overlapRows.get(1), "第二首", "00:20", "00:40", false);
        assertNotNull(SegmentBatchDraft.validate(SOURCE, List.of(overlapRows.get(0), unchecked)).get(0).track());
        var touching = SegmentBatchDraft.validate(SOURCE,
                SegmentBatchDraft.parse("00:00-00:30 第一首\n00:30-01:00 第二首", SOURCE));
        assertTrue(touching.stream().allMatch(value -> value.track() != null));
    }

    @Test void infersAnEditedBlankEndUsingOnlyTheNextRow() {
        var rows = SegmentBatchDraft.parse("00:00-00:20 第一首\n00:30-01:00 第二首", SOURCE);
        var edited = copy(rows.get(0), "第一首", "00:00", "", true);
        var values = SegmentBatchDraft.validate(SOURCE, List.of(edited, rows.get(1)));
        assertEquals(30_000, values.get(0).track().segmentEndMillis());
    }

    @Test void limitsNonblankRowsAndRejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> SegmentBatchDraft.parse("\n \n", SOURCE));
        assertThrows(IllegalArgumentException.class, () -> SegmentBatchDraft.parse(null, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> SegmentBatchDraft.parse("00:00 歌曲", null));
        assertEquals(200, SegmentBatchDraft.parse("00:00-00:01 歌曲\n".repeat(200), SOURCE).size());
        assertThrows(IllegalArgumentException.class,
                () -> SegmentBatchDraft.parse("00:00-00:01 歌曲\n".repeat(201), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> SegmentBatchDraft.validate(SOURCE, List.of()));
    }

    private static SegmentBatchDraft.Row copy(SegmentBatchDraft.Row row, String title, String start, String end, boolean selected) {
        return new SegmentBatchDraft.Row(row.id(), row.line(), title, row.artist(), start, end, selected);
    }
}
