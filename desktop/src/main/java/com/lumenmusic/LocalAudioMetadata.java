package com.lumenmusic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Reads only bounded metadata regions; never rewrites the user's audio file. */
final class LocalAudioMetadata {
    static final int MAX_TAG_BYTES = 16 * 1024 * 1024;
    static final int MAX_PICTURE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LYRIC_BYTES = 2 * 1024 * 1024;

    record Metadata(String title, String artist, URI coverUri, String lyrics, int durationSeconds) { }

    static Metadata read(Path audio, Path dataDirectory) throws IOException {
        Values values = new Values();
        try (RandomAccessFile input = new RandomAccessFile(audio.toFile(), "r")) {
            byte[] magic = read(input, 0, (int) Math.min(12, input.length()));
            if (starts(magic, "ID3")) {
                id3(input, 0, input.length(), values);
                id3v1(input, values);
            } else if (starts(magic, "fLaC")) {
                flac(input, values);
            } else if (starts(magic, "OggS")) {
                ogg(input, values);
            } else if (starts(magic, "RIFF") && ascii(magic, 8, 4).equals("WAVE")) {
                riff(input, values, false);
            } else if (starts(magic, "FORM") && (ascii(magic, 8, 4).equals("AIFF")
                    || ascii(magic, 8, 4).equals("AIFC"))) {
                riff(input, values, true);
            } else if (magic.length >= 8 && ascii(magic, 4, 4).equals("ftyp")) {
                mp4(input, 0, input.length(), 0, new Budget(), values);
            } else {
                id3v1(input, values);
            }
        }
        URI cover = null;
        if (dataDirectory != null) {
            if (values.picture != null) {
                String extension = pictureExtension(values.picture);
                if (extension != null) {
                    try { cover = persist(dataDirectory.resolve("local-artwork"), values.picture, extension).toUri(); }
                    catch (IOException ignored) { /* A read-only cache must not prevent importing audio. */ }
                }
            }
            if (!values.lyrics.isBlank()) {
                try { persist(dataDirectory.resolve("local-lyrics"), values.lyrics.getBytes(StandardCharsets.UTF_8), "txt"); }
                catch (IOException ignored) { }
            }
        }
        return new Metadata(values.title, values.artist, cover, values.lyrics, values.durationSeconds);
    }

    private static final class Values {
        String title = "", artist = "", lyrics = "";
        byte[] picture;
        boolean frontPicture;
        int durationSeconds;
        void duration(long units, long unitsPerSecond) {
            if (units > 0 && unitsPerSecond > 0 && units / unitsPerSecond < Integer.MAX_VALUE)
                durationSeconds = (int) ((units + unitsPerSecond - 1) / unitsPerSecond);
        }
        void text(String key, String text) {
            text = clean(text);
            if (text.isEmpty()) return;
            switch (key) {
                case "TITLE" -> { if (title.isEmpty()) title = text; }
                case "ARTIST" -> { if (artist.isEmpty()) artist = text; }
                case "LYRICS" -> {
                    if (text.length() <= MAX_LYRIC_BYTES && (lyrics.isEmpty()
                            || (Lyrics.parse(lyrics).isEmpty() && !Lyrics.parse(text).isEmpty()))) lyrics = text;
                }
                default -> { }
            }
        }
        void picture(byte[] bytes, boolean front) {
            if (bytes.length > 0 && bytes.length <= MAX_PICTURE_BYTES && pictureExtension(bytes) != null
                    && (picture == null || (front && !frontPicture))) {
                picture = bytes;
                frontPicture = front;
            }
        }
    }

