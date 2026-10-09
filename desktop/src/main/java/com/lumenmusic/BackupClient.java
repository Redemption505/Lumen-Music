package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Local backup export, preview and restore boundary. Never called on the JavaFX thread. */
public final class BackupClient {
    public record Preview(int favorites, int playlists, int history,
                          long backgroundBytes, String members) { }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public BackupClient(URI base) { this.base = base; }

    public Preview export(Path destination) throws Exception { return request("export", destination); }
    public Preview inspect(Path source) throws Exception { return request("inspect", source); }
    public Preview restore(Path source) throws Exception { return request("restore", source); }

    private Preview request(String action, Path path) throws Exception {
        String body = "<backup path=\"" + escape(path.toAbsolutePath().normalize().toString()) + "\"/>";
        var request = HttpRequest.newBuilder(base.resolve("/api/backup/" + action))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = "error".equals(root.getTagName()) ? root.getAttribute("message") : "";
            throw new IllegalStateException("备份服务 HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        if (!"backup-preview".equals(root.getTagName()))
            throw new IllegalStateException("备份服务响应格式错误");
        return new Preview(Integer.parseInt(root.getAttribute("favorites")),
                Integer.parseInt(root.getAttribute("playlists")),
                Integer.parseInt(root.getAttribute("history")),
                Long.parseLong(root.getAttribute("backgroundBytes")),
                root.getAttribute("members"));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
