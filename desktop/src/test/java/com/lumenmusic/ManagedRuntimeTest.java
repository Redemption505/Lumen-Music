package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedRuntimeTest {
    @TempDir Path root;

    @Test void existingMatchingServiceIsReusedWithoutRequiringPythonOrSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> {
            byte[] body = "<health status=\"ok\" provider=\"demo\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var service = ManagedPythonService.start(base(server), root.resolve("missing"),
                "missing-python", "demo", root.resolve("service.log"))) {
            assertFalse(service.ownsProcess());
        } finally { server.stop(0); }
    }

    @Test void serviceHealthProbePropagatesCancellationInsteadOfStartingPython() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> {
            entered.countDown();
            try { allowResponse.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        Thread starter = new Thread(() -> {
            try {
                ManagedPythonService.start(base(server), root.resolve("missing"),
                        "missing-python", "demo", root.resolve("service.log"));
            } catch (Throwable error) { outcome.set(error); }
        });
        try {
            starter.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            starter.interrupt();
            starter.join(2000);
            assertFalse(starter.isAlive());
            assertInstanceOf(InterruptedException.class, outcome.get());
        } finally {
            allowResponse.countDown();
            starter.interrupt();
            server.stop(0);
        }
    }

    @Test void cancelledProxyDoesNotInspectOrSpawnExecutable() throws Exception {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                ManagedStreamProxy.start(URI.create("http://127.0.0.1:8766"),
                        URI.create("http://127.0.0.1:8765"), root.resolve("missing"), root.resolve("service.log"));
            } catch (Throwable error) { outcome.set(error); }
        });
        starter.start();
        starter.join(2000);
        assertFalse(starter.isAlive());
        assertInstanceOf(InterruptedException.class, outcome.get());
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
