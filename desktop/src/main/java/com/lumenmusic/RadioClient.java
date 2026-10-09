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
import java.util.Collection;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Private-radio HTTP boundary. Call from a background thread. */
public final class RadioClient {
    public record Item(Track track, String reason) { }
    public record Result(List<Item> items, String status, String message, String intents) {
        public Result { items = List.copyOf(items); }
    }
    public record Feedback(Track track, String kind, String until) { }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public RadioClient(URI base) { this.base = base; }

    public Result generate(List<Track> seeds, String hint, String mode, boolean neutral,
                           Collection<String> exclude) throws Exception {
        StringBuilder xml = new StringBuilder("<radio mode=\"").append(escape(mode))
                .append("\" hint=\"").append(escape(hint))
                .append("\" neutral=\"").append(neutral).append("\"><seed>");
        if (seeds != null) for (Track track : seeds) xml.append(radioTrackXml(track));
        xml.append("</seed>");
        if (exclude != null) for (String id : exclude)
            xml.append("<exclude id=\"").append(escape(id)).append("\"/>");
        xml.append("</radio>");
        Element root = send("POST", "/api/radio", xml.toString());
        if (!"radio".equals(root.getTagName())) throw formatError();
        List<Item> items = new ArrayList<>();
        for (Element node : children(root, "item")) {
            List<Track> tracks = new ArrayList<>();
            LibraryClient.readTracks(node, tracks);
            if (tracks.size() != 1) throw new IllegalStateException("私人电台响应格式错误");
            items.add(new Item(tracks.get(0), node.getAttribute("reason")));
        }
        return new Result(items, root.getAttribute("status"), root.getAttribute("message"),
                root.getAttribute("intents"));
    }

    public List<Feedback> feedback() throws Exception {
        Element root = send("GET", "/api/radio/feedback", null);
        if (!"feedback".equals(root.getTagName())) throw formatError();
        List<Feedback> entries = new ArrayList<>();
        for (Element node : children(root, "entry")) {
            List<Track> tracks = new ArrayList<>();
            LibraryClient.readTracks(node, tracks);
            if (tracks.size() != 1) throw new IllegalStateException("私人电台反馈响应格式错误");
            entries.add(new Feedback(tracks.get(0), node.getAttribute("kind"), node.getAttribute("until")));
        }
        return List.copyOf(entries);
    }

    public List<Feedback> feedback(Track track, String kind) throws Exception {
        Element root = send("POST", "/api/radio/feedback", "<feedback kind=\"" + escape(kind)
                + "\">" + radioTrackXml(track) + "</feedback>");
        if (!"feedback".equals(root.getTagName())) throw formatError();
        List<Feedback> entries = new ArrayList<>();
        for (Element node : children(root, "entry")) {
            List<Track> tracks = new ArrayList<>();
            LibraryClient.readTracks(node, tracks);
            if (tracks.size() != 1) throw new IllegalStateException("私人电台反馈响应格式错误");
            entries.add(new Feedback(tracks.get(0), node.getAttribute("kind"), node.getAttribute("until")));
        }
        return List.copyOf(entries);
    }

    public List<Feedback> restore(String id) throws Exception {
        Element root = send("DELETE", "/api/radio/feedback/" + encode(id), null);
        if (!"feedback".equals(root.getTagName())) throw formatError();
        List<Feedback> entries = new ArrayList<>();
        for (Element node : children(root, "entry")) {
            List<Track> tracks = new ArrayList<>();
            LibraryClient.readTracks(node, tracks);
            if (tracks.size() != 1) throw new IllegalStateException("私人电台反馈响应格式错误");
            entries.add(new Feedback(tracks.get(0), node.getAttribute("kind"), node.getAttribute("until")));
        }
        return List.copyOf(entries);
    }

    private Element send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(path.equals("/api/radio") ? 75 : 30));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/xml; charset=utf-8");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("私人电台 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        return root;
    }

    private static String radioTrackXml(Track track) {
        if (track == null) throw new IllegalArgumentException("Track is required");
        return LibraryClient.trackXml(track);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }

    private static IllegalStateException formatError() {
        return new IllegalStateException("私人电台响应格式错误");
    }
}
