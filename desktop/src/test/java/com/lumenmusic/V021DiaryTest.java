package com.lumenmusic;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class V021DiaryTest {
    private final Track first = new Track("first", "First", "Artist", 90, "local");
    private final Track second = new Track("second", "Second", "Artist", 100, "local");
    private final Track playlistOnly = new Track("playlist-only", "Playlist", "Artist", 110, "local");

    @Test void overviewBatchUsesVisibleSongsOnceAndExcludesPlaylistRows() {
        var diary = diary(List.of(
                section("weekly-first", first, null),
                section("reheard", first),
                section("monthly-playlists", playlistOnly),
                section("recent", second, first)), List.of(), List.of());

        assertEquals(List.of(first, second), DiaryPane.tracksForTab(diary, 0));
        assertThrows(UnsupportedOperationException.class,
                () -> DiaryPane.tracksForTab(diary, 0).add(playlistOnly));
    }

    @Test void noteAndLyricBatchStayWithinCurrentViewAndIgnoreMissingTracks() {
        var diary = diary(List.of(section("recent", playlistOnly)), List.of(
                new DiaryClient.Note(first, "note"), new DiaryClient.Note(null, "unknown"),
                new DiaryClient.Note(first, "duplicate")), List.of(
                lyric("one", second), lyric("two", second), lyric("unknown", null)));

        assertEquals(List.of(first), DiaryPane.tracksForTab(diary, 1));
        assertEquals(List.of(second), DiaryPane.tracksForTab(diary, 2));
        assertTrue(DiaryPane.tracksForTab(diary, -1).isEmpty());
        assertTrue(DiaryPane.tracksForTab(null, 0).isEmpty());
    }

    private static DiaryClient.Section section(String id, Track... tracks) {
        return new DiaryClient.Section(id, id, tracks.length, java.util.Arrays.stream(tracks)
                .map(track -> new DiaryClient.Entry(track, "", "", "", "", "", "", 1, false))
                .toList());
    }

    private static DiaryClient.LyricFavorite lyric(String id, Track track) {
        return new DiaryClient.LyricFavorite(id, track, 0, "", "line");
    }

    private static DiaryClient.Diary diary(List<DiaryClient.Section> sections,
                                          List<DiaryClient.Note> notes,
                                          List<DiaryClient.LyricFavorite> lyrics) {
        return new DiaryClient.Diary("Asia/Shanghai", "", new DiaryClient.Summary(0, 0, 0, 0, 0, 0),
                sections, notes, lyrics);
    }
}