    private static void id3(RandomAccessFile input, long start, long end, Values values) throws IOException {
        if (end - start < 10) return;
        byte[] header = read(input, start, 10);
        int version = header[3] & 255;
        if (!starts(header, "ID3") || (version != 3 && version != 4)) return;
        int size = syncSafe(header, 6);
        if (size < 0 || size > MAX_TAG_BYTES || size > end - start - 10) return;
        // Reserved flags and v2.2 compression are not guessed at.
        if ((header[5] & (version == 3 ? 31 : 15)) != 0) return;
        byte[] tag = read(input, start + 10, size);
        boolean unsynchronized = (header[5] & 128) != 0;
        if (unsynchronized && version == 3) tag = deUnsync(tag);
        int at = 0;
        if ((header[5] & 64) != 0) {
            if (tag.length < 4) return;
            int extended = version == 4 ? syncSafe(tag, 0) : int32(tag, 0, false);
            long next = version == 4 ? extended : extended + 4L;
            if (extended < 0 || next < 4 || next > tag.length) return;
            at = (int) next;
        }
        while (at + 10 <= tag.length) {
            String name = ascii(tag, at, 4);
            if (!name.matches("[A-Z0-9]{4}")) break;
            int length = version == 4 ? syncSafe(tag, at + 4) : int32(tag, at + 4, false);
            int flags = tag[at + 9] & 255;
            at += 10;
            if (length < 0 || length > tag.length - at) break;
            // Compression/encryption/grouping need additional decoding; skip safely.
            int unsupported = version == 4 ? 0xfc : 0xff;
            if ((flags & unsupported) == 0) {
                int bodyStart = at;
                if (version == 4 && (flags & 1) != 0) bodyStart += 4; // data length indicator
                if (bodyStart <= at + length) {
                    byte[] body = Arrays.copyOfRange(tag, bodyStart, at + length);
                    if (version == 4 && (unsynchronized || (flags & 2) != 0)) body = deUnsync(body);
                    id3Frame(name, body, values);
                }
            }
            at += length;
        }
    }

