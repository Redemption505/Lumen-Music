package com.lumenmusic;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeReunionTest {
    @Test void prioritizesReheardAndUsesRealLocalDates() {
        var selection = HomeReunion.select(diary(
                section("weekly-first", entry("new", "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z", "", false)),
                section("reheard", entry("old", "2026-10-06T18:00:00Z", "", "2025-01-01T18:00:00Z", false)))).orElseThrow();
        assertEquals("old", selection.track().id());
        assertEquals("你在 2025-01-02 收藏，在 2026-10-07 又听到了它", selection.reason());
    }

    @Test void fallsBackToConfirmedFirstListenThenRecent() {
        var first = HomeReunion.select(diary(
                section("reheard", entry("bad", "bad date", "", "", false)),
                section("weekly-first", entry("new", "2026-10-04T18:00:00Z", "2026-10-01T18:00:00Z", "", false)),
                section("recent", entry("recent", "2026-10-06T18:00:00Z", "", "", false)))).orElseThrow();
        assertEquals("new", first.track().id());
        assertEquals("你在 2026-10-02 第一次听到这首歌", first.reason());
    }

    @Test void legacyRecordWithoutFirstDateNeverClaimsFirstListen() {
        var old = entry("legacy", "2026-09-01T10:00:00Z", "", "", true);
        var selection = HomeReunion.select(diary(section("weekly-first", old), section("recent", old))).orElseThrow();
        assertEquals("你在 2026-09-01 听过这首歌", selection.reason());
        assertFalse(selection.reason().contains("第一次"));
    }

    @Test void noHistoryOrUndatedRecordsProduceNoInventedMemory() {
        assertTrue(HomeReunion.select(null).isEmpty());
        assertTrue(HomeReunion.select(diary()).isEmpty());
        assertTrue(HomeReunion.select(diary(section("recent", entry("no-date", "", "", "", true)))).isEmpty());
    }

    private static DiaryClient.Entry entry(String id, String played, String first, String favorite, boolean legacy) {
        return new DiaryClient.Entry(new Track(id, id, "作者", 120, "local", null, null, 0, null, 0, 0),
                played, first, favorite, "", "", "", 1, legacy);
    }

    private static DiaryClient.Section section(String id, DiaryClient.Entry entry) {
        return new DiaryClient.Section(id, id, 1, List.of(entry));
    }

    private static DiaryClient.Diary diary(DiaryClient.Section... sections) {
        return new DiaryClient.Diary("Asia/Shanghai", "2026-10-07T00:00:00Z",
                new DiaryClient.Summary(0, 0, 0, 0, 0, 0), List.of(sections), List.of(), List.of());
    }
}
