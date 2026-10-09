package com.lumenmusic;

import java.net.URI;

/** Optional end-to-end check against a running Bilibili-backed local service. */
public final class RealSearchSmoke {
    public static void main(String[] args) throws Exception {
        var base = URI.create(System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765"));
        var tracks = new MusicClient(base).search("周杰伦");
        if (tracks.isEmpty()) throw new AssertionError("真实搜索没有返回曲目");
        for (var track : tracks) {
            if (!"bilibili".equals(track.source()) || !track.bvid().startsWith("BV") || track.page() < 1)
                throw new AssertionError("真实曲目缺少 BV / 分 P 身份：" + track);
        }
        System.out.println("PASS: Java → Python → Bilibili 搜索；" + tracks.size()
                + " 首；首项 " + tracks.get(0).bvid());
    }
}
