package com.lumenmusic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/** Persists manual lyric choices and automatic matches, keyed by stable track identity. */
public final class LyricsSelectionStore {
    private final Path directory;
    private final Replacement replacement;

    public enum Origin { MANUAL, AUTOMATIC }

    /** A safe UI message; file names, track metadata and parser details are not exposed. */
    public static final class CorruptRecordException extends IOException {
        private final Origin origin;

        private CorruptRecordException(Origin origin) {
            super((origin == Origin.MANUAL ? "手动歌词记录损坏" : "自动歌词记录损坏")
                    + "，原文件已保留；可重新匹配歌词");
            this.origin = origin;
        }

        public Origin origin() { return origin; }
    }

    @FunctionalInterface
    interface Replacement {
        void replace(Path temporary, Path target) throws IOException;
    }

    /** Synchronized LRC text is preserved byte-for-character as a Java string, including its line breaks. */
    public record Selection(String title, String artist, String lrc, int offsetMillis,
                            Origin origin, long startMillis) {
        public Selection(String title, String artist, String lrc, int offsetMillis) {
            this(title, artist, lrc, offsetMillis, Origin.MANUAL, 0);
        }

        public Selection(String title, String artist, String lrc, int offsetMillis, Origin origin) {
            this(title, artist, lrc, offsetMillis, origin, 0);
        }

        public Selection {
            if (title == null || artist == null || lrc == null || origin == null)
                throw new IllegalArgumentException("Lyrics selection fields cannot be null");
            if (startMillis < 0) throw new IllegalArgumentException("Lyrics start time cannot be negative");
        }

        public Selection withOffsetMillis(int offsetMillis) {
            return new Selection(title, artist, lrc, offsetMillis, origin, startMillis);
        }

        public Selection withStartMillis(long startMillis) {
            return new Selection(title, artist, lrc, offsetMillis, origin, startMillis);
        }
    }

    public LyricsSelectionStore(Path directory) throws IOException {
        this(directory, (temporary, target) -> Files.move(temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    LyricsSelectionStore(Path directory, Replacement replacement) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.replacement = replacement;
        Files.createDirectories(this.directory);
    }

    /** Uses the same per-user data root as the desktop app, with selections in their own subdirectory. */
    public static Path defaultDirectory() {
        if (ReleaseProfile.current().localFirst())
            return ReleaseProfile.current().appDataDirectory().resolve("lyrics-selections");
        String configured = System.getenv("LUMEN_LIBRARY_PATH");
        if (configured != null && !configured.isBlank()) {
            Path parent = Path.of(configured).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent.resolve("lyrics-selections");
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank())
            return Path.of(local, "Lumen Music", "lyrics-selections");
        String xdg = System.getenv("XDG_DATA_HOME");
        Path data = xdg != null && !xdg.isBlank() ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return data.resolve("Lumen Music").resolve("lyrics-selections");
    }

    public synchronized Optional<Selection> load(Track track) throws IOException {
        String identity = identity(track);
        Path file = file(identity);
        if (Files.exists(file)) return loadFile(identity, file, Origin.MANUAL);
        return loadFile(identity, automaticFile(identity), Origin.AUTOMATIC);
    }

    public synchronized Optional<Selection> read(Track track) throws IOException { return load(track); }

    private Optional<Selection> loadFile(String identity, Path file, Origin origin) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
            if (!"1".equals(values.getProperty("version"))
                    || !identity.equals(values.getProperty("trackIdentity")))
                throw new IOException("unsupported version or track identity mismatch");
            String title = values.getProperty("title");
            String artist = values.getProperty("artist");
            String lrc = values.getProperty("lrc");
            String offset = values.getProperty("offsetMillis");
            if (title == null || artist == null || lrc == null || offset == null)
                throw new IOException("required field is missing");
            String savedOrigin = values.getProperty("origin");
            if (savedOrigin != null && !origin.name().equals(savedOrigin))
                throw new IOException("lyrics selection origin mismatch");
            long startMillis = Long.parseLong(values.getProperty("startMillis", "0"));
            return Optional.of(new Selection(title, artist, lrc, Integer.parseInt(offset), origin, startMillis));
        } catch (IOException | RuntimeException error) {
            throw new CorruptRecordException(origin);
        }
    }

    public synchronized void save(Track track, Selection selection) throws IOException {
        if (selection == null) throw new IllegalArgumentException("selection is required");
        String identity = identity(track);
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("trackIdentity", identity);
        values.setProperty("title", selection.title());
        values.setProperty("artist", selection.artist());
        values.setProperty("lrc", selection.lrc());
        values.setProperty("offsetMillis", Integer.toString(selection.offsetMillis()));
        values.setProperty("startMillis", Long.toString(selection.startMillis()));
        values.setProperty("origin", selection.origin().name());
        Path target = selection.origin() == Origin.MANUAL ? file(identity) : automaticFile(identity);
        try {
            loadFile(identity, target, selection.origin());
        } catch (CorruptRecordException error) {
            preserveCorruptFile(target);
        }
        Path temporary = Files.createTempFile(directory, "lyrics-selection-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "Lumen Music lyrics selection");
            }
            replacement.replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Keep one exact recovery copy, and refuse replacement when it cannot retain this original. */
    private void preserveCorruptFile(Path target) throws IOException {
        Path recovery = target.resolveSibling(target.getFileName() + ".corrupt");
        try {
            if (!Files.isRegularFile(target)) throw new IOException("not a regular file");
            if (!Files.exists(recovery)) Files.copy(target, recovery);
            if (!Files.isRegularFile(recovery) || Files.mismatch(target, recovery) != -1)
                throw new IOException("recovery copy differs");
        } catch (IOException | RuntimeException error) {
            throw new IOException("损坏歌词记录无法安全保留，未覆盖原文件");
        }
    }

    public synchronized void saveAutomatic(Track track, Selection selection) throws IOException {
        if (selection == null) throw new IllegalArgumentException("selection is required");
        save(track, new Selection(selection.title(), selection.artist(), selection.lrc(),
                selection.offsetMillis(), Origin.AUTOMATIC, selection.startMillis()));
    }

    /** Bilibili parts share a BV id, so page is part of that source's identity. */
    static String identity(Track track) {
        if (track == null || track.id() == null || track.id().isBlank())
            throw new IllegalArgumentException("Track id is required");
        if (track.isSegment()) return "segment\u0000" + track.sourceTrackId() + "\u0000" + track.id();
        if (track.bvid() != null && !track.bvid().isBlank() && track.page() > 0)
            return "bilibili\u0000" + track.bvid() + "\u0000" + track.page();
        String source = track.source() == null ? "" : track.source();
        return source + "\u0000" + track.id();
    }

    private Path file(String identity) { return directory.resolve(sha256(identity) + ".properties"); }

    private Path automaticFile(String identity) {
        return directory.resolve(sha256(identity) + ".automatic.properties");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
