package com.lumenmusic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An immutable, local snapshot for searching library items and application commands. */
public final class QuickSearchIndex {
    public enum Type {
        TRACK("歌曲"), PLAYLIST("歌单"), COMMAND("操作");

        private final String label;

        Type(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Command(String id, String title, String subtitle, Runnable action) { }

    public record Result(Type type, String title, String subtitle, Track track,
                         LibraryClient.Playlist playlist, Command command) { }

    private static final int MAX_RESULTS = 60;
    private final List<Result> entries;

    private QuickSearchIndex(List<Result> entries) {
        this.entries = List.copyOf(entries);
    }

    public static QuickSearchIndex build(List<Track> favorites,
                                         List<LibraryClient.Playlist> playlists,
                                         List<Track> versions,
                                         List<Track> localTracks,
                                         List<Command> commands) {
        Map<String, TrackEntry> tracks = new LinkedHashMap<>();
        addTracks(tracks, favorites, "收藏");
        if (playlists != null) {
            for (LibraryClient.Playlist playlist : playlists) {
                if (playlist != null) addTracks(tracks, playlist.tracks(), "歌单：" + safe(playlist.name()));
            }
        }
        addTracks(tracks, versions, "版本");
        addTracks(tracks, localTracks, "本地");

        List<Result> entries = new ArrayList<>();
        if (commands != null) {
            for (Command command : commands) {
                if (command != null && command.action() != null && !safe(command.title()).isBlank()) {
                    entries.add(new Result(Type.COMMAND, command.title(), safe(command.subtitle()),
                            null, null, command));
                }
            }
        }
        if (playlists != null) {
            for (LibraryClient.Playlist playlist : playlists) {
                if (playlist != null) {
                    int count = playlist.tracks() == null ? 0 : playlist.tracks().size();
                    entries.add(new Result(Type.PLAYLIST, safe(playlist.name()), count + " 首歌曲",
                            null, playlist, null));
                }
            }
        }
        for (TrackEntry entry : tracks.values()) {
            String artist = safe(entry.track.artist());
            String sources = String.join(" · ", entry.sources);
            String subtitle = artist.isBlank() ? sources : artist + (sources.isBlank() ? "" : " · " + sources);
            entries.add(new Result(Type.TRACK, safe(entry.track.title()), subtitle,
                    entry.track, null, null));
        }
        return new QuickSearchIndex(entries);
    }

    public List<Result> search(String query) {
        String needle = normalize(query);
        String[] terms = needle.isBlank() ? new String[0] : needle.split("\\s+");
        List<RankedResult> matches = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Result result = entries.get(i);
            int rank = rank(result, needle, terms);
            if (rank >= 0) matches.add(new RankedResult(result, rank, i));
        }
        matches.sort(Comparator.comparingInt(RankedResult::rank)
                .thenComparingInt(RankedResult::position));
        return matches.stream().limit(MAX_RESULTS).map(RankedResult::result).toList();
    }

    private static void addTracks(Map<String, TrackEntry> entries, List<Track> source, String label) {
        if (source == null) return;
        for (Track track : source) {
            if (track == null) continue;
            String id = safe(track.id());
            String key = id.isBlank() ? "missing-id:" + entries.size() : id;
            TrackEntry entry = entries.computeIfAbsent(key, ignored -> new TrackEntry(track));
            entry.sources.add(label);
        }
    }

    private static int rank(Result result, String needle, String[] terms) {
        if (terms.length == 0) return switch (result.type()) {
            case COMMAND -> 0;
            case PLAYLIST -> 1;
            case TRACK -> 2;
        };
        String title = normalize(result.title());
        String subtitle = normalize(result.subtitle());
        String searchable = title + " " + subtitle;
        for (String term : terms) if (!searchable.contains(term)) return -1;
        if (title.equals(needle)) return 0;
        if (title.startsWith(needle)) return 1;
        if (title.contains(needle)) return 2;
        if (subtitle.contains(needle)) return 3;
        return 4;
    }

    private static String normalize(String value) {
        return safe(value).strip().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private record RankedResult(Result result, int rank, int position) { }

    private static final class TrackEntry {
        private final Track track;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();

        private TrackEntry(Track track) { this.track = track; }
    }
}
