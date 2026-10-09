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
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Personal-library HTTP boundary. Call from a background thread. */
public final class LibraryClient {
    public record Playlist(String id, String name, List<Track> tracks,
                           String description, String coverDataUri) {
        public Playlist(String id, String name, List<Track> tracks) {
            this(id, name, tracks, "", "");
        }
        public Playlist {
            tracks = List.copyOf(tracks);
            description = description == null ? "" : description;
            coverDataUri = coverDataUri == null ? "" : coverDataUri;
        }
        @Override public String toString() { return name + "（" + tracks.size() + "）"; }
    }
    public record Snapshot(List<Track> favorites, List<Playlist> playlists) { }
    public record EditResult(Snapshot snapshot, String undoToken) { }
    public record BatchResult(Snapshot snapshot, int savedCount, int skippedCount, String playlistId) { }
    public record ImportResult(Snapshot snapshot, String name, int importedCount,
                               int skippedCount, int duplicateCount, boolean truncated) { }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public LibraryClient(URI base) { this.base = base; }

    public Snapshot load() throws Exception { return send("GET", "/api/library", null); }
    public Snapshot favorite(Track track) throws Exception {
        return send("POST", "/api/library/favorites", trackXml(track));
    }
    public Snapshot unfavorite(String trackId) throws Exception {
        return send("DELETE", "/api/library/favorites/" + encode(trackId), null);
    }
    public Snapshot updateSegment(Track track) throws Exception {
        if (track == null || !track.isSegment()) throw new IllegalArgumentException("Segment is required");
        return send("PATCH", "/api/library/tracks/" + encode(track.id()), trackXml(track));
    }
    public Snapshot deleteSegment(String trackId) throws Exception {
        return send("DELETE", "/api/library/tracks/" + encode(trackId), null);
    }
    public BatchResult saveSegments(List<Track> tracks, String playlistName, String batchId) throws Exception {
        if (tracks == null || tracks.isEmpty() || tracks.size() > 200
                || tracks.stream().anyMatch(track -> track == null || !track.isSegment()))
            throw new IllegalArgumentException("A batch requires 1 to 200 segments");
        if (batchId == null || !UUID.fromString(batchId).toString().equals(batchId))
            throw new IllegalArgumentException("A canonical batch UUID is required");
        if (playlistName != null && playlistName.isBlank())
            throw new IllegalArgumentException("Playlist name is required");
        StringBuilder body = new StringBuilder("<segments destination=\"")
                .append(playlistName == null ? "favorites" : "playlist")
                .append("\" batchId=\"").append(escape(batchId)).append('"');
        if (playlistName != null) body.append(" name=\"").append(escape(playlistName.trim())).append('"');
        body.append('>');
        for (Track track : tracks) body.append(trackXml(track));
        Element root = sendRoot("POST", "/api/library/segments/batch", body.append("</segments>").toString());
        String playlistId = root.getAttribute("playlistId");
        return new BatchResult(readSnapshot(root), Integer.parseInt(root.getAttribute("savedCount")),
                Integer.parseInt(root.getAttribute("skippedCount")), playlistId.isBlank() ? null : playlistId);
    }
    public Snapshot moveFavorite(int from, int to) throws Exception {
        return send("POST", "/api/library/favorites/move", moveXml(from, to));
    }
    public Snapshot movePlaylist(int from, int to) throws Exception {
        return send("POST", "/api/library/playlists/move", moveXml(from, to));
    }
    public Snapshot createPlaylist(String name) throws Exception {
        return send("POST", "/api/library/playlists", "<playlist name=\"" + escape(name) + "\"/>");
    }
    public Snapshot renamePlaylist(String id, String name) throws Exception {
        return send("PATCH", "/api/library/playlists/" + encode(id),
                "<playlist name=\"" + escape(name) + "\"/>");
    }
    /** Null omits a field; an empty string clears it. */
    public Snapshot updatePlaylistMetadata(String id, String description, String coverDataUri) throws Exception {
        if (description == null && coverDataUri == null)
            throw new IllegalArgumentException("歌单更新内容不能为空");
        StringBuilder body = new StringBuilder("<playlist>");
        if (description != null) body.append("<description>").append(escape(description))
                .append("</description>");
        if (coverDataUri != null) body.append("<coverDataUri>").append(escape(coverDataUri))
                .append("</coverDataUri>");
        return send("PATCH", "/api/library/playlists/" + encode(id), body.append("</playlist>").toString());
    }
    public Snapshot deletePlaylist(String id) throws Exception {
        return send("DELETE", "/api/library/playlists/" + encode(id), null);
    }
    public Snapshot addToPlaylist(String id, Track track) throws Exception {
        return send("POST", "/api/library/playlists/" + encode(id) + "/tracks", trackXml(track));
    }
    public Snapshot removeFromPlaylist(String id, String trackId) throws Exception {
        return send("DELETE", "/api/library/playlists/" + encode(id) + "/tracks/" + encode(trackId), null);
    }
    public EditResult removeTracksFromPlaylist(String id, List<String> trackIds) throws Exception {
        if (trackIds == null || trackIds.isEmpty() || trackIds.stream().anyMatch(
                trackId -> trackId == null || trackId.isBlank()))
            throw new IllegalArgumentException("Track IDs are required");
        StringBuilder body = new StringBuilder("<remove>");
        for (String trackId : trackIds)
            body.append("<track id=\"").append(escape(trackId)).append("\"/>");
        return sendEdit("POST", "/api/library/playlists/" + encode(id) + "/tracks/remove",
                body.append("</remove>").toString());
    }
    public Snapshot undoPlaylistRemoval(String id, String token) throws Exception {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Undo token is required");
        return send("POST", "/api/library/playlists/" + encode(id) + "/tracks/undo",
                "<undo token=\"" + escape(token) + "\"/>");
    }
    public Snapshot moveInPlaylist(String id, int from, int to) throws Exception {
        return send("POST", "/api/library/playlists/" + encode(id) + "/move", moveXml(from, to));
    }

