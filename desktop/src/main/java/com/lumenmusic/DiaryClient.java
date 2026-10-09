package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Music-diary HTTP boundary. Call from a background thread. */
public final class DiaryClient {
    public record Summary(long totalPlays, long thisMonthPlays, long sourceCount,
                          long trackCount, long songCount, long unknownFirstCount) { }
    public record Entry(Track track, String playedAt, String firstPlayedAt, String favoriteAt,
                        String songId, String playlistId, String playlistName, long playCount,
                        boolean legacy) { }
    public record Section(String id, String title, long count, List<Entry> entries) {
        public Section { entries = List.copyOf(entries); }
    }
    public record Note(Track track, String text, String createdAt, String updatedAt) {
        public Note(Track track, String text) { this(track, text, "", ""); }
        public Note {
            createdAt = createdAt == null ? "" : createdAt;
            updatedAt = updatedAt == null ? "" : updatedAt;
        }
    }
    public record LyricFavorite(String id, Track track, long positionMillis,
                                String createdAt, String text) { }
    public record Diary(String zone, String generatedAt, Summary summary, List<Section> sections,
                        List<Note> notes, List<LyricFavorite> lyricFavorites) {
        public Diary {
            sections = List.copyOf(sections);
            notes = List.copyOf(notes);
            lyricFavorites = List.copyOf(lyricFavorites);
        }
        public Section section(String id) {
            return sections.stream().filter(section -> section.id().equals(id)).findFirst()
                    .orElse(new Section(id, "", 0, List.of()));
        }
    }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public DiaryClient(URI base) { this.base = base; }

    public Diary load() throws Exception {
        return send("GET", "/api/diary" + zoneQuery(), null);
    }

    public Diary saveNote(Track track, String text) throws Exception {
        requireTrack(track);
        return send("POST", "/api/diary/notes" + zoneQuery(), "<note trackId=\"" + escape(track.id())
                + "\"><text>" + escape(text) + "</text>" + LibraryClient.trackXml(track) + "</note>");
    }

    public Diary saveLyric(Track track, long positionMillis, String text) throws Exception {
        requireTrack(track);
        if (positionMillis < 0 || text == null || text.isBlank())
            throw new IllegalArgumentException("歌词片段不能为空，位置不能为负数");
        return send("POST", "/api/diary/lyrics" + zoneQuery(), "<lyric trackId=\"" + escape(track.id())
                + "\" positionMillis=\"" + positionMillis + "\"><text>" + escape(text)
                + "</text>" + LibraryClient.trackXml(track) + "</lyric>");
    }

    public Diary deleteLyric(String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("歌词收藏 ID 不能为空");
        return send("DELETE", "/api/diary/lyrics/" + encode(id) + zoneQuery(), null);
    }

    private Diary send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/xml; charset=utf-8");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("最近播放 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"diary".equals(root.getTagName())) throw new IllegalStateException("最近播放响应格式错误");
        Element summaryNode = child(root, "summary");
        Summary summary = new Summary(number(summaryNode, "totalPlays"),
                number(summaryNode, "thisMonthPlays"), number(summaryNode, "sourceCount"),
                number(summaryNode, "trackCount"), number(summaryNode, "songCount"),
                number(summaryNode, "unknownFirstCount"));
        List<Section> sections = new ArrayList<>();
        for (Element section : children(root, "section")) {
            List<Entry> entries = new ArrayList<>();
            for (Element entry : children(section, "entry"))
                entries.add(new Entry(track(entry), attribute(entry, "playedAt"),
                        attribute(entry, "firstPlayedAt"), attribute(entry, "favoriteAt"),
                        attribute(entry, "songId"), attribute(entry, "playlistId"),
                        attribute(entry, "playlistName"), number(entry, "playCount"),
                        Boolean.parseBoolean(attribute(entry, "legacy"))));
            sections.add(new Section(section.getAttribute("id"), section.getAttribute("title"),
                    number(section, "count"), entries));
        }
        List<Note> notes = new ArrayList<>();
        Element notesNode = child(root, "notes");
        for (Element note : children(notesNode, "note"))
            notes.add(new Note(track(note), text(note), attribute(note, "createdAt"), attribute(note, "updatedAt")));
        List<LyricFavorite> lyrics = new ArrayList<>();
        Element lyricsNode = child(root, "lyricFavorites");
        for (Element lyric : children(lyricsNode, "lyric"))
            lyrics.add(new LyricFavorite(lyric.getAttribute("id"), track(lyric),
                    number(lyric, "positionMillis"), attribute(lyric, "createdAt"), text(lyric)));
        return new Diary(attribute(root, "zone"), attribute(root, "generatedAt"),
                summary, sections, notes, lyrics);
    }

    private static Track track(Element parent) {
        if (parent == null) return null;
        List<Track> tracks = new ArrayList<>();
        Element element = child(parent, "track");
        if (element != null) LibraryClient.readTracks(parent, tracks);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    private static String text(Element parent) {
        Element text = child(parent, "text");
        return text == null ? "" : text.getTextContent();
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
        return result;
    }

    private static long number(Element node, String name) {
        if (node == null || node.getAttribute(name).isBlank()) return 0;
        return Long.parseLong(node.getAttribute(name));
    }

    private static String attribute(Element node, String name) {
        return node == null ? "" : node.getAttribute(name);
    }

    private static void requireTrack(Track track) {
        if (track == null || track.id() == null || track.id().isBlank())
            throw new IllegalArgumentException("曲目不能为空");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String zoneQuery() {
        return "?zone=" + encode(ZoneId.systemDefault().getId());
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }
}
