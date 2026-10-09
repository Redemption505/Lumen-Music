package com.lumenmusic;

import com.sun.jna.NativeLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

/** Encodes the synthetic WAV with the existing bundled VLC AAC encoder. */
public final class ContinuousPlaybackSmokeLauncher {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getenv("LUMEN_LIBRARY_PATH")).toAbsolutePath().getParent();
        if (root == null || !root.toString().contains("continuous-playback-smoke"))
            throw new IllegalStateException("An isolated continuous playback profile is required");
        Path wave = root.resolve("continuous-fixture.wav");
        Path fixture = root.resolve("continuous-fixture.m4a");
        if (!Files.isRegularFile(wave) || Files.exists(fixture))
            throw new IllegalStateException("A fresh synthetic WAV is required");
        String vlcHome = System.getenv("LUMEN_VLC_HOME");
        if (vlcHome == null || !Files.isRegularFile(Path.of(vlcHome, "libvlc.dll")))
            throw new IllegalStateException("The existing bundled VLC encoder is required");
        NativeLibrary.addSearchPath("libvlc", vlcHome);
        MediaPlayerFactory factory = new MediaPlayerFactory("--no-video", "--quiet", "--no-metadata-network-access");
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
            if (!encoder.media().play(wave.toUri().toString(), sout, ":no-sout-video"))
                throw new IllegalStateException("Unable to open synthetic AAC encoder");
            if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("AAC encoding timed out");
            if (error.get() != null) throw new IllegalStateException(error.get());
        } finally {
            encoder.controls().stop(); encoder.release(); factory.release();
        }
        byte[] bytes = Files.readAllBytes(fixture);
        if (bytes.length < 1000 || bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p')
            throw new IllegalStateException("The encoder did not produce genuine MP4 audio");
        System.out.println("AAC fixture: " + fixture + " bytes=" + bytes.length);
        Application.launch(ContinuousPlaybackSmoke.class, args);
    }
}
