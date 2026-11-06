package com.lumenmusic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded cover bytes shared by URI, with successful downloads persisted across sessions. */
public final class CoverCache implements AutoCloseable {
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;
    private final Path directory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService workers = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "lumen-cover-cache");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<URI, CompletableFuture<byte[]>> inFlight = new HashMap<>();
    private final Set<CompletableFuture<Void>> invalidations = new HashSet<>();
    private final Object diskLock = new Object();
    private boolean closed;

    public CoverCache(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        trim();
    }

    public synchronized CompletableFuture<byte[]> load(URI uri) {
        Objects.requireNonNull(uri);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Cover cache is closed"));
        if (!profileAllows(uri, ReleaseProfile.current()))
            return CompletableFuture.failedFuture(new IOException("本地测试版不加载在线封面"));
        CompletableFuture<byte[]> existing = inFlight.get(uri);
        if (existing != null) return existing;
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        inFlight.put(uri, result);
        workers.execute(() -> {
            byte[] bytes = null;
            Throwable failure = null;
            try { bytes = loadBytes(uri); }
            catch (Exception error) { failure = error; }
            synchronized (CoverCache.this) { inFlight.remove(uri, result); }
            if (failure == null) result.complete(bytes);
            else result.completeExceptionally(failure);
        });
        return result;
    }

    /** Await this before retrying a cover whose bytes passed the header check but failed decoding. */
    public synchronized CompletableFuture<Void> invalidate(URI uri) {
        Objects.requireNonNull(uri);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Cover cache is closed"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        invalidations.add(result);
        result.whenComplete((value, error) -> {
            synchronized (CoverCache.this) { invalidations.remove(result); }
        });
        workers.execute(() -> {
            try {
                synchronized (diskLock) { Files.deleteIfExists(directory.resolve(key(uri) + ".img")); }
                result.complete(null);
            } catch (IOException error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private byte[] loadBytes(URI uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            byte[] bytes = readLimited(Path.of(uri));
            validateImage(bytes);
            return bytes;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new IOException("Unsupported cover URI scheme");
        Path cached = directory.resolve(key(uri) + ".img");
        synchronized (diskLock) {
            if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    byte[] bytes = readLimited(cached);
                    validateImage(bytes);
                    Files.setLastModifiedTime(cached, FileTime.fromMillis(System.currentTimeMillis()));
                    return bytes;
                } catch (IOException invalid) {
                    Files.deleteIfExists(cached);
                }
            }
        }
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 LumenMusic/0.2")
                .header("Accept", "image/jpeg,image/png,image/gif,image/bmp").GET();
        String host = uri.getHost();
        if (host != null) host = host.toLowerCase(Locale.ROOT);
        if (host != null && (host.equals("hdslb.com") || host.endsWith(".hdslb.com")
                || host.equals("bilibili.com") || host.endsWith(".bilibili.com")
                || host.equals("biliimg.com") || host.endsWith(".biliimg.com")))
            builder.header("Referer", "https://www.bilibili.com/");
        CompletableFuture<HttpResponse<byte[]>> request = http.sendAsync(builder.build(),
                info -> new LimitedBody());
        HttpResponse<byte[]> response;
        try { response = request.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            throw error;
        } catch (TimeoutException error) {
            request.cancel(true);
            throw new IOException("Cover download timed out", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Cover HTTP status " + response.statusCode());
        byte[] bytes = response.body();
        validateImage(bytes);
        synchronized (diskLock) {
            try { store(cached, bytes); }
            catch (IOException unavailable) { /* A disk cache failure must not hide a downloaded cover. */ }
        }
        return bytes;
    }

    static boolean profileAllows(URI uri, ReleaseProfile profile) {
        return profile.allowsCoverUri(uri);
    }

    private static byte[] readLimited(Path path) throws IOException {
        if (Files.size(path) > MAX_IMAGE_BYTES) throw new IOException("Cover image exceeds 8 MiB");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("Cover image exceeds 8 MiB");
            return bytes;
        }
    }

    private void store(Path cached, byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path partial = Files.createTempFile(directory, "cover-", ".part");
        try {
            Files.write(partial, bytes);
            Files.move(partial, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            trim();
        } finally { Files.deleteIfExists(partial); }
    }

    private void trim() throws IOException {
        List<Path> owned = new ArrayList<>();
        long total = 0;
        try (var entries = Files.newDirectoryStream(directory, "*.img")) {
            for (Path path : entries) {
                if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.img")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                owned.add(path);
                total += Files.size(path);
            }
        }
        owned.sort(Comparator.comparing(CoverCache::modified));
        for (Path path : owned) {
            if (total <= MAX_CACHE_BYTES) break;
            long size = Files.size(path);
            Files.deleteIfExists(path);
            total -= size;
        }
    }

    private static FileTime modified(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException missing) { return FileTime.fromMillis(0); }
    }

    private static String key(URI uri) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static void validateImage(byte[] bytes) throws IOException {
        int size = bytes.length;
        boolean png = size >= 45 && (bytes[0] & 255) == 137 && matches(bytes, 1, "PNG\r\n\u001a\n")
                && matches(bytes, 12, "IHDR") && matches(bytes, size - 8, "IEND");
        boolean jpeg = size >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255 && (bytes[size - 2] & 255) == 255 && (bytes[size - 1] & 255) == 217;
        boolean gif = size >= 14 && (matches(bytes, 0, "GIF87a") || matches(bytes, 0, "GIF89a"))
                && bytes[size - 1] == 59;
        boolean webp = size >= 20 && matches(bytes, 0, "RIFF") && matches(bytes, 8, "WEBP")
                && (matches(bytes, 12, "VP8 ") || matches(bytes, 12, "VP8L") || matches(bytes, 12, "VP8X"))
                && littleEndianSize(bytes, 4) + 8 == size;
        boolean bmp = size >= 54 && matches(bytes, 0, "BM") && littleEndianSize(bytes, 2) == size;
        if (!png && !jpeg && !gif && !webp && !bmp) throw new IOException("Cover response is not a complete image");
    }

    private static boolean matches(byte[] bytes, int offset, String text) {
        for (int i = 0; i < text.length(); i++) if ((bytes[offset + i] & 255) != text.charAt(i)) return false;
        return true;
    }

    private static long littleEndianSize(byte[] bytes, int offset) {
        long size = 0;
        for (int i = 0; i < 4; i++) size |= (long) (bytes[offset + i] & 255) << (i * 8);
        return size;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var result : inFlight.values()) result.cancel(true);
        inFlight.clear();
        for (var result : Set.copyOf(invalidations)) result.cancel(true);
        invalidations.clear();
        workers.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > MAX_IMAGE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Cover image exceeds 8 MiB"));
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
