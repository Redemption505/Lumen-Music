package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Personal organization HTTP boundary. Call from a background thread. */
public final class SmartPlaylistClient {
    public record Rules(boolean favoriteOnly, Integer favoriteOlderDays, String listening,
                        Integer listeningDays, String cached, Integer minDuration,
                        Integer maxDuration, List<String> tagsAll) {
        public Rules { tagsAll = List.copyOf(tagsAll == null ? List.of() : tagsAll); }
        public static Rules empty() { return new Rules(false, null, "any", null, "any", null, null, List.of()); }
    }
    public record Entry(Track track, String favoriteAt, String lastPlayedAt, int playCount,
                        Boolean cached, List<String> tags, List<String> suggestions) {
        public Entry {
            tags = List.copyOf(tags);
            suggestions = List.copyOf(suggestions);
        }
    }
    public record SmartPlaylist(String id, String name, String folderId, Rules rules) {
        @Override public String toString() { return name; }
    }
    public record Folder(String id, String name) {
        @Override public String toString() { return name; }
    }
    public record Assignment(String playlistId, String folderId) { }
    public record State(List<Entry> entries, List<SmartPlaylist> smartPlaylists,
                        List<Folder> folders, List<Assignment> assignments,
                        List<Track> results, String createdPlaylistId) {
        public State {
            entries = List.copyOf(entries);
            smartPlaylists = List.copyOf(smartPlaylists);
            folders = List.copyOf(folders);
            assignments = List.copyOf(assignments);
            results = List.copyOf(results);
        }
    }

    private static final String PATH = "/api/library/organization";
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SmartPlaylistClient(URI base) { this.base = base; }

    public State load() throws Exception { return send("GET", null); }
    public State load(Set<String> cachedIds) throws Exception {
        return send("POST", "<organization action=\"load\">" + cacheXml(cachedIds) + "</organization>");
    }
    public State preview(String id, Rules rules, Set<String> cachedIds) throws Exception {
        return send("POST", "<organization action=\"preview\"" + attr("id", id) + ">"
                + (rules == null ? "" : rulesXml(rules)) + cacheXml(cachedIds) + "</organization>");
    }
    public State save(String id, String name, String folderId, Rules rules) throws Exception {
        return send("POST", "<organization action=\"save\"" + attr("id", id) + attr("name", name)
                + attr("folderId", folderId) + ">" + rulesXml(rules) + "</organization>");
    }
    public State delete(String id) throws Exception { return action("delete", attr("id", id), ""); }
    public State snapshot(String id, String name, String folderId, Rules rules, Set<String> cachedIds)
            throws Exception {
        return send("POST", "<organization action=\"snapshot\"" + attr("id", id)
                + attr("name", name) + attr("folderId", folderId) + ">"
                + (rules == null ? "" : rulesXml(rules)) + cacheXml(cachedIds) + "</organization>");
    }
    public State tags(String mode, List<String> ids, List<String> tags) throws Exception {
        StringBuilder body = new StringBuilder("<ids>");
        for (String id : ids) body.append("<id").append(attr("value", id)).append("/>");
        body.append("</ids><tags>");
        for (String tag : tags) body.append("<tag").append(attr("value", tag)).append("/>");
        return action("tags", attr("mode", mode), body.append("</tags>").toString());
    }
    public State folderSave(String id, String name) throws Exception {
        return action("folderSave", attr("id", id) + attr("name", name), "");
    }
    public State folderDelete(String id) throws Exception { return action("folderDelete", attr("id", id), ""); }
    public State folderAssign(String playlistId, String folderId) throws Exception {
        return action("folderAssign", attr("playlistId", playlistId)
                + attr("folderId", folderId == null ? "" : folderId), "");
    }

    private State action(String action, String attributes, String body) throws Exception {
        return send("POST", "<organization action=\"" + action + "\"" + attributes + ">"
                + body + "</organization>");
    }

