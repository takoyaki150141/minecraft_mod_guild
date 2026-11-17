package dev.guildquests.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GuildGamblingViewTest {
    @Test public void faceDownCardsNeverContainTheirRankOrSuit() {
        var hidden = GuildGamblingView.card("K", "♥", true);
        assertTrue(hidden.getBoolean("hidden"));
        assertEquals(java.util.Set.of("hidden"), hidden.getAllKeys());
    }

    @Test public void allFiftyTwoFacesKeepTheirActualRankAndSuit() {
        for (String suit : new String[]{"♠", "♥", "♦", "♣"})
            for (String rank : new String[]{"A","2","3","4","5","6","7","8","9","10","J","Q","K"}) {
                var face = GuildGamblingView.card(rank, suit, false);
                assertFalse(face.getBoolean("hidden"));
                assertEquals(rank, face.getString("rank"));
                assertEquals(suit, face.getString("suit"));
            }
    }

    @Test public void animationDoesNotPublishTheAlreadySettledOutcome() {
        for (String phase : new String[]{"BETTING", "PLAYER_TURN", "SPINNING", "FLIPPING", "RACING"}) {
            var view = GuildGamblingView.round(phase, 10, 999, "勝ち", 800);
            assertFalse(view.contains("result")); assertFalse(view.contains("payout"));
            assertEquals(10, view.getLong("bet")); assertEquals(999, view.getLong("balance"));
        }
        var done = GuildGamblingView.round("DONE", 10, 999, "勝ち", 800);
        assertEquals("勝ち", done.getString("result")); assertEquals(800, done.getLong("payout"));
    }

    @Test public void reelsRevealOnlyAsEachServerStopIsReached() {
        int[] reels = {2, 1, 5}, stops = {18, 26, 34};
        assertArrayEquals(new int[]{-1,-1,-1}, GuildGamblingView.visibleReels(reels,17,stops,true,false));
        assertArrayEquals(new int[]{2,-1,-1}, GuildGamblingView.visibleReels(reels,18,stops,true,false));
        assertArrayEquals(new int[]{2,1,-1}, GuildGamblingView.visibleReels(reels,26,stops,true,false));
        assertArrayEquals(reels, GuildGamblingView.visibleReels(reels,34,stops,true,false));
        assertArrayEquals(reels, GuildGamblingView.visibleReels(reels,0,stops,false,true));
        assertArrayEquals(new int[]{-1,-1,-1}, GuildGamblingView.visibleReels(reels,99,stops,false,false));
    }
}
