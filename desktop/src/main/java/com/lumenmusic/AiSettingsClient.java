package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** AI intent preferences on the local service. The response never returns the API key. */
final class AiSettingsClient {
    record Settings(boolean enabled, String model, String baseUrl, boolean keyConfigured) { }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    AiSettingsClient(URI base) { this.base = base; }

    Settings read() throws Exception { return request(null); }

    Settings save(boolean enabled, String model, String baseUrl, String newKey) throws Exception {
        String body = "<ai-settings enabled=\"" + enabled + "\" model=\"" + escape(model)
                + "\" baseUrl=\"" + escape(baseUrl) + "\""
                + (newKey == null || newKey.isBlank() ? "" : " apiKey=\"" + escape(newKey) + "\"")
                + "/>";
        return request(body);
    }

    private Settings request(String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve("/api/ai-settings"))
                .timeout(Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()))
                .getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("AI 配置服务 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"ai-settings".equals(root.getTagName()))
            throw new IllegalStateException("AI 配置响应格式错误");
        return new Settings(Boolean.parseBoolean(root.getAttribute("enabled")),
                root.getAttribute("model"), root.getAttribute("baseUrl"),
                Boolean.parseBoolean(root.getAttribute("keyConfigured")));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
