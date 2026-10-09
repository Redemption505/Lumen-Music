package com.lumenmusic;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/** Actual child-process start, reuse and shutdown check for the packaged launcher. */
public final class ServiceLifecycleSmoke {
    public static void main(String[] args) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        URI base = URI.create("http://127.0.0.1:" + port);
        Path servicePath = LumenLauncher.findServiceDirectory();
        String library = System.getenv("LUMEN_LIBRARY_PATH");
        check(library != null && !library.isBlank(), "生命周期测试须使用独立资料目录");
        Path log = Path.of(library).toAbsolutePath().getParent().resolve("launcher-smoke.log");
        String python = System.getenv("LUMEN_PYTHON");
        try (var owned = ManagedPythonService.start(base, servicePath, python, "demo", log)) {
            check(owned.ownsProcess(), "未启动自有服务进程");
            check(health(base), "自有服务健康检查失败");
            try (var reused = ManagedPythonService.start(base, servicePath, python, "demo", log)) {
                check(!reused.ownsProcess(), "误认为已有服务是自有进程");
            }
            try {
                ManagedPythonService.start(base, servicePath, python, "bilibili", log);
                throw new AssertionError("错误复用了不同数据源的服务");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("占用"), "错误的数据源冲突提示");
            }
            check(health(base), "关闭复用句柄时误杀了已有服务");
        }
        check(!health(base), "应用退出后自有服务仍在运行");
        System.out.println("PASS: launcher starts, reuses and closes only its Python child");
    }

    private static boolean health(URI base) {
        try {
            var request = HttpRequest.newBuilder(base.resolve("/api/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (Exception ignored) { return false; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
