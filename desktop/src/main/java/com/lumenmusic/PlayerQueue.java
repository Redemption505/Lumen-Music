package com.lumenmusic;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/** A snapshot of the selected tracks and the order in which they play. */
public final class PlayerQueue {
    public enum RepeatMode { OFF, ALL, ONE, SHUFFLE }
    public enum Source { CURRENT_LIST, MANUAL, RADIO }

    public record Snapshot(List<Track> tracks, List<Source> sources, int index,
                           RepeatMode repeatMode, List<Integer> shuffleHistory, int shuffleCursor) {
        public Snapshot {
            tracks = List.copyOf(tracks);
            sources = List.copyOf(sources);
            shuffleHistory = List.copyOf(shuffleHistory);
            Objects.requireNonNull(repeatMode);
            if (tracks.size() != sources.size() || index < -1 || index >= tracks.size()
                    || (tracks.isEmpty() && index != -1)
                    || shuffleCursor < 0 || (!shuffleHistory.isEmpty() && shuffleCursor >= shuffleHistory.size())
                    || (shuffleHistory.isEmpty() && shuffleCursor != 0))
                throw new IllegalArgumentException("Invalid queue snapshot");
            for (int entry : shuffleHistory) {
                if (entry < 0 || entry >= tracks.size()) throw new IllegalArgumentException("Invalid shuffle history");
            }
        }
    }

    private List<Track> tracks = List.of();
    private List<Source> sources = List.of();
    private int index = -1;
    private RepeatMode repeatMode = RepeatMode.ALL;
    private final List<Integer> shuffleHistory = new ArrayList<>();
    private int shuffleCursor;
    private Set<String> excludedIds = Set.of();
    private final IntUnaryOperator randomIndex;
    private int reservedShuffleNext = -1;

