package com.lumenmusic;

import java.net.URI;
import java.util.UUID;

public record Track(String id, String title, String artist, int durationSeconds, String source,
                    URI mediaUri, String bvid, int page, URI coverUri, int partCount, long viewCount,
                    String sourceTrackId, long segmentStartMillis, long segmentEndMillis,
                    int sourceDurationSeconds) {
    public Track {
        if (sourceTrackId != null) {
            if (sourceTrackId.isBlank()) throw new IllegalArgumentException("片段缺少原音源身份");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("片段歌名不能为空");
            title = title.trim();
            if (sourceDurationSeconds < 0) throw new IllegalArgumentException("原音源时长不能为负");
            int expectedDuration = segmentDuration(segmentStartMillis, segmentEndMillis);
            if (sourceDurationSeconds > 0 && segmentEndMillis > sourceDurationSeconds * 1000L)
                throw new IllegalArgumentException("片段结束时间超出音源时长");
            if (durationSeconds != expectedDuration)
                throw new IllegalArgumentException("片段时长与范围不一致");
        } else if (segmentStartMillis != 0 || segmentEndMillis != 0 || sourceDurationSeconds != 0) {
            throw new IllegalArgumentException("普通曲目不能包含片段信息");
        }
    }

    public Track(String id, String title, String artist, int durationSeconds, String source,
                 URI mediaUri, String bvid, int page, URI coverUri, int partCount, long viewCount) {
        this(id, title, artist, durationSeconds, source, mediaUri, bvid, page, coverUri,
                partCount, viewCount, null, 0, 0, 0);
    }

    public boolean isSegment() { return sourceTrackId != null; }

    public Track audioSource() {
        return isSegment() ? new Track(sourceTrackId, title, artist, sourceDurationSeconds, source,
                mediaUri, bvid, page, coverUri, partCount, viewCount) : this;
    }

    public static Track segment(Track source, String title, String artist, long startMillis, long endMillis) {
        if (source == null) throw new IllegalArgumentException("请选择原音源");
        Track original = source.audioSource();
        return new Track("segment:" + UUID.randomUUID(), title, segmentArtist(artist, original.artist()),
                segmentDuration(startMillis, endMillis), original.source(), original.mediaUri(),
                original.bvid(), original.page(), original.coverUri(), original.partCount(),
                original.viewCount(), original.id(), startMillis, endMillis,
                original.durationSeconds());
    }

    public Track withSegment(String title, String artist, long startMillis, long endMillis) {
        if (!isSegment()) throw new IllegalStateException("只能编辑片段曲目");
        return new Track(id, title, segmentArtist(artist, this.artist), segmentDuration(startMillis, endMillis), source,
                mediaUri, bvid, page, coverUri, partCount, viewCount, sourceTrackId,
                startMillis, endMillis, sourceDurationSeconds);
    }

    private static int segmentDuration(long startMillis, long endMillis) {
        if (startMillis < 0 || endMillis <= startMillis)
            throw new IllegalArgumentException("片段范围无效：结束时间必须晚于开始时间");
        if (endMillis - startMillis > Integer.MAX_VALUE * 1000L)
            throw new IllegalArgumentException("片段范围过长");
        return (int) ((endMillis - startMillis + 999) / 1000);
    }

    private static String segmentArtist(String requested, String existing) {
        return requested == null || requested.isBlank() ? existing : requested.trim();
    }
    public Track(String id, String title, String artist, int durationSeconds, String source,
                 URI mediaUri, String bvid, int page, URI coverUri, int partCount) {
        this(id, title, artist, durationSeconds, source, mediaUri, bvid, page, coverUri, partCount, 0);
    }
    public Track(String id, String title, String artist, int durationSeconds, String source,
                 URI mediaUri, String bvid, int page, URI coverUri) {
        this(id, title, artist, durationSeconds, source, mediaUri, bvid, page, coverUri, 0);
    }
    public Track(String id, String title, String artist, int durationSeconds, String source,
                 URI mediaUri, String bvid, int page) {
        this(id, title, artist, durationSeconds, source, mediaUri, bvid, page, null);
    }
    public Track(String id, String title, String artist, int durationSeconds, String source) {
        this(id, title, artist, durationSeconds, source, null, null, 0, null);
    }
    public Track(String id, String title, String artist, int durationSeconds, String source, URI mediaUri) {
        this(id, title, artist, durationSeconds, source, mediaUri, null, 0, null);
    }
    @Override public String toString() { return title + " · " + artist; }
}
