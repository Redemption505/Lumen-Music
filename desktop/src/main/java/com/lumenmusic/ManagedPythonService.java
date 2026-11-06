package com.lumenmusic;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;

/** Starts the loopback service when needed and stops only the process it owns. */
public final class ManagedPythonService implements AutoCloseable {
    private static final HttpClient HEALTH_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500)).build();
    private final Process owned;

    private ManagedPythonService(Process owned) { this.owned = owned; }

    public boolean ownsProcess() { return owned != null; }

    public static ManagedPythonService start(URI base, Path serviceDirectory, String python,
                                             String provider, Path logFile) throws Exception {
        return start(base, serviceDirectory, python, provider, ReleaseProfile.from("full"), logFile);
    }

    public static ManagedPythonService start(URI base, Path serviceDirectory, String python,
                                             String provider, ReleaseProfile profile,
                                             Path logFile) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!"127.0.0.1".equals(base.getHost()) && !"localhost".equalsIgnoreCase(base.getHost()))
            throw new IllegalArgumentException("曲库服务必须使用本机地址");
        int port = base.getPort() < 0 ? 8765 : base.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("服务端口无效");
        String existing = healthProvider(base);
        if (existing != null) {
            if (existing.equals(provider)) return new ManagedPythonService(null);
            throw new IOException("端口 " + port + " 已由其他服务占用（数据源：" + existing + "）");
        }
        if (!Files.isRegularFile(serviceDirectory.resolve("lumen_service/__main__.py")))
            throw new IOException("找不到 Python 服务源码：" + serviceDirectory);

        Files.createDirectories(logFile.toAbsolutePath().getParent());
        List<List<String>> candidates = new ArrayList<>();
        if (python != null && !python.isBlank()) candidates.add(List.of(python));
        else {
            Path bundled = serviceDirectory.resolveSibling("python").resolve("python.exe");
            if (Files.isRegularFile(bundled)) candidates.add(List.of(bundled.toAbsolutePath().toString()));
            else {
                candidates.add(List.of("python"));
                if (System.getProperty("os.name", "").startsWith("Windows"))
                    candidates.add(List.of("py", "-3"));
            }
        }
        IOException startError = null;
        for (List<String> executable : candidates) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            List<String> command = new ArrayList<>(executable);
            command.addAll(List.of("-m", "lumen_service", "--port", Integer.toString(port),
                    "--provider", profile.serviceArgumentProvider(provider), "--release-profile", profile.id()));
            var builder = new ProcessBuilder(command).directory(serviceDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.environment().put("PYTHONPATH", serviceDirectory.toAbsolutePath().toString());
            if (profile.localFirst()) {
                Path profileData = profile.appDataDirectory();
                builder.environment().put("LUMEN_LIBRARY_PATH", profileData.resolve("library.json").toString());
                builder.environment().put("LUMEN_HISTORY_PATH", profileData.resolve("history.json").toString());
                builder.environment().put("LUMEN_AI_SETTINGS_PATH", profileData.resolve("ai-settings.json").toString());
            }
            Process process;
            try { process = builder.start(); }
            catch (IOException error) { startError = error; continue; }

            boolean ready = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && process.isAlive()) {
                    if (provider.equals(healthProvider(base))) {
                        ready = true;
                        return new ManagedPythonService(process);
                    }
                    Thread.sleep(120);
                }
            } finally {
                if (!ready) stopProcess(process);
            }
            throw new IOException("Python 服务启动失败，请查看日志：" + logFile.toAbsolutePath());
        }
        throw new IOException("找不到 Python 3.10+ 解释器，请设置 LUMEN_PYTHON", startError);
    }

    private static String healthProvider(URI base) throws InterruptedException {
        try {
            var request = HttpRequest.newBuilder(base.resolve("/api/health"))
                    .timeout(Duration.ofMillis(700)).GET().build();
            var response = HEALTH_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()))
                    .getDocumentElement();
            if (!"health".equals(root.getTagName()) || !"ok".equals(root.getAttribute("status"))) return null;
            return root.getAttribute("provider");
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception ignored) { return null; }
    }

    @Override public void close() {
        stopProcess(owned);
    }

    static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
