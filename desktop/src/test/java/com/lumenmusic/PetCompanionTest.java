package com.lumenmusic;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetCompanionTest {
    @Test
    void eachEventCycleUsesEightDistinctPhrasesBeforeRepeating() {
        PetPhrases phrases = new PetPhrases();
        for (PetPhrases.PhraseCycle cycle : List.of(phrases.playing, phrases.paused,
                phrases.trackChanged, phrases.favorited, phrases.unfavorited,
                phrases.newFavorite, phrases.replayedOldFavorite, phrases.focusCompleted)) {
            List<String> firstRound = nextRound(cycle);
            assertEquals(8, cycle.size());
            assertEquals(8, new HashSet<>(firstRound).size());

            List<String> secondRound = nextRound(cycle);
            assertEquals(firstRound, secondRound);
            assertTrue(java.util.stream.IntStream.range(1, firstRound.size())
                    .allMatch(index -> !firstRound.get(index - 1).equals(firstRound.get(index))));
            assertNotEquals(firstRound.get(firstRound.size() - 1), secondRound.get(0));
        }
    }

    @Test
    void eventCategoriesAdvanceIndependently() {
        PetPhrases phrases = new PetPhrases();
        String firstPlaying = phrases.playing.next();
        String firstPaused = phrases.paused.next();
        phrases.trackChanged.next();

        assertEquals(firstPlaying, new PetPhrases().playing.next());
        assertEquals(firstPaused, new PetPhrases().paused.next());
        PetPhrases expected = new PetPhrases();
        expected.playing.next();
        assertEquals(expected.playing.next(), phrases.playing.next());
        expected.paused.next();
        assertEquals(expected.paused.next(), phrases.paused.next());
    }

    private static List<String> nextRound(PetPhrases.PhraseCycle cycle) {
        return java.util.stream.IntStream.range(0, cycle.size())
                .mapToObj(ignored -> cycle.next())
                .toList();
    }
}
