package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class PlayerQueueTest {
    private static final Track ONE = new Track("1", "第一首", "作者", 10, "test");
    private static final Track TWO = new Track("2", "第二首", "作者", 10, "test");
    private static final Track THREE = new Track("3", "第三首", "作者", 10, "test");
    private static final Track FOUR = new Track("4", "第四首", "作者", 10, "test");

    @Test void automaticPreviewMatchesRepeatAndExcludedTrackAdvancement() {
        for (var mode : PlayerQueue.RepeatMode.values()) {
            var queue = new PlayerQueue();
            assertNull(queue.peekAfterEnd());
            queue.select(List.of(ONE, TWO, THREE), 1);
            queue.setRepeatMode(mode);
            queue.setExcludedIds(Set.of(TWO.id()));
            for (int count = 0; count < 6; count++) {
                var before = queue.snapshot();
                Track preview = queue.peekAfterEnd();
                assertEquals(before, queue.snapshot());
                assertEquals(preview, queue.peekAfterEnd());
                assertEquals(preview, queue.advanceAfterEnd(), mode.toString());
                assertNotEquals(TWO, preview);
            }
            queue.setExcludedIds(Set.of(ONE.id(), TWO.id(), THREE.id()));
            assertNull(queue.peekAfterEnd());
            assertNull(queue.advanceAfterEnd());
        }
    }

    @Test void automaticPreviewRepeatsOneAndStopsAtOffTail() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE), 0);
        for (var mode : PlayerQueue.RepeatMode.values()) {
            queue.setRepeatMode(mode);
            Track expected = mode == PlayerQueue.RepeatMode.OFF ? null : ONE;
            assertEquals(expected, queue.peekAfterEnd());
            assertEquals(expected, queue.advanceAfterEnd());
        }
        queue.select(List.of(ONE, TWO), 1);
        queue.setRepeatMode(PlayerQueue.RepeatMode.ONE);
        assertEquals(TWO, queue.peekAfterEnd());
        queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
        assertNull(queue.peekAfterEnd());
        assertEquals(TWO, queue.current());
    }

    @Test void shufflePreviewReservesExactlyOneDrawAndDoesNotChangeSnapshot() {
        for (boolean automatic : List.of(false, true)) {
            int[] draws = {0};
            var queue = new PlayerQueue(bound -> draws[0]++ % bound);
            queue.select(List.of(ONE, TWO, THREE), 0);
            queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
            var before = queue.snapshot();
            assertEquals(TWO, queue.peekAfterEnd());
            for (int count = 0; count < 10; count++) assertEquals(TWO, queue.peekAfterEnd());
            assertEquals(1, draws[0]);
            assertEquals(before, queue.snapshot());
            assertEquals(TWO, automatic ? queue.advanceAfterEnd() : queue.move(1));
            assertEquals(1, draws[0]);
            assertEquals(List.of(0, 1), queue.snapshot().shuffleHistory());
            assertEquals(THREE, queue.peekAfterEnd());
            assertEquals(2, draws[0]);
            assertEquals(THREE, queue.move(1));
            assertEquals(2, draws[0]);
        }
    }

    @Test void shufflePreviewUsesForwardHistoryWithoutDrawingOrMovingCursor() {
        var queue = new PlayerQueue(bound -> { fail("Forward history must not draw randomly"); return 0; });
        var snapshot = new PlayerQueue.Snapshot(List.of(ONE, TWO, THREE),
                List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.CURRENT_LIST,
                        PlayerQueue.Source.CURRENT_LIST),
                1, PlayerQueue.RepeatMode.SHUFFLE, List.of(0, 1, 2), 1);
        queue.restore(snapshot);
        assertEquals(THREE, queue.peekAfterEnd());
        assertEquals(snapshot, queue.snapshot());
        assertEquals(THREE, queue.move(1));
        assertEquals(TWO, queue.move(-1));
        assertEquals(THREE, queue.peekAfterEnd());
        assertEquals(THREE, queue.advanceAfterEnd());
    }

    @Test void previousNavigationDiscardsRandomReservation() {
        int[] draws = {0};
        var queue = new PlayerQueue(bound -> draws[0]++ % bound);
        queue.select(List.of(ONE, TWO, THREE), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        assertEquals(TWO, queue.peekAfterEnd());
        assertEquals(ONE, queue.move(-1));
        assertEquals(THREE, queue.peekAfterEnd());
        assertEquals(2, draws[0]);
        assertEquals(THREE, queue.move(1));
        assertEquals(2, draws[0]);
    }

    @Test void queueMutationsDiscardRandomReservation() {
        List<Consumer<PlayerQueue>> changes = List.of(
                queue -> queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE),
                queue -> queue.setExcludedIds(Set.of(FOUR.id())),
                queue -> queue.selectIndex(1),
                queue -> queue.select(List.of(ONE, TWO, THREE, FOUR), 0),
                queue -> queue.restore(queue.snapshot()),
                queue -> queue.restore(List.of(ONE, TWO, THREE, FOUR), 0),
                queue -> queue.reorder(0, 1),
                queue -> queue.playNext(FOUR, PlayerQueue.Source.RADIO),
                queue -> queue.removeAt(3),
                queue -> queue.replaceById(new Track(TWO.id(), "Updated", "Author", 20, "test")),
                queue -> { queue.clear(); queue.select(List.of(ONE, TWO, THREE, FOUR), 0); });
        for (Consumer<PlayerQueue> change : changes) {
            int[] draws = {0};
            var queue = new PlayerQueue(bound -> draws[0]++ % bound);
            queue.select(List.of(ONE, TWO, THREE, FOUR), 0);
            queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
            assertEquals(TWO, queue.peekAfterEnd());
            assertEquals(1, draws[0]);
            change.accept(queue);
            var before = queue.snapshot();
            Track preview = queue.peekAfterEnd();
            assertEquals(2, draws[0]);
            assertEquals(before, queue.snapshot());
            assertEquals(preview, queue.advanceAfterEnd());
            assertEquals(2, draws[0]);
        }
    }

    @Test void unchangedExclusionsRetainRandomReservation() {
        int[] draws = {0};
        var queue = new PlayerQueue(bound -> { draws[0]++; return 0; });
        queue.select(List.of(ONE, TWO, THREE), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        assertEquals(TWO, queue.peekAfterEnd());
        queue.setExcludedIds(Set.of());
        assertEquals(TWO, queue.peekAfterEnd());
        assertEquals(1, draws[0]);
        assertEquals(TWO, queue.advanceAfterEnd());
        assertEquals(1, draws[0]);
    }

    @Test void manualInsertionOverridesReservationAndSkipsExcludedManualItems() {
        int[] draws = {0};
        var queue = new PlayerQueue(bound -> { draws[0]++; return 0; });
        queue.select(List.of(ONE, TWO, THREE), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        assertEquals(TWO, queue.peekAfterEnd());
        queue.playNext(FOUR);
        assertEquals(FOUR, queue.peekAfterEnd());
        assertEquals(FOUR, queue.advanceAfterEnd());
        queue.enqueue(List.of(TWO, ONE));
        queue.setExcludedIds(Set.of(TWO.id()));
        assertEquals(ONE, queue.peekAfterEnd());
        assertEquals(ONE, queue.advanceAfterEnd());
        assertEquals(1, draws[0]);
    }

    @Test void radioPreviewFollowsArrangedOrderWithoutWrappingOrRandomDraws() {
        int[] draws = {0};
        var queue = new PlayerQueue(bound -> { draws[0]++; return 0; });
        assertNull(queue.peekWithoutWrap());
        queue.restore(List.of(ONE, TWO, THREE, FOUR), 0,
                List.of(PlayerQueue.Source.RADIO, PlayerQueue.Source.MANUAL,
                        PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.RADIO));
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        queue.setExcludedIds(Set.of(TWO.id()));
        assertEquals(THREE, queue.peekAfterEnd());
        assertEquals(1, draws[0]);
        for (Track expected : List.of(THREE, FOUR)) {
            var before = queue.snapshot();
            assertEquals(expected, queue.peekWithoutWrap());
            assertEquals(expected, queue.peekWithoutWrap());
            assertEquals(before, queue.snapshot());
            assertEquals(expected, queue.advanceWithoutWrap());
        }
        assertNull(queue.peekWithoutWrap());
        assertNull(queue.advanceWithoutWrap());
        assertEquals(FOUR, queue.current());
        assertEquals(1, draws[0]);
        assertEquals(ONE, queue.peekAfterEnd());
        assertEquals(2, draws[0]);
        assertEquals(ONE, queue.advanceAfterEnd());
        assertEquals(2, draws[0]);
    }

    @Test void radioDrainsManualInsertionsBeforeTailAndNeverWraps() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, THREE), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        queue.playNext(TWO);
        assertEquals(ONE, queue.current());
        assertEquals(TWO, queue.advanceWithoutWrap());
        assertEquals(THREE, queue.advanceWithoutWrap());
        assertNull(queue.advanceWithoutWrap());
        assertEquals(THREE, queue.current());
        queue.playNext(ONE);
        assertEquals(ONE, queue.advanceWithoutWrap());
        assertNull(queue.advanceWithoutWrap());
    }

    @Test void radioBoundarySkipsExcludedAndKeepsOrdinaryRepeatUnchanged() {
        var queue = new PlayerQueue();
        assertNull(queue.advanceWithoutWrap());
        queue.select(List.of(ONE, TWO, THREE), 0);
        queue.setExcludedIds(java.util.Set.of(TWO.id()));
        assertEquals(THREE, queue.advanceWithoutWrap());
        assertNull(queue.advanceWithoutWrap());
        assertEquals(ONE, queue.advanceAfterEnd());
        queue.setRepeatMode(PlayerQueue.RepeatMode.ONE);
        assertEquals(ONE, queue.advanceAfterEnd());
    }

    @Test void excludedPartsAreSkippedByEveryAutomaticModeAndCanBeRestored() {
        for (var mode : PlayerQueue.RepeatMode.values()) {
            var queue = new PlayerQueue();
            queue.select(List.of(ONE, TWO, THREE), 1);
            queue.setRepeatMode(mode);
            queue.setExcludedIds(java.util.Set.of(TWO.id()));
            for (int n = 0; n < 30; n++) {
                Track next = queue.advanceAfterEnd();
                if (next == null) break;
                assertNotEquals(TWO, next);
            }
            assertNotEquals(TWO, queue.move(-1));
            queue.setExcludedIds(java.util.Set.of(ONE.id(), TWO.id(), THREE.id()));
            assertNull(queue.advanceAfterEnd());
            assertNull(queue.move(1));
            queue.setExcludedIds(java.util.Set.of());
            assertNotNull(queue.move(1));
        }
    }

    @Test void queueKeepsItsSnapshotWhenSearchResultsChange() {
        var visibleResults = new ArrayList<>(List.of(ONE, TWO));
        var queue = new PlayerQueue();
        queue.select(visibleResults, 0);
        visibleResults.clear();
        assertEquals(List.of(ONE, TWO), queue.tracks());
        assertEquals(TWO, queue.move(-1));
        assertEquals(ONE, queue.move(1));
    }

    @Test void selectingAnotherSourceReplacesQueueAndRejectsInvalidIndex() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO), 1);
        queue.select(List.of(ONE), 0);
        assertEquals(List.of(ONE), queue.tracks());
        assertEquals(ONE, queue.current());
        assertThrows(IllegalArgumentException.class, () -> queue.select(List.of(TWO), 1));
        assertEquals(ONE, queue.current());
    }

    @Test void playbackEndRespectsEachRepeatMode() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO), 0);

        assertEquals(PlayerQueue.RepeatMode.ALL, queue.repeatMode());
        assertEquals(TWO, queue.advanceAfterEnd());
        assertEquals(ONE, queue.advanceAfterEnd());

        queue.setRepeatMode(PlayerQueue.RepeatMode.ONE);
        assertEquals(ONE, queue.advanceAfterEnd());
        assertEquals(ONE, queue.current());

        queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
        assertEquals(TWO, queue.advanceAfterEnd());
        assertNull(queue.advanceAfterEnd());
        assertEquals(TWO, queue.current());
        assertEquals(ONE, queue.move(1));
    }

    @Test void playbackEndHandlesEmptyAndSingleTrackQueues() {
        var queue = new PlayerQueue();
        assertNull(queue.advanceAfterEnd());
        queue.select(List.of(ONE), 0);
        assertEquals(ONE, queue.advanceAfterEnd());
        queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
        assertNull(queue.advanceAfterEnd());
        assertEquals(ONE, queue.current());
        assertThrows(NullPointerException.class, () -> queue.setRepeatMode(null));
        assertEquals(PlayerQueue.RepeatMode.OFF, queue.repeatMode());
    }

    @Test void multipartIdentityAndTitlesRemainStableAcrossNavigation() {
        Track pageOne = new Track("bilibili:BV1nhMuzaEJr:p1", "合集 · 第一 P", "UP", 80,
                "bilibili", null, "BV1nhMuzaEJr", 1);
        Track pageTwo = new Track("bilibili:BV1nhMuzaEJr:p2", "合集 · 第二 P", "UP", 90,
                "bilibili", null, "BV1nhMuzaEJr", 2);
        var queue = new PlayerQueue();
        queue.select(List.of(pageOne, pageTwo), 0);
        assertEquals(pageTwo, queue.advanceAfterEnd());
        assertEquals("合集 · 第二 P", queue.current().title());
        assertEquals(2, queue.current().page());
        assertNotEquals(pageOne.id(), pageTwo.id());
        queue.setRepeatMode(PlayerQueue.RepeatMode.OFF);
        assertNull(queue.advanceAfterEnd());
        assertEquals(pageOne, queue.move(1));
    }

    @Test void shuffleAvoidsImmediateRepeatAndPreviousReturnsHistory() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        assertEquals(TWO, queue.advanceAfterEnd());
        assertEquals(ONE, queue.move(-1));
        assertEquals(TWO, queue.move(1));
        assertEquals(ONE, queue.advanceAfterEnd());
        queue.setRepeatMode(PlayerQueue.RepeatMode.ALL);
        assertEquals(TWO, queue.advanceAfterEnd());
    }

    @Test void playNextInsertsAfterCurrentWithoutChangingCurrentTrack() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO), 1);
        queue.playNext(THREE);
        assertEquals(TWO, queue.current());
        assertEquals(List.of(ONE, TWO, THREE), queue.tracks());
        assertEquals(THREE, queue.advanceAfterEnd());
        assertEquals(ONE, queue.advanceAfterEnd());
    }

    @Test void reorderMovesPendingItemsAndTracksCurrentIndex() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO, THREE), 1);
        queue.reorder(2, 0);
        assertEquals(List.of(THREE, ONE, TWO), queue.tracks());
        assertEquals(TWO, queue.current());
        assertEquals(THREE, queue.advanceAfterEnd());
        assertThrows(IllegalArgumentException.class, () -> queue.reorder(0, 3));
    }

    @Test void shuffleHistorySurvivesReorderingAndPlayNextIsNext() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        assertEquals(TWO, queue.advanceAfterEnd());
        queue.reorder(0, 1);
        assertEquals(TWO, queue.current());
        assertEquals(ONE, queue.move(-1));
        assertEquals(TWO, queue.move(1));
        queue.playNext(THREE);
        assertEquals(THREE, queue.advanceAfterEnd());
    }

    @Test void sourcesAndBatchEnqueueKeepManualItemsAheadOfRadio() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO), 0);
        queue.playNext(THREE, PlayerQueue.Source.RADIO);
        queue.enqueue(List.of(TWO, ONE));
        assertEquals(List.of(ONE, TWO, ONE, THREE, TWO), queue.tracks());
        assertEquals(List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.MANUAL,
                PlayerQueue.Source.MANUAL, PlayerQueue.Source.RADIO,
                PlayerQueue.Source.CURRENT_LIST), queue.sources());
        queue.playNext(THREE);
        assertEquals(PlayerQueue.Source.MANUAL, queue.sourceAt(1));
        assertEquals(THREE, queue.advanceWithoutWrap());
    }

    @Test void removingByPositionKeepsDuplicateOccurrencesAndSelectsEligibleSuccessor() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO, ONE, THREE), 0);
        assertFalse(queue.removeAt(2));
        assertEquals(List.of(ONE, TWO, THREE), queue.tracks());
        queue.setExcludedIds(java.util.Set.of(TWO.id()));
        assertTrue(queue.removeAt(0));
        assertEquals(THREE, queue.current());
        assertTrue(queue.removeAt(1));
        assertNull(queue.current());
        assertEquals(List.of(TWO), queue.tracks());
        queue.clear();
        assertEquals(List.of(), queue.sources());
        assertNull(queue.current());
    }

    @Test void removeIndicesValidatesBeforeMutatingAndUsesPreviousAtTail() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO, THREE), 2);
        assertThrows(IllegalArgumentException.class, () -> queue.removeIndices(List.of(1, 9)));
        assertEquals(List.of(ONE, TWO, THREE), queue.tracks());
        assertTrue(queue.removeIndices(List.of(2, 2)));
        assertEquals(TWO, queue.current());
    }

    @Test void shuffleDrainsManualOrderAndSnapshotRestoresPlaybackState() {
        var queue = new PlayerQueue();
        queue.select(List.of(ONE, TWO, THREE), 0);
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        queue.reorder(2, 1);
        assertEquals(List.of(ONE, THREE, TWO), queue.tracks());
        assertEquals(List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.MANUAL,
                PlayerQueue.Source.MANUAL), queue.sources());
        assertEquals(THREE, queue.advanceAfterEnd());
        var snapshot = queue.snapshot();
        assertEquals(TWO, queue.advanceAfterEnd());
        queue.clear();
        queue.restore(snapshot);
        assertEquals(THREE, queue.current());
        assertEquals(TWO, queue.advanceAfterEnd());
        assertEquals(THREE, queue.move(-1));
        queue.setRepeatMode(PlayerQueue.RepeatMode.ONE);
        assertEquals(THREE, queue.advanceAfterEnd());
    }

    @Test void sourcePreservingRestoreAndSelectionDoNotResetProvenance() {
        var queue = new PlayerQueue();
        queue.restore(List.of(ONE, TWO), 0,
                List.of(PlayerQueue.Source.RADIO, PlayerQueue.Source.MANUAL));
        queue.selectIndex(1);
        assertEquals(PlayerQueue.Source.MANUAL, queue.sourceAt(queue.index()));
        assertEquals(List.of(PlayerQueue.Source.RADIO, PlayerQueue.Source.MANUAL), queue.sources());
    }

    @Test void futureReorderReplacesStaleShuffleForwardHistory() {
        var queue = new PlayerQueue();
        queue.restore(new PlayerQueue.Snapshot(List.of(ONE, TWO, THREE),
                List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.CURRENT_LIST,
                        PlayerQueue.Source.CURRENT_LIST), 0, PlayerQueue.RepeatMode.SHUFFLE,
                List.of(0, 1), 0));
        queue.reorder(1, 2);
        assertEquals(List.of(ONE, THREE, TWO), queue.tracks());
        assertEquals(THREE, queue.move(1));
        assertEquals(TWO, queue.move(1));
    }

    @Test void unchangedExclusionsPreserveRestoredShuffleHistory() {
        var queue = new PlayerQueue();
        var snapshot = new PlayerQueue.Snapshot(List.of(ONE, TWO, THREE),
                List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.CURRENT_LIST,
                        PlayerQueue.Source.CURRENT_LIST), 0, PlayerQueue.RepeatMode.SHUFFLE,
                List.of(0, 2), 0);
        queue.restore(snapshot);
        queue.setExcludedIds(java.util.Set.of());
        assertEquals(snapshot, queue.snapshot());
        assertEquals(THREE, queue.move(1));
    }

    @Test void draggingFutureRadioItemCommitsTheWholeFutureOrder() {
        var queue = new PlayerQueue();
        queue.restore(List.of(ONE, TWO, THREE), 0,
                List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.RADIO,
                        PlayerQueue.Source.CURRENT_LIST));
        queue.setRepeatMode(PlayerQueue.RepeatMode.SHUFFLE);
        queue.reorder(2, 1);
        assertEquals(List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.MANUAL,
                PlayerQueue.Source.MANUAL), queue.sources());
        assertEquals(THREE, queue.advanceAfterEnd());
        assertEquals(TWO, queue.advanceAfterEnd());
    }

    @Test void removingOtherOccurrencesPreservesSurvivingShuffleHistory() {
        var queue = new PlayerQueue();
        queue.restore(new PlayerQueue.Snapshot(List.of(ONE, TWO, THREE, ONE),
                List.of(PlayerQueue.Source.CURRENT_LIST, PlayerQueue.Source.MANUAL,
                        PlayerQueue.Source.RADIO, PlayerQueue.Source.MANUAL),
                1, PlayerQueue.RepeatMode.SHUFFLE, List.of(0, 1, 3, 2), 1));
        assertFalse(queue.removeIndices(List.of(0, 3)));
        assertEquals(List.of(TWO, THREE), queue.tracks());
        assertEquals(TWO, queue.current());
        assertEquals(List.of(0, 1), queue.snapshot().shuffleHistory());
        assertEquals(0, queue.snapshot().shuffleCursor());
        assertEquals(THREE, queue.move(1));
        assertEquals(TWO, queue.move(-1));
    }
}
