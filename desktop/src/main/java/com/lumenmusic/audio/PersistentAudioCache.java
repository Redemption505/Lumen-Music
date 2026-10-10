package com.lumenmusic.audio;

import com.lumenmusic.MusicClient.DownloadCancellation;
import com.lumenmusic.Track;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Opt-in, bounded disk cache for complete audio files. Never exposes a partial download. */
public final class PersistentAudioCache {
    private static final String SETTINGS = "settings.properties";
    private static final long DEFAULT_LIMIT = 512L * 1024 * 1024;
    private final Path directory;
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean enabled;
    private long maxBytes = DEFAULT_LIMIT;
    private long generation;

    @FunctionalInterface public interface Downloader {
        void download(Track track, Path target, DownloadCancellation cancellation) throws Exception;
    }

    private record Entry(Path audio, Path metadata, long size, String digest) { }

    public PersistentAudioCache(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        readSettings();
        readEntries();
        trim();
    }

    public synchronized boolean enabled() { return enabled; }
    public synchronized long maxBytes() { return maxBytes; }
    public synchronized long generation() { return generation; }
    /** Inventory size; restored entries are checksum-verified only when used or inspected. */
    public synchronized long usedBytes() {
        return entries.values().stream().mapToLong(Entry::size).sum();
    }
    /** Inventory count, not the number of checksum-verified audio files. */
    public synchronized int entryCount() { return entries.size(); }

    public synchronized void configure(boolean enabled, long maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        Properties settings = new Properties();
        settings.setProperty("version", "1");
        settings.setProperty("enabled", Boolean.toString(enabled));
        settings.setProperty("maxBytes", Long.toString(maxBytes));
        writeAtomically(directory.resolve(SETTINGS), settings);
        this.enabled = enabled;
        this.maxBytes = maxBytes;
        ++generation;
        trim();
    }

    public Optional<URI> lookup(Track track) throws IOException {
        return lookup(track, true);
    }

    /** Validate a source without counting a speculative preparation as playback. */
    public Optional<URI> peek(Track track) throws IOException {
        return lookup(track, false);
    }

    private Optional<URI> lookup(Track track, boolean markUsed) throws IOException {
        String key = key(track);
        Entry entry;
        synchronized (this) {
            if (!enabled) return Optional.empty();
            entry = entries.get(key);
        }
        if (entry == null) return Optional.empty();
        boolean valid;
        try { valid = valid(entry); }
        catch (java.nio.file.NoSuchFileException removed) { valid = false; }
        synchronized (this) {
            if (!enabled || entries.get(key) != entry) return Optional.empty();
            if (!valid) {
                remove(key, entry);
                return Optional.empty();
            }
            if (markUsed) touch(entry.audio());
            return Optional.of(entry.audio().toUri());
        }
    }

    /** Inspect complete files without counting library browsing as cache playback. */
    public Set<String> cachedTrackIds(Collection<Track> tracks) throws IOException {
        Map<String, Entry> snapshot;
        synchronized (this) { snapshot = Map.copyOf(entries); }
        Map<String, Boolean> checked = new HashMap<>();
        Set<String> result = new HashSet<>();
        for (Track track : tracks) {
            String sourceKey = key(track);
            Entry entry = snapshot.get(sourceKey);
            if (entry == null) continue;
            Boolean available = checked.get(sourceKey);
            if (available == null) {
                try { available = valid(entry); }
                catch (java.nio.file.NoSuchFileException removed) { available = false; }
                checked.put(sourceKey, available);
            }
            synchronized (this) {
                if (available && entries.get(sourceKey) == entry) result.add(track.id());
            }
        }
        return Set.copyOf(result);
    }

    /** Skip a background cache write if the user disabled caching before it acquired the lock. */
    public Optional<URI> storeIfEnabled(Track track, DownloadCancellation cancellation,
                                                       Downloader downloader) throws Exception {
        synchronized (this) { if (!enabled) return Optional.empty(); }
        try { return Optional.of(store(track, cancellation, downloader)); }
        catch (CacheChangedException ignored) { return Optional.empty(); }
    }

