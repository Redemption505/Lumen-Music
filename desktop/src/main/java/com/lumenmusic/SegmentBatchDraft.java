package com.lumenmusic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Editable candidates retain their identity until the user saves the batch. */
final class SegmentBatchDraft {
    static final int MAX_ROWS = 200;
    private static final String TIME = "\\d+:\\d+(?::\\d+)?(?:\\.\\d+)?";
    private static final Pattern NUMBER = Pattern.compile("^\\s*(?:\\d+\\s*[.)、）]|[（(]\\d+[）)]|\\d+\\s+(?=\\[?\\d+:))\\s*");
    private static final Pattern START = Pattern.compile("^\\s*(?:\\[([^]]+)\\]|(" + TIME + ")(?![\\d:.]))");
    private static final Pattern RANGE = Pattern.compile("^\\s*[-–—－~～至]\\s*(?:\\[([^]]+)\\]|(" + TIME + ")(?![\\d:.A-Za-z]))");
    private static final Pattern BAD_RANGE = Pattern.compile("^\\s*[-–—－~～至]\\s*((?:-?\\d|[A-Za-z]+:)[^\\s|｜,，;；：]*)");
    private static final Pattern SEPARATOR = Pattern.compile("^\\s*(?:[-–—－|｜:：,，;；]\\s*)?");

    record Row(String id, int line, String title, String artist, String start, String end, boolean selected) { }
    record Validation(Track track, String message) { }

    static List<Row> parse(String text, Track source) {
        Track original = original(source);
        List<Row> rows = new ArrayList<>();
        String[] lines = text == null ? new String[0] : text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String input = lines[index].strip();
            if (input.isEmpty()) continue;
            if (rows.size() == MAX_ROWS) throw new IllegalArgumentException("一次最多整理 200 条曲目");
            input = NUMBER.matcher(input).replaceFirst("");
            String start = "", end = "";
            var beginning = START.matcher(input);
            if (beginning.find()) {
                start = timeGroup(beginning.group(1), beginning.group(2));
                input = input.substring(beginning.end());
                var range = RANGE.matcher(input);
                if (range.find()) {
                    end = timeGroup(range.group(1), range.group(2));
                    input = input.substring(range.end());
                } else {
                    var malformed = BAD_RANGE.matcher(input);
                    if (malformed.find()) {
                        end = malformed.group(1);
                        input = input.substring(malformed.end());
                    }
                }
                input = SEPARATOR.matcher(input).replaceFirst("");
            }
            rows.add(new Row("segment:" + UUID.randomUUID(), index + 1, input.strip(),
                    original.artist(), start, end, true));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("请粘贴带时间戳和歌名的曲目表");
        List<Row> completed = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            String end = row.end();
            if (end.isBlank()) {
                Long inferred = inferredEnd(original, rows, index);
                if (inferred != null) end = LyricTiming.formatStart(inferred);
            }
            completed.add(new Row(row.id(), row.line(), row.title(), row.artist(), row.start(), end, row.selected()));
        }
        return List.copyOf(completed);
    }

    static List<Validation> validate(Track source, List<Row> rows) {
        Track original = original(source);
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("请选择要保存的曲目");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("一次最多整理 200 条曲目");
        List<Validation> result = new ArrayList<>();
        long[] starts = new long[rows.size()];
        long[] ends = new long[rows.size()];
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            try {
                if (row == null) throw new IllegalArgumentException("曲目行无效");
                validateId(row.id());
                long start = parseTime(row.start(), "起点");
                long end;
                if (row.end() == null || row.end().isBlank()) {
                    Long inferred = inferredEnd(original, rows, index);
                    if (inferred == null) throw new IllegalArgumentException("终点未知，请填写结束时间");
                    end = inferred;
                } else end = parseTime(row.end(), "终点");
                Track generated = Track.segment(original, row.title(), row.artist(), start, end);
                Track track = new Track(row.id(), generated.title(), generated.artist(), generated.durationSeconds(),
                        generated.source(), generated.mediaUri(), generated.bvid(), generated.page(), generated.coverUri(),
                        generated.partCount(), generated.viewCount(), generated.sourceTrackId(),
                        generated.segmentStartMillis(), generated.segmentEndMillis(), generated.sourceDurationSeconds());
                starts[index] = start;
                ends[index] = end;
                result.add(new Validation(row.selected() ? track : null, ""));
            } catch (IllegalArgumentException error) {
                result.add(new Validation(null, error.getMessage()));
            }
        }
        int previous = -1;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row == null || !row.selected() || ends[index] <= starts[index]) continue;
            if (previous >= 0 && starts[index] <= starts[previous]) {
                invalidate(result, index, starts[index] == starts[previous] ? "起点与前一选中曲目相同" : "时间顺序倒置，请调整曲目顺序");
            }
            for (int other = 0; other < index; other++) {
                Row earlier = rows.get(other);
                if (earlier == null || !earlier.selected() || ends[other] <= starts[other]) continue;
                if (starts[index] == starts[other] && ends[index] == ends[other]) {
                    invalidate(result, other, "片段范围重复，请取消重复条目");
                    invalidate(result, index, "片段范围重复，请取消重复条目");
                } else if (starts[index] < ends[other] && starts[other] < ends[index]) {
                    invalidate(result, other, "选中的片段时间范围重叠");
                    invalidate(result, index, "选中的片段时间范围重叠");
                }
            }
            previous = index;
        }
        return List.copyOf(result);
    }

    private static void invalidate(List<Validation> result, int index, String message) {
        result.set(index, new Validation(null, message));
    }

    private static Long inferredEnd(Track source, List<Row> rows, int index) {
        Row row = rows.get(index);
        if (row == null || row.title() == null || row.title().isBlank()) return null;
        try {
            long start = parseTime(row.start(), "起点");
            if (index + 1 < rows.size()) {
                Row following = rows.get(index + 1);
                if (following == null || following.title() == null || following.title().isBlank()) return null;
                long next = parseTime(following.start(), "起点");
                if (source.durationSeconds() > 0 && next >= source.durationSeconds() * 1000L) return null;
                if (following.end() != null && !following.end().isBlank()) {
                    long end = parseTime(following.end(), "终点");
                    if (end <= next || (source.durationSeconds() > 0 && end > source.durationSeconds() * 1000L))
                        return null;
                }
                return next > start ? next : null;
            }
            long tail = source.durationSeconds() * 1000L;
            return tail > start ? tail : null;
        } catch (IllegalArgumentException error) { return null; }
    }

    private static long parseTime(String text, String name) {
        try { return LyricTiming.parseStart(text); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(name + "格式无效，请使用 分:秒 或 时:分:秒"); }
    }

    private static void validateId(String id) {
        try {
            if (id == null || !id.equals("segment:" + UUID.fromString(id.substring("segment:".length()))))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            throw new IllegalArgumentException("片段身份无效，请重新解析曲目表");
        }
    }

    private static Track original(Track source) {
        if (source == null) throw new IllegalArgumentException("请选择原音源");
        return source.audioSource();
    }

    private static String timeGroup(String bracket, String plain) { return bracket == null ? plain : bracket.strip(); }
    private SegmentBatchDraft() { }
}
