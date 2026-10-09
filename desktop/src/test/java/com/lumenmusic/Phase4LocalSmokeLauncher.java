package com.lumenmusic;

import com.sun.jna.NativeLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Application;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.callback.AudioCallback;

/** Encodes synthetic PCM using the existing bundled VLC, then launches an isolated real app. */
public final class Phase4LocalSmokeLauncher {
    private Phase4LocalSmokeLauncher() { }

    public static void main(String[] args) throws Exception {
        Path root = profile();
        Files.createDirectories(root);
        for (String name : List.of("LUMEN_LIBRARY_PATH", "LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH"))
            check(!Files.exists(Path.of(required(name))), "Fresh profile required: " + name);
        String fixtureSource = System.getProperty("lumen.phase4.local.fixtureSource");
        Path first = fixtureSource == null ? encode(root, "phase4-local-first", 440) : reuse(root, fixtureSource, "phase4-local-first");
        Path second = fixtureSource == null ? encode(root, "phase4-local-second", 660) : reuse(root, fixtureSource, "phase4-local-second");
        if (List.of(args).contains("--fixtures-only")) return;
        Application.launch(Phase4LocalSmoke.class, first.toString(), second.toString());
    }

    private static Path reuse(Path root, String configured, String name) throws Exception {
        Path sourceRoot = Path.of(configured).toAbsolutePath().normalize();
        check(sourceRoot.startsWith(Path.of("build/phase4-core-b/local").toAbsolutePath().normalize()), "Reuse source must be a synthetic phase4 local profile");
        Path source = sourceRoot.resolve(name + ".flac"), destination = root.resolve(name + ".flac");
        check(!sourceRoot.equals(root) && !Files.exists(destination), "Reuse must copy into a fresh profile without changing original fixtures");
        byte[] bytes = Files.readAllBytes(source);
        check(bytes.length > 1000 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("fLaC"), "Reuse source is not FLAC");
        Files.copy(source, destination);
        System.setProperty("lumen.vlc.home", required("LUMEN_VLC_HOME"));
        NativeLibrary.addSearchPath("libvlc", required("LUMEN_VLC_HOME"));
        completeStreamInfo(destination);
        System.out.println("REUSED synthetic FLAC " + source + " -> " + destination);
        return destination;
    }

    static Path profile() {
        Path root = Path.of(required("LUMEN_LIBRARY_PATH")).toAbsolutePath().normalize().getParent();
        Path allowed = Path.of("build/phase4-core-b").toAbsolutePath().normalize();
        check(root != null && root.startsWith(allowed) && !root.equals(allowed), "Profile must be a fresh child of build/phase4-core-b");
        for (String name : List.of("LUMEN_PREFERENCES_PATH", "LUMEN_HISTORY_PATH", "LUMEN_AI_SETTINGS_PATH", "LOCALAPPDATA", "APPDATA"))
            check(Path.of(required(name)).toAbsolutePath().normalize().startsWith(root), name + " escapes isolated profile");
        check(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().startsWith(root), "java.io.tmpdir escapes profile");
        check(Path.of(System.getProperty("javafx.cachedir", "")).toAbsolutePath().normalize().startsWith(root), "javafx.cachedir escapes profile");
        for (String name : List.of("LUMEN_AI_API_KEY", "OPENAI_API_KEY"))
            check(System.getenv(name) == null || System.getenv(name).isBlank(), "Clear inherited AI keys for isolated smoke");
        return root;
    }

