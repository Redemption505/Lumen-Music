package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Local-service boundary for conservative, manually reviewable lyric matches. */
final class LyricsClient {
    record Candidate(int id, String title, String artist, int durationSeconds,
                     int score, String reason, String lrc) {
        @Override public String toString() {
            return title + " · " + artist + " (" + durationSeconds + " 秒，" + reason + ")";
        }
    }
    record Lookup(List<Candidate> candidates, Integer autoId) { }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    LyricsClient(URI base) { this.base = base; }

    Lookup search(String title, String artist, int durationSeconds) throws Exception {
        String query = "title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&artist=" + URLEncoder.encode(artist, StandardCharsets.UTF_8)
                + "&duration=" + Math.max(0, durationSeconds);
        URI uri = base.resolve("/api/lyrics?" + query);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = root.getTagName().equals("error") ? root.getAttribute("message") : "";
            throw new IllegalStateException("歌词服务返回 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!root.getTagName().equals("lyrics")) throw new IllegalStateException("歌词响应格式错误");
        var nodes = root.getElementsByTagName("candidate");
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            var element = (Element) nodes.item(index);
            var lrcNodes = element.getElementsByTagName("lrc");
            String lrc = lrcNodes.getLength() == 0 ? "" : lrcNodes.item(0).getTextContent();
            candidates.add(new Candidate(Integer.parseInt(element.getAttribute("id")),
                    element.getAttribute("title"), element.getAttribute("artist"),
                    Integer.parseInt(element.getAttribute("duration")),
                    Integer.parseInt(element.getAttribute("score")),
                    element.getAttribute("reason"), lrc));
        }
        String auto = root.getAttribute("autoId");
        Integer autoId = auto.isBlank() ? null : Integer.valueOf(auto);
        return new Lookup(List.copyOf(candidates), autoId);
    }
}
