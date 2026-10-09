package com.lumenmusic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.lumenmusic.WindowResize.Edge.*;

final class WindowResizeTest {
    private final WindowResize.Bounds initial = new WindowResize.Bounds(100, 80, 1000, 700);

    @Test void detectsAllEdgesAndKeepsInteriorAndOutsideClear() {
        assertEquals(NW, WindowResize.edgeAt(2, 2, 1000, 700));
        assertEquals(N, WindowResize.edgeAt(500, 2, 1000, 700));
        assertEquals(NE, WindowResize.edgeAt(998, 2, 1000, 700));
        assertEquals(E, WindowResize.edgeAt(998, 350, 1000, 700));
        assertEquals(SE, WindowResize.edgeAt(998, 698, 1000, 700));
        assertEquals(S, WindowResize.edgeAt(500, 698, 1000, 700));
        assertEquals(SW, WindowResize.edgeAt(2, 698, 1000, 700));
        assertEquals(W, WindowResize.edgeAt(2, 350, 1000, 700));
        assertEquals(NONE, WindowResize.edgeAt(8, 8, 1000, 700));
        assertEquals(NONE, WindowResize.edgeAt(-1, 2, 1000, 700));
        assertEquals(NONE, WindowResize.edgeAt(1001, 350, 1000, 700));
    }

    @Test void everyDirectionChangesOnlyItsDraggedSides() {
        for (var edge : WindowResize.Edge.values()) {
            var changed = resize(edge, 40, 25);
            assertEquals(edge.west ? 140 : 100, changed.x());
            assertEquals(edge.north ? 105 : 80, changed.y());
            assertEquals(edge.west ? 960 : edge.east ? 1040 : 1000, changed.width());
            assertEquals(edge.north ? 675 : edge.south ? 725 : 700, changed.height());
            if (edge.west) assertEquals(1100, changed.x() + changed.width());
            if (edge.north) assertEquals(780, changed.y() + changed.height());
        }
    }

    @Test void clampsBothDimensionsAndKeepsOppositeCornerFixed() {
        var smallest = resize(NW, 2000, 2000);
        assertEquals(new WindowResize.Bounds(240, 180, 860, 600), smallest);
        var largest = WindowResize.resize(initial, NW, -2000, -2000, 860, 600, 1400, 900);
        assertEquals(new WindowResize.Bounds(-300, -120, 1400, 900), largest);
        assertEquals(new WindowResize.Bounds(100, 80, 860, 600), resize(SE, -2000, -2000));
    }

    private WindowResize.Bounds resize(WindowResize.Edge edge, double dx, double dy) {
        return WindowResize.resize(initial, edge, dx, dy, 860, 600, Double.MAX_VALUE, Double.MAX_VALUE);
    }
}
