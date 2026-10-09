package com.lumenmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Stores a queue snapshot without changing or starting the player. */
public final class PlaybackStateStore {
    private final Path file;

    public record State(List<Track> tracks, int index, PlayerQueue.RepeatMode repeatMode,
                        double positionSeconds, List<PlayerQueue.Source> sources) {
        public State(List<Track> tracks, int index, PlayerQueue.RepeatMode repeatMode,
                     double positionSeconds) {
            this(tracks, index, repeatMode, positionSeconds,
                    java.util.Collections.nCopies(tracks.size(), PlayerQueue.Source.CURRENT_LIST));
        }

        public State {
            tracks = List.copyOf(Objects.requireNonNull(tracks, "Tracks are required"));
            sources = List.copyOf(Objects.requireNonNull(sources, "Queue sources are required"));
            Objects.requireNonNull(repeatMode, "Repeat mode is required");
            if (sources.size() != tracks.size())
                throw new IllegalArgumentException("Queue source count must match track count");
            for (Track track : tracks) LocalStateFile.validateTrack(track);
            if (index < -1 || index >= tracks.size())
                throw new IllegalArgumentException("Queue index does not identify a current track");
            if (!Double.isFinite(positionSeconds) || positionSeconds < 0)
                throw new IllegalArgumentException("Playback position must be finite and nonnegative");
        }
    }

    public PlaybackStateStore(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
    }

    public synchronized Optional<State> read() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        try {
            Properties values = LocalStateFile.read(file, "playback");
            List<Track> tracks = LocalStateFile.readTracks(values);
            List<PlayerQueue.Source> sources = new java.util.ArrayList<>();
            for (int i = 0; i < tracks.size(); i++) {
                sources.add(PlayerQueue.Source.valueOf(values.getProperty("source." + i, "CURRENT_LIST")));
            }
            State state = new State(tracks,
                    Integer.parseInt(LocalStateFile.required(values, "index")),
                    PlayerQueue.RepeatMode.valueOf(LocalStateFile.required(values, "repeatMode")),
                    Double.parseDouble(LocalStateFile.required(values, "positionSeconds")), sources);
            String currentIdentity = state.index() < 0 ? "" : LocalStateFile.trackIdentity(state.tracks().get(state.index()));
            if (!currentIdentity.equals(LocalStateFile.required(values, "currentTrackIdentity")))
                throw new IOException("Current track identity mismatch");
            return Optional.of(state);
        } catch (IOException | RuntimeException error) {
            throw LocalStateFile.damaged(file, "播放", error);
        }
    }

    public synchronized void save(State state) throws IOException {
        Objects.requireNonNull(state, "Playback state is required");
        Properties values = LocalStateFile.values("playback");
        LocalStateFile.writeTracks(values, state.tracks());
        values.setProperty("index", Integer.toString(state.index()));
        values.setProperty("repeatMode", state.repeatMode().name());
        values.setProperty("positionSeconds", Double.toString(state.positionSeconds()));
        for (int i = 0; i < state.sources().size(); i++)
            values.setProperty("source." + i, state.sources().get(i).name());
        values.setProperty("currentTrackIdentity", state.index() < 0 ? ""
                : LocalStateFile.trackIdentity(state.tracks().get(state.index())));
        LocalStateFile.save(file, values);
    }
}