    /** The downloader writes to a private .part file. On failure all existing entries remain intact. */
    public URI store(Track track, DownloadCancellation cancellation, Downloader downloader)
            throws Exception {
        long startedGeneration;
        synchronized (this) {
            if (!enabled) throw new CacheChangedException("Persistent audio cache is disabled");
            startedGeneration = generation;
        }
        cancellation.check();
        Optional<URI> existing = lookup(track);
        if (existing.isPresent()) return existing.get();
        Path partial = Files.createTempFile(directory, "download-", ".part");
        String key = key(track);
        Path audio = directory.resolve(key + ".m4a");
        Path metadata = directory.resolve(key + ".properties");
        try {
            downloader.download(track.audioSource(), partial, cancellation);
            cancellation.check();
            long size = Files.size(partial);
            if (size == 0) throw new IOException("Audio is empty");
            String digest = digest(partial);
            cancellation.check();
            Properties values = new Properties();
            values.setProperty("version", "1");
            values.setProperty("size", Long.toString(size));
            values.setProperty("sha256", digest);
            synchronized (this) {
                cancellation.check();
                if (!enabled || generation != startedGeneration)
                    throw new CacheChangedException("Cache settings changed or cache was cleared during download");
                if (size > maxBytes) throw new IOException("Audio exceeds cache capacity");
                Entry concurrent = entries.get(key);
                if (concurrent != null) return concurrent.audio().toUri();
                // Only the short publish step holds the lock; download and hashing never do.
                Files.move(partial, audio, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try {
                    cancellation.check();
                    writeAtomically(metadata, values);
                    cancellation.check();
                } catch (IOException error) {
                    Files.deleteIfExists(audio);
                    Files.deleteIfExists(metadata);
                    throw error;
                }
                Entry entry = new Entry(audio, metadata, size, digest);
                entries.put(key, entry);
                touch(audio);
                trim();
                return audio.toUri();
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    public synchronized void clear() throws IOException {
        ++generation;
        IOException failure = null;
        for (var item : Map.copyOf(entries).entrySet()) {
            try { remove(item.getKey(), item.getValue()); }
            catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }

    private static final class CacheChangedException extends IllegalStateException {
        CacheChangedException(String message) { super(message); }
    }

    private void readSettings() throws IOException {
        Path file = directory.resolve(SETTINGS);
        if (!Files.exists(file)) return;
        Properties values = new Properties();
        try {
            try (InputStream input = Files.newInputStream(file)) { values.load(input); }
            if (!"1".equals(values.getProperty("version"))) return;
            long limit = Long.parseLong(values.getProperty("maxBytes"));
            if (limit <= 0) return;
            maxBytes = limit;
            enabled = Boolean.parseBoolean(values.getProperty("enabled"));
        } catch (RuntimeException ignored) {
            // Invalid settings fail closed: caching stays disabled.
        }
    }

    private void readEntries() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.properties")) {
            for (Path metadata : files) {
                String name = metadata.getFileName().toString();
                if (name.equals(SETTINGS) || !name.matches("[0-9a-f]{64}\\.properties")) continue;
                String key = name.substring(0, 64);
                Path audio = directory.resolve(key + ".m4a");
                try {
                    Properties values = new Properties();
                    try (InputStream input = Files.newInputStream(metadata)) { values.load(input); }
                    long size = Long.parseLong(values.getProperty("size"));
                    String sha = values.getProperty("sha256");
                    Entry entry = new Entry(audio, metadata, size, sha);
                    // Startup may run on JavaFX: inspect inventory without reading entire audio files.
                    if (!"1".equals(values.getProperty("version")) || !validStructure(entry)) {
                        Files.deleteIfExists(metadata);
                        Files.deleteIfExists(audio);
                    } else entries.put(key, entry);
                } catch (RuntimeException | IOException error) {
                    Files.deleteIfExists(metadata);
                    Files.deleteIfExists(audio);
                }
            }
        }
        // Remove only recognized abandoned cache files; never touch unrelated user files.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.m4a")) {
            for (Path audio : files) {
                String name = audio.getFileName().toString();
                if (name.matches("[0-9a-f]{64}\\.m4a") && !entries.containsKey(name.substring(0, 64)))
                    Files.deleteIfExists(audio);
            }
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "download-*.part")) {
            for (Path partial : files) Files.deleteIfExists(partial);
        }
    }

    private boolean valid(Entry entry) throws IOException {
        return validStructure(entry) && digest(entry.audio()).equals(entry.digest());
    }

    private boolean validStructure(Entry entry) throws IOException {
        return entry.size() > 0 && entry.digest() != null
                && entry.digest().matches("[0-9a-f]{64}")
                && Files.isRegularFile(entry.audio()) && Files.size(entry.audio()) == entry.size();
    }

    private void trim() throws IOException {
        while (usedBytes() > maxBytes && !entries.isEmpty()) {
            var oldest = entries.entrySet().stream()
                    .min(Comparator.comparing(item -> modified(item.getValue().audio())))
                    .orElseThrow();
            remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static FileTime modified(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException ignored) { return FileTime.fromMillis(0); }
    }

    private void touch(Path audio) throws IOException {
        long newest = entries.values().stream()
                .mapToLong(entry -> modified(entry.audio()).toMillis()).max().orElse(0);
        Files.setLastModifiedTime(audio, FileTime.fromMillis(
                Math.max(System.currentTimeMillis(), newest + 1)));
    }

    private void remove(String key, Entry entry) throws IOException {
        Files.deleteIfExists(entry.audio());
        Files.deleteIfExists(entry.metadata());
        entries.remove(key);
    }

    private static String key(Track track) {
        if (track == null || track.id() == null || track.id().isBlank())
            throw new IllegalArgumentException("Track id is required");
        return HexFormat.of().formatHex(sha256().digest(track.audioSource().id().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String digest(Path file) throws IOException {
        MessageDigest hash = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int length; (length = input.read(buffer)) >= 0;) {
                if (length > 0) hash.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static void writeAtomically(Path target, Properties values) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "cache-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { values.store(output, "Lumen Music cache"); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
