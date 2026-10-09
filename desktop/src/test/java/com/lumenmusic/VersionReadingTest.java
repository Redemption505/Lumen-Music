package com.lumenmusic;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionReadingTest {
    private final Track resource = new Track("live-in-title", "现场 Live 翻唱", "歌手", 180, "local");

    @Test void groupingUsesOnlySavedPreferenceAndPreservesEachConcreteResource() {
        var other = new VersionCatalog.Version("other", "个人标记", "歌手甲", resource);
        Track preferredTrack = new Track("exact-preferred", "同一首歌", "歌手乙", 120, "local");
        var preferred = new VersionCatalog.Version("preferred", "录音室", "歌手乙", preferredTrack);
        var song = new VersionCatalog.Song("song", "个人歌名", "", "preferred", List.of(other, preferred));
        assertEquals(List.of(preferred, other), VersionReading.ordered(song));
        assertSame(preferredTrack, VersionReading.ordered(song).get(0).track());
        assertTrue(VersionReading.preferred(song, preferred));
        assertFalse(VersionReading.preferred(song, other));
        assertEquals(List.of(other, preferred), song.versions());
        var unknown = new VersionCatalog.Song("song", "个人歌名", "", "missing", List.of(other, preferred));
        assertEquals(List.of(other, preferred), VersionReading.ordered(unknown));
        assertFalse(VersionReading.preferred(unknown, other));
    }

    @Test void titleCannotCreateVersionTagsAndOnlyActualSegmentGetsSegmentBadge() {
        var unmarked = new VersionCatalog.Version("version", "", "歌手", resource);
        assertEquals("未标注版本", VersionReading.label(unmarked));
        assertEquals("私人版本", VersionReading.label(new VersionCatalog.Version("version", "私人版本", "歌手", resource)));
        Track segment = Track.segment(resource, "自选片段", "歌手", 10000, 20000);
        assertEquals("翻唱 · 片段", VersionReading.label(new VersionCatalog.Version("segment", "翻唱", "歌手", segment)));
    }
}
