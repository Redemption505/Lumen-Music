package com.lumenmusic;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Persists the last successful recommendation response for local startup restoration. */
public final class RecommendationStore {
    private final Path file;

    public RecommendationStore(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
    }

    public synchronized Optional<MusicClient.RecommendationResult> read() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        try {
            Properties values = LocalStateFile.read(file, "recommendations");
            return Optional.of(new MusicClient.RecommendationResult(LocalStateFile.readTracks(values),
                    LocalStateFile.required(values, "intents"), LocalStateFile.required(values, "tasteSource")));
        } catch (IOException | RuntimeException error) {
            throw LocalStateFile.damaged(file, "推荐", error);
        }
    }

    public synchronized void save(MusicClient.RecommendationResult result) throws IOException {
        Objects.requireNonNull(result, "Recommendation result is required");
        Properties values = LocalStateFile.values("recommendations");
        LocalStateFile.writeTracks(values, result.tracks());
        values.setProperty("intents", Objects.requireNonNull(result.intents(), "Recommendation intents are required"));
        values.setProperty("tasteSource", Objects.requireNonNull(result.tasteSource(), "Recommendation source is required"));
        LocalStateFile.save(file, values);
    }
}

/** Shared XML properties codec for the two desktop state snapshots. */
final class LocalStateFile {
    private LocalStateFile() { }

    static Properties values(String kind) {
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("kind", kind);
        return values;
    }

    static Properties read(Path file, String kind) throws IOException {
        Properties values = new Properties();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) { values.loadFromXML(input); }
        if (!"1".equals(values.getProperty("version")) || !kind.equals(values.getProperty("kind")))
            throw new IOException("Unsupported state version or kind");
        return values;
    }

    static void save(Path file, Properties values) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "lumen-state-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.storeToXML(output, "Lumen Music local state", "UTF-8");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void writeTracks(Properties values, List<Track> tracks) {
        Objects.requireNonNull(tracks, "Tracks are required");
        values.setProperty("trackCount", Integer.toString(tracks.size()));
        for (int index = 0; index < tracks.size(); index++) {
            Track track = tracks.get(index);
            validateTrack(track);
            String prefix = "track." + index + ".";
            values.setProperty(prefix + "identity", trackIdentity(track));
            values.setProperty(prefix + "id", track.id());
            values.setProperty(prefix + "title", track.title());
            values.setProperty(prefix + "artist", track.artist());
            values.setProperty(prefix + "durationSeconds", Integer.toString(track.durationSeconds()));
            values.setProperty(prefix + "source", track.source());
            values.setProperty(prefix + "page", Integer.toString(track.page()));
            values.setProperty(prefix + "partCount", Integer.toString(track.partCount()));
            values.setProperty(prefix + "viewCount", Long.toString(track.viewCount()));
            if (track.mediaUri() != null) values.setProperty(prefix + "mediaUri", track.mediaUri().toString());
            if (track.bvid() != null) values.setProperty(prefix + "bvid", track.bvid());
            if (track.coverUri() != null) values.setProperty(prefix + "coverUri", track.coverUri().toString());
            if (track.isSegment()) {
                values.setProperty(prefix + "sourceTrackId", track.sourceTrackId());
                values.setProperty(prefix + "segmentStartMillis", Long.toString(track.segmentStartMillis()));
                values.setProperty(prefix + "segmentEndMillis", Long.toString(track.segmentEndMillis()));
                values.setProperty(prefix + "sourceDurationSeconds", Integer.toString(track.sourceDurationSeconds()));
            }
        }
    }

    static List<Track> readTracks(Properties values) throws IOException {
        int count = Integer.parseInt(required(values, "trackCount"));
        if (count < 0) throw new IOException("Track count cannot be negative");
        List<Track> tracks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "track." + index + ".";
            Track track = new Track(required(values, prefix + "id"), required(values, prefix + "title"),
                    required(values, prefix + "artist"), Integer.parseInt(required(values, prefix + "durationSeconds")),
                    required(values, prefix + "source"), uri(values, prefix + "mediaUri"),
                    values.getProperty(prefix + "bvid"), Integer.parseInt(required(values, prefix + "page")),
                    uri(values, prefix + "coverUri"), Integer.parseInt(required(values, prefix + "partCount")),
                    Long.parseLong(values.getProperty(prefix + "viewCount", "0")),
                    values.getProperty(prefix + "sourceTrackId"),
                    Long.parseLong(values.getProperty(prefix + "segmentStartMillis", "0")),
                    Long.parseLong(values.getProperty(prefix + "segmentEndMillis", "0")),
                    Integer.parseInt(values.getProperty(prefix + "sourceDurationSeconds", "0")));
            validateTrack(track);
            if (!trackIdentity(track).equals(required(values, prefix + "identity")))
                throw new IOException("Track identity mismatch");
            tracks.add(track);
        }
        return List.copyOf(tracks);
    }

    static void validateTrack(Track track) {
        LyricsSelectionStore.identity(track);
        if (track.title() == null || track.artist() == null || track.source() == null)
            throw new IllegalArgumentException("Track title, artist and source are required");
        if (track.durationSeconds() < 0 || track.page() < 0 || track.partCount() < 0)
            throw new IllegalArgumentException("Track duration and part fields cannot be negative");
    }

    static String trackIdentity(Track track) {
        return Base64.getEncoder().encodeToString(LyricsSelectionStore.identity(track).getBytes(StandardCharsets.UTF_8));
    }

    private static URI uri(Properties values, String key) {
        String value = values.getProperty(key);
        return value == null ? null : URI.create(value);
    }

    static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null) throw new IOException("Required state field is missing: " + key);
        return value;
    }

    static IOException damaged(Path file, String kind, Exception error) {
        return new IOException(kind + "状态损坏，原文件已保留：" + file
                + " (" + error.getMessage() + ")", error);
    }
}
