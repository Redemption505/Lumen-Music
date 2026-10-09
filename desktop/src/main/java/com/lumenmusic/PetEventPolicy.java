package com.lumenmusic;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Objects;

/** Rate limits automatic pet speech; manual interaction is separate. */
final class PetEventPolicy {
    static final Duration GLOBAL_COOLDOWN = Duration.ofSeconds(90);
    static final Duration SAME_EVENT_COOLDOWN = Duration.ofMinutes(5);
    static final Duration NIGHT_GLOBAL_COOLDOWN = Duration.ofMinutes(10);
    static final Duration NIGHT_SAME_EVENT_COOLDOWN = Duration.ofMinutes(30);

    private final Clock clock;
    private final EnumMap<PetCompanion.Event, Instant> lastByEvent =
            new EnumMap<>(PetCompanion.Event.class);
    private Instant lastAutomatic;
    private boolean closed;

    PetEventPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    boolean accept(PetCompanion.Event event, boolean hidden, boolean quiet, boolean nightMode) {
        Objects.requireNonNull(event);
        if (closed || hidden || (quiet && event != PetCompanion.Event.FOCUS_COMPLETED)
                || (nightMode && !event.isMilestone())) return false;

        Instant now = clock.instant();
        Duration globalCooldown = nightMode ? NIGHT_GLOBAL_COOLDOWN : GLOBAL_COOLDOWN;
        Duration eventCooldown = nightMode ? NIGHT_SAME_EVENT_COOLDOWN : SAME_EVENT_COOLDOWN;
        if (withinCooldown(lastAutomatic, now, globalCooldown)
                || withinCooldown(lastByEvent.get(event), now, eventCooldown)) return false;

        lastAutomatic = now;
        lastByEvent.put(event, now);
        return true;
    }

    void close() {
        closed = true;
    }

    private static boolean withinCooldown(Instant last, Instant now, Duration cooldown) {
        return last != null && Duration.between(last, now).compareTo(cooldown) < 0;
    }
}
