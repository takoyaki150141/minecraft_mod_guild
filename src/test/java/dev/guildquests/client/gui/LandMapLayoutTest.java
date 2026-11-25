package dev.guildquests.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandMapLayoutTest {
    @Test
    void hitTestMapsCellsAndRejectsOutside() {
        LandMapLayout layout = LandMapLayout.of(800, 480, 4);
        int firstX = layout.cellX(0);
        int firstY = layout.cellY(0);
        assertEquals(0, layout.indexAt(firstX + 1, firstY + 1));
        assertEquals(-1, layout.indexAt(layout.left() - 1, firstY + 1));
        assertEquals(-1, layout.indexAt(firstX + 1, layout.top() - 1));
        assertEquals(-1, layout.indexAt(layout.left() + layout.gridWidth() + 1, firstY + 1));
        int last = layout.side() * layout.side() - 1;
        assertEquals(last, layout.indexAt(layout.cellX(last) + 1, layout.cellY(last) + 1));
    }

    @Test
    void radiusIsClampedAndGridFitsTheScreen() {
        LandMapLayout large = LandMapLayout.of(320, 200, 99);
        assertEquals(8, large.radius());
        assertTrue(large.cell() >= 8);
        assertTrue(large.gridWidth() <= 320);
        assertEquals(17, large.side());

        LandMapLayout small = LandMapLayout.of(1920, 1080, 0);
        assertEquals(1, small.radius());
    }
}
