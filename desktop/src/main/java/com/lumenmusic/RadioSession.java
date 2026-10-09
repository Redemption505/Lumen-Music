package com.lumenmusic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coordinates a private-radio preview and recently seen track identities. */
public final class RadioSession {
    private static final int MAX_SEEN = 200;

    private final Deque<RadioClient.Item> preview = new ArrayDeque<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final LinkedHashSet<String> dismissed = new LinkedHashSet<>();
    private List<Track> seeds = List.of();
    private String hint = "";
    private String mode = "familiar";
    private boolean neutral = true;
    private boolean active;
    private boolean autoContinue = true;

    public void start(List<Track> seeds, String hint, String mode, boolean neutral) {
        if (!"familiar".equals(mode) && !"discover".equals(mode))
            throw new IllegalArgumentException("Radio mode must be familiar or discover");
        this.seeds = List.copyOf(seeds == null ? List.of() : seeds);
        this.hint = hint == null ? "" : hint;
        this.mode = mode;
        this.neutral = neutral;
        this.active = true;
        this.autoContinue = true;
        this.preview.clear();
        this.seen.clear();
        this.dismissed.clear();
        for (Track seed : this.seeds) remember(seed.id());
    }

    public void stop() { active = false; }
    public boolean active() { return active; }
    public boolean autoContinue() { return autoContinue; }
    public void setAutoContinue(boolean autoContinue) { this.autoContinue = autoContinue; }
    public List<Track> seeds() { return seeds; }
    public String hint() { return hint; }
    public String mode() { return mode; }
    public boolean neutral() { return neutral; }
    public List<RadioClient.Item> preview() { return List.copyOf(preview); }

    public void accept(RadioClient.Result result) {
        if (!active) return;
        if (result == null) throw new IllegalArgumentException("Radio result is required");
        preview.clear();
        Set<String> included = new LinkedHashSet<>();
        for (RadioClient.Item item : result.items()) {
            if (item == null || item.track() == null) continue;
            String id = item.track().id();
            if (seen.contains(id) || dismissed.contains(id) || !included.add(id)) continue;
            preview.addLast(item);
        }
    }

    public RadioClient.Item popNext() {
        if (!active || !autoContinue) return null;
        RadioClient.Item next = preview.pollFirst();
        if (next == null) return null;
        remember(next.track().id());
        return next;
    }

    public RadioClient.Item take(String id) {
        if (!active || id == null) return null;
        for (var iterator = preview.iterator(); iterator.hasNext();) {
            RadioClient.Item item = iterator.next();
            if (!id.equals(item.track().id())) continue;
            iterator.remove();
            remember(id);
            return item;
        }
        return null;
    }

    public void remove(String id) {
        if (id == null) return;
        preview.removeIf(item -> id.equals(item.track().id()));
        dismissed.add(id);
    }

    public void recordPlayed(Track track) {
        if (track == null) return;
        preview.removeIf(item -> track.id().equals(item.track().id()));
        remember(track.id());
    }

    public void restoreRemoved(String id) {
        dismissed.remove(id);
        seen.remove(id);
    }

    public Set<String> seenIds() {
        Set<String> ids = new LinkedHashSet<>(seen);
        ids.addAll(dismissed);
        return Set.copyOf(ids);
    }

    private void remember(String id) {
        if (id == null || id.isBlank()) return;
        seen.remove(id);
        seen.add(id);
        while (seen.size() > MAX_SEEN) seen.remove(seen.iterator().next());
    }
}
