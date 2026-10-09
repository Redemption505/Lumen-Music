package com.lumenmusic;

import com.lumenmusic.audio.LoudnessMeter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoudnessMeterTest {
    static float[] tone(double amplitude, double seconds) {
        float[] pcm = new float[(int) (LoudnessMeter.SAMPLE_RATE * seconds) * 2];
        for (int frame = 0; frame < pcm.length / 2; frame++) {
            float sample = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * frame / LoudnessMeter.SAMPLE_RATE));
            pcm[frame * 2] = pcm[frame * 2 + 1] = sample;
        }
        return pcm;
    }

    @Test void actualPcmAmplitudeDifferenceChangesMeasuredLoudnessAndGain() {
        LoudnessMeter quiet = new LoudnessMeter(), loud = new LoudnessMeter();
        quiet.accept(tone(.1, 2)); loud.accept(tone(.4, 2));
        var q = quiet.result(); var l = loud.result();
        assertEquals(20 * Math.log10(4), l.integratedLufs() - q.integratedLufs(), .001);
        assertTrue(q.gainDb() > 0); assertTrue(l.gainDb() < 0);
        assertEquals(LoudnessMeter.TARGET_LUFS, q.integratedLufs() + q.gainDb(), .001);
        assertEquals(LoudnessMeter.TARGET_LUFS, l.integratedLufs() + l.gainDb(), .001);
        assertEquals(.1, q.peak(), .000001);
    }

    @Test void gainIsBoundedAndSilentAudioDoesNotCreateInfiniteBoost() {
        LoudnessMeter silence = new LoudnessMeter();
        silence.accept(new float[48_000]);
        assertThrows(IllegalStateException.class, silence::result);
        LoudnessMeter veryQuiet = new LoudnessMeter();
        veryQuiet.accept(tone(.001, 2));
        assertEquals(LoudnessMeter.MAX_BOOST_DB, veryQuiet.result().gainDb());
        LoudnessMeter impulse = new LoudnessMeter();
        float[] samples = tone(.02, 2); samples[48000] = 1;
        impulse.accept(samples);
        assertTrue(impulse.result().gainDb() <= -1);
    }
}
