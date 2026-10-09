package com.lumenmusic;

import com.lumenmusic.audio.DemoAudioLibrary;
import com.sun.jna.NativeLibrary;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javafx.application.Application;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

/** Prepares genuine AAC/MP4 with the bundled VLC encoder before starting the real app smoke. */
public final class CachePlaybackSmokeLauncher {
    public static void main(String[] args) throws Exception {
        Path fixture = createFixture();
        Application.launch(CachePlaybackSmoke.class, fixture.toString());
    }

    static Path createFixture() throws Exception {
        String configured = System.getenv("LUMEN_LIBRARY_PATH");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("An isolated library path is required");
        Path root = Path.of(configured).toAbsolutePath().normalize().getParent();
        if (root == null || Files.exists(Path.of(configured))) throw new IllegalStateException("Refusing an existing library");
        Files.createDirectories(root);
        Path fixture = root.resolve("cache-fixture.m4a");
        if (Files.exists(fixture)) throw new IllegalStateException("Refusing an existing audio fixture");
        String vlcHome = System.getenv("LUMEN_VLC_HOME");
        if (vlcHome == null || !Files.isRegularFile(Path.of(vlcHome, "libvlc.dll")))
            throw new IllegalStateException("The bundled VLC runtime is required");
        Path extendedWave = root.resolve("cache-fixture-source.wav");
        if (Files.exists(extendedWave)) throw new IllegalStateException("Refusing an existing audio source fixture");
        try (DemoAudioLibrary demo = new DemoAudioLibrary();
                AudioInputStream original = AudioSystem.getAudioInputStream(Path.of(demo.resolve("demo-1")).toFile())) {
            byte[] once = original.readAllBytes();
            byte[] extended = new byte[once.length * 4];
            for (int repeat = 0; repeat < 4; repeat++)
                System.arraycopy(once, 0, extended, repeat * once.length, once.length);
            try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(extended),
                    original.getFormat(), original.getFrameLength() * 4)) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, extendedWave.toFile());
            }
        }
        NativeLibrary.addSearchPath("libvlc", vlcHome);
        MediaPlayerFactory factory = new MediaPlayerFactory("--no-video", "--no-metadata-network-access", "--quiet");
        MediaPlayer encoder = factory.mediaPlayers().newMediaPlayer();
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            encoder.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void finished(MediaPlayer player) { done.countDown(); }
                @Override public void error(MediaPlayer player) { error.set("VLC AAC fixture encoding failed"); done.countDown(); }
            });
            String destination = fixture.toString().replace('\\', '/');
            String sout = ":sout=#transcode{vcodec=none,acodec=mp4a,ab=128,channels=2,samplerate=44100}"
                    + ":std{access=file,mux=mp4,dst=\"" + destination + "\"}";
            if (!encoder.media().play(extendedWave.toUri().toString(), sout, ":no-sout-video"))
                throw new IllegalStateException("Unable to open AAC encoder fixture");
            if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("AAC fixture encoding timed out");
            if (error.get() != null) throw new IllegalStateException(error.get());
        } finally {
            encoder.controls().stop(); encoder.release(); factory.release();
        }
        byte[] bytes = Files.readAllBytes(fixture);
        if (bytes.length < 1000 || bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p')
            throw new IllegalStateException("VLC did not produce a valid MP4 fixture");
        System.out.println("AAC fixture: " + fixture + " (" + bytes.length + " bytes)");
        return fixture;
    }
}
