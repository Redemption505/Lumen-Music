package com.lumenmusic.audio;

import com.lumenmusic.MusicClient;
import com.lumenmusic.MusicClient.DownloadCancellation;
import com.lumenmusic.Track;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Session-only files let JavaFX seek reliably in Bilibili fragmented MP4 audio. */
public final class OnlineAudioCache implements AutoCloseable {
    private final MusicClient client;
    private final PersistentAudioCache persistent;
    private final Map<String, Path> ready = new ConcurrentHashMap<>();
    private final Path directory;

    public OnlineAudioCache(MusicClient client) {
        this(client, null);
    }

    public OnlineAudioCache(MusicClient client, PersistentAudioCache persistent) {
        this.client = client;
        this.persistent = persistent;
        try { directory = Files.createTempDirectory("lumen-online-"); }
        catch (IOException error) { throw new UncheckedIOException(error); }
        directory.toFile().deleteOnExit();
    }

    public URI resolve(Track track, DownloadCancellation cancellation) throws Exception {
        return resolve(track, cancellation, (stage, attempt) -> { });
    }

    public URI resolve(Track track, DownloadCancellation cancellation, MusicClient.PreparationListener listener)
            throws Exception {
        cancellation.check();
        Track source = track.audioSource();
        Path cached = ready.get(source.id());
        if (cached != null && Files.isRegularFile(cached)) {
            retainInPersistentCache(track, cached, cancellation);
            cancellation.check();
            return cached.toUri();
        }
        Path partial = Files.createTempFile(directory, "audio-", ".part");
        partial.toFile().deleteOnExit();
        Path file = null;
        boolean committed = false;
        try {
            boolean restored = false;
            if (persistent != null && persistent.enabled()) {
                try {
                    var saved = persistent.lookup(track);
                    if (saved.isPresent()) {
                        copyCompleteFile(saved.get(), partial, cancellation);
                        restored = true;
                    }
                } catch (IOException error) {
                    // A cache entry may be cleared while it is copied; the network remains usable.
                }
            }
            if (!restored) {
                client.downloadStream(source, partial, cancellation, listener);
                cancellation.check();
                retainInPersistentCache(track, partial, cancellation);
            }
            cancellation.check();
            file = Files.createTempFile(directory, "audio-", ".m4a");
            Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            cancellation.check();
            Path completedFile = file;
            Path chosen = ready.compute(source.id(), (id, previous) ->
                    previous != null && Files.isRegularFile(previous) ? previous : completedFile);
            try { cancellation.check(); }
            catch (Exception error) {
                ready.remove(source.id(), completedFile);
                throw error;
            }
            if (chosen != file) {
                Files.deleteIfExists(file);
                return chosen.toUri();
            }
            file.toFile().deleteOnExit();
            committed = true;
            return file.toUri();
        } finally {
            Files.deleteIfExists(partial);
            if (file != null && !committed) Files.deleteIfExists(file);
        }
    }

    /** Read a completed session source without starting a download or persistent write. */
    public URI lookup(Track track) {
        Path file = ready.get(track.audioSource().id());
        return file != null && Files.isRegularFile(file) ? file.toUri() : null;
    }

    /** The caller owns this detached file until adoption or deletion. */
    public Path prepare(Track track, DownloadCancellation cancellation, long maxBytes) throws Exception {
        Path file = Files.createTempFile(directory, "next-", ".m4a");
        boolean complete = false;
        try {
            client.downloadStream(track.audioSource(), file, cancellation, maxBytes, (stage, attempt) -> { });
            cancellation.check();
            file.toFile().deleteOnExit();
            complete = true;
            return file;
        } finally { if (!complete) Files.deleteIfExists(file); }
    }

    /** JavaFX plays a private copy so clearing the persistent cache cannot hit its media file lock. */
    public Path prepareCached(URI cached, DownloadCancellation cancellation) throws IOException {
        cancellation.check();
        Path file = Files.createTempFile(directory, "next-", ".m4a");
        boolean complete = false;
        try {
            copyCompleteFile(cached, file, cancellation);
            file.toFile().deleteOnExit();
            complete = true;
            return file;
        } finally { if (!complete) Files.deleteIfExists(file); }
    }

    private static void copyCompleteFile(URI cached, Path target, DownloadCancellation cancellation)
            throws IOException {
        cancellation.check();
        Path source = Path.of(cached);
        long expected = Files.size(source);
        if (expected <= 0) throw new IOException("Cached audio is empty");
        long copied = 0;
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                cancellation.check();
                copied += count;
                if (copied > expected) throw new IOException("Cached audio changed during copy");
                output.write(buffer, 0, count);
            }
        }
        cancellation.check();
        if (copied != expected) throw new IOException("Cached audio changed during copy");
    }

    /** Transfers a detached prepared file into the normal session, without persisting it. */
    public URI adoptPrepared(Track track, Path file) throws IOException {
        if (file == null || !file.getParent().equals(directory) || !Files.isRegularFile(file))
            throw new IOException("Prepared session audio is unavailable");
        Path chosen = ready.compute(track.audioSource().id(), (id, previous) ->
                previous != null && Files.isRegularFile(previous) ? previous : file);
        if (!chosen.equals(file)) Files.deleteIfExists(file);
        return chosen.toUri();
    }

    /** Called asynchronously only after the player confirms it used a prepared complete file. */
    public void retainPrepared(Track track, URI uri, DownloadCancellation cancellation) throws Exception {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) return;
        Path file = Path.of(uri);
        if (Files.isRegularFile(file)) retainInPersistentCache(track, file, cancellation);
    }

    private void retainInPersistentCache(Track track, Path completeFile, DownloadCancellation cancellation)
            throws Exception {
        if (persistent == null || !persistent.enabled() || Files.size(completeFile) > persistent.maxBytes()) return;
        try {
            persistent.storeIfEnabled(track, cancellation, (ignored, target, token) ->
                    Files.copy(completeFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
        } catch (IOException error) {
            // Disk storage is optional, but cancellation must still stop the current request.
            cancellation.check();
        }
    }

    @Override public void close() {
        try (var files = Files.list(directory)) {
            files.forEach(file -> {
                try { Files.deleteIfExists(file); }
                catch (IOException ignored) { file.toFile().deleteOnExit(); }
            });
        } catch (IOException ignored) {
            // A remaining open file is registered for deletion when the process exits.
        }
        ready.clear();
        try { Files.deleteIfExists(directory); }
        catch (IOException ignored) { directory.toFile().deleteOnExit(); }
    }
}
