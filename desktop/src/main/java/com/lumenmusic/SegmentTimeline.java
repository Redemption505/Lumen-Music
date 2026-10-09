package com.lumenmusic;

import javafx.util.Duration;

/** Maps a source media clock to an independently playable segment. */
final class SegmentTimeline {
    static Duration position(Track track, Duration sourcePosition) {
        if (track == null || !track.isSegment()) return sourcePosition;
        if (sourcePosition.isUnknown() || sourcePosition.isIndefinite()) return Duration.ZERO;
        return Duration.millis(Math.max(0, Math.min(track.segmentEndMillis() - track.segmentStartMillis(),
                sourcePosition.toMillis() - track.segmentStartMillis())));
    }

    static Duration duration(Track track, Duration sourceDuration) {
        return track != null && track.isSegment()
                ? Duration.millis(track.segmentEndMillis() - track.segmentStartMillis()) : sourceDuration;
    }

    static double sourceFraction(Track track, double fraction, Duration sourceDuration) {
        double ratio = Math.max(0, Math.min(1, fraction));
        if (track == null || !track.isSegment()) return ratio;
        double sourceMillis = sourceDuration.toMillis();
        if (!Double.isFinite(sourceMillis) || sourceMillis <= 0) return 0;
        return Math.min(1, (track.segmentStartMillis()
                + ratio * (track.segmentEndMillis() - track.segmentStartMillis())) / sourceMillis);
    }

    static String sourceLyrics(String content, Track segment) {
        var lines = Lyrics.parse(content);
        StringBuilder result = new StringBuilder();
        Lyrics.Line previous = null;
        for (Lyrics.Line line : lines) {
            if (line.millis() < segment.segmentStartMillis()) { previous = line; continue; }
            if (line.millis() >= segment.segmentEndMillis()) break;
            if (result.isEmpty() && previous != null && line.millis() > segment.segmentStartMillis())
                append(result, 0, previous.text());
            append(result, line.millis() - segment.segmentStartMillis(), line.text());
            previous = null;
        }
        if (result.isEmpty() && previous != null) append(result, 0, previous.text());
        return result.toString();
    }

    private static void append(StringBuilder result, long millis, String text) {
        result.append(String.format(java.util.Locale.ROOT, "[%02d:%02d.%03d]%s%n",
                millis / 60000, millis / 1000 % 60, millis % 1000, text));
    }

    private SegmentTimeline() { }
}
