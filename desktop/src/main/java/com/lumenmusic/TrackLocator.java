package com.lumenmusic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds title or artist matches in a track snapshot and cycles their source-list positions. */
public final class TrackLocator {
    private List<Track> items = List.of();
    private String query = "";
    private List<Integer> matches = List.of();
    private int cursor = -1;

    public void setItems(List<Track> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
        rebuild();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        rebuild();
    }

    /** Source-list indexes matching the current query, in source-list order. */
    public List<Integer> matches() {
        return matches;
    }

    /** Zero-based position in {@link #matches()}, or -1 when there are no matches. */
    public int cursor() {
        return cursor;
    }

    /** Source-list index of the current match, or -1 when there are no matches. */
    public int currentIndex() {
        return cursor < 0 ? -1 : matches.get(cursor);
    }

    /** Advances to the next match and returns its source-list index; a single match stays selected. */
    public int nextIndex() {
        if (matches.isEmpty()) return -1;
        if (matches.size() > 1) cursor = (cursor + 1) % matches.size();
        return currentIndex();
    }

    private void rebuild() {
        List<Integer> found = new ArrayList<>();
        if (!query.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                Track track = items.get(i);
                if (track == null) continue;
                String title = track.title() == null ? "" : track.title().toLowerCase(Locale.ROOT);
                String artist = track.artist() == null ? "" : track.artist().toLowerCase(Locale.ROOT);
                if (title.contains(query) || artist.contains(query)) found.add(i);
            }
        }
        matches = List.copyOf(found);
        cursor = matches.isEmpty() ? -1 : 0;
    }
}
