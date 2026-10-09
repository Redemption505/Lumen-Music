package com.lumenmusic;

import java.net.URI;

/** Java-to-Python library contract check against a temporary demo service. */
public final class LibrarySmokeTest {
    public static void main(String[] args) throws Exception {
        URI base = URI.create(System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765"));
        var client = new LibraryClient(base);
        var tracks = new MusicClient(base).search("");
        check(tracks.size() >= 2, "演示曲目不足");
        Track first = tracks.get(0);
        Track second = tracks.get(1);
        check(client.load().favorites().isEmpty(), "测试曲库必须为空");

        var snapshot = client.favorite(first);
        check(snapshot.favorites().size() == 1 && snapshot.favorites().get(0).equals(first), "收藏序列化失败");
        check(client.favorite(first).favorites().size() == 1, "重复收藏未去重");
        client.favorite(second);
        snapshot = client.moveFavorite(1, 0);
        check(snapshot.favorites().get(0).id().equals(second.id()), "收藏排序失败");

        snapshot = client.createPlaylist("周杰伦相关视频 <测试>");
        check(snapshot.playlists().size() == 1, "新建歌单失败");
        String playlistId = snapshot.playlists().get(0).id();
        client.addToPlaylist(playlistId, first);
        snapshot = client.addToPlaylist(playlistId, second);
        check(snapshot.playlists().get(0).tracks().size() == 2, "歌单添加曲目失败");
        snapshot = client.moveInPlaylist(playlistId, 1, 0);
        check(snapshot.playlists().get(0).tracks().get(0).id().equals(second.id()), "歌单排序失败");
        snapshot = client.renamePlaylist(playlistId, "新的 & 歌单");
        check(snapshot.playlists().get(0).name().equals("新的 & 歌单"), "歌单名称 XML 转义失败");
        URI localUri = URI.create("file:///C:/Music/%E6%B5%8B%E8%AF%95%20song.mp3");
        Track local = new Track(localUri.toString(), "测试 song", "本地文件", 0, "local",
                localUri, null, 0, URI.create("https://example.com/cover.jpg?a=1&b=2"));
        snapshot = client.favorite(local);
        check(snapshot.favorites().contains(local), "本地文件和封面 URI 未完整往返保存");
        client.unfavorite(local.id());
        Track clipA = Track.segment(first, "片段 A", "演示歌手", 1_000, 3_500);
        Track clipB = Track.segment(first, "片段 B", "演示歌手", 2_000, 6_000);
        snapshot = client.favorite(clipA);
        check(snapshot.favorites().contains(clipA), "片段收藏往返失败");
        client.favorite(clipB);
        snapshot = client.addToPlaylist(playlistId, clipA);
        check(snapshot.playlists().get(0).tracks().contains(clipA), "片段加入歌单失败");
        Track edited = clipA.withSegment("片段 A 改名", "新歌手", 1_500, 4_000);
        snapshot = client.updateSegment(edited);
        check(snapshot.favorites().contains(edited), "片段编辑未更新收藏");
        check(snapshot.playlists().get(0).tracks().contains(edited), "片段编辑未同步歌单");
        check(client.deleteSegment(clipB.id()).favorites().contains(edited), "删除一个片段影响了另一个");
        snapshot = client.deleteSegment(clipA.id());
        check(!snapshot.favorites().contains(clipA)
                && !snapshot.playlists().get(0).tracks().contains(clipA), "片段删除未清理歌单引用");
        client.removeFromPlaylist(playlistId, first.id());
        client.deletePlaylist(playlistId);
        client.unfavorite(first.id());
        snapshot = client.unfavorite(second.id());
        check(snapshot.favorites().isEmpty() && snapshot.playlists().isEmpty(), "曲库清理失败");
        System.out.println("PASS: Java ↔ Python library favorites, playlists, order, deduplication, XML, segments");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
