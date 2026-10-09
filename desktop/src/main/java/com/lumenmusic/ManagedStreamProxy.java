package com.lumenmusic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Owns the optional local Axum stream process used by the packaged app. */
final class ManagedStreamProxy implements AutoCloseable {
    private static final HttpClient HEALTH_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500)).build();
    private final Process owned;

    private ManagedStreamProxy(Process owned) { this.owned = owned; }

    static ManagedStreamProxy start(URI base, URI python, Path executable, Path log) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!"127.0.0.1".equals(base.getHost()) || !"127.0.0.1".equals(python.getHost()))
            throw new IllegalArgumentException("音频侧车仅支持本机地址");
        if (healthy(base, python)) return new ManagedStreamProxy(null);
        if (!Files.isRegularFile(executable))
            throw new IOException("未找到 Axum 音频侧车：" + executable);
        Files.createDirectories(log.toAbsolutePath().getParent());
        ProcessBuilder builder = new ProcessBuilder(executable.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("LUMEN_PROXY_ADDR", base.getHost() + ":" + base.getPort());
        builder.environment().put("LUMEN_PYTHON_SERVICE_URL", python.toString());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Process process = builder.start();
        boolean ready = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && process.isAlive()) {
                if (healthy(base, python)) {
                    ready = true;
                    return new ManagedStreamProxy(process);
                }
                Thread.sleep(100);
            }
        } finally {
            if (!ready) ManagedPythonService.stopProcess(process);
        }
        throw new IOException("Axum 音频侧车启动失败，请查看日志：" + log);
    }

    private static boolean healthy(URI base, URI python) throws InterruptedException {
        try {
            var request = HttpRequest.newBuilder(base.resolve("/health"))
                    .timeout(Duration.ofMillis(700)).GET().build();
            var response = HEALTH_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && "ok".equals(response.body())
                    && python.resolve("/").toString().equals(
                            response.headers().firstValue("X-Lumen-Upstream").orElse(""));
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception ignored) { return false; }
    }

    @Override public void close() {
        ManagedPythonService.stopProcess(owned);
    }
}
