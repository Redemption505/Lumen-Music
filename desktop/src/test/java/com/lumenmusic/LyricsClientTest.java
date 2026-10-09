package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LyricsClientTest {
    @Test void parsesCandidatesAndSendsEncodedQuery() throws Exception {
        var requested = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/lyrics", exchange -> {
            requested.set(exchange.getRequestURI().getRawQuery());
            byte[] xml = ("<lyrics autoId=\"7\"><candidate id=\"7\" title=\"晴天\" "
                    + "artist=\"周杰伦\" duration=\"269\" score=\"100\" "
                    + "reason=\"曲名、歌手及长度匹配\"><lrc>[00:01.00]第一句\n"
                    + "[00:02.00]第二句</lrc></candidate></lyrics>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, xml.length);
            try (var body = exchange.getResponseBody()) { body.write(xml); }
        });
        server.start();
        try {
            var client = new LyricsClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var result = client.search("晴天 & 雨", "周杰伦", 269);
            assertEquals(7, result.autoId());
            assertEquals(2, Lyrics.parse(result.candidates().get(0).lrc()).size());
            assertEquals("晴天 & 雨", URLDecoder.decode(requested.get().split("&")[0].split("=")[1],
                    StandardCharsets.UTF_8));
            assertTrue(result.candidates().get(0).toString().contains("周杰伦"));
        } finally { server.stop(0); }
    }
}
