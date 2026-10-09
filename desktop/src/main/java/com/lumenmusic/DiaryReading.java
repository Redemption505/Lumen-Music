package com.lumenmusic;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Dates are taken only from saved timestamps, in the diary's declared zone. */
final class DiaryReading {
    static final String ALL = "全部日期";
    static final String UNKNOWN = "日期未知";

    private DiaryReading() { }

    static ZoneId zone(String value) {
        try { return ZoneId.of(value); }
        catch (Exception ignored) { return ZoneId.systemDefault(); }
    }

    static LocalDate date(String timestamp, String zone) {
        try { return Instant.parse(timestamp).atZone(zone(zone)).toLocalDate(); }
        catch (Exception ignored) { return null; }
    }

    static String noteTimestamp(DiaryClient.Note note) {
        // An update can locate a legacy note without claiming it was created that day.
        try { Instant.parse(note.createdAt()); return note.createdAt(); }
        catch (Exception ignored) { return note.updatedAt(); }
    }

    static String dateKey(String timestamp, String zone) {
        LocalDate value = date(timestamp, zone);
        return value == null ? UNKNOWN : value.toString();
    }

    static String display(String timestamp, String zone) {
        try {
            return DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
                    .withZone(zone(zone)).format(Instant.parse(timestamp));
        } catch (Exception ignored) { return UNKNOWN; }
    }

    static String noteDates(DiaryClient.Note note, String zone) {
        String created = "创建于 " + display(note.createdAt(), zone);
        if (date(note.createdAt(), zone) == null) created = "创建日期未知";
        if (date(note.updatedAt(), zone) != null && !note.updatedAt().equals(note.createdAt()))
            created += "  ·  更新于 " + display(note.updatedAt(), zone);
        return created;
    }

    static boolean matches(String timestamp, String zone, String filter) {
        return filter == null || ALL.equals(filter) || dateKey(timestamp, zone).equals(filter);
    }

    static <T> Map<String, List<T>> groups(List<T> values, Function<T, String> timestamp, String zone) {
        Map<String, List<T>> collected = new LinkedHashMap<>();
        values.forEach(value -> collected.computeIfAbsent(dateKey(timestamp.apply(value), zone),
                ignored -> new ArrayList<>()).add(value));
        Map<String, List<T>> groups = new LinkedHashMap<>();
        collected.keySet().stream()
                .sorted(Comparator.comparing((String value) -> UNKNOWN.equals(value))
                        .thenComparing(Comparator.reverseOrder()))
                .forEach(key -> groups.put(key, collected.get(key).stream()
                        .sorted(Comparator.comparing((T value) -> instant(timestamp.apply(value)),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList()));
        return groups;
    }

    private static Instant instant(String timestamp) {
        try { return Instant.parse(timestamp); }
        catch (Exception ignored) { return null; }
    }
}
