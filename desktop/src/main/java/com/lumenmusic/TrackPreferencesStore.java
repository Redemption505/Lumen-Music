package com.lumenmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Properties;

/** Local per-part exclusions and confirmed unavailable-resource markers. */
public final class TrackPreferencesStore {
    private final Path file;
    private Set<String> excluded = Set.of();
    private Map<String, String> unavailable = Map.of();

    public TrackPreferencesStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (!Files.exists(this.file)) return;
        try {
            Properties values = LocalStateFile.read(this.file, "track-preferences");
            Set<String> skips = new HashSet<>();
            Map<String, String> failures = new HashMap<>();
            int count = Integer.parseInt(LocalStateFile.required(values, "count"));
            if (count < 0 || count > 100000) throw new IOException("Invalid preference count");
            for (int i = 0; i < count; i++) {
                String prefix = "item." + i + ".";
                String id = LocalStateFile.required(values, prefix + "id");
                if (id.isBlank()) throw new IOException("Empty track identity");
                if (Boolean.parseBoolean(values.getProperty(prefix + "excluded"))) skips.add(id);
                String reason = values.getProperty(prefix + "failure", "");
                if (!reason.isBlank()) failures.put(id, reason);
            }
            excluded = Set.copyOf(skips);
            unavailable = Map.copyOf(failures);
        } catch (IOException | RuntimeException error) {
            throw LocalStateFile.damaged(this.file, "曲目偏好", error);
        }
    }

    public synchronized boolean isExcluded(String id) { return excluded.contains(id); }
    public synchronized Set<String> excludedIds() { return excluded; }
    public synchronized boolean isUnavailable(String id) { return unavailable.containsKey(id); }
    public synchronized String failureReason(String id) { return unavailable.getOrDefault(id, ""); }
    public synchronized Set<String> unavailableIds() { return Set.copyOf(unavailable.keySet()); }

    public synchronized void setExcluded(String id, boolean value) throws IOException {
        checkId(id);
        Set<String> updated = new HashSet<>(excluded);
        if (value) updated.add(id); else updated.remove(id);
        save(updated, unavailable);
    }

    public synchronized void markUnavailable(String id, String reason) throws IOException {
        checkId(id);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Failure reason is required");
        Map<String, String> updated = new HashMap<>(unavailable);
        updated.put(id, reason);
        save(excluded, updated);
    }

    public synchronized void clearUnavailable(String id) throws IOException {
        if (!unavailable.containsKey(id)) return;
        Map<String, String> updated = new HashMap<>(unavailable);
        updated.remove(id);
        save(excluded, updated);
    }

    private void save(Set<String> skips, Map<String, String> failures) throws IOException {
        Properties values = LocalStateFile.values("track-preferences");
        Set<String> all = new HashSet<>(skips);
        all.addAll(failures.keySet());
        var ids = all.stream().sorted().toList();
        values.setProperty("count", Integer.toString(ids.size()));
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i), prefix = "item." + i + ".";
            values.setProperty(prefix + "id", id);
            values.setProperty(prefix + "excluded", Boolean.toString(skips.contains(id)));
            if (failures.containsKey(id)) values.setProperty(prefix + "failure", failures.get(id));
        }
        LocalStateFile.save(file, values);
        excluded = Set.copyOf(skips);
        unavailable = Map.copyOf(failures);
    }

    private static void checkId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Track identity is required");
    }
}