    public PlayerQueue() {
        this(bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    PlayerQueue(IntUnaryOperator randomIndex) {
        this.randomIndex = Objects.requireNonNull(randomIndex);
    }

    public void setExcludedIds(Set<String> ids) {
        Set<String> updated = Set.copyOf(ids);
        if (excludedIds.equals(updated)) return;
        excludedIds = updated;
        resetShuffleHistory();
    }

    private boolean eligible(int position) {
        return position >= 0 && position < tracks.size() && !excludedIds.contains(tracks.get(position).id());
    }

    public void select(List<Track> results, int selected) {
        select(results, selected, Source.CURRENT_LIST);
    }

    public void select(List<Track> results, int selected, Source source) {
        Objects.requireNonNull(source);
        if (selected < 0 || selected >= results.size()) throw new IllegalArgumentException("无效曲目位置");
        tracks = List.copyOf(results);
        sources = Collections.nCopies(tracks.size(), source);
        index = selected;
        resetShuffleHistory();
    }

    /** Selects an existing item without discarding queue sources. */
    public void selectIndex(int selected) {
        if (selected < 0 || selected >= tracks.size()) throw new IllegalArgumentException("无效曲目位置");
        index = selected;
        resetShuffleHistory();
    }

    /** Inserts a track immediately after the current track and makes it the next playback item. */
    public void playNext(Track track) {
        playNext(track, Source.MANUAL);
    }

    public void playNext(Track track, Source source) {
        insert(index < 0 ? 0 : index + 1, List.of(Objects.requireNonNull(track)), Objects.requireNonNull(source));
    }

    public void enqueue(Track track) { enqueue(List.of(Objects.requireNonNull(track))); }

    /** Appends arranged items before future radio items, preserving input order. */
    public void enqueue(List<Track> additions) {
        Objects.requireNonNull(additions);
        if (additions.isEmpty()) return;
        int insertion = tracks.size();
        for (int i = Math.max(0, index + 1); i < sources.size(); i++) {
            if (sources.get(i) == Source.RADIO) { insertion = i; break; }
        }
        insert(insertion, additions, Source.MANUAL);
    }

    private void insert(int insertion, List<Track> additions, Source source) {
        List<Track> updated = new ArrayList<>(tracks);
        updated.addAll(insertion, additions);
        List<Source> updatedSources = new ArrayList<>(sources);
        for (int i = 0; i < additions.size(); i++) updatedSources.add(insertion + i, source);
        tracks = List.copyOf(updated);
        sources = List.copyOf(updatedSources);
        reservedShuffleNext = -1;
        if (index >= insertion) index += additions.size();
        else if (index < 0) index = insertion;
        for (int i = 0; i < shuffleHistory.size(); i++) {
            int historyIndex = shuffleHistory.get(i);
            if (historyIndex >= insertion) shuffleHistory.set(i, historyIndex + additions.size());
        }
        if (shuffleHistory.isEmpty() && index >= 0) resetShuffleHistory();
        else if (repeatMode == RepeatMode.SHUFFLE && source == Source.MANUAL) {
            while (shuffleHistory.size() > shuffleCursor + 1) shuffleHistory.remove(shuffleHistory.size() - 1);
        }
    }

    /** Moves one queued item from {@code from} to {@code to}, preserving the current item and shuffle history. */
    public void reorder(int from, int to) {
        if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size())
            throw new IllegalArgumentException("无效曲目位置");
        if (from == to) return;
        reservedShuffleNext = -1;

        int oldCurrent = index;
        List<Track> updated = new ArrayList<>(tracks);
        Track moved = updated.remove(from);
        updated.add(to, moved);
        List<Source> updatedSources = new ArrayList<>(sources);
        Source movedSource = updatedSources.remove(from);
        updatedSources.add(to, movedSource);
        tracks = List.copyOf(updated);
        index = remapIndex(oldCurrent, from, to);
        if (from > oldCurrent && to > oldCurrent) {
            for (int i = Math.max(0, index + 1); i < updatedSources.size(); i++) {
                updatedSources.set(i, Source.MANUAL);
            }
        }
        sources = List.copyOf(updatedSources);
        for (int i = 0; i < shuffleHistory.size(); i++)
            shuffleHistory.set(i, remapIndex(shuffleHistory.get(i), from, to));
        if (repeatMode == RepeatMode.SHUFFLE && from > oldCurrent && to > oldCurrent) {
            while (shuffleHistory.size() > shuffleCursor + 1) shuffleHistory.remove(shuffleHistory.size() - 1);
        }
    }

    private static int remapIndex(int value, int from, int to) {
        if (value == from) return to;
        if (from < to && value > from && value <= to) return value - 1;
        if (to < from && value >= to && value < from) return value + 1;
        return value;
    }
    public Track current() { return index < 0 ? null : tracks.get(index); }
    public List<Source> sources() { return sources; }
    public Source sourceAt(int position) { return sources.get(position); }
    public Snapshot snapshot() {
        return new Snapshot(tracks, sources, index, repeatMode, shuffleHistory, shuffleCursor);
    }
    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        reservedShuffleNext = -1;
        tracks = snapshot.tracks();
        sources = snapshot.sources();
        index = snapshot.index();
        repeatMode = snapshot.repeatMode();
        shuffleHistory.clear();
        shuffleHistory.addAll(snapshot.shuffleHistory());
        shuffleCursor = snapshot.shuffleCursor();
    }
    public void restore(List<Track> snapshot, int selected) {
        restore(snapshot, selected, Collections.nCopies(snapshot.size(), Source.CURRENT_LIST));
    }
    public void restore(List<Track> snapshot, int selected, List<Source> savedSources) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(savedSources);
        if (snapshot.size() != savedSources.size() || selected < -1 || selected >= snapshot.size()
                || (snapshot.isEmpty() && selected != -1))
            throw new IllegalArgumentException("Invalid queue state");
        tracks = List.copyOf(snapshot);
        sources = List.copyOf(savedSources);
        index = selected;
        resetShuffleHistory();
    }

    public void replaceById(Track replacement) {
        tracks = tracks.stream().map(track -> track.id().equals(replacement.id()) ? replacement : track).toList();
        reservedShuffleNext = -1;
    }

    public void removeById(String id) {
        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id().equals(id)) matching.add(i);
        removeIndices(matching);
    }
    /** Returns true when the selected occurrence was removed. */
    public boolean removeAt(int position) { return removeIndices(List.of(position)); }

    public boolean removeIndices(Collection<Integer> positions) {
        Objects.requireNonNull(positions);
        Set<Integer> removed = new HashSet<>(positions);
        for (int position : removed) {
            if (position < 0 || position >= tracks.size()) throw new IllegalArgumentException("无效曲目位置");
        }
        if (removed.isEmpty()) return false;
        reservedShuffleNext = -1;
        boolean currentRemoved = removed.contains(index);
        int[] shifted = new int[tracks.size()];
        List<Track> remaining = new ArrayList<>();
        List<Source> remainingSources = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            shifted[i] = removed.contains(i) ? -1 : remaining.size();
            if (!removed.contains(i)) {
                remaining.add(tracks.get(i));
                remainingSources.add(sources.get(i));
            }
        }
        int nextIndex = index < 0 ? -1 : shifted[index];
        if (currentRemoved) {
            for (int i = index + 1; i < tracks.size(); i++) {
                if (shifted[i] >= 0 && eligible(i)) { nextIndex = shifted[i]; break; }
            }
            if (nextIndex < 0) {
                for (int i = index - 1; i >= 0; i--) {
                    if (shifted[i] >= 0 && eligible(i)) { nextIndex = shifted[i]; break; }
                }
            }
        }
        tracks = List.copyOf(remaining);
        sources = List.copyOf(remainingSources);
        index = nextIndex;
        if (currentRemoved) resetShuffleHistory();
        else {
            List<Integer> remainingHistory = new ArrayList<>();
            int nextCursor = shuffleCursor;
            for (int i = 0; i < shuffleHistory.size(); i++) {
                int mapped = shifted[shuffleHistory.get(i)];
                if (mapped >= 0) remainingHistory.add(mapped);
                else if (i <= shuffleCursor) nextCursor--;
            }
            shuffleHistory.clear();
            shuffleHistory.addAll(remainingHistory);
            shuffleCursor = Math.max(0, nextCursor);
            if (shuffleHistory.isEmpty() && index >= 0) resetShuffleHistory();
        }
        return currentRemoved;
    }

    public void clear() {
        tracks = List.of();
        sources = List.of();
        index = -1;
        resetShuffleHistory();
    }
    public List<Track> tracks() { return tracks; }
    public int index() { return index; }
    public RepeatMode repeatMode() { return repeatMode; }
    public void setRepeatMode(RepeatMode mode) {
        repeatMode = java.util.Objects.requireNonNull(mode);
        resetShuffleHistory();
    }

    private void resetShuffleHistory() {
        reservedShuffleNext = -1;
        shuffleHistory.clear();
        if (index >= 0) shuffleHistory.add(index);
        shuffleCursor = 0;
    }

    /** Manual navigation always wraps, regardless of the repeat mode. */
    public Track move(int step) {
        if (tracks.isEmpty()) return null;
        var available = new ArrayList<Integer>();
        for (int i = 0; i < tracks.size(); i++) if (eligible(i)) available.add(i);
        if (available.isEmpty()) return null;
        if (step == 0) return eligible(index) ? current() : null;
        if (repeatMode != RepeatMode.SHUFFLE) {
            reservedShuffleNext = -1;
            int direction = step < 0 ? -1 : 1;
            int next = index;
            for (int count = 0; count < Math.abs(step); count++) {
                do { next = Math.floorMod(next + direction, tracks.size()); } while (!eligible(next));
            }
            index = next;
        }
        else if (step < 0) {
            reservedShuffleNext = -1;
            while (shuffleCursor > 0) {
                int previous = shuffleHistory.get(--shuffleCursor);
                if (eligible(previous)) { index = previous; return current(); }
            }
            if (!eligible(index)) index = available.get(0);
        } else if (step > 0) {
            for (int count = 0; count < step; count++) {
                boolean foundHistory = false;
                while (shuffleCursor + 1 < shuffleHistory.size()) {
                    int next = shuffleHistory.get(++shuffleCursor);
                    if (eligible(next)) { index = next; foundHistory = true; break; }
                }
                if (foundHistory) {
                    reservedShuffleNext = -1;
                    continue;
                }
                int next = nextManual();
                if (next < 0) {
                    next = reserveShuffleNext(available);
                }
                reservedShuffleNext = -1;
                index = next;
                shuffleHistory.add(next);
                shuffleCursor++;
            }
        }
        return current();
    }

    private int nextManual() {
        for (int i = index + 1; i < tracks.size(); i++) {
            if (sources.get(i) == Source.MANUAL && eligible(i)) return i;
        }
        return -1;
    }

    private int reserveShuffleNext(List<Integer> available) {
        if (reservedShuffleNext < 0) {
            List<Integer> candidates = new ArrayList<>(available);
            if (candidates.size() > 1) candidates.remove(Integer.valueOf(index));
            reservedShuffleNext = candidates.get(randomIndex.applyAsInt(candidates.size()));
        }
        return reservedShuffleNext;
    }

    /** Previews arranged playback without wrapping or changing playback state. */
    public Track peekWithoutWrap() {
        int next = nextWithoutWrap();
        return next < 0 ? null : tracks.get(next);
    }

    private int nextWithoutWrap() {
        for (int next = index + 1; next < tracks.size(); next++) {
            if (eligible(next)) return next;
        }
        return -1;
    }

    /** Previews automatic advancement, reserving a random successor until navigation or mutation. */
    public Track peekAfterEnd() {
        if (tracks.isEmpty()) return null;
        if (repeatMode == RepeatMode.ONE && eligible(index)) return current();
        if (repeatMode == RepeatMode.OFF) return peekWithoutWrap();
        if (repeatMode != RepeatMode.SHUFFLE) {
            for (int count = 1; count <= tracks.size(); count++) {
                int next = Math.floorMod(index + count, tracks.size());
                if (eligible(next)) return tracks.get(next);
            }
            return null;
        }
        for (int cursor = shuffleCursor + 1; cursor < shuffleHistory.size(); cursor++) {
            int next = shuffleHistory.get(cursor);
            if (eligible(next)) return tracks.get(next);
        }
        int manual = nextManual();
        if (manual >= 0) return tracks.get(manual);
        List<Integer> available = new ArrayList<>();
        for (int position = 0; position < tracks.size(); position++) {
            if (eligible(position)) available.add(position);
        }
        return available.isEmpty() ? null : tracks.get(reserveShuffleNext(available));
    }

    /** Drains arranged tracks in order before radio candidates, without wrapping. */
    public Track advanceWithoutWrap() {
        reservedShuffleNext = -1;
        int next = nextWithoutWrap();
        if (next < 0) return null;
        index = next;
        resetShuffleHistory();
        return current();
    }

    /** Returns the next track to play, or null when playback should stop. */
    public Track advanceAfterEnd() {
        if (tracks.isEmpty()) return null;
        if (repeatMode == RepeatMode.ONE && eligible(index)) return current();
        if (repeatMode == RepeatMode.OFF) {
            for (int next = index + 1; next < tracks.size(); next++) {
                if (eligible(next)) { index = next; return current(); }
            }
            return null;
        }
        return move(1);
    }
}
