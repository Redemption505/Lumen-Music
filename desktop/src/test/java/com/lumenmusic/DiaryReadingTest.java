package com.lumenmusic;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiaryReadingTest {
    private final Track first = new Track("first", "曲目一", "歌手", 180, "local");
    private final Track second = new Track("second", "曲目二", "歌手", 180, "local");

    @Test void datesUseDiaryZoneAcrossMidnightAndDoNotInventLegacyCreation() {
        assertEquals(LocalDate.of(2026, 10, 7),
                DiaryReading.date("2026-10-06T17:30:00Z", "Asia/Shanghai"));
        assertEquals(LocalDate.of(2026, 10, 6),
                DiaryReading.date("2026-10-06T17:30:00Z", "America/Los_Angeles"));
        DiaryClient.Note legacy = new DiaryClient.Note(first, "旧回忆", "", "2026-10-06T17:30:00Z");
        assertEquals("2026-10-07", DiaryReading.dateKey(DiaryReading.noteTimestamp(legacy), "Asia/Shanghai"));
        assertEquals("创建日期未知  ·  更新于 2026年10月7日 01:30", DiaryReading.noteDates(legacy, "Asia/Shanghai"));
        assertEquals("创建日期未知", DiaryReading.noteDates(new DiaryClient.Note(first, "旧回忆"), "Asia/Shanghai"));
        assertEquals(DiaryReading.UNKNOWN, DiaryReading.dateKey("not-a-timestamp", "Asia/Shanghai"));
    }

    @Test void groupsKeepOriginalCreationDayAfterAnEditAndUnknownLast() {
        DiaryClient.Note edited = new DiaryClient.Note(first, "已更新",
                "2026-10-04T16:05:00Z", "2026-10-07T09:00:00Z");
        DiaryClient.Note later = new DiaryClient.Note(second, "新回忆",
                "2026-10-06T16:05:00Z", "2026-10-06T16:05:00Z");
        DiaryClient.Note unknown = new DiaryClient.Note(first, "旧回忆");
        var groups = DiaryReading.groups(List.of(unknown, edited, later), DiaryReading::noteTimestamp, "Asia/Shanghai");
        assertEquals(List.of("2026-10-07", "2026-10-05", DiaryReading.UNKNOWN), List.copyOf(groups.keySet()));
        assertEquals(List.of(edited), groups.get("2026-10-05"));
        assertTrue(DiaryReading.noteDates(edited, "Asia/Shanghai").contains("更新于 2026年10月7日"));
        assertFalse(DiaryReading.matches(DiaryReading.noteTimestamp(edited), "Asia/Shanghai", "2026-10-07"));
    }

    @Test void visibleBatchTracksFollowTheFilterAndDeduplicateRepeatedLyricCards() {
        var diary = diary(List.of(new DiaryClient.Note(first, "旧备注"),
                        new DiaryClient.Note(second, "新备注", "2026-10-06T17:00:00Z", "2026-10-06T17:00:00Z")),
                List.of(new DiaryClient.LyricFavorite("one", first, 1200, "2026-10-06T17:00:00Z", "第一句"),
                        new DiaryClient.LyricFavorite("two", first, 2400, "2026-10-06T17:10:00Z", "第二句"),
                        new DiaryClient.LyricFavorite("three", second, 65000, "2026-10-05T10:00:00Z", "另一日")));
        assertEquals(List.of(first), DiaryPane.tracksForTab(diary, 1, DiaryReading.UNKNOWN, DiaryReading.ALL));
        assertEquals(List.of(second), DiaryPane.tracksForTab(diary, 1, "2026-10-07", DiaryReading.ALL));
        assertEquals(List.of(first), DiaryPane.tracksForTab(diary, 2, DiaryReading.ALL, "2026-10-07"));
        assertEquals(List.of(), DiaryPane.tracksForTab(diary, 2, DiaryReading.ALL, DiaryReading.UNKNOWN));
        assertEquals(List.of(first, second), DiaryPane.tracksForTab(diary, 2));
    }

    @Test void exportIncludesUnchangedTextPositionAndHonestDates() {
        var diary = diary(List.of(new DiaryClient.Note(first, "完整备注\n下一行")),
                List.of(new DiaryClient.LyricFavorite("one", first, 65000, "2026-10-06T17:00:00Z", "原歌词\n下一行")));
        String export = DiaryPane.exportText(diary, true);
        assertTrue(export.contains("：完整备注\n下一行\n  创建日期未知"));
        assertTrue(export.contains("@ 1:05：原歌词\n下一行\n  收藏于 2026年10月7日 01:00"));
    }

    private DiaryClient.Diary diary(List<DiaryClient.Note> notes, List<DiaryClient.LyricFavorite> lyrics) {
        return new DiaryClient.Diary("Asia/Shanghai", "2026-10-07T10:00:00Z",
                new DiaryClient.Summary(0, 0, 0, 0, 0, 0), List.of(), notes, lyrics);
    }
}