    private State send(String method, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(PATH)).timeout(Duration.ofSeconds(15));
        if (body == null) builder.GET();
        else builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/xml; charset=utf-8");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("歌单整理 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"organization".equals(root.getTagName()) || !"1".equals(root.getAttribute("version")))
            throw new IllegalStateException("歌单整理响应格式错误");
        List<Entry> entries = new ArrayList<>();
        for (Element element : children(section(root, "tracks"), "entry")) {
            List<Track> tracks = new ArrayList<>();
            LibraryClient.readTracks(element, tracks);
            if (tracks.size() != 1) throw new IllegalStateException("歌单整理曲目格式错误");
            entries.add(new Entry(tracks.get(0), optional(element, "favoriteAt"),
                    optional(element, "lastPlayedAt"), number(element, "playCount", 0),
                    optional(element, "cached") == null ? null
                            : Boolean.parseBoolean(element.getAttribute("cached")),
                    tagValues(section(element, "tags")), tagValues(section(element, "suggestions"))));
        }
        List<SmartPlaylist> smart = new ArrayList<>();
        for (Element element : children(section(root, "smartPlaylists"), "smartPlaylist"))
            smart.add(new SmartPlaylist(element.getAttribute("id"), element.getAttribute("name"),
                    optional(element, "folderId"), readRules(section(element, "rules"))));
        List<Folder> folders = new ArrayList<>();
        for (Element element : children(section(root, "folders"), "folder"))
            folders.add(new Folder(element.getAttribute("id"), element.getAttribute("name")));
        List<Assignment> assignments = new ArrayList<>();
        for (Element element : children(section(root, "playlistFolders"), "assignment"))
            assignments.add(new Assignment(element.getAttribute("playlistId"), element.getAttribute("folderId")));
        List<Track> results = new ArrayList<>();
        Element resultSection = section(root, "results");
        if (resultSection != null) LibraryClient.readTracks(resultSection, results);
        return new State(entries, smart, folders, assignments, results, optional(root, "createdPlaylistId"));
    }

    private static Rules readRules(Element element) {
        if (element == null) return Rules.empty();
        return new Rules(Boolean.parseBoolean(element.getAttribute("favoriteOnly")),
                nullableNumber(element, "favoriteOlderDays"), value(element, "listening", "any"),
                nullableNumber(element, "listeningDays"), value(element, "cached", "any"),
                nullableNumber(element, "minDuration"), nullableNumber(element, "maxDuration"),
                tagValues(element));
    }
    private static String rulesXml(Rules rules) {
        StringBuilder body = new StringBuilder("<rules favoriteOnly=\"").append(rules.favoriteOnly())
                .append('"').append(attr("listening", rules.listening()))
                .append(attr("cached", rules.cached()));
        if (rules.favoriteOlderDays() != null) body.append(attr("favoriteOlderDays", rules.favoriteOlderDays().toString()));
        if (rules.listeningDays() != null) body.append(attr("listeningDays", rules.listeningDays().toString()));
        if (rules.minDuration() != null) body.append(attr("minDuration", rules.minDuration().toString()));
        if (rules.maxDuration() != null) body.append(attr("maxDuration", rules.maxDuration().toString()));
        body.append('>');
        for (String tag : rules.tagsAll()) body.append("<tag").append(attr("value", tag)).append("/>");
        return body.append("</rules>").toString();
    }
    private static String cacheXml(Set<String> ids) {
        if (ids == null) return "";
        StringBuilder body = new StringBuilder("<cachedIds>");
        if (ids != null) for (String id : ids) body.append("<id").append(attr("value", id)).append("/>");
        return body.append("</cachedIds>").toString();
    }
    private static String attr(String name, String value) {
        return value == null ? "" : " " + name + "=\"" + escape(value) + '"';
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }
    private static String optional(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? null : value;
    }
    private static String value(Element element, String name, String fallback) {
        String value = element.getAttribute(name);
        return value.isBlank() ? fallback : value;
    }
    private static Integer nullableNumber(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? null : Integer.valueOf(value);
    }
    private static int number(Element element, String name, int fallback) {
        Integer value = nullableNumber(element, name);
        return value == null ? fallback : value;
    }
    private static Element section(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        return null;
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
        return result;
    }
    private static List<String> tagValues(Element parent) {
        List<String> values = new ArrayList<>();
        for (Element tag : children(parent, "tag")) values.add(tag.getAttribute("value"));
        return values;
    }
}
