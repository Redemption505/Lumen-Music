package com.lumenmusic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Timed LRC lines. Metadata and malformed lines are ignored. */
final class Lyrics {
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIMESTAMP = Pattern.compile("<(\\d{2}):([0-5]\\d)\\.(\\d{1,3})>");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d+)]\\s*$");

    record Line(long millis, String text) { }

    static List<Line> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        long offset = 0;
        Matcher offsetMatcher = OFFSET.matcher(content);
        if (offsetMatcher.find()) {
            try { offset = Long.parseLong(offsetMatcher.group(1)); }
            catch (NumberFormatException ignored) { }
        }
        List<Line> lines = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            Matcher matcher = TIMESTAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find()) {
                long millis = timestampMillis(matcher);
                if (millis < 0) continue;
                times.add(millis);
                end = matcher.end();
            }
            String body = raw.substring(end).stripLeading();
            if (times.isEmpty()) {
                Matcher wordMatcher = WORD_TIMESTAMP.matcher(body);
                if (!wordMatcher.lookingAt()) continue;
                times.add(timestampMillis(wordMatcher));
            }
            String text = WORD_TIMESTAMP.matcher(body).replaceAll("").strip();
            if (text.isEmpty()) continue;
            for (long time : times) lines.add(new Line(Math.max(0, time + offset), text));
        }
        lines.sort(Comparator.comparingLong(Line::millis));
        return List.copyOf(lines);
    }

    private static long timestampMillis(Matcher matcher) {
        int seconds = Integer.parseInt(matcher.group(2));
        if (seconds >= 60) return -1;
        String fraction = matcher.group(3);
        long millis = fraction == null ? 0 :
                fraction.length() == 1 ? Integer.parseInt(fraction) * 100L :
                fraction.length() == 2 ? Integer.parseInt(fraction) * 10L :
                Integer.parseInt(fraction);
        return Long.parseLong(matcher.group(1)) * 60_000L + seconds * 1_000L + millis;
    }

    static int currentIndex(List<Line> lines, long millis) {
        int low = 0, high = lines.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).millis() <= millis) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }
}
