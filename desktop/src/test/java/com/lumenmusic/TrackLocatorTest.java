package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

final class TrackLocatorTest {
    @Test void matchesTitleAndArtistCaseInsensitivelyUsingRootLocale() {
        TrackLocator locator = new TrackLocator();
        locator.setItems(List.of(track("Istanbul Nights", "Ada"), track("Quiet", "Ipek"), track("Other", "Else")));

        locator.setQuery("ISTANBUL");
        assertEquals(List.of(0), locator.matches());
        locator.setQuery("ipek");
        assertEquals(List.of(1), locator.matches());

        LocaleGuard.runWithTurkishLocale(() -> {
            locator.setQuery("ISTANBUL");
            assertEquals(List.of(0), locator.matches());
        });
    }

    @Test void emptyQueryEmptyListAndNoMatchesHaveNoCursor() {
        TrackLocator locator = new TrackLocator();
        locator.setItems(List.of(track("Song", "Artist")));
        locator.setQuery(" ");
        assertEquals(List.of(), locator.matches());
        assertEquals(-1, locator.cursor());
        assertEquals(-1, locator.currentIndex());
        assertEquals(-1, locator.nextIndex());

        locator.setQuery("missing");
        assertEquals(List.of(), locator.matches());
        locator.setItems(List.of());
        locator.setQuery("song");
        assertEquals(List.of(), locator.matches());
        assertEquals(-1, locator.currentIndex());
    }

    @Test void advancesAndWrapsAcrossSourceIndexesAndKeepsSingleMatch() {
        TrackLocator locator = new TrackLocator();
        locator.setItems(List.of(track("hit one", "A"), track("miss", "B"), track("hit two", "C")));
        locator.setQuery("hit");

        assertEquals(0, locator.cursor());
        assertEquals(0, locator.currentIndex());
        assertEquals(2, locator.nextIndex());
        assertEquals(0, locator.nextIndex());

        locator.setQuery("two");
        assertEquals(0, locator.cursor());
        assertEquals(2, locator.currentIndex());
        assertEquals(2, locator.nextIndex());
    }

    @Test void eachItemsReplacementRebuildsPositionsAndResetsToFirstMatch() {
        TrackLocator locator = new TrackLocator();
        Track first = track("match A", "A");
        Track middle = track("other", "X");
        Track second = track("match B", "B");
        locator.setItems(List.of(first, middle, second));
        locator.setQuery("match");
        assertEquals(2, locator.nextIndex());

        locator.setItems(List.of(middle, first, second)); // reorder
        assertEquals(List.of(1, 2), locator.matches());
        assertEquals(1, locator.currentIndex());
        assertEquals(2, locator.nextIndex());

        locator.setItems(List.of(middle, second)); // removal
        assertEquals(List.of(1), locator.matches());
        assertEquals(1, locator.currentIndex());
        assertEquals(1, locator.nextIndex());

        locator.setItems(List.of(track("new match", "N"), middle, second)); // insertion
        assertEquals(List.of(0, 2), locator.matches());
        assertEquals(0, locator.currentIndex());

        locator.setItems(List.of(middle, track("replacement", "R"), track("new match", "N")));
        assertEquals(List.of(2), locator.matches());
        assertEquals(2, locator.currentIndex());
    }

    @Test void snapshotsAreImmutableAndDoNotChangeTheSuppliedList() {
        Track track = track("Song", "Artist");
        var source = new java.util.ArrayList<>(List.of(track));
        TrackLocator locator = new TrackLocator();
        locator.setItems(source);
        locator.setQuery("song");

        source.clear();
        assertEquals(List.of(), source);
        assertEquals(List.of(0), locator.matches());
        assertEquals(0, locator.currentIndex());
        assertThrows(UnsupportedOperationException.class, () -> locator.matches().add(4));
    }

    private static Track track(String title, String artist) {
        return new Track(title + artist, title, artist, 180, "demo");
    }

    private static final class LocaleGuard {
        private static void runWithTurkishLocale(Runnable action) {
            var original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                action.run();
            } finally {
                Locale.setDefault(original);
            }
        }
    }
}
