package com.lumenmusic;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadioSessionTest {
    private static Track track(String id) { return new Track(id, "Song " + id, "Artist", 90, "demo"); }
    private static RadioClient.Item item(String id, String reason) {
        return new RadioClient.Item(track(id), reason);
    }
    private static RadioClient.Result result(RadioClient.Item... items) {
        return new RadioClient.Result(List.of(items), "ready", "", "");
    }

    @Test void filtersSeedAndRepeatedIdsAndRemovedTracksDoNotReturn() {
        RadioSession session = new RadioSession();
        session.start(List.of(track("seed"), track("seed")), "calm", "discover", false);
        session.accept(result(item("seed", "seed"), item("a", "first"), item("a", "duplicate"),
                item("b", "second")));
        assertEquals(List.of("a", "b"), session.preview().stream().map(i -> i.track().id()).toList());

        session.remove("a");
        assertTrue(session.seenIds().contains("a"));
        session.accept(result(item("a", "should not return"), item("b", "updated"), item("c", "third")));
        assertEquals(List.of("b", "c"), session.preview().stream().map(i -> i.track().id()).toList());
        assertEquals("updated", session.preview().get(0).reason());
    }

    @Test void stoppingAndAutoContinueControlConsumptionWithoutClearingPreview() {
        RadioSession session = new RadioSession();
        session.start(List.of(track("seed")), "", "familiar", true);
        session.accept(result(item("a", "why a"), item("b", "why b")));
        session.setAutoContinue(false);
        assertNull(session.popNext());
        assertEquals(2, session.preview().size());
        assertEquals("why b", session.take("b").reason());
        assertEquals(List.of("a"), session.preview().stream().map(i -> i.track().id()).toList());
        assertTrue(session.seenIds().contains("b"));

        session.setAutoContinue(true);
        session.stop();
        assertFalse(session.active());
        assertNull(session.popNext());
        assertNull(session.take("a"));
        session.accept(result(item("late", "stale response")));
        assertEquals(1, session.preview().size());

        session.start(List.of(track("new-seed")), "rain", "discover", false);
        assertTrue(session.active());
        assertTrue(session.autoContinue());
        assertEquals("discover", session.mode());
        assertEquals("rain", session.hint());
        assertFalse(session.neutral());
        assertEquals(List.of(track("new-seed")), session.seeds());
        assertEquals(List.of(), session.preview());
        assertEquals(java.util.Set.of("new-seed"), session.seenIds());
    }

    @Test void popNextAndRecordPlayedRememberRecentIdsWithBoundedHistory() {
        RadioSession session = new RadioSession();
        session.start(List.of(), "", "familiar", true);
        session.accept(result(item("one", "play"), item("two", "next")));
        assertEquals("one", session.popNext().track().id());
        session.recordPlayed(track("external"));
        assertTrue(session.seenIds().containsAll(List.of("one", "external")));

        for (int i = 0; i < 205; i++) session.recordPlayed(track("recent-" + i));
        assertEquals(200, session.seenIds().size());
        assertFalse(session.seenIds().contains("one"));
        assertTrue(session.seenIds().contains("recent-204"));
        assertEquals("two", session.popNext().track().id());
    }

    @Test void manuallyPlayedPreviewItemIsRemovedBeforeAutomaticContinuation() {
        RadioSession session = new RadioSession();
        session.start(List.of(), "", "familiar", true);
        session.accept(result(item("a", "manual selection"), item("b", "next")));

        session.recordPlayed(track("a"));

        assertTrue(session.seenIds().contains("a"));
        assertEquals(List.of("b"), session.preview().stream().map(i -> i.track().id()).toList());
        assertEquals("b", session.popNext().track().id());
    }

    @Test void dismissedItemsStayExcludedAfterRecentPlaybackHistoryEvictsThem() {
        RadioSession session = new RadioSession();
        session.start(List.of(), "", "familiar", true);
        session.accept(result(item("a", "dismiss me"), item("b", "keep")));
        session.remove("a");

        for (int i = 0; i < 205; i++) session.recordPlayed(track("recent-" + i));
        session.accept(result(item("a", "must stay dismissed"), item("b", "eligible")));

        assertFalse(session.seenIds().contains("recent-0"));
        assertTrue(session.seenIds().contains("a"));
        assertEquals(List.of("b"), session.preview().stream().map(i -> i.track().id()).toList());
    }

    @Test void restoringFeedbackAllowsTheResourceOnTheNextRefresh() {
        RadioSession session = new RadioSession();
        session.start(List.of(), "", "familiar", false);
        session.accept(result(item("a", "candidate")));
        session.remove("a");
        session.restoreRemoved("a");
        assertFalse(session.seenIds().contains("a"));
        session.accept(result(item("a", "restored")));
        assertEquals("a", session.popNext().track().id());
    }

    @Test void rejectsUnknownModeWithoutChangingCurrentSession() {
        RadioSession session = new RadioSession();
        session.start(List.of(track("seed")), "", "familiar", true);
        session.accept(result(item("a", "preview")));

        assertThrows(IllegalArgumentException.class,
                () -> session.start(List.of(), "", "unknown", false));
        assertTrue(session.active());
        assertEquals("familiar", session.mode());
        assertEquals(List.of("a"), session.preview().stream().map(i -> i.track().id()).toList());
    }
}
