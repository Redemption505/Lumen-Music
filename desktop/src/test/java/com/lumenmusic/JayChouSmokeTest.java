package com.lumenmusic;

import java.net.URI;
import java.util.List;

/** Live Jay Chou video search, audio availability and personal-library integration. */
public final class JayChouSmokeTest {
    public static void main(String[] args) throws Exception {
        URI base = URI.create(System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765"));
        var music = new MusicClient(base);
        var library = new LibraryClient(base);
        Track sunny = find(music.search("周杰伦 晴天"), "晴天");
        Track porcelain = find(music.search("周杰伦 青花瓷"), "青花瓷");
        for (Track track : List.of(sunny, porcelain)) {
            check(track.bvid() != null && track.page() == 1, "缺少 BV 与分 P：" + track);
            music.checkStream(track);
            library.favorite(track);
        }
        var snapshot = library.createPlaylist("周杰伦相关视频验收");
        String playlistId = snapshot.playlists().get(snapshot.playlists().size() - 1).id();
        library.addToPlaylist(playlistId, sunny);
        library.addToPlaylist(playlistId, porcelain);
        snapshot = library.load();
        check(snapshot.favorites().stream().anyMatch(track -> track.bvid().equals(sunny.bvid())),
                "晴天收藏未恢复");
        check(snapshot.favorites().stream().anyMatch(track -> track.bvid().equals(porcelain.bvid())),
                "青花瓷收藏未恢复");
        var playlist = snapshot.playlists().stream().filter(item -> item.id().equals(playlistId))
                .findFirst().orElseThrow();
        check(playlist.tracks().size() == 2, "歌单未保存两条真实视频曲目");
        System.out.println("PASS: 周杰伦相关视频搜索、AAC 音轨预检、收藏与歌单："
                + sunny.bvid() + "、" + porcelain.bvid());
    }

    private static Track find(List<Track> tracks, String song) {
        return tracks.stream().filter(track -> track.title().contains("周杰伦")
                && track.title().contains(song)).findFirst()
                .orElseThrow(() -> new AssertionError("未找到周杰伦《" + song + "》相关视频"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