    private static void id3Frame(String name, byte[] body, Values values) {
        if (body.length == 0) return;
        int encoding = body[0] & 255;
        if (encoding > 3) return;
        switch (name) {
            case "TIT2" -> values.text("TITLE", decode(body, 1, body.length, encoding));
            case "TPE1" -> values.text("ARTIST", decode(body, 1, body.length, encoding));
            case "TLEN" -> {
                try { values.duration(Long.parseLong(clean(decode(body, 1, body.length, encoding))), 1000); }
                catch (NumberFormatException ignored) { }
            }
            case "USLT" -> {
                if (body.length < 4) return;
                int start = afterTerminator(body, 4, encoding);
                if (start >= 0) values.text("LYRICS", decode(body, start, body.length, encoding));
            }
            case "SYLT" -> {
                if (body.length < 6 || body[4] != 2 || body[5] != 1) return;
                int at = afterTerminator(body, 6, encoding);
                if (at < 0) return;
                StringBuilder lrc = new StringBuilder();
                while (at < body.length) {
                    int textEnd = terminator(body, at, encoding);
                    if (textEnd < 0) break;
                    String text = decode(body, at, textEnd, encoding);
                    at = textEnd + (encoding == 1 || encoding == 2 ? 2 : 1);
                    if (body.length - at < 4) break;
                    long millis = Integer.toUnsignedLong(int32(body, at, false));
                    at += 4;
                    if (millis <= 5_999_999 && !text.isBlank())
                        lrc.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]%s\n",
                                millis / 60_000, millis / 1000 % 60, millis % 1000, text.strip()));
                }
                values.text("LYRICS", lrc.toString());
            }
            case "APIC" -> {
                int mimeEnd = terminator(body, 1, 0);
                if (mimeEnd < 0 || mimeEnd + 1 >= body.length) return;
                boolean front = body[mimeEnd + 1] == 3;
                int imageStart = afterTerminator(body, mimeEnd + 2, encoding);
                if (imageStart >= 0 && body.length - imageStart <= MAX_PICTURE_BYTES)
                    values.picture(Arrays.copyOfRange(body, imageStart, body.length), front);
            }
            default -> { }
        }
    }

    private static void id3v1(RandomAccessFile input, Values values) throws IOException {
        if (input.length() < 128) return;
        byte[] tag = read(input, input.length() - 128, 128);
        if (starts(tag, "TAG")) {
            values.text("TITLE", decode(tag, 3, 33, 0));
            values.text("ARTIST", decode(tag, 33, 63, 0));
        }
    }

    private static void flac(RandomAccessFile input, Values values) throws IOException {
        long at = 4;
        int consumed = 0;
        for (int blocks = 0; blocks < 256 && at + 4 <= input.length(); blocks++) {
            byte[] header = read(input, at, 4);
            int length = (header[1] & 255) << 16 | (header[2] & 255) << 8 | header[3] & 255;
            int type = header[0] & 127;
            at += 4;
            if (length > input.length() - at) return;
            if (type == 0 && length == 34) {
                long packed = ByteBuffer.wrap(read(input, at + 10, 8)).getLong();
                values.duration(packed & 0xfffffffffL, packed >>> 44);
            } else if (type == 4 || type == 6) {
                if (length > MAX_TAG_BYTES - consumed) return;
                consumed += length;
                byte[] block = read(input, at, length);
                if (type == 4) vorbis(block, 0, values);
                else flacPicture(block, values);
            }
            at += length;
            if ((header[0] & 128) != 0) return;
        }
    }

    private static void vorbis(byte[] bytes, int start, Values values) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.position(start);
        if (!skipLength(b)) return;
        if (b.remaining() < 4) return;
        int count = b.getInt();
        if (count < 0 || count > 100_000 || count > b.remaining() / 4) return;
        for (int i = 0; i < count; i++) {
            if (b.remaining() < 4) return;
            int length = b.getInt();
            if (length < 0 || length > b.remaining()) return;
            String entry = new String(bytes, b.position(), length, StandardCharsets.UTF_8);
            b.position(b.position() + length);
            int separator = entry.indexOf('=');
            if (separator <= 0) continue;
            String key = entry.substring(0, separator).toUpperCase(Locale.ROOT);
            String text = entry.substring(separator + 1);
            switch (key) {
                case "TITLE", "ARTIST" -> values.text(key, text);
                case "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS" -> values.text("LYRICS", text);
                case "METADATA_BLOCK_PICTURE" -> {
                    if (text.length() <= (MAX_PICTURE_BYTES + 4096L) * 4 / 3 + 4) {
                        try { flacPicture(java.util.Base64.getDecoder().decode(text), values); }
                        catch (IllegalArgumentException ignored) { }
                    }
                }
                default -> { }
            }
        }
    }

    private static boolean skipLength(ByteBuffer b) {
        if (b.remaining() < 4) return false;
        int length = b.getInt();
        if (length < 0 || length > b.remaining()) return false;
        b.position(b.position() + length);
        return true;
    }

    private static void flacPicture(byte[] bytes, Values values) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.remaining() < 4) return;
        int type = b.getInt();
        if (!skipLength(b) || !skipLength(b) || b.remaining() < 20) return;
        b.position(b.position() + 16); // width, height, depth, palette size
        int length = b.getInt();
        if (length <= 0 || length > MAX_PICTURE_BYTES || length > b.remaining()) return;
        values.picture(Arrays.copyOfRange(bytes, b.position(), b.position() + length), type == 3);
    }

    private static void ogg(RandomAccessFile input, Values values) throws IOException {
        long at = 0;
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        int serial = 0;
        boolean haveSerial = false;
        for (int pages = 0; pages < 1024 && at + 27 <= input.length() && at < MAX_TAG_BYTES * 2L; pages++) {
            byte[] header = read(input, at, 27);
            if (!starts(header, "OggS") || header[4] != 0) return;
            int pageSerial = int32(header, 14, true);
            if (!haveSerial) { serial = pageSerial; haveSerial = true; }
            int segments = header[26] & 255;
            if (at + 27 + segments > input.length()) return;
            byte[] laces = read(input, at + 27, segments);
            long payload = at + 27 + segments;
            int total = 0;
            for (byte lace : laces) total += lace & 255;
            if (total > input.length() - payload) return;
            if (serial == pageSerial) {
                for (byte lace : laces) {
                    int size = lace & 255;
                    if (size > MAX_TAG_BYTES - packet.size()) return;
                    packet.write(read(input, payload, size));
                    payload += size;
                    if (size < 255) {
                        byte[] content = packet.toByteArray();
                        if (content.length >= 7 && content[0] == 3 && ascii(content, 1, 6).equals("vorbis")) {
                            vorbis(content, 7, values);
                            return;
                        }
                        if (starts(content, "OpusTags")) {
                            vorbis(content, 8, values);
                            return;
                        }
                        // Only identification followed by comments are metadata packets.
                        if (!(content.length >= 7 && content[0] == 1 && ascii(content, 1, 6).equals("vorbis"))
                                && !starts(content, "OpusHead")) return;
                        packet.reset();
                    }
                }
            }
            at += 27L + segments + total;
        }
    }

    private static final class Budget { int bytes; int atoms; }

    private static void mp4(RandomAccessFile input, long start, long end, int depth,
                            Budget budget, Values values) throws IOException {
        if (depth > 10) return;
        for (long at = start; at + 8 <= end;) {
            if (++budget.atoms > 10_000) return;
            byte[] header = read(input, at, 8);
            long length = Integer.toUnsignedLong(int32(header, 0, false));
            String type = ascii(header, 4, 4);
            int headerSize = 8;
            if (length == 1) {
                if (end - at < 16) return;
                length = ByteBuffer.wrap(read(input, at + 8, 8)).getLong();
                headerSize = 16;
            } else if (length == 0) length = end - at;
            if (length < headerSize || length > end - at) return;
            long content = at + headerSize, atomEnd = at + length;
            switch (type) {
                case "moov", "udta", "ilst" -> mp4(input, content, atomEnd, depth + 1, budget, values);
                case "meta" -> { if (atomEnd - content >= 4) mp4(input, content + 4, atomEnd, depth + 1, budget, values); }
                case "mvhd" -> {
                    if (atomEnd - content >= 20) {
                        byte[] movie = read(input, content, (int) Math.min(32, atomEnd - content));
                        int version = movie[0] & 255;
                        if (version == 0) values.duration(Integer.toUnsignedLong(int32(movie, 16, false)),
                                Integer.toUnsignedLong(int32(movie, 12, false)));
                        else if (version == 1 && movie.length >= 32) values.duration(ByteBuffer.wrap(movie, 24, 8).getLong(),
                                Integer.toUnsignedLong(int32(movie, 20, false)));
                    }
                }
                case "©nam", "©ART", "aART", "©lyr", "covr" -> mp4Item(input, content, atomEnd, type, budget, values);
                default -> { }
            }
            at = atomEnd;
        }
    }

    private static void mp4Item(RandomAccessFile input, long start, long end, String type,
                                Budget budget, Values values) throws IOException {
        for (long at = start; at + 16 <= end;) {
            if (++budget.atoms > 10_000) return;
            byte[] header = read(input, at, 16);
            long size = Integer.toUnsignedLong(int32(header, 0, false));
            if (size < 16 || size > end - at) return;
            if (ascii(header, 4, 4).equals("data")) {
                long length = size - 16;
                if (length <= MAX_TAG_BYTES - budget.bytes && length <= (type.equals("covr") ? MAX_PICTURE_BYTES : MAX_LYRIC_BYTES)) {
                    budget.bytes += (int) length;
                    byte[] data = read(input, at + 16, (int) length);
                    int dataType = int32(header, 8, false) & 0xffffff;
                    switch (type) {
                        case "covr" -> values.picture(data, true);
                        case "©nam", "©ART", "aART", "©lyr" -> {
                            if (dataType == 1 || dataType == 2) {
                                String key = type.equals("©nam") ? "TITLE" : type.equals("©lyr") ? "LYRICS" : "ARTIST";
                                values.text(key, new String(data, dataType == 2 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8));
                            }
                        }
                        default -> { }
                    }
                }
            }
            at += size;
        }
    }

    private static void riff(RandomAccessFile input, Values values, boolean aiff) throws IOException {
        byte[] header = read(input, 0, 12);
        long end = Math.min(input.length(), Integer.toUnsignedLong(int32(header, 4, !aiff)) + 8);
        int consumed = 0;
        long byteRate = 0, audioBytes = 0;
        for (long at = 12, chunks = 0; at + 8 <= end && chunks < 10_000; chunks++) {
            byte[] chunk = read(input, at, 8);
            String type = ascii(chunk, 0, 4);
            long length = Integer.toUnsignedLong(int32(chunk, 4, !aiff));
            at += 8;
            if (length > end - at) return;
            if (!aiff && type.equals("fmt ") && length >= 16) {
                byte[] format = read(input, at, 16);
                byteRate = Integer.toUnsignedLong(int32(format, 8, true));
            } else if (!aiff && type.equals("data")) audioBytes += length;
            else if (type.equalsIgnoreCase("id3 ") && length <= MAX_TAG_BYTES - consumed) {
                consumed += (int) length;
                id3(input, at, at + length, values);
            }
            else if (!aiff && type.equals("LIST") && length >= 4 && length <= MAX_TAG_BYTES - consumed) {
                consumed += (int) length;
                byte[] info = read(input, at, (int) length);
                if (starts(info, "INFO")) {
                    for (int p = 4; p + 8 <= info.length;) {
                        String key = ascii(info, p, 4);
                        long size = Integer.toUnsignedLong(int32(info, p + 4, true));
                        p += 8;
                        if (size > info.length - p) break;
                        if (key.equals("INAM") || key.equals("IART"))
                            values.text(key.equals("INAM") ? "TITLE" : "ARTIST", decode(info, p, p + (int) size, 0));
                        p += (int) size + (int) (size & 1);
                    }
                }
            }
            at += length + (length & 1);
        }
        if (!aiff) values.duration(audioBytes, byteRate);
    }

    private static byte[] read(RandomAccessFile input, long at, int length) throws IOException {
        if (length < 0 || length > MAX_TAG_BYTES || at < 0 || length > input.length() - at)
            throw new IOException("音频标签范围无效");
        byte[] result = new byte[length];
        input.seek(at);
        input.readFully(result);
        return result;
    }

    private static int syncSafe(byte[] bytes, int at) {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            int value = bytes[at + i] & 255;
            if (value > 127) return -1;
            result = result << 7 | value;
        }
        return result;
    }

    private static int int32(byte[] bytes, int at, boolean littleEndian) {
        return ByteBuffer.wrap(bytes, at, 4).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getInt();
    }

    private static byte[] deUnsync(byte[] bytes) {
        byte[] decoded = new byte[bytes.length];
        int length = 0;
        for (int i = 0; i < bytes.length; i++) {
            decoded[length++] = bytes[i];
            if (bytes[i] == (byte) 255 && i + 1 < bytes.length && bytes[i + 1] == 0) i++;
        }
        return Arrays.copyOf(decoded, length);
    }

    private static int terminator(byte[] bytes, int start, int encoding) {
        int step = encoding == 1 || encoding == 2 ? 2 : 1;
        for (int at = start; at + step <= bytes.length; at += step)
            if (bytes[at] == 0 && (step == 1 || bytes[at + 1] == 0)) return at;
        return -1;
    }

    private static int afterTerminator(byte[] bytes, int start, int encoding) {
        int end = terminator(bytes, start, encoding);
        return end < 0 ? -1 : end + (encoding == 1 || encoding == 2 ? 2 : 1);
    }

    private static String decode(byte[] bytes, int start, int end, int encoding) {
        if (start < 0 || end < start || end > bytes.length) return "";
        Charset charset = switch (encoding) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        return new String(bytes, start, end - start, charset);
    }

    private static String clean(String text) {
        int terminator = text.indexOf('\0');
        if (terminator >= 0) text = text.substring(0, terminator);
        return text.replace("\ufeff", "").strip();
    }

    private static String ascii(byte[] bytes, int at, int length) {
        return at < 0 || at + length > bytes.length ? "" : new String(bytes, at, length, StandardCharsets.ISO_8859_1);
    }

    private static boolean starts(byte[] bytes, String text) { return ascii(bytes, 0, text.length()).equals(text); }

    private static String pictureExtension(byte[] bytes) {
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "png";
        if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "jpg";
        if (starts(bytes, "GIF87a") || starts(bytes, "GIF89a")) return "gif";
        if (starts(bytes, "RIFF") && ascii(bytes, 8, 4).equals("WEBP")) return "webp";
        return null;
    }

    private static Path persist(Path directory, byte[] bytes, String extension) throws IOException {
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Files.createDirectories(directory);
        Path destination = directory.resolve(hash + "." + extension);
        if (Files.isRegularFile(destination)) return destination;
        Path temporary = Files.createTempFile(directory, ".metadata-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }
}
