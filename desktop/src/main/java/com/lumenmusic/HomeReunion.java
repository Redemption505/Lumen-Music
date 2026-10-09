package com.lumenmusic;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.util.Optional;

/** Selects one small, truthful listening-memory card from the existing diary. */
public final class HomeReunion {
    public record Selection(Track track, String reason) { }

    private HomeReunion() { }

    public static Optional<Selection> select(DiaryClient.Diary diary) {
        if (diary == null) return Optional.empty();
        ZoneId zone;
        try { zone = ZoneId.of(diary.zone()); }
        catch (DateTimeException | NullPointerException ex) { zone = ZoneId.systemDefault(); }
        for (String section : java.util.List.of("reheard", "weekly-first", "recent")) {
            for (DiaryClient.Entry entry : diary.section(section).entries()) {
                Track track = entry.track();
                if (track == null || track.id() == null || track.id().isBlank()) continue;
                String date = date("weekly-first".equals(section) ? entry.firstPlayedAt() : entry.playedAt(), zone);
                if (date == null) continue;
                String reason = switch (section) {
                    case "reheard" -> {
                        String favorite = date(entry.favoriteAt(), zone);
                        yield favorite == null ? "你在 " + date + " 重听过这首歌"
                                : "你在 " + favorite + " 收藏，在 " + date + " 又听到了它";
                    }
                    case "weekly-first" -> "你在 " + date + " 第一次听到这首歌";
                    default -> "你在 " + date + " 听过这首歌";
                };
                return Optional.of(new Selection(track, reason));
            }
        }
        return Optional.empty();
    }

    private static String date(String timestamp, ZoneId zone) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try { return DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.parse(timestamp).atZone(zone)); }
        catch (DateTimeException ex) { return null; }
    }
}
