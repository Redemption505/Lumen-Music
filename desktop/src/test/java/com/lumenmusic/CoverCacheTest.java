package com.lumenmusic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoverCacheTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZAAAAABJRU5ErkJggg==");
    @TempDir Path directory;

    @Test void failedRequestCanRecoverAndValidDownloadSurvivesRestartOffline() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> userAgent = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            if (requests.incrementAndGet() == 1) respond(exchange, 503, "unavailable".getBytes(StandardCharsets.UTF_8));
            else respond(exchange, 200, PNG);
        });
        URI uri = uri(server);
        try {
            try (CoverCache cache = new CoverCache(directory)) {
                assertThrows(ExecutionException.class, () -> cache.load(uri).get(5, TimeUnit.SECONDS));
                assertArrayEquals(PNG, cache.load(uri).get(5, TimeUnit.SECONDS));
                assertEquals(2, requests.get());
                assertNotNull(userAgent.get());
            }
            server.stop(0);
            try (CoverCache restarted = new CoverCache(directory)) {
                assertArrayEquals(PNG, restarted.load(uri).get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, requests.get());
            assertEquals(1, imageCount());
        } finally { server.stop(0); }
    }

    @Test void simultaneousSubscribersShareOneRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, PNG);
        });
        try (CoverCache cache = new CoverCache(directory)) {
            var first = cache.load(uri(server));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = cache.load(uri(server));
            assertSame(first, second);
            release.countDown();
            assertArrayEquals(PNG, first.get(5, TimeUnit.SECONDS));
            assertArrayEquals(PNG, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, requests.get());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void corruptCacheAndHtmlResponsesNeverBecomePermanentHits() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<byte[]> response = new AtomicReference<>("<html>login</html>".getBytes(StandardCharsets.UTF_8));
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, response.get());
        });
        try (CoverCache cache = new CoverCache(directory)) {
            URI uri = uri(server);
            assertThrows(ExecutionException.class, () -> cache.load(uri).get(5, TimeUnit.SECONDS));
            assertEquals(0, imageCount());
            response.set(PNG);
            assertArrayEquals(PNG, cache.load(uri).get(5, TimeUnit.SECONDS));
            Path image;
            try (var files = Files.list(directory)) {
                image = files.filter(path -> path.toString().endsWith(".img")).findFirst().orElseThrow();
            }
            Files.writeString(image, "broken");
            assertArrayEquals(PNG, cache.load(uri).get(5, TimeUnit.SECONDS));
            assertArrayEquals(PNG, Files.readAllBytes(image));
            assertEquals(3, requests.get());
        } finally { server.stop(0); }
    }

    @Test void chunkedResponseIsBoundedByActualBytesAndCanThenRecover() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (requests.incrementAndGet() > 1) { respond(exchange, 200, PNG); return; }
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(PNG);
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 129; i++) body.write(chunk);
            } catch (IOException disconnected) { /* The bounded subscriber cancels this response. */ }
            finally { exchange.close(); }
        });
        try (CoverCache cache = new CoverCache(directory)) {
            assertThrows(ExecutionException.class, () -> cache.load(uri(server)).get(5, TimeUnit.SECONDS));
            assertEquals(0, imageCount());
            assertArrayEquals(PNG, cache.load(uri(server)).get(5, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        } finally { server.stop(0); }
    }

    @Test void localFileIsReadFromItsExplicitUriWithoutCopyAndClosingRejectsNewLoads() throws Exception {
        Path local = directory.resolve("local.png");
        Files.write(local, PNG);
        Path disk = directory.resolve("cache");
        CoverCache cache = new CoverCache(disk);
        try {
            assertArrayEquals(PNG, cache.load(local.toUri()).get(5, TimeUnit.SECONDS));
            try (var files = Files.list(disk)) { assertEquals(0, files.count()); }
            Files.writeString(local, "invalid");
            assertThrows(ExecutionException.class, () -> cache.load(local.toUri()).get(5, TimeUnit.SECONDS));
        } finally { cache.close(); }
        assertThrows(ExecutionException.class, () -> cache.load(local.toUri()).get(5, TimeUnit.SECONDS));
    }

    @Test void capacityEvictsOnlyRecognizedCacheFiles() throws Exception {
        Path oldest = directory.resolve("a".repeat(64) + ".img");
        try (var file = FileChannel.open(oldest, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            file.position(CoverCache.MAX_CACHE_BYTES - 1);
            file.write(ByteBuffer.wrap(new byte[] {0}));
        }
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(0));
        Path unrelated = directory.resolve("personal.img");
        Files.writeString(unrelated, "keep");
        HttpServer server = server(exchange -> respond(exchange, 200, PNG));
        try (CoverCache cache = new CoverCache(directory)) {
            assertArrayEquals(PNG, cache.load(uri(server)).get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(oldest));
            assertEquals("keep", Files.readString(unrelated));
        } finally { server.stop(0); }
    }

    @Test void decoderRejectedBytesCanBeInvalidatedBeforeAnotherFetch() throws Exception {
        byte[] corrupt = PNG.clone();
        corrupt[corrupt.length - 9] ^= 1;
        AtomicReference<byte[]> response = new AtomicReference<>(corrupt);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            assertFalse(exchange.getRequestHeaders().getFirst("Accept").contains("webp"));
            respond(exchange, 200, response.get());
        });
        try (CoverCache cache = new CoverCache(directory)) {
            URI uri = uri(server);
            assertArrayEquals(corrupt, cache.load(uri).get(5, TimeUnit.SECONDS));
            cache.invalidate(uri).get(5, TimeUnit.SECONDS);
            assertEquals(0, imageCount());
            response.set(PNG);
            assertArrayEquals(PNG, cache.load(uri).get(5, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        } finally { server.stop(0); }
    }

    private long imageCount() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.img")).count();
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cover", handler);
        server.start();
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cover");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try {
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        } finally { exchange.close(); }
    }
}
