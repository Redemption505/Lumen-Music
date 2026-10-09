package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Real HTTP and JavaFX startup/retry regression on disposable data; no native input. */
public final class DiaryStartupSmoke extends Application {
    private final CountDownLatch allowRuntime = new CountDownLatch(1);
    private final AtomicReference<String> sharedStatus = new AtomicReference<>("选择曲目开始播放");
    private final AtomicInteger diaryRequests = new AtomicInteger();
    private final AtomicInteger statusUpdates = new AtomicInteger();
    private Stage stage;
    private LumenFxApp app;
    private DiaryPane pane;
    private HttpServer server;
    private StartupCoordinator<AutoCloseable> runtime;
    private Path root;

    @Override public void start(Stage initial) {
        stage = initial;
        Platform.setImplicitExit(false);
        Thread driver = new Thread(() -> {
            int code = 0;
            try { exercise(); }
            catch (Throwable error) { error.printStackTrace(); code = 1; }
            finally {
                allowRuntime.countDown();
                try { fx(() -> {
                    if (pane != null) pane.close();
                    if (app != null) app.stop();
                    for (Window window : List.copyOf(Window.getWindows())) window.hide();
                }); } catch (Exception error) { error.printStackTrace(); code = 1; }
                if (server != null) server.stop(0);
                if (runtime != null) runtime.close();
            }
            System.exit(code);
        }, "diary-startup-regression");
        driver.setDaemon(true);
        driver.start();
    }

    private void exercise() throws Exception {
        root = Path.of(System.getenv("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        check(root.toString().contains("diary-startup-smoke"), "dedicated profile required");
        for (String name : List.of("LOCALAPPDATA", "APPDATA", "LUMEN_HISTORY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(Path.of(System.getenv(name)).toAbsolutePath().startsWith(root), "profile escaped");
        check(System.getenv("LUMEN_AI_API_KEY") == null && System.getenv("OPENAI_API_KEY") == null, "keys entered smoke");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        installDiaryEndpoint(server, false);
        server.start();
        URI local = URI.create("http://127.0.0.1:" + port);
        fx(() -> {
            pane = new DiaryPane(new DiaryClient(local), ignored -> { }, ignored -> { }, message -> {
                sharedStatus.set(message);
                statusUpdates.incrementAndGet();
            }, ignored -> { });
            pane.setReadFailureRecovery(previous -> sharedStatus.compareAndSet(previous, "听歌记录已更新"));
        });
        Thread.sleep(300);
        check(diaryRequests.get() == 0, "constructor sent HTTP before explicit refresh");
        server.stop(0);
        fx(pane::refresh);
        await(() -> sharedStatus.get().contains("听歌记录服务未连接"), "connection failure lacked actionable feedback");
        check(!sharedStatus.get().contains("null"), "null feedback remained");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        installDiaryEndpoint(server, true);
        server.start();
        fx(pane::refresh);
        await(() -> sharedStatus.get().equals("听歌记录已更新"), "recovery did not clear its old shared error");
        server.stop(0);
        fx(pane::refresh);
        await(() -> sharedStatus.get().contains("听歌记录服务未连接"), "second connection failure not shown");
        sharedStatus.set("正在播放：合成验收音频");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        installDiaryEndpoint(server, true);
        server.start();
        fx(pane::refresh);
        await(() -> fxValue(() -> ((Label) pane.lookup("#diary-feedback")).getText().equals("已更新")), "second retry did not load real HTTP diary");
        check(sharedStatus.get().equals("正在播放：合成验收音频"), "successful diary refresh overwrote unrelated status");
        server.stop(0);
        fx(pane::refresh);
        await(() -> sharedStatus.get().contains("听歌记录服务未连接"), "read error before failed write not shown");
        int beforeWriteFailure = statusUpdates.get();
        fx(() -> pane.saveLyricFavorite(new Track("diary-fixture", "Fixture", "Fixture", 120, "demo", null),
                0, "synthetic lyric"));
        await(() -> statusUpdates.get() > beforeWriteFailure, "actual unavailable write did not report failure");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        installDiaryEndpoint(server, true);
        server.start();
        fx(pane::refresh);
        await(() -> fxValue(() -> ((Label) pane.lookup("#diary-feedback")).getText().equals("已更新")),
                "read retry after failed write did not complete");
        check(sharedStatus.get().contains("听歌记录服务未连接"), "successful read hid an unresolved failed write");

        Field runtimeField = LumenLauncher.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        check(runtimeField.get(null) == null, "fixture unexpectedly owns another runtime");
        Constructor<?> services = Class.forName("com.lumenmusic.LumenLauncher$RuntimeServices")
                .getDeclaredConstructor(ManagedPythonService.class, ManagedStreamProxy.class);
        services.setAccessible(true);
        runtime = new StartupCoordinator<>(() -> {
            if (!allowRuntime.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("startup gate timed out");
            ManagedPythonService managed = ManagedPythonService.start(URI.create(System.getenv("LUMEN_SERVICE_URL")),
                    root.resolve("absent-source"), "absent-python", "demo", root.resolve("owned-fixture.log"));
            return (AutoCloseable) services.newInstance(managed, null);
        });
        runtimeField.set(null, runtime);
        fx(() -> {
            app = new LumenFxApp();
            app.startUi(stage, List.of());
        });
        Thread.sleep(400);
        check(fxValue(() -> value(app, "diaryPane", DiaryPane.class).lookup("#diary-feedback") instanceof Label label
                && label.getText().isEmpty()), "pending runtime triggered premature diary read");
        check(!fxValue(() -> value(app, "serviceInitialized", Boolean.class)), "startup gate was bypassed");
        allowRuntime.countDown();
        await(() -> fxValue(() -> value(app, "serviceInitialized", Boolean.class)
                && ((Label) value(app, "diaryPane", DiaryPane.class).lookup("#diary-feedback")).getText().equals("已更新")),
                "runtime availability did not cause actual diary refresh");
        check(!fxValue(() -> value(app, "status", Label.class).getText().contains("操作失败")), "startup left diary failure in shared status");
        String evidence = "PASS constructor sends no HTTP; explicit unavailable refresh gives actionable feedback; real HTTP retry clears only its old error; unrelated status and unresolved failed write retained; gated runtime startup waits and then loads diary\n";
        Files.writeString(root.resolve("diary-startup-evidence.txt"), evidence);
        System.out.print(evidence);
    }

    private void installDiaryEndpoint(HttpServer target, boolean success) {
        target.createContext("/api/diary", exchange -> {
            diaryRequests.incrementAndGet();
            byte[] body = (success ? "<diary zone=\"Asia/Shanghai\"><summary/><notes/><lyricFavorites/></diary>"
                    : "<error message=\"fixture unavailable\"/>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(success ? 200 : 503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
    }

    private static <T> T value(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }
    private void await(CheckedBoolean condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(50); }
        throw new AssertionError(message);
    }
    private void fx(Checked action) throws Exception { fxValue(() -> { action.run(); return null; }); }
    private <T> T fxValue(CheckedValue<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface CheckedValue<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
