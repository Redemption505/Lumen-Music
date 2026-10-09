package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.time.Duration;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** The HTTP boundary; UI and queue never depend on provider implementation details. */
public final class MusicClient {
    private static final long MAX_AUDIO_BYTES = 256L * 1024 * 1024;
    private static final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lumen-audio-deadline");
        thread.setDaemon(true);
        return thread;
    });
    public enum PreparationStage { CHECKING, DOWNLOADING, RETRYING }
    @FunctionalInterface public interface PreparationListener {
        void onProgress(PreparationStage stage, int attempt);
    }
    private static final PreparationListener NO_PROGRESS = (stage, attempt) -> { };
    public static final class StreamException extends IOException {
        private final String code;
        public StreamException(String code) {
            this(code, PlaybackFailure.forCode(code).displayMessage());
        }
        public StreamException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
    public static final class DownloadCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<InputStream> active = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<?>> request = new AtomicReference<>();

        public void cancel() {
            cancelled.set(true);
            CompletableFuture<?> pending = request.getAndSet(null);
            if (pending != null) {
                discardResponse(pending);
                pending.cancel(true);
            }
            InputStream input = active.getAndSet(null);
            if (input != null) {
                try { input.close(); }
                catch (IOException ignored) { }
            }
        }

        public void check() throws InterruptedIOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("在线音频下载已取消");
        }

        private void register(InputStream input) throws InterruptedIOException {
            active.set(input);
            try { check(); }
            catch (InterruptedIOException error) {
                active.compareAndSet(input, null);
                throw error;
            }
        }

        private void clear(InputStream input) { active.compareAndSet(input, null); }
        private void register(CompletableFuture<?> pending) throws InterruptedIOException {
            request.set(pending);
            try { check(); }
            catch (InterruptedIOException error) {
                request.compareAndSet(pending, null);
                discardResponse(pending);
                pending.cancel(true);
                throw error;
            }
        }
    }
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public MusicClient(URI base) {
        this.base = base;
    }

    public URI streamUri(Track track) {
        track = track.audioSource();
        if (!"bilibili".equals(track.source()) || track.bvid() == null || track.page() < 1)
            throw new IllegalArgumentException("曲目缺少 B 站播放身份");
        // The optional sidecar becomes ready after the window and its clients are created.
        String configured = System.getProperty("lumen.stream.base", "");
        URI streamBase = configured.isBlank() ? base : URI.create(configured);
        return streamBase.resolve("/api/tracks/" + URLEncoder.encode(track.id(), StandardCharsets.UTF_8)
                + "/stream");
    }

    public URI checkStream(Track track) throws Exception {
        return checkStream(track, new DownloadCancellation());
    }

    public URI checkStream(Track track, DownloadCancellation cancellation) throws Exception {
        return checkStream(track, cancellation, NO_PROGRESS);
    }

    public URI checkStream(Track track, DownloadCancellation cancellation, PreparationListener listener)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        for (int attempt = 0; ; attempt++) {
            cancellation.check();
            try {
                listener.onProgress(PreparationStage.CHECKING, attempt + 1);
                return checkStreamOnce(track, deadline, cancellation, MAX_AUDIO_BYTES);
            } catch (IOException error) {
                cancellation.check();
                if (attempt >= 1 || !PlaybackFailure.from(error).retryable()
                        || System.nanoTime() >= deadline) throw error;
                listener.onProgress(PreparationStage.RETRYING, attempt + 2);
                Thread.sleep(200);
            }
        }
    }

    private URI checkStreamOnce(Track track, long deadline, DownloadCancellation cancellation, long maxBytes)
            throws Exception {
        URI uri = streamUri(track);
        var request = HttpRequest.newBuilder(uri).timeout(remaining(deadline))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        var response = send(request, HttpResponse.BodyHandlers.discarding(), cancellation, deadline);
        if (response.statusCode() != 200) {
            String code = response.headers().firstValue("X-Lumen-Error-Code").orElse("HTTP_" + response.statusCode());
            throw streamFailure(code);
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.startsWith("audio/mp4")) throw new IllegalStateException("在线音频格式不受支持：" + type);
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (length <= 0 || length > Math.min(MAX_AUDIO_BYTES, maxBytes))
            throw new IllegalStateException("在线音频大小无效或超过 256 MB 临时缓存上限");
        return uri;
    }

    public void downloadStream(Track track, Path target, DownloadCancellation cancellation) throws Exception {
        downloadStream(track, target, cancellation, MAX_AUDIO_BYTES, NO_PROGRESS);
    }

    public void downloadStream(Track track, Path target, DownloadCancellation cancellation,
                               PreparationListener listener) throws Exception {
        downloadStream(track, target, cancellation, MAX_AUDIO_BYTES, listener);
    }

    public void downloadStream(Track track, Path target, DownloadCancellation cancellation,
                               long maxBytes, PreparationListener listener) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        for (int attempt = 0; ; attempt++) {
            cancellation.check();
            try {
                listener.onProgress(PreparationStage.CHECKING, attempt + 1);
                URI uri = checkStreamOnce(track,
                        Math.min(deadline, System.nanoTime() + Duration.ofSeconds(35).toNanos()),
                        cancellation, maxBytes);
                listener.onProgress(PreparationStage.DOWNLOADING, attempt + 1);
                downloadOnce(uri, target, cancellation, maxBytes, deadline);
                return;
            } catch (Exception error) {
                Files.deleteIfExists(target);
                cancellation.check();
                if (attempt >= 1 || !(error instanceof IOException) || !PlaybackFailure.from(error).retryable()
                        || System.nanoTime() >= deadline) throw error;
                listener.onProgress(PreparationStage.RETRYING, attempt + 2);
                Thread.sleep(200);
            }
        }
    }

    private void downloadOnce(URI uri, Path target, DownloadCancellation cancellation,
                              long maxBytes, long deadline) throws Exception {
        cancellation.check();
        var request = HttpRequest.newBuilder(uri).timeout(remaining(deadline)).GET().build();
        var response = send(request, HttpResponse.BodyHandlers.ofInputStream(), cancellation, deadline);
        ScheduledFuture<?> timeout = deadlines.schedule(() -> {
            try { response.body().close(); } catch (IOException ignored) { }
        }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (InputStream input = response.body()) {
            cancellation.register(input);
            try {
                if (response.statusCode() != 200) {
                    String code = response.headers().firstValue("X-Lumen-Error-Code")
                            .orElse("HTTP_" + response.statusCode());
                    throw streamFailure(code);
                }
                long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (!type.startsWith("audio/mp4"))
                    throw new IllegalStateException("在线音频格式不受支持：" + type);
                if (expected <= 0 || expected > Math.min(MAX_AUDIO_BYTES, maxBytes))
                    throw new IllegalStateException("在线音频大小无效或超过 256 MB 临时缓存上限");
                long copied = 0;
                try (OutputStream output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        cancellation.check();
                        copied += count;
                        if (copied > expected || copied > Math.min(MAX_AUDIO_BYTES, maxBytes))
                            throw new StreamException("UPSTREAM_CONNECTION_LOST", "在线音频长度与响应不一致");
                        output.write(buffer, 0, count);
                    }
                }
                if (copied != expected) throw new StreamException("UPSTREAM_CONNECTION_LOST");
                cancellation.check();
            } finally { cancellation.clear(input); }
        } catch (IOException error) {
            if (System.nanoTime() >= deadline) throw new HttpTimeoutException("Audio download timed out");
            throw error;
        } finally { timeout.cancel(false); }
    }

    private static Duration remaining(long deadline) throws HttpTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new HttpTimeoutException("Audio preparation timed out");
        return Duration.ofNanos(nanos);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                    DownloadCancellation cancellation, long deadline) throws Exception {
        cancellation.check();
        CompletableFuture<HttpResponse<T>> pending = http.sendAsync(request, handler);
        cancellation.register(pending);
        try {
            HttpResponse<T> response = pending.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
            try { cancellation.check(); }
            catch (InterruptedIOException error) {
                if (response.body() instanceof InputStream input) input.close();
                throw error;
            }
            return response;
        } catch (CancellationException error) {
            cancellation.check();
            throw new InterruptedIOException("Audio request cancelled");
        } catch (TimeoutException error) {
            discardResponse(pending);
            throw new HttpTimeoutException("Audio request timed out");
        } catch (InterruptedException error) {
            discardResponse(pending);
            Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            cancellation.check();
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        } finally {
            cancellation.request.compareAndSet(pending, null);
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static void discardResponse(CompletableFuture<?> pending) {
        pending.thenAccept(value -> {
            if (value instanceof HttpResponse<?> response && response.body() instanceof InputStream input) {
                try { input.close(); } catch (IOException ignored) { }
            }
        });
    }

    private static StreamException streamFailure(String code) {
        return new StreamException(code);
    }

    public List<Track> search(String query) throws Exception {
        return search(query, "all");
    }

    public List<Track> search(String query, String kind) throws Exception {
        return search(query, kind, true);
    }

    public List<Track> search(String query, String kind, boolean rerank) throws Exception {
        return searchDetailed(query, kind, rerank).tracks();
    }

    public SearchResult searchDetailed(String query, String kind) throws Exception {
        return searchDetailed(query, kind, true);
    }

    public SearchResult searchDetailed(String query, String kind, boolean rerank) throws Exception {
        if (!List.of("all", "single", "collection").contains(kind))
            throw new IllegalArgumentException("未知搜索筛选方式：" + kind);
        var uri = base.resolve("/api/tracks?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&kind=" + kind + "&rerank=" + rerank);
        var response = requestTracks(uri);
        return new SearchResult(response.tracks(), response.unresolvedCount());
    }

    /** Resolve every page of an ordinary search result before replacing the play queue. */
    public List<Track> resolveCollection(String bvid) throws Exception {
        if (bvid == null || !bvid.matches("BV[0-9A-Za-z]{10}"))
            throw new IllegalArgumentException("合集缺少有效 BV 号");
        return requestTracks(base.resolve("/api/collections/"
                + URLEncoder.encode(bvid, StandardCharsets.UTF_8) + "/tracks")).tracks();
    }

    /** BV search already returns every part; reuse it only when the snapshot is complete. */
    static List<Track> completeCollection(List<Track> results, Track selected) {
        if (selected.bvid() == null || selected.partCount() < 2) return List.of();
        var parts = results.stream().filter(track -> selected.bvid().equals(track.bvid()))
                .sorted(java.util.Comparator.comparingInt(Track::page)).toList();
        if (parts.size() != selected.partCount()) return List.of();
        for (int index = 0; index < parts.size(); index++) {
            Track part = parts.get(index);
            if (part.page() != index + 1 || part.partCount() != parts.size()) return List.of();
        }
        return parts;
    }

    public record SearchResult(List<Track> tracks, int unresolvedCount) { }
    public record RecommendationResult(List<Track> tracks, String intents, String tasteSource) { }

    public RecommendationResult recommendations() throws Exception {
        return recommendations("");
    }

    public RecommendationResult recommendations(String hint) throws Exception {
        var response = requestTracks(base.resolve("/api/recommendations?hint="
                + URLEncoder.encode(hint == null ? "" : hint, StandardCharsets.UTF_8)));
        return new RecommendationResult(response.tracks(), response.intents(), response.tasteSource());
    }

    private record TracksResponse(List<Track> tracks, String intents, String tasteSource,
                                  int unresolvedCount) { }

    private TracksResponse requestTracks(URI uri) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body())).getDocumentElement();
        if (response.statusCode() != 200) {
            String detail = root.getTagName().equals("error") ? root.getAttribute("message") : "";
            String code = root.getTagName().equals("error") ? root.getAttribute("code") : "";
            String explanation = switch (code) {
                case "UPSTREAM_TIMEOUT" -> "连接 B 站超时，请检查网络后重试";
                case "UPSTREAM_UNAVAILABLE", "UPSTREAM_HTTP_ERROR" ->
                        "B 站接口暂时不可用，请稍后重试";
                case "UPSTREAM_REJECTED" -> "B 站暂时限制了游客请求，请稍后重试";
                case "UPSTREAM_INVALID_RESPONSE", "UPSTREAM_API_ERROR" ->
                        "B 站返回了异常数据，请稍后重试";
                case "VIDEO_NOT_FOUND" -> "该 B 站视频不存在或已失效";
                default -> "曲库服务返回 HTTP " + response.statusCode();
            };
            String message = explanation + (detail.isBlank() ? "" : "（" + detail + "）");
            if (!code.isBlank()) throw new StreamException(code, message);
            throw new IllegalStateException(message);
        }
        if (!root.getTagName().equals("tracks")) throw new IllegalStateException("曲库响应格式错误");
        var nodes = root.getElementsByTagName("track");
        var tracks = new ArrayList<Track>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = (Element) nodes.item(i);
            String page = node.getAttribute("page");
            String bvid = node.getAttribute("bvid");
            String coverUri = node.getAttribute("coverUri");
            String mediaUri = node.getAttribute("mediaUri");
            String partCount = node.getAttribute("partCount");
            tracks.add(new Track(node.getAttribute("id"), node.getAttribute("title"), node.getAttribute("artist"),
                    Integer.parseInt(node.getAttribute("durationSeconds")), node.getAttribute("source"),
                    mediaUri.isBlank() ? null : URI.create(mediaUri),
                    bvid.isBlank() ? null : bvid, page.isBlank() ? 0 : Integer.parseInt(page),
                    coverUri.isBlank() ? null : URI.create(coverUri),
                    partCount.isBlank() ? 0 : Integer.parseInt(partCount),
                    node.getAttribute("viewCount").isBlank() ? 0 : Long.parseLong(node.getAttribute("viewCount")),
                    node.getAttribute("sourceTrackId").isBlank() ? null : node.getAttribute("sourceTrackId"),
                    node.getAttribute("segmentStartMillis").isBlank() ? 0 : Long.parseLong(node.getAttribute("segmentStartMillis")),
                    node.getAttribute("segmentEndMillis").isBlank() ? 0 : Long.parseLong(node.getAttribute("segmentEndMillis")),
                    node.getAttribute("sourceDurationSeconds").isBlank() ? 0 : Integer.parseInt(node.getAttribute("sourceDurationSeconds"))));
        }
        String unresolved = root.getAttribute("unresolvedCount");
        return new TracksResponse(List.copyOf(tracks), root.getAttribute("intents"),
                root.getAttribute("tasteSource"), unresolved.isBlank() ? 0 : Integer.parseInt(unresolved));
    }
}
