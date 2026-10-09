package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiaryMetadataTest {
    @Test void preservesKnownUtcDatesAndUnknownLegacyCreationDate() throws Exception {
        String track = "<track id=\"one\" title=\"歌\" artist=\"作者\" durationSeconds=\"60\" source=\"demo\"/>";
        String xml = "<diary zone=\"UTC\" generatedAt=\"2026-10-07T00:00:00Z\"><summary/><notes>"
                + "<note><text>旧备注</text>" + track + "</note>"
                + "<note createdAt=\"2026-10-06T00:00:00Z\" updatedAt=\"2026-10-07T00:00:00Z\">"
                + "<text>新备注</text>" + track + "</note>"
                + "<note updatedAt=\"2026-10-07T00:00:00Z\"><text>旧备注修改</text>" + track
                + "</note></notes><lyricFavorites/></diary>";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/diary", exchange -> {
            byte[] response = xml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var diary = new DiaryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).load();
            assertEquals("", diary.notes().get(0).createdAt());
            assertEquals("", diary.notes().get(0).updatedAt());
            assertEquals("2026-10-06T00:00:00Z", diary.notes().get(1).createdAt());
            assertEquals("2026-10-07T00:00:00Z", diary.notes().get(1).updatedAt());
            assertEquals("", diary.notes().get(2).createdAt());
            assertEquals("2026-10-07T00:00:00Z", diary.notes().get(2).updatedAt());
            assertEquals("", new DiaryClient.Note(diary.notes().get(0).track(), "兼容").createdAt());
        } finally { server.stop(0); }
    }
}
