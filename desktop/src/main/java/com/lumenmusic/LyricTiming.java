package com.lumenmusic;

import java.util.Locale;

/** Converts a song's LRC clock to the enclosing video's clock. */
final class LyricTiming {
    static long mediaMillis(long lyricMillis, long startMillis, long offsetMillis) {
        try { return Math.addExact(Math.addExact(lyricMillis, startMillis), offsetMillis); }
        catch (ArithmeticException error) {
            throw new IllegalArgumentException("歌词时间超出范围");
        }
    }

    static long relativeMillis(long mediaMillis, long startMillis, long offsetMillis) {
        try { return Math.max(-1, Math.subtractExact(Math.subtractExact(mediaMillis, startMillis), offsetMillis)); }
        catch (ArithmeticException error) { return offsetMillis < 0 ? Long.MAX_VALUE : -1; }
    }

    static long parseStart(String text) {
        if (text == null || !text.strip().matches("\\d+:[0-5]\\d(?::[0-5]\\d)?(?:\\.\\d{1,3})?"))
            throw new IllegalArgumentException("起点格式为 分:秒 或 时:分:秒");
        String[] fraction = text.strip().split("\\.", 2);
        String[] parts = fraction[0].split(":");
        try {
            long seconds = 0;
            for (String part : parts) seconds = Math.addExact(Math.multiplyExact(seconds, 60), Long.parseLong(part));
            long millis = Math.multiplyExact(seconds, 1000);
            if (fraction.length == 2) millis = Math.addExact(millis, Long.parseLong((fraction[1] + "000").substring(0, 3)));
            return millis;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("歌曲起点超出范围");
        }
    }

    static String formatStart(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d.%03d", seconds / 60, seconds % 60, millis % 1000);
    }

    private LyricTiming() { }
}
