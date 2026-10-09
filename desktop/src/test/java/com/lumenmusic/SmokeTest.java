package com.lumenmusic;

import java.net.URI;
import java.util.ArrayList;

/** Runs against the real Python service, without a test framework dependency. */
public final class SmokeTest {
    public static void main(String[] args) throws Exception {
        var client = new MusicClient(URI.create(args.length == 0
                ? System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765") : args[0]));
        var tracks = client.search("");
        check(tracks.size() >= 2, "演示曲库缺少曲目");
        var mutable = new ArrayList<>(tracks);
        var queue = new PlayerQueue();
        queue.select(mutable, 0);
        mutable.clear();
        check(queue.current().equals(tracks.get(0)), "队列必须保存独立快照");
        check(client.search("不存在的曲目-zzzz").isEmpty(), "搜索应正确过滤");
        check(queue.current().equals(tracks.get(0)), "搜索不能改动队列");
        check(queue.move(-1).equals(tracks.get(tracks.size() - 1)), "上一首应循环到末尾");
        check(queue.move(1).equals(tracks.get(0)), "下一首应循环到首部");
        check(!client.search(tracks.get(0).title()).isEmpty(), "中文搜索和 XML 解析失败");
        System.out.println("PASS: Java ↔ Python HTTP、中文搜索、队列快照、循环切换");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
