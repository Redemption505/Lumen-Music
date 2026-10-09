package com.lumenmusic;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Packaged entry point: one window owns its Python service process. */
public final class LumenLauncher {
    public record RuntimeStartupResult(boolean serviceAvailable, boolean proxyAvailable, String message) { }
    private static volatile StartupCoordinator<RuntimeServices> runtime;

    public static void main(String[] args) {
        DiagnosticLog.installUncaughtExceptionHandler();
        Path stateDirectory = serviceLog().getParent();
        SingleInstanceGuard instance;
        try {
            instance = SingleInstanceGuard.acquire(stateDirectory);
        } catch (Exception error) {
            System.err.println("无法确认 Lumen Music 单实例状态：" + error.getMessage());
            return;
        }
        if (instance == null) return;
        try (instance) {
        URI base = URI.create(System.getenv().getOrDefault("LUMEN_SERVICE_URL", "http://127.0.0.1:8765"));
        String provider = System.getenv().getOrDefault("LUMEN_PROVIDER", "bilibili");
        Path vlcHome = findVlcHome();
        if (vlcHome != null) System.setProperty("lumen.vlc.home", vlcHome.toString());
        if (!List.of("bilibili", "demo").contains(provider))
            throw new IllegalArgumentException("LUMEN_PROVIDER 只支持 bilibili 或 demo");
        runtime = new StartupCoordinator<>(() -> startServices(base, provider));
        try { LumenFxApp.main(args); }
        finally { closeRuntime(); }
        }
    }

    /** Called after the window is visible; direct JavaFX launchers use their existing service. */
    public static CompletableFuture<RuntimeStartupResult> startRuntimeAsync() {
        StartupCoordinator<RuntimeServices> current = runtime;
        if (current == null) return CompletableFuture.completedFuture(new RuntimeStartupResult(true, false, ""));
        return current.start().handle((services, error) -> error == null ? services.result()
                : new RuntimeStartupResult(false, false,
                        "个人曲库与在线搜索启动失败，可重试。详情见本地服务日志。"));
    }

    public static CompletableFuture<RuntimeStartupResult> runtimeReady() { return startRuntimeAsync(); }
    public static CompletableFuture<RuntimeStartupResult> retryRuntime() { return startRuntimeAsync(); }
    public static void closeRuntime() {
        StartupCoordinator<RuntimeServices> current = runtime;
        if (current != null) current.close();
    }

    private static RuntimeServices startServices(URI base, String provider) throws Exception {
        ManagedPythonService service;
        try {
            service = ManagedPythonService.start(base, findServiceDirectory(),
                    System.getenv("LUMEN_PYTHON"), provider, serviceLog());
        } catch (Exception error) {
            if (error instanceof InterruptedException) throw error;
            DiagnosticLog.recordStartup("SERVICE_START_ERROR");
            System.err.println("个人曲库与在线搜索暂不可用：" + error.getMessage());
            throw error;
        }
        ManagedStreamProxy proxy = null;
        boolean handedOff = false;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                URI streamBase = URI.create(System.getenv().getOrDefault(
                        "LUMEN_STREAM_URL", "http://127.0.0.1:8766"));
                proxy = ManagedStreamProxy.start(streamBase, base, findProxyExecutable(), serviceLog());
                System.setProperty("lumen.stream.base", streamBase.toString());
            } catch (Exception error) {
                if (error instanceof InterruptedException) throw error;
                DiagnosticLog.recordStartup("PROXY_START_ERROR");
                System.err.println("Axum 音频侧车暂不可用，使用 Python 音频代理：" + error.getMessage());
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            RuntimeServices result = new RuntimeServices(service, proxy);
            handedOff = true;
            return result;
        } finally {
            if (!handedOff) {
                if (proxy != null) proxy.close();
                service.close();
            }
        }
    }

    private record RuntimeServices(ManagedPythonService service, ManagedStreamProxy proxy) implements AutoCloseable {
        RuntimeStartupResult result() {
            return new RuntimeStartupResult(true, proxy != null, proxy == null
                    ? "音频侧车暂不可用，已使用 Python 音频代理。" : "");
        }
        @Override public void close() {
            try { if (proxy != null) proxy.close(); }
            finally { service.close(); }
        }
    }

    static Path findServiceDirectory() throws Exception {
        Path code = Path.of(LumenLauncher.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().normalize();
        Path root = Files.isDirectory(code) ? code : code.getParent();
        for (Path candidate : List.of(root.resolve("service"), root.resolve("../../service"),
                Path.of("service"), Path.of("../service"))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized.resolve("lumen_service/__main__.py"))) return normalized;
        }
        throw new IllegalStateException("安装目录中找不到 Python 服务");
    }

    static Path findProxyExecutable() throws Exception {
        Path code = Path.of(LumenLauncher.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().normalize();
        Path root = Files.isDirectory(code) ? code : code.getParent();
        String name = System.getProperty("os.name", "").startsWith("Windows")
                ? "lumen-stream-proxy.exe" : "lumen-stream-proxy";
        for (Path candidate : List.of(root.resolve("stream-proxy").resolve(name),
                root.resolve("../../../stream-proxy/target/release").resolve(name),
                Path.of("../stream-proxy/target/release").resolve(name))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) return normalized;
        }
        throw new IllegalStateException("安装目录中找不到 Axum 音频侧车");
    }

    static Path findVlcHome() {
        String configured = System.getenv("LUMEN_VLC_HOME");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        try {
            Path code = Path.of(LumenLauncher.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            Path root = Files.isDirectory(code) ? code : code.getParent();
            for (Path candidate : List.of(root.resolve("vlc"),
                    root.resolve("../../../build/tools/vlc-3.0.23/vlc-3.0.23"),
                    Path.of("../build/tools/vlc-3.0.23/vlc-3.0.23"))) {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized.resolve("libvlc.dll"))
                        && Files.isDirectory(normalized.resolve("plugins"))) return normalized;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static Path serviceLog() {
        String appData = System.getenv("LOCALAPPDATA");
        Path root = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(appData);
        return root.resolve("Lumen Music").resolve("service.log");
    }
}
