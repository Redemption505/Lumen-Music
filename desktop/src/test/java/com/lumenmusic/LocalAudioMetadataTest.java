package com.lumenmusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalAudioMetadataTest {
    @TempDir Path directory;
    // A complete 1x1 PNG, rather than bytes with only an image signature.
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=");

    @Test void id3v23UnicodeLyricsAndPicturePersistWithoutChangingAudio() throws Exception {
        byte[] original = id3(3, frame(3, "TIT2", text("内嵌标题")), frame(3, "TPE1", text("歌手")),
                frame(3, "TLEN", text("120001")), frame(3, "USLT", join(new byte[]{3}, latin("eng"), new byte[]{0}, utf8("第一行\n第二行"))),
                frame(3, "APIC", join(new byte[]{3}, latin("image/png"), new byte[]{0, 3, 0}, PNG)));
        Path audio = write("filename.mp3", original);
        LocalAudioMetadata.Metadata result = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("内嵌标题", result.title());
        assertEquals("歌手", result.artist());
        assertEquals("第一行\n第二行", result.lyrics());
        assertEquals(121, result.durationSeconds());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(result.coverUri())));
        assertArrayEquals(original, Files.readAllBytes(audio));
        assertEquals(result.coverUri(), LocalAudioMetadata.read(audio, directory.resolve("data")).coverUri());
        try (var paths = Files.list(directory.resolve("data/local-artwork"))) { assertEquals(1, paths.count()); }
        try (var paths = Files.list(directory.resolve("data/local-lyrics"))) { assertEquals(1, paths.count()); }
    }

    @Test void utf16DescriptionTerminatorsAreAlignedAndLrcIsPreserved() throws Exception {
        String lrc = "[offset:100]\n[00:01.250]同步歌词";
        byte[] description = "描述".getBytes(StandardCharsets.UTF_16);
        byte[] lyric = lrc.getBytes(StandardCharsets.UTF_16);
        Path audio = write("utf16.mp3", id3(3, frame(3, "USLT", join(new byte[]{1}, latin("zho"),
                description, new byte[]{0, 0}, lyric))));
        assertEquals(lrc, LocalAudioMetadata.read(audio, directory.resolve("data")).lyrics());
    }

    @Test void id3v24SyltProducesTimedLinesAndSkipsCompressedFrames() throws Exception {
        byte[] sylt = join(new byte[]{3}, latin("eng"), new byte[]{2, 1, 0}, utf8("First"), new byte[]{0}, be(1500),
                utf8("Second"), new byte[]{0}, be(30250));
        byte[] compressed = frame(4, "TIT2", text("Wrong"));
        compressed[9] = 8;
        Path audio = write("sync.mp3", id3(4, compressed, frame(4, "TIT2", text("Correct")),
                frame(4, "USLT", join(new byte[]{3}, latin("eng"), new byte[]{0}, utf8("plain fallback"))),
                frame(4, "SYLT", sylt)));
        var metadata = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("Correct", metadata.title());
        assertEquals(List.of(new Lyrics.Line(1500, "First"), new Lyrics.Line(30250, "Second")), Lyrics.parse(metadata.lyrics()));
    }

    @Test void id3v24UnsynchronizationRestoresEmbeddedJpeg() throws Exception {
        byte[] jpeg = new byte[]{(byte)255, (byte)216, (byte)255, (byte)224, 1, 2};
        byte[] unsynced = new byte[]{(byte)255, (byte)216, (byte)255, 0, (byte)224, 1, 2};
        byte[] picture = frame(4, "APIC", join(new byte[]{0}, latin("image/jpeg"), new byte[]{0, 3, 0}, unsynced));
        picture[9] = 2;
        Path audio = write("unsync.mp3", id3(4, picture));
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(LocalAudioMetadata.read(audio, directory.resolve("data")).coverUri())));
    }

    @Test void flacReadsCommentsPictureAndStreamDuration() throws Exception {
        byte[] info = new byte[34];
        long packed = 44_100L << 44 | 2L << 41 | 15L << 36 | 44_100L * 12;
        ByteBuffer.wrap(info, 10, 8).putLong(packed);
        byte[] comments = comments("TITLE=FLAC title", "ARTIST=FLAC artist", "LYRICS=[00:03.000]FLAC line");
        Path audio = write("song.flac", join(latin("fLaC"), flacBlock(0, false, info),
                flacBlock(4, false, comments), flacBlock(6, true, flacPicture(PNG))));
        var metadata = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("FLAC title", metadata.title());
        assertEquals("FLAC artist", metadata.artist());
        assertEquals(12, metadata.durationSeconds());
        assertEquals("[00:03.000]FLAC line", metadata.lyrics());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(metadata.coverUri())));
    }

    @Test void flacVorbisBase64PictureAndPlainLyricsAreRecognized() throws Exception {
        Path audio = write("comments.flac", join(latin("fLaC"), flacBlock(4, true,
                comments("UNSYNCEDLYRICS=First\nSecond", "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(flacPicture(PNG))))));
        var metadata = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("First\nSecond", metadata.lyrics());
        assertNotNull(metadata.coverUri());
    }

    @Test void m4aSkipsLargeMediaAtomAndReadsNestedItunesMetadata() throws Exception {
        byte[] movie = new byte[20];
        ByteBuffer.wrap(movie, 12, 8).putInt(1000).putInt(123456);
        byte[] metadata = atom("moov", join(atom("mvhd", movie), atom("udta", atom("meta", join(new byte[4], atom("ilst", join(
                item("©nam", utf8("M4A title"), 1), item("©ART", utf8("M4A artist"), 1),
                item("©lyr", utf8("[00:02.000]M4A lyric"), 1), item("covr", PNG, 14))))))));
        Path audio = directory.resolve("large.m4a");
        // Sparse media region ensures metadata reads do not allocate/read the whole audio file.
        try (RandomAccessFile file = new RandomAccessFile(audio.toFile(), "rw")) {
            file.write(atom("ftyp", latin("M4A ")));
            file.write(be(64 * 1024 * 1024));
            file.write(latin("mdat"));
            file.seek(12L + 64 * 1024 * 1024);
            file.write(metadata);
        }
        var result = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("M4A title", result.title());
        assertEquals("M4A artist", result.artist());
        assertEquals(124, result.durationSeconds());
        assertEquals("[00:02.000]M4A lyric", result.lyrics());
        assertNotNull(result.coverUri());
    }

    @Test void wavPcmAndEmbeddedId3ReadWithoutDependingOnPlaybackLibraries() throws Exception {
        byte[] fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).putShort((short)1)
                .putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).array();
        byte[] tags = id3(3, frame(3, "TIT2", text("WAV title")), frame(3, "APIC",
                join(new byte[]{3}, latin("image/png"), new byte[]{0, 3, 0}, PNG)));
        byte[] wave = join(latin("WAVE"), riffChunk("fmt ", fmt), riffChunk("data", new byte[16000]), riffChunk("id3 ", tags));
        Path audio = write("pcm.wav", join(latin("RIFF"), le(wave.length), wave));
        var result = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("WAV title", result.title());
        assertEquals(1, result.durationSeconds());
        assertNotNull(result.coverUri());
        try (var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(audio.toFile())) {
            assertEquals(8000, stream.getFrameLength()); // the fixture also contains valid playable PCM
        }
    }

    @Test void oggVorbisAndOpusCommentPacketsAreRead() throws Exception {
        byte[] comment = comments("TITLE=Ogg title", "LYRICS=Plain Ogg lyric", "METADATA_BLOCK_PICTURE="
                + Base64.getEncoder().encodeToString(flacPicture(PNG)));
        for (boolean opus : List.of(false, true)) {
            byte[] id = opus ? latin("OpusHead") : join(new byte[]{1}, latin("vorbis"));
            byte[] tag = opus ? join(latin("OpusTags"), comment) : join(new byte[]{3}, latin("vorbis"), comment, new byte[]{1});
            Path audio = write(opus ? "test.opus" : "test.ogg", join(oggPage(id, 0), oggPage(tag, 1)));
            var result = LocalAudioMetadata.read(audio, directory.resolve("data"));
            assertEquals("Ogg title", result.title());
            assertEquals("Plain Ogg lyric", result.lyrics());
            assertNotNull(result.coverUri());
        }
    }

    @Test void oversizedAndTruncatedMetadataDoNotFailOrAllocateItsAdvertisedSize() throws Exception {
        byte[] huge = join(latin("ID3"), new byte[]{4, 0, 0}, syncSafe(LocalAudioMetadata.MAX_TAG_BYTES + 1));
        byte[] truncated = id3(3, frame(3, "TIT2", text("title")));
        Path first = write("oversize.mp3", huge);
        Path second = write("truncated.mp3", java.util.Arrays.copyOf(truncated, 12));
        assertEquals("", LocalAudioMetadata.read(first, directory.resolve("data")).title());
        assertEquals("", LocalAudioMetadata.read(second, directory.resolve("data")).title());
        Path flac = write("bad.flac", join(latin("fLaC"), new byte[]{(byte)132, 0, 0, 8}, le(Integer.MAX_VALUE), le(1)));
        assertEquals("", LocalAudioMetadata.read(flac, directory.resolve("data")).title());
        Path mp4 = write("bad.m4a", join(atom("ftyp", latin("M4A ")), be(1), latin("moov"),
                ByteBuffer.allocate(8).putLong(Long.MAX_VALUE).array()));
        assertEquals("", LocalAudioMetadata.read(mp4, directory.resolve("data")).title());
    }

    @Test void metadataStillReturnsTextWhenCacheCannotBeWritten() throws Exception {
        Path cache = write("blocked-cache", new byte[]{0});
        Path audio = write("cache.mp3", id3(3, frame(3, "TIT2", text("Title")), frame(3, "USLT",
                join(new byte[]{3}, latin("eng"), new byte[]{0}, utf8("Lyric"))), frame(3, "APIC",
                join(new byte[]{3}, latin("image/png"), new byte[]{0, 3, 0}, PNG))));
        var result = LocalAudioMetadata.read(audio, cache);
        assertEquals("Title", result.title());
        assertEquals("Lyric", result.lyrics());
        assertNull(result.coverUri());
    }

    @Test void oversizedPictureDoesNotPreventTitleOrLyricsReading() throws Exception {
        byte[] picture = new byte[LocalAudioMetadata.MAX_PICTURE_BYTES + 1];
        System.arraycopy(PNG, 0, picture, 0, PNG.length);
        Path audio = write("large-cover.mp3", id3(3, frame(3, "APIC",
                join(new byte[]{3}, latin("image/png"), new byte[]{0, 3, 0}, picture)),
                frame(3, "TIT2", text("Title after picture"))));
        var result = LocalAudioMetadata.read(audio, directory.resolve("data"));
        assertEquals("Title after picture", result.title());
        assertNull(result.coverUri());
    }

    private Path write(String name, byte[] data) throws IOException { return Files.write(directory.resolve(name), data); }
    static byte[] text(String value) { return join(new byte[]{3}, utf8(value)); }
    static byte[] frame(int version, String name, byte[] body) { return join(latin(name), version == 4 ? syncSafe(body.length) : be(body.length), new byte[2], body); }
    static byte[] id3(int version, byte[]... frames) {
        byte[] body = join(frames);
        return join(latin("ID3"), new byte[]{(byte)version, 0, 0}, syncSafe(body.length), body);
    }
    private static byte[] flacBlock(int type, boolean last, byte[] body) {
        return join(new byte[]{(byte)(type | (last ? 128 : 0)), (byte)(body.length >>> 16), (byte)(body.length >>> 8), (byte)body.length}, body);
    }
    private static byte[] flacPicture(byte[] picture) {
        return join(be(3), be(9), latin("image/png"), be(0), be(1), be(1), be(8), be(0), be(picture.length), picture);
    }
    private static byte[] comments(String... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(le(0));
        output.writeBytes(le(values.length));
        for (String value : values) { byte[] bytes = utf8(value); output.writeBytes(le(bytes.length)); output.writeBytes(bytes); }
        return output.toByteArray();
    }
    private static byte[] item(String name, byte[] data, int type) { return atom(name, atom("data", join(be(type), be(0), data))); }
    private static byte[] atom(String name, byte[] data) { return join(be(data.length + 8), latin(name), data); }
    private static byte[] riffChunk(String name, byte[] data) { return join(latin(name), le(data.length), data, new byte[data.length & 1]); }
    private static byte[] oggPage(byte[] packet, int sequence) {
        byte[] header = new byte[27];
        System.arraycopy(latin("OggS"), 0, header, 0, 4);
        ByteBuffer.wrap(header, 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(123);
        ByteBuffer.wrap(header, 18, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence);
        int segments = packet.length / 255 + 1;
        header[26] = (byte)segments;
        byte[] laces = new byte[segments];
        java.util.Arrays.fill(laces, (byte)255);
        laces[segments - 1] = (byte)(packet.length % 255);
        return join(header, laces, packet);
    }
    private static byte[] syncSafe(int value) { return new byte[]{(byte)(value >>> 21 & 127), (byte)(value >>> 14 & 127), (byte)(value >>> 7 & 127), (byte)(value & 127)}; }
    private static byte[] be(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    private static byte[] le(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] latin(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    static byte[] join(byte[]... chunks) { ByteArrayOutputStream output = new ByteArrayOutputStream(); for (byte[] chunk : chunks) output.writeBytes(chunk); return output.toByteArray(); }
}
