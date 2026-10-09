package com.lumenmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/** The local catalogue references audio files; it never copies or removes them. */
public final class LocalMusicStore {
    // Separate store instances can reference the same catalogue. On Windows a reader's
    // open XML stream prevents replacing that file, so synchronize disk access across instances.
    private static final ReentrantLock DISK_LOCK = new ReentrantLock(true);
    public record State(List<Track> tracks, Map<String, String> lyrics) {
        public State { tracks = List.copyOf(tracks); lyrics = Map.copyOf(lyrics); }
    }
    private final Path file;
    public LocalMusicStore(Path file) { this.file = file.toAbsolutePath().normalize(); }
    public boolean exists() { return Files.exists(file); }
    public State read() throws IOException {
        DISK_LOCK.lock();
        try { return readState(); }
        finally { DISK_LOCK.unlock(); }
    }
    private State readState() throws IOException {
        if (!exists()) return new State(List.of(), Map.of());
        try {
            Properties values = LocalStateFile.read(file, "local-music");
            List<Track> tracks = LocalStateFile.readTracks(values);
            Map<String, String> lyrics = new LinkedHashMap<>();
            for (int i = 0; i < tracks.size(); i++) {
                Track track = tracks.get(i);
                if (!"local".equals(track.source()) || track.mediaUri() == null
                        || !"file".equalsIgnoreCase(track.mediaUri().getScheme()))
                    throw new IOException("Invalid local audio reference");
                String text = values.getProperty("lyrics." + i, "");
                if (!text.isBlank()) lyrics.put(track.id(), text);
            }
            return new State(tracks, lyrics);
        } catch (IOException | RuntimeException error) {
            throw LocalStateFile.damaged(file, "本地音乐", error);
        }
    }
    public void save(State state) throws IOException {
        Properties values = LocalStateFile.values("local-music");
        LocalStateFile.writeTracks(values, state.tracks());
        for (int i = 0; i < state.tracks().size(); i++) {
            Track track = state.tracks().get(i);
            if (!"local".equals(track.source()) || track.mediaUri() == null
                    || !"file".equalsIgnoreCase(track.mediaUri().getScheme()))
                throw new IOException("Invalid local audio reference");
            String text = state.lyrics().get(track.id());
            if (text != null && !text.isBlank()) values.setProperty("lyrics." + i, text);
        }
        DISK_LOCK.lock();
        try { LocalStateFile.save(file, values); }
        finally { DISK_LOCK.unlock(); }
    }
}
