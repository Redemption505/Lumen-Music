package com.lumenmusic;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuickSearchIndexTest {
    @Test void combinesLibrarySourcesWithoutDuplicatingSongs() {
        Track shared = new Track("shared", "Moonlight", "Alice", 90, "local");
        Track version = new Track("version", "Moonlight Live", "Alice", 95, "bilibili");
        LibraryClient.Playlist playlist = new LibraryClient.Playlist("p1", "晚间歌单", List.of(shared));
        QuickSearchIndex index = QuickSearchIndex.build(List.of(shared), List.of(playlist),
                List.of(shared, version), List.of(shared), List.of());

        List<QuickSearchIndex.Result> songs = index.search("moonlight").stream()
                .filter(result -> result.type() == QuickSearchIndex.Type.TRACK).toList();
        assertEquals(2, songs.size());
        assertSame(shared, songs.get(0).track());
        assertTrue(songs.get(0).subtitle().contains("Alice"));
        assertTrue(songs.get(0).subtitle().contains("收藏"));
        assertTrue(songs.get(0).subtitle().contains("晚间歌单"));
        assertTrue(songs.get(0).subtitle().contains("版本"));
        assertTrue(songs.get(0).subtitle().contains("本地"));
    }

    @Test void searchesPlaylistsCommandsAndArtistWithUsefulRanking() {
        Track track = new Track("t1", "Cloud", "Night Singer", 120, "local");
        LibraryClient.Playlist playlist = new LibraryClient.Playlist("p1", "Night Drive", List.of(track));
        QuickSearchIndex.Command command = new QuickSearchIndex.Command("theme", "夜间模式", "切换主题", () -> { });
        QuickSearchIndex index = QuickSearchIndex.build(List.of(track), List.of(playlist),
                List.of(), List.of(), List.of(command));

        assertEquals(QuickSearchIndex.Type.PLAYLIST, index.search("night").get(0).type());
        assertEquals(QuickSearchIndex.Type.TRACK, index.search("singer").get(0).type());
        assertEquals(command, index.search("夜间").get(0).command());
        assertTrue(index.search("nothing").isEmpty());
        assertEquals(QuickSearchIndex.Type.COMMAND, index.search("").get(0).type());
    }
}