    public ImportResult importPublicFavorite(String url) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/api/library/playlists/import"))
                .timeout(Duration.ofSeconds(150))
                .header("Content-Type", "application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "<import url=\"" + escape(url) + "\"/>", StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("收藏夹导入 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"import".equals(root.getTagName()))
            throw new IllegalStateException("收藏夹导入响应格式错误");
        return new ImportResult(load(), root.getAttribute("name"),
                Integer.parseInt(root.getAttribute("importedCount")),
                Integer.parseInt(root.getAttribute("skippedCount")),
                Integer.parseInt(root.getAttribute("duplicateCount")),
                Boolean.parseBoolean(root.getAttribute("truncated")));
    }

    private Snapshot send(String method, String path, String body) throws Exception {
        return sendEdit(method, path, body).snapshot();
    }

    private EditResult sendEdit(String method, String path, String body) throws Exception {
        Element root = sendRoot(method, path, body);
        return new EditResult(readSnapshot(root), root.getAttribute("undoToken"));
    }

    private Element sendRoot(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(12));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/xml; charset=utf-8");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("个人曲库 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"library".equals(root.getTagName()) || !"1".equals(root.getAttribute("version")))
            throw new IllegalStateException("个人曲库响应格式错误");
        return root;
    }

    private static Snapshot readSnapshot(Element root) {
        var favorites = new ArrayList<Track>();
        Element favoriteNode = (Element) root.getElementsByTagName("favorites").item(0);
        if (favoriteNode != null) readTracks(favoriteNode, favorites);
        var playlists = new ArrayList<Playlist>();
        Element playlistNode = (Element) root.getElementsByTagName("playlists").item(0);
        if (playlistNode != null) {
            var nodes = playlistNode.getElementsByTagName("playlist");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                var tracks = new ArrayList<Track>();
                readTracks(node, tracks);
                playlists.add(new Playlist(node.getAttribute("id"), node.getAttribute("name"), List.copyOf(tracks),
                        metadataText(node, "description"), metadataText(node, "coverDataUri")));
            }
        }
        return new Snapshot(List.copyOf(favorites), List.copyOf(playlists));
    }

    private static String metadataText(Element playlist, String name) {
        for (var child = playlist.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element && name.equals(element.getTagName()))
                return element.getTextContent();
        return "";
    }

    static void readTracks(Element parent, List<Track> tracks) {
        var nodes = parent.getElementsByTagName("track");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            String mediaUri = node.getAttribute("mediaUri");
            String coverUri = node.getAttribute("coverUri");
            String page = node.getAttribute("page");
            String partCount = node.getAttribute("partCount");
            tracks.add(new Track(node.getAttribute("id"), node.getAttribute("title"),
                    node.getAttribute("artist"), Integer.parseInt(node.getAttribute("durationSeconds")),
                    node.getAttribute("source"), mediaUri.isBlank() ? null : URI.create(mediaUri),
                    node.getAttribute("bvid").isBlank() ? null : node.getAttribute("bvid"),
                    page.isBlank() ? 0 : Integer.parseInt(page),
                    coverUri.isBlank() ? null : URI.create(coverUri),
                    partCount.isBlank() ? 0 : Integer.parseInt(partCount),
                    node.getAttribute("viewCount").isBlank() ? 0 : Long.parseLong(node.getAttribute("viewCount")),
                    node.getAttribute("sourceTrackId").isBlank() ? null : node.getAttribute("sourceTrackId"),
                    node.getAttribute("segmentStartMillis").isBlank() ? 0 : Long.parseLong(node.getAttribute("segmentStartMillis")),
                    node.getAttribute("segmentEndMillis").isBlank() ? 0 : Long.parseLong(node.getAttribute("segmentEndMillis")),
                    node.getAttribute("sourceDurationSeconds").isBlank() ? 0 : Integer.parseInt(node.getAttribute("sourceDurationSeconds"))));
        }
    }

    static String trackXml(Track track) {
        StringBuilder xml = new StringBuilder("<track id=\"").append(escape(track.id()))
                .append("\" title=\"").append(escape(track.title()))
                .append("\" artist=\"").append(escape(track.artist()))
                .append("\" durationSeconds=\"").append(track.durationSeconds())
                .append("\" source=\"").append(escape(track.source())).append('"');
        if (track.bvid() != null && !track.bvid().isBlank())
            xml.append(" bvid=\"").append(escape(track.bvid())).append('"');
        if (track.page() > 0) xml.append(" page=\"").append(track.page()).append('"');
        if (track.mediaUri() != null)
            xml.append(" mediaUri=\"").append(escape(track.mediaUri().toString())).append('"');
        if (track.coverUri() != null)
            xml.append(" coverUri=\"").append(escape(track.coverUri().toString())).append('"');
        if (track.partCount() > 0) xml.append(" partCount=\"").append(track.partCount()).append('"');
        if (track.viewCount() > 0) xml.append(" viewCount=\"").append(track.viewCount()).append('"');
        if (track.isSegment())
            xml.append(" sourceTrackId=\"").append(escape(track.sourceTrackId()))
                    .append("\" segmentStartMillis=\"").append(track.segmentStartMillis())
                    .append("\" segmentEndMillis=\"").append(track.segmentEndMillis())
                    .append("\" sourceDurationSeconds=\"").append(track.sourceDurationSeconds()).append('"');
        return xml.append("/>").toString();
    }
    private static String moveXml(int from, int to) {
        return "<move from=\"" + from + "\" to=\"" + to + "\"/>";
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
