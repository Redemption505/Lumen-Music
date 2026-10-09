package com.lumenmusic;

import com.lumenmusic.audio.LoudnessAnalyzer;
import com.lumenmusic.audio.LoudnessMeter;
import com.lumenmusic.audio.VlcAudioPlayer;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import com.sun.jna.Pointer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.Equalizer;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;

/** Explicit native decoder acceptance; all fixtures and settings stay in the provided test directory. */
public final class LoudnessNativeProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        Path quiet = writeTone(directory.resolve("quiet.wav"), .05);
        Path loud = writeTone(directory.resolve("loud.wav"), .4);
        try (LoudnessAnalyzer analyzer = new LoudnessAnalyzer()) {
            long start = System.nanoTime();
            LoudnessMeter.Result q = analyzer.analyze(quiet.toUri()).get(20, TimeUnit.SECONDS);
            LoudnessMeter.Result l = analyzer.analyze(loud.toUri()).get(20, TimeUnit.SECONDS);
            double delta = l.integratedLufs() - q.integratedLufs();
            System.out.printf("Measured quiet=%s loud=%s delta=%.3f%n", q, l, delta);
            if (Math.abs(delta - 20 * Math.log10(8)) > .1 || q.gainDb() <= 0 || l.gainDb() >= 0)
                throw new AssertionError("PCM analysis did not measure the audio amplitude");
            if (q.frames() < 48_000 * 1.8 || l.frames() < 48_000 * 1.8)
                throw new AssertionError("Decoder did not analyze complete track PCM");
            System.out.printf("PASS real VLC PCM: quiet=%.2f LUFS gain=%+.2f dB; loud=%.2f LUFS gain=%+.2f dB; delta=%.2f dB; frames=%d/%d; elapsed=%.2fs%n",
                    q.integratedLufs(), q.gainDb(), l.integratedLufs(), l.gainDb(), delta,
                    q.frames(), l.frames(), (System.nanoTime() - start) / 1e9);
            LoudnessMeter.Result qOut = measureWithGain(quiet, q.gainDb());
            LoudnessMeter.Result lOut = measureWithGain(loud, l.gainDb());
            if (Math.abs(qOut.integratedLufs() + 18) > .2 || Math.abs(lOut.integratedLufs() + 18) > .2)
                throw new AssertionError("VLC equalizer did not apply the measured normalization gain: " + qOut + " / " + lOut);
            System.out.printf("PASS actual VLC gain PCM: quiet output=%.2f LUFS; loud output=%.2f LUFS; peaks=%.3f/%.3f%n",
                    qOut.integratedLufs(), lOut.integratedLufs(), qOut.peak(), lOut.peak());
            LoudnessMeter.Result plain = measureWithGain(loud, null);
            LoudnessMeter.Result unity = measureWithGain(loud, 0.0);
            if (Math.abs(plain.integratedLufs() - unity.integratedLufs()) > .15
                    || Math.abs(plain.peak() - unity.peak()) > .01)
                throw new AssertionError("Preinstalled unity equalizer changed normal playback: " + plain + " / " + unity);
            System.out.printf("PASS preinstalled unity gain: plain=%.2f LUFS; unity=%.2f LUFS; peaks=%.3f/%.3f%n",
                    plain.integratedLufs(), unity.integratedLufs(), plain.peak(), unity.peak());
            var canceled = analyzer.analyze(quiet.toUri());
            if (!canceled.cancel(true) || !canceled.isCancelled())
                throw new AssertionError("Pending analysis did not cancel");
            LoudnessMeter.Result afterCancel = analyzer.analyze(loud.toUri()).get(20, TimeUnit.SECONDS);
            if (Math.abs(afterCancel.integratedLufs() - l.integratedLufs()) > .1)
                throw new AssertionError("Canceled analysis affected the replacement measurement");
            System.out.println("PASS cancel and replacement measurement");
        }
    }

    private static LoudnessMeter.Result measureWithGain(Path file, Double gain) throws Exception {
        MediaPlayerFactory factory = new MediaPlayerFactory("--no-video", "--quiet");
        try {
            MediaPlayer player = factory.mediaPlayers().newMediaPlayer();
            try {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<String> failure = new AtomicReference<>();
                LoudnessMeter meter = new LoudnessMeter();
                if (gain != null) {
                    Equalizer equalizer = factory.equalizer().newEqualizer();
                    equalizer.setPreamp((float) (gain + VlcAudioPlayer.NORMALIZATION_UNITY_PREAMP_DB));
                    player.audio().setEqualizer(equalizer);
                }
                player.audio().callback("S16N", 48_000, 2, new AudioCallbackAdapter() {
                    @Override public void play(MediaPlayer player, Pointer samples, int count, long pts) {
                        short[] raw = samples.getShortArray(0, count * 2);
                        float[] pcm = new float[raw.length];
                        for (int i = 0; i < raw.length; i++) pcm[i] = raw[i] / 32768f;
                        meter.accept(pcm);
                    }
                });
                player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                    @Override public void finished(MediaPlayer player) { done.countDown(); }
                    @Override public void error(MediaPlayer player) { failure.set("VLC gain decode failed"); done.countDown(); }
                });
                if (!player.media().play(file.toUri().toString())) throw new AssertionError("Cannot play gain fixture");
                if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Gain decode timed out");
                if (failure.get() != null) throw new AssertionError(failure.get());
                return meter.result();
            } finally { player.controls().stop(); player.release(); }
        } finally { factory.release(); }
    }

    static Path writeTone(Path file, double amplitude) throws Exception {
        int rate = 48_000, frames = rate * 2;
        byte[] pcm = new byte[frames * 4];
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) Math.round(Math.sin(2 * Math.PI * 1000 * frame / rate) * amplitude * 32767);
            for (int channel = 0; channel < 2; channel++) {
                int offset = frame * 4 + channel * 2;
                pcm[offset] = (byte) sample; pcm[offset + 1] = (byte) (sample >>> 8);
            }
        }
        try (var stream = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(rate, 16, 2, true, false), frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }
}
