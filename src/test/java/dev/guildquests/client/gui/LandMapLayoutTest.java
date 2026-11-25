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
        assertTrue(large.cell() >= 4);
        assertTrue(large.gridWidth() <= 320);
        assertTrue(large.gridBottom() <= large.infoTop());
        assertEquals(17, large.side());

        LandMapLayout small = LandMapLayout.of(1920, 1080, 0);
        assertEquals(1, small.radius());
    }

    @Test
    void narrowWindowKeepsTheTextAreaWide() {
        LandMapLayout layout = LandMapLayout.of(320, 200, 4);
        // The text uses the window width, not the much narrower grid.
        assertTrue(layout.contentWidth() >= 280, "contentWidth=" + layout.contentWidth());
        assertTrue(layout.contentWidth() > layout.gridWidth());
        assertEquals(layout.contentLeft() * 2 + layout.contentWidth(), 320);
    }

    @Test
    void infoAndButtonsStayInsideTheWindow() {
        for (int width : new int[]{200, 256, 320, 640, 1280}) {
            for (int height : new int[]{140, 160, 200, 360, 720}) {
                LandMapLayout layout = LandMapLayout.of(width, height, 4, 5);
                String label = width + "x" + height;
                assertTrue(layout.infoTop() + layout.infoHeight() <= layout.buttonY(), label);
                assertTrue(layout.buttonY() + LandMapLayout.BUTTON_HEIGHT <= Math.max(height, 180), label);
                assertTrue(layout.gridBottom() <= layout.infoTop(), label);
                assertTrue(layout.left() >= 0 && layout.left() + layout.gridWidth() <= width, label);
            }
        }
    }
}
