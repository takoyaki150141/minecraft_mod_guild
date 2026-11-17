package dev.guildquests.gui;

import dev.guildquests.client.gui.GamblingLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GamblingLayoutTest {
    @Test public void playAreaAndControlsStayVisibleWithoutOverlapping() {
        for (int[] size : new int[][]{{320,180},{320,240},{427,240},{480,270},{640,391},{854,480},{1920,1080},{3440,1440}}) {
            var l = GamblingLayout.of(size[0], size[1]);
            assertTrue(l.left() >= 0); assertTrue(l.right() <= size[0]);
            assertTrue(l.titleY() >= 0); assertTrue(l.titleY() + 9 < l.boardY());
            assertTrue(l.boardHeight() >= 70);
            assertTrue(l.boardBottom() + 6 <= l.summaryY());
            assertTrue(l.summaryY() + 9 < l.actionY());
            assertTrue(l.actionY() + 20 < l.footerY());
            assertTrue(l.footerY() + 20 <= size[1]);
            if (!l.compact()) {
                assertTrue(l.boardBottom() + 6 <= l.betY());
                assertTrue(l.betY() + 20 < l.summaryY());
            }
        }
    }
}