    private static Path encode(Path root, String name, int frequency) throws Exception {
        String vlc = required("LUMEN_VLC_HOME");
        check(Files.isRegularFile(Path.of(vlc, "libvlc.dll")) && Files.isDirectory(Path.of(vlc, "plugins")), "Existing VLC runtime required");
        Path wave = root.resolve(name + ".wav"), flac = root.resolve(name + ".flac");
        check(!Files.exists(wave) && !Files.exists(flac), "Refusing to overwrite an existing fixture");
        writeWave(wave, frequency);
        NativeLibrary.addSearchPath("libvlc", vlc);
        System.setProperty("lumen.vlc.home", vlc);
        MediaPlayerFactory factory = new MediaPlayerFactory("--no-video", "--no-metadata-network-access", "--quiet");
        MediaPlayer encoder = factory.mediaPlayers().newMediaPlayer();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        try {
            encoder.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void finished(MediaPlayer player) { done.countDown(); }
                @Override public void error(MediaPlayer player) { error.set("FLAC fixture encoding failed"); done.countDown(); }
            });
            String destination = flac.toString().replace('\\', '/');
            String sout = ":sout=#transcode{vcodec=none,acodec=flac,channels=1,samplerate=44100}"
                    + ":std{access=file,mux=raw,dst=\"" + destination + "\"}";
            check(encoder.media().play(wave.toUri().toString(), sout, ":no-sout-video"), "Unable to start native FLAC encoder");
            check(done.await(60, TimeUnit.SECONDS), "FLAC fixture encoding timed out");
            check(error.get() == null, String.valueOf(error.get()));
        } finally {
            encoder.controls().stop();
            encoder.release();
            factory.release();
        }
        byte[] bytes = Files.readAllBytes(flac);
        check(bytes.length > 1000 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("fLaC"), "Native encoder did not produce FLAC");
        completeStreamInfo(flac);
        System.out.println("FIXTURE FLAC " + flac + " bytes=" + bytes.length + " synthetic-tone=" + frequency + "Hz source-duration=24s");
        return flac;
    }

    /** VLC's streaming FLAC mux cannot seek back to fill STREAMINFO; derive that value from a real decode. */
    private static void completeStreamInfo(Path flac) throws Exception {
        MediaPlayerFactory factory = new MediaPlayerFactory("--no-video", "--no-metadata-network-access", "--quiet");
        MediaPlayer decoder = factory.mediaPlayers().newMediaPlayer();
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicLong decodedSamples = new AtomicLong();
        AtomicReference<String> error = new AtomicReference<>();
        try {
            // Count decoder frames directly. Another lossy-timing transcode is not a trustworthy frame counter.
            decoder.audio().callback("S16N", 44100, 1, new AudioCallback() {
                @Override public void play(MediaPlayer player, com.sun.jna.Pointer samples, int count, long pts) { decodedSamples.addAndGet(count); }
                @Override public void pause(MediaPlayer player, long pts) { }
                @Override public void resume(MediaPlayer player, long pts) { }
                @Override public void flush(MediaPlayer player, long pts) { }
                @Override public void drain(MediaPlayer player) { drained.countDown(); }
                @Override public void setVolume(float volume, boolean mute) { }
            });
            decoder.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void finished(MediaPlayer player) { done.countDown(); }
                @Override public void error(MediaPlayer player) { error.set("FLAC fixture PCM decode failed"); done.countDown(); }
            });
            check(decoder.media().play(flac.toUri().toString()), "Unable to start native PCM decode");
            check(done.await(60, TimeUnit.SECONDS), "Native PCM decode timed out");
            check(error.get() == null, String.valueOf(error.get()));
            check(drained.await(5, TimeUnit.SECONDS), "Native decoder did not drain all samples");
        } finally {
            decoder.controls().stop();
            decoder.release();
            factory.release();
        }
        long samples = decodedSamples.get();
        // The native transcode trims 50ms from this 24s source. Preserve its measured duration precisely.
        check(Math.abs(samples / 44100.0 - 24) <= .1, "Decoded synthetic FLAC is not approximately the 24s source: " + samples);
        byte[] bytes = Files.readAllBytes(flac);
        check(bytes.length > 42 && (bytes[4] & 0x7f) == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 34,
                "FLAC fixture must start with the standard 34-byte STREAMINFO block");
        int rate = (bytes[18] & 0xff) << 12 | (bytes[19] & 0xff) << 4 | (bytes[20] & 0xf0) >>> 4;
        int channels = ((bytes[20] & 0x0e) >>> 1) + 1;
        int bits = ((bytes[20] & 1) << 4 | (bytes[21] & 0xf0) >>> 4) + 1;
        check(rate == 44100 && channels == 1 && bits == 16, "Unexpected synthetic FLAC PCM format");
        long recordedSamples = bytes[21] & 0x0f;
        for (int index = 22; index <= 25; index++) recordedSamples = recordedSamples * 256 + (bytes[index] & 0xff);
        check(recordedSamples == 0 || recordedSamples == samples, "FLAC STREAMINFO contradicts native decoded samples");
        if (recordedSamples == 0) {
            bytes[21] = (byte) ((bytes[21] & 0xf0) | samples >>> 32);
            for (int index = 22; index <= 25; index++) bytes[index] = (byte) (samples >>> (8 * (25 - index)));
            Files.write(flac, bytes);
        }
        System.out.println("FLAC_VERIFIED nativeDecodedSamples=" + samples
                + " STREAMINFO_before=" + recordedSamples + " STREAMINFO_after=" + samples + " rate=" + rate + " duration=" + samples / (double) rate + "s");
    }

    private static void writeWave(Path file, int frequency) throws Exception {
        int rate = 44100, samples = rate * 24, bytes = samples * 2;
        ByteBuffer wave = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        wave.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        wave.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for (int sample = 0; sample < samples; sample++)
            wave.putShort((short) Math.round(32767 * .08 * Math.sin(2 * Math.PI * frequency * sample / rate)));
        Files.write(file, wave.array());
    }

    static String required(String name) {
        String value = System.getenv(name);
        check(value != null && !value.isBlank(), "Missing " + name);
        return value;
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
