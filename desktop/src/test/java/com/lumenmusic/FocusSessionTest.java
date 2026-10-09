package com.lumenmusic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FocusSessionTest {
    @Test
    void pauseResumeAndCompleteOnceAtZero() {
        AtomicLong now = new AtomicLong();
        AtomicInteger completions = new AtomicInteger();
        FocusSession session = new FocusSession(Duration.ofSeconds(10), now::get, completions::incrementAndGet);
        session.start();
        now.set(Duration.ofSeconds(4).toNanos());
        assertEquals(Duration.ofSeconds(6), session.remaining());
        session.pause();
        now.set(Duration.ofSeconds(20).toNanos());
        assertEquals(Duration.ofSeconds(6), session.remaining());
        session.start();
        now.set(Duration.ofSeconds(26).toNanos());
        assertEquals(Duration.ZERO, session.remaining());
        assertEquals(FocusSession.State.COMPLETED, session.state());
        session.tick();
        session.pause();
        assertEquals(1, completions.get());
    }

    @Test
    void cancelAndChangingPresetResetWithoutCompletion() {
        AtomicLong now = new AtomicLong();
        AtomicInteger completions = new AtomicInteger();
        FocusSession session = new FocusSession(Duration.ofMinutes(25), now::get, completions::incrementAndGet);
        session.start();
        now.set(Duration.ofMinutes(10).toNanos());
        session.cancel();
        assertEquals(FocusSession.State.IDLE, session.state());
        assertEquals(Duration.ofMinutes(25), session.remaining());
        session.start();
        now.set(Duration.ofMinutes(15).toNanos());
        session.setDuration(Duration.ofMinutes(45));
        assertEquals(FocusSession.State.IDLE, session.state());
        assertEquals(Duration.ofMinutes(45), session.remaining());
        assertEquals(0, completions.get());
        assertThrows(IllegalArgumentException.class, () -> session.setDuration(Duration.ZERO));
    }

    @Test
    void sleepCallbackOnlyAfterElapsedTimeAndCanRestart() {
        AtomicLong now = new AtomicLong();
        AtomicInteger pauses = new AtomicInteger();
        FocusSession sleep = new FocusSession(Duration.ofMinutes(15), now::get, pauses::incrementAndGet);
        sleep.start();
        now.set(Duration.ofMinutes(16).toNanos());
        sleep.tick();
        sleep.tick();
        assertEquals(1, pauses.get());
        sleep.start();
        now.set(Duration.ofMinutes(17).toNanos());
        sleep.cancel();
        now.set(Duration.ofMinutes(40).toNanos());
        sleep.tick();
        assertEquals(1, pauses.get());
    }
}
