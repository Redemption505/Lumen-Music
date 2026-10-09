package com.lumenmusic;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectionSnapshotTest {
    private Track part(int page) {
        return new Track("bilibili:BV1ar4y1C7zq:p" + page, "P" + page, "作者", 180,
                "bilibili", null, "BV1ar4y1C7zq", page, null, 2);
    }

    @Test void reusesCompletePartsInPageOrderWithoutAnotherNetworkRequest() {
        Track first = part(1), second = part(2);
        assertEquals(List.of(first, second),
                MusicClient.completeCollection(List.of(second, first), second));
    }

    @Test void incompleteOrDuplicateResultsStillRequireCollectionResolution() {
        Track first = part(1), second = part(2);
        assertTrue(MusicClient.completeCollection(List.of(first), first).isEmpty());
        assertTrue(MusicClient.completeCollection(List.of(first, first), first).isEmpty());
        Track other = new Track("other", "其他视频", "作者", 180, "bilibili", null,
                "BV1xx411c7mD", 2, null, 2);
        assertTrue(MusicClient.completeCollection(List.of(first, other), first).isEmpty());
        assertEquals(List.of(first, second),
                MusicClient.completeCollection(List.of(first, other, second), first));
    }
}
