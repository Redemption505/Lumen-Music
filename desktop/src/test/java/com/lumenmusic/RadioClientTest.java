package com.lumenmusic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class RadioClientTest {
    @Test void generatesParsesItemsAndSendsEscapedSeedAndExclusions() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server((exchange, request) -> {
            requests.add(request);
            return "<radio status=\"ready\" message=\"有 &amp; 无\" intents=\"现场\" future=\"ignored\">"
                    + "<item reason=\"来自风格线索\" future=\"ignored\"><track id=\"radio-1\" "
                    + "title=\"晴天 &amp; 雨天\" artist=\"歌手\" durationSeconds=\"240\" "
                    + "source=\"bilibili\"/></item></radio>";
        });
        try {
            RadioClient client = client(server);
            Track local = new Track("local-1", "本地 \"曲目\"\n下一行", "作者 & 合作者", 70,
                    "local", URI.create("file:///C:/Music/private.flac"));
            var result = client.generate(List.of(local), "ambient & <soft>\tline", "discover", false,
                    List.of("seen:1", "exclude & me"));

            assertEquals("ready", result.status());
            assertEquals("有 & 无", result.message());
            assertEquals("现场", result.intents());
            assertEquals(1, result.items().size());
            assertEquals("来自风格线索", result.items().get(0).reason());
            assertEquals("晴天 & 雨天", result.items().get(0).track().title());

            Request request = requests.get(0);
            assertEquals("POST", request.method());
            assertEquals("/api/radio", request.path());
            Element root = parse(request.body());
            assertEquals("discover", root.getAttribute("mode"));
            assertEquals("ambient & <soft>\tline", root.getAttribute("hint"));
            assertEquals("false", root.getAttribute("neutral"));
            Element sentTrack = (Element) root.getElementsByTagName("track").item(0);
            assertEquals(local.id(), sentTrack.getAttribute("id"));
            assertEquals("file:///C:/Music/private.flac", sentTrack.getAttribute("mediaUri"));
            assertEquals(List.of("seen:1", "exclude & me"), directChildren(root, "exclude").stream()
                    .map(node -> node.getAttribute("id")).toList());
        } finally { server.stop(0); }
    }

    @Test void feedbackRoutesParseSnapshotsAndEncodeIds() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server((exchange, request) -> {
            requests.add(request);
            return "<feedback future=\"ignored\"><entry id=\"id:1\" kind=\"tired\" "
                    + "until=\"2026-10-06T12:00:00Z\"><track id=\"track:1\" title=\"Song\" "
                    + "artist=\"Artist\" durationSeconds=\"90\" source=\"demo\"/></entry>"
                    + "<entry id=\"id:2\" kind=\"skip\" until=\"\"><track id=\"track:2\" "
                    + "title=\"Other\" artist=\"Artist\" durationSeconds=\"80\" "
                    + "source=\"demo\"/></entry></feedback>";
        });
        try {
            RadioClient client = client(server);
            var snapshot = client.feedback();
            var afterPost = client.feedback(new Track("track:1", "Song", "Artist", 90, "demo"), "version");
            var afterRestore = client.restore("track:1 / 特別");
            URI localUri = URI.create("file:///C:/Users/demo/My%20Music/song.mp3");
            assertTrue(localUri.getPath().contains("My Music"));
            var afterLocalRestore = client.restore(localUri.toString());
            assertEquals(List.of("tired", "skip"), snapshot.stream().map(RadioClient.Feedback::kind).toList());
            assertEquals("2026-10-06T12:00:00Z", snapshot.get(0).until());
            assertEquals("", snapshot.get(1).until());
            assertEquals("track:1", afterPost.get(0).track().id());
            assertEquals("track:2", afterRestore.get(1).track().id());
            assertEquals("track:2", afterLocalRestore.get(1).track().id());

            assertEquals(List.of("GET /api/radio/feedback", "POST /api/radio/feedback",
                    "DELETE /api/radio/feedback/track%3A1%20%2F%20%E7%89%B9%E5%88%A5",
                    "DELETE /api/radio/feedback/file%3A%2F%2F%2FC%3A%2FUsers%2Fdemo%2FMy%2520Music%2Fsong.mp3"),
                    requests.stream()
                    .map(r -> r.method() + " " + r.path()).toList());
            assertEquals("version", parse(requests.get(1).body()).getAttribute("kind"));
            assertEquals("track:1", ((Element) parse(requests.get(1).body())
                    .getElementsByTagName("track").item(0)).getAttribute("id"));
        } finally { server.stop(0); }
    }

    @Test void handlesEmptyUnknownFieldsAndReportsHttpAndFormatErrors() throws Exception {
        HttpServer emptyServer = server((exchange, request) ->
                "<radio status=\"insufficient\" message=\"再提供一首\" intents=\"\" extra=\"x\"/>");
        try {
            var result = client(emptyServer).generate(List.of(), "", "familiar", true, List.of());
            assertEquals("insufficient", result.status());
            assertEquals("再提供一首", result.message());
            assertTrue(result.items().isEmpty());
        } finally { emptyServer.stop(0); }

        HttpServer errorServer = server(503, (exchange, request) -> "<error message=\"radio unavailable\"/>");
        try {
            assertTrue(assertThrows(IllegalStateException.class, () -> client(errorServer)
                    .generate(List.of(), "", "familiar", true, List.of())).getMessage()
                    .contains("radio unavailable"));
        } finally { errorServer.stop(0); }

        HttpServer malformedServer = server((exchange, request) -> "<wrong/>");
        try {
            assertTrue(assertThrows(IllegalStateException.class, () -> client(malformedServer)
                    .feedback()).getMessage().contains("响应格式错误"));
        } finally { malformedServer.stop(0); }
    }

    @Test void rejectsDoctypeResponses() throws Exception {
        HttpServer server = server((exchange, request) -> "<!DOCTYPE feedback [<!ENTITY x SYSTEM \"file:///C:/secret\">]>"
                + "<feedback>&x;</feedback>");
        try {
            assertThrows(Exception.class, () -> client(server).feedback());
        } finally { server.stop(0); }
    }

    private static HttpServer server(Responder responder) throws Exception { return server(200, responder); }

    private static HttpServer server(int status, Responder responder) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/radio", exchange -> handle(exchange, status, responder));
        server.createContext("/api/radio/feedback", exchange -> handle(exchange, status, responder));
        server.start();
        return server;
    }

    private static void handle(HttpExchange exchange, int status, Responder responder) throws java.io.IOException {
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = responder.respond(exchange, request).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static RadioClient client(HttpServer server) {
        return new RadioClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static Element parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private static List<Element> directChildren(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && tag.equals(element.getTagName())) result.add(element);
        return result;
    }

    private record Request(String method, String path, String body) { }
    @FunctionalInterface private interface Responder {
        String respond(HttpExchange exchange, Request request) throws java.io.IOException;
    }
}
