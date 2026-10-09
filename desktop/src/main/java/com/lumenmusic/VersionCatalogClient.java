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
import org.w3c.dom.Node;

/** Version catalog HTTP boundary. Call from a background thread. */
public final class VersionCatalogClient {
    private static final String PATH = "/api/library/versions";

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public VersionCatalogClient(URI base) { this.base = base; }

    public VersionCatalog load() throws Exception { return send("GET", PATH, null); }

    public VersionCatalog create(String title, String note, List<VersionCatalog.Version> versions) throws Exception {
        if (versions == null || versions.isEmpty()) throw new IllegalArgumentException("At least one version is required");
        StringBuilder xml = new StringBuilder("<song title=\"").append(escape(title))
                .append("\" note=\"").append(escape(note)).append("\">");
        for (var version : versions) xml.append(versionXml(version));
        return send("POST", PATH, xml.append("</song>").toString());
    }

    public VersionCatalog update(String songId, String title, String note, String preferredId) throws Exception {
        StringBuilder xml = new StringBuilder("<song");
        if (title != null) xml.append(" title=\"").append(escape(title)).append('"');
        if (note != null) xml.append(" note=\"").append(escape(note)).append('"');
        if (preferredId != null) xml.append(" preferredVersionId=\"").append(escape(preferredId)).append('"');
        return send("PATCH", PATH + "/" + encode(songId), xml.append("/>").toString());
    }

    public VersionCatalog add(String songId, String label, String performer, Track track) throws Exception {
        return send("POST", PATH + "/" + encode(songId) + "/versions",
                versionXml(new VersionCatalog.Version(label, performer, track)));
    }

    public VersionCatalog updateVersion(String songId, String versionId, String label, String performer)
            throws Exception {
        return send("PATCH", PATH + "/" + encode(songId) + "/versions/" + encode(versionId),
                "<version label=\"" + escape(label) + "\" performer=\"" + escape(performer) + "\"/>");
    }

    public VersionCatalog merge(String songId, String otherSongId) throws Exception {
        return send("POST", PATH + "/" + encode(songId) + "/merge",
                "<merge songId=\"" + escape(otherSongId) + "\"/>");
    }

    public VersionCatalog detach(String songId, String versionId) throws Exception {
        return send("DELETE", PATH + "/" + encode(songId) + "/versions/" + encode(versionId), null);
    }

    public VersionCatalog replace(String songId, String versionId, Track track) throws Exception {
        return send("POST", PATH + "/" + encode(songId) + "/versions/" + encode(versionId)
                + "/replace", LibraryClient.trackXml(track));
    }

    public VersionCatalog undo() throws Exception { return send("POST", PATH + "/undo", null); }

    private VersionCatalog send(String method, String path, String body) throws Exception {
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
            throw new IllegalStateException("版本目录 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"catalog".equals(root.getTagName()))
            throw new IllegalStateException("版本目录响应格式错误");
        List<VersionCatalog.Song> songs = new ArrayList<>();
        for (Element song : children(root, "song")) {
            List<VersionCatalog.Version> versions = new ArrayList<>();
            for (Element version : children(song, "version")) {
                List<Track> tracks = new ArrayList<>();
                LibraryClient.readTracks(version, tracks);
                if (tracks.size() != 1) throw new IllegalStateException("版本目录响应中的版本缺少唯一音源");
                versions.add(new VersionCatalog.Version(version.getAttribute("id"),
                        version.getAttribute("label"), version.getAttribute("performer"), tracks.get(0)));
            }
            songs.add(new VersionCatalog.Song(song.getAttribute("id"), song.getAttribute("title"),
                    song.getAttribute("note"), song.getAttribute("preferredVersionId"), versions));
        }
        return new VersionCatalog(songs);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
        }
        return result;
    }

    private static String versionXml(VersionCatalog.Version version) {
        if (version == null || version.track() == null)
            throw new IllegalArgumentException("Version and track are required");
        return "<version label=\"" + escape(version.label()) + "\" performer=\""
                + escape(version.performer()) + "\">" + LibraryClient.trackXml(version.track()) + "</version>";
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }
}
