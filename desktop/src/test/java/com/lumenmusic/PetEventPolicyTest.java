package com.lumenmusic;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static com.lumenmusic.PetCompanion.Event.FOCUS_COMPLETED;
import static com.lumenmusic.PetCompanion.Event.NEW_FAVORITE;
import static com.lumenmusic.PetCompanion.Event.PLAYING;
import static com.lumenmusic.PetCompanion.Event.REPLAYED_OLD_FAVORITE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetEventPolicyTest {
    @Test
    void globalAndSameEventCooldownsAdvanceOnlyWhenShown() {
        AdjustableClock clock = new AdjustableClock();
        PetEventPolicy policy = new PetEventPolicy(clock);
        assertTrue(policy.accept(NEW_FAVORITE, false, false, false));
        clock.advance(Duration.ofSeconds(89));
        assertFalse(policy.accept(REPLAYED_OLD_FAVORITE, false, false, false));
        clock.advance(Duration.ofSeconds(1));
        assertTrue(policy.accept(REPLAYED_OLD_FAVORITE, false, false, false));
        clock.advance(Duration.ofSeconds(90));
        assertFalse(policy.accept(NEW_FAVORITE, false, false, false));
        clock.advance(Duration.ofMinutes(2));
        assertTrue(policy.accept(NEW_FAVORITE, false, false, false));
    }

    @Test
    void hiddenAndQuietSuppressWithoutUsingQuota() {
        AdjustableClock clock = new AdjustableClock();
        PetEventPolicy policy = new PetEventPolicy(clock);
        assertFalse(policy.accept(NEW_FAVORITE, true, false, false));
        assertFalse(policy.accept(NEW_FAVORITE, false, true, false));
        assertTrue(policy.accept(FOCUS_COMPLETED, false, true, false));
        assertFalse(policy.accept(FOCUS_COMPLETED, false, true, false));
        clock.advance(Duration.ofMinutes(5));
        assertTrue(policy.accept(NEW_FAVORITE, false, false, false));
    }

    @Test
    void nightOnlyAllowsMilestonesWithLongerCooldowns() {
        AdjustableClock clock = new AdjustableClock();
        PetEventPolicy policy = new PetEventPolicy(clock);
        assertFalse(policy.accept(PLAYING, false, false, true));
        assertTrue(policy.accept(NEW_FAVORITE, false, false, true));
        clock.advance(Duration.ofMinutes(9));
        assertFalse(policy.accept(REPLAYED_OLD_FAVORITE, false, false, true));
        clock.advance(Duration.ofMinutes(1));
        assertTrue(policy.accept(REPLAYED_OLD_FAVORITE, false, false, true));
        clock.advance(Duration.ofMinutes(10));
        assertFalse(policy.accept(NEW_FAVORITE, false, false, true));
        clock.advance(Duration.ofMinutes(10));
        assertTrue(policy.accept(NEW_FAVORITE, false, false, true));
    }

    @Test
    void closePermanentlySuppressesEventsWithoutSchedulingWork() {
        AdjustableClock clock = new AdjustableClock();
        PetEventPolicy policy = new PetEventPolicy(clock);
        policy.close();
        assertFalse(policy.accept(FOCUS_COMPLETED, false, false, false));
        clock.advance(Duration.ofDays(1));
        assertFalse(policy.accept(FOCUS_COMPLETED, false, false, false));
    }

    private static final class AdjustableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-06T12:00:00Z");

        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
