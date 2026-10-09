package com.lumenmusic;

/** Temporary A-B marks on the current track's relative media clock. */
final class PracticeLoop {
    static final long MIN_RANGE_MILLIS = 500;
    private long startMillis = -1;
    private long endMillis = -1;
    private boolean enabled;

    long startMillis() { return startMillis; }
    long endMillis() { return endMillis; }
    boolean enabled() { return enabled; }
    boolean complete() { return startMillis >= 0 && endMillis > startMillis; }

    void clear() {
        startMillis = endMillis = -1;
        enabled = false;
    }

    void markStart(long position, long duration) {
        validatePosition(position, duration);
        if (duration - position < MIN_RANGE_MILLIS)
            throw new IllegalArgumentException("A 点后需至少保留 0.5 秒");
        startMillis = position;
        if (endMillis - startMillis < MIN_RANGE_MILLIS) {
            endMillis = -1;
            enabled = false;
        }
    }

    void markEnd(long position, long duration) {
        if (startMillis < 0) throw new IllegalArgumentException("请先标记 A 点");
        configure(startMillis, position, duration);
        enabled = true;
    }

    void configure(long start, long end, long duration) {
        validatePosition(start, duration);
        validatePosition(end, duration);
        if (end - start < MIN_RANGE_MILLIS)
            throw new IllegalArgumentException("B 点需晚于 A 点至少 0.5 秒");
        startMillis = start;
        endMillis = end;
    }

    void setEnabled(boolean value) {
        if (value && !complete()) throw new IllegalArgumentException("请先设置 A、B 两点");
        enabled = value;
    }

    boolean outside(long position) {
        return enabled && (position < startMillis || position >= endMillis);
    }

    long sourceStart(Track track) { return Math.addExact(sourceOffset(track), startMillis); }
    long sourceEnd(Track track) { return Math.addExact(sourceOffset(track), endMillis); }

    private static long sourceOffset(Track track) {
        return track != null && track.isSegment() ? track.segmentStartMillis() : 0;
    }

    private static void validatePosition(long position, long duration) {
        if (duration <= 0) throw new IllegalArgumentException("请等待音频时长就绪");
        if (position < 0 || position > duration)
            throw new IllegalArgumentException("时间需在当前曲目范围内");
    }
}
