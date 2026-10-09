package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Persisted home layout boundary. Call from a background thread. */
public final class HomeClient {
    public static final List<String> DEFAULT_SECTIONS = List.of("continue", "pinned", "reunion", "discover");
    public record Pin(String kind, String id) {
        public Pin {
            if (!("playlist".equals(kind) || "smart".equals(kind)) || id == null || id.isBlank())
                throw new IllegalArgumentException("首页常驻需要普通或智能歌单 ID");
        }
    }
    public record Preferences(List<String> sections, List<Pin> pinned) {
        public Preferences {
            sections = List.copyOf(sections);
            pinned = List.copyOf(pinned);
            if (sections.size() != DEFAULT_SECTIONS.size()
                    || !new HashSet<>(sections).equals(new HashSet<>(DEFAULT_SECTIONS)))
                throw new IllegalArgumentException("首页区域必须是四个区域的完整排列");
            if (pinned.size() > 6 || new HashSet<>(pinned).size() != pinned.size())
                throw new IllegalArgumentException("首页常驻最多六个，且不能重复");
        }
    }
    public static Preferences defaults() { return new Preferences(DEFAULT_SECTIONS, List.of()); }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public HomeClient(URI base) { this.base = base; }
    public Preferences load() throws Exception { return send(null); }
    public Preferences save(Preferences preferences) throws Exception {
        StringBuilder body = new StringBuilder("<home version=\"1\"><sections>");
        for (String section : preferences.sections())
            body.append("<section id=\"").append(escape(section)).append("\"/>");
        body.append("</sections><pinned>");
        for (Pin pin : preferences.pinned())
            body.append("<pin kind=\"").append(pin.kind()).append("\" id=\"")
                    .append(escape(pin.id())).append("\"/>");
        return send(body.append("</pinned></home>").toString());
    }

    private Preferences send(String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/api/library/home")).timeout(Duration.ofSeconds(15));
        if (body == null) request.GET();
        else request.header("Content-Type", "application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("首页偏好 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"home".equals(root.getTagName()) || !"1".equals(root.getAttribute("version")))
            throw new IllegalStateException("首页偏好响应格式或版本错误");
        List<Element> children = children(root);
        if (children.size() != 2 || !"sections".equals(children.get(0).getTagName())
                || !"pinned".equals(children.get(1).getTagName()))
            throw new IllegalStateException("首页偏好响应区域缺失");
        List<String> sections = new ArrayList<>();
        for (Element section : children(children.get(0))) {
            if (!"section".equals(section.getTagName()) || !children(section).isEmpty())
                throw new IllegalStateException("首页区域响应格式错误");
            sections.add(section.getAttribute("id"));
        }
        List<Pin> pins = new ArrayList<>();
        for (Element pin : children(children.get(1))) {
            if (!"pin".equals(pin.getTagName()) || !children(pin).isEmpty())
                throw new IllegalStateException("首页常驻响应格式错误");
            pins.add(new Pin(pin.getAttribute("kind"), pin.getAttribute("id")));
        }
        return new Preferences(sections, pins);
    }
    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element) result.add(element);
        return result;
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }
}
