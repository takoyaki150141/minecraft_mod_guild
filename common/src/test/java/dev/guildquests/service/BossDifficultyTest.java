package dev.guildquests.service;

import org.junit.Test;

import static org.junit.Assert.*;

public class BossDifficultyTest {
    private static final BossDifficulty.Profile NEW_PLAYER = new BossDifficulty.Profile(0, 1, 4, 20, 0, 0, 0, 0);
    private static final BossDifficulty.Profile VETERAN = new BossDifficulty.Profile(5, 750000, 20, 5000, 100, 40, 10, 0);

    @Test public void threeMillionHealthIsPlayableForBothExtremes() {
        double rookie = BossDifficulty.outgoing(.1, .1, 3_000_000, 80, NEW_PLAYER);
        double veteran = BossDifficulty.outgoing(750000, 750000, 3_000_000, 80, VETERAN);
        assertTrue("a beginner can finish without hundreds of thousands of hits", rookie >= 3_000_000.0 / 107);
        assertTrue("progression still improves damage", veteran > rookie);
        assertTrue("four veteran hits cannot end the encounter", veteran * 4 < 3_000_000);
        assertTrue("unexpected mod damage is bounded on the first hit",
                BossDifficulty.outgoing(1e30, .1, 3_000_000, 80, NEW_PLAYER) <= 75000);
    }

    @Test public void mitigationAndAttackQualityStillMatter() {
        double full = BossDifficulty.outgoing(10, 10, 300, 80, NEW_PLAYER);
        assertTrue(BossDifficulty.outgoing(1, 10, 300, 80, NEW_PLAYER) < full);
        double naked = BossDifficulty.incoming(15, 15, 20, 6, NEW_PLAYER);
        double armored = BossDifficulty.incoming(2, 15, 20, 6, NEW_PLAYER);
        assertTrue(armored < naked);
        assertEquals(0, BossDifficulty.incoming(0, 15, 20, 6, NEW_PLAYER), 0);
        assertEquals(0, BossDifficulty.outgoing(0, 10, 300, 80, NEW_PLAYER), 0);
    }

    @Test public void burstDamageCannotOneShotAnyHealthTier() {
        for (double health : new double[]{20, 1000, 1e9}) {
            assertTrue(BossDifficulty.incoming(1e30, 10, health, 6, VETERAN) <= health / 4);
        }
        BossDifficulty.Budget budget = new BossDifficulty.Budget();
        double total = 0;
        for (int i = 0; i < 1000; i++) total += budget.take(100, 75000, 75000);
        assertEquals(75000, total, .001);
        assertEquals(0, budget.take(119, 75000, 75000), 0);
        assertEquals(75000, budget.take(120, 75000, 75000), 0);
    }

    @Test public void levelEquipmentAndEffectsContributeWithoutDominating() {
        assertTrue(VETERAN.power() > NEW_PLAYER.power());
        var buff = new BossDifficulty.Profile(0, 1, 4, 20, 0, 0, 3, 0);
        var debuff = new BossDifficulty.Profile(0, 1, 4, 20, 0, 0, 0, 3);
        assertTrue(buff.power() > NEW_PLAYER.power());
        assertTrue(debuff.power() < NEW_PLAYER.power());
        assertTrue(BossDifficulty.incoming(10, 10, 20, 6, debuff)
                < BossDifficulty.incoming(10, 10, 20, 6, NEW_PLAYER));
    }

    @Test public void guildProgressUsesConfiguredQuestRanks() {
        var thresholds = java.util.List.of(80, 0, 15, 5, 30, 50, 50);
        assertEquals(0, BossDifficulty.guildLevel(0, thresholds));
        assertEquals(1, BossDifficulty.guildLevel(5, thresholds));
        assertEquals(5, BossDifficulty.guildLevel(80, thresholds));
        assertEquals(5, BossDifficulty.guildLevel(Integer.MAX_VALUE, thresholds));
        var ranked = new BossDifficulty.Profile(5, 1, 4, 20, 0, 0, 0, 0);
        assertTrue(ranked.power() > NEW_PLAYER.power());
        assertTrue(ranked.power() < NEW_PLAYER.power() * 1.1);
    }

    @Test public void invalidModNumbersAndClockResetStayBounded() {
        var invalid = new BossDifficulty.Profile(Integer.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NaN, Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, Double.POSITIVE_INFINITY);
        assertTrue(Double.isFinite(invalid.power()));
        assertEquals(0, BossDifficulty.outgoing(Double.NaN, 1, 300, 80, invalid), 0);
        assertEquals(0, BossDifficulty.incoming(Double.POSITIVE_INFINITY, 1, 20, 6, invalid), 0);
        assertTrue(Double.isFinite(BossDifficulty.outgoing(10, Double.NaN, 300, 80, invalid)));
        var budget = new BossDifficulty.Budget();
        assertEquals(10, budget.take(1000, 10, 10), 0);
        assertEquals(10, budget.take(1, 10, 10), 0);
    }

    @Test public void windowBoundaryCannotDoubleBurstDamage() {
        var budget = new BossDifficulty.Budget();
        assertEquals(1, budget.take(100, 1, 75000), 0);
        assertEquals(74999, budget.take(119, 75000, 75000), 0);
        assertEquals(1, budget.take(120, 75000, 75000), 0);
        assertEquals(0, budget.take(138, 75000, 75000), 0);
        assertEquals(74999, budget.take(139, 75000, 75000), 0);
    }

    @Test public void everySlidingSecondStaysWithinLimitUnderSustainedSpam() {
        var budget = new BossDifficulty.Budget();
        double[] totals = new double[200];
        for (int tick = 0; tick < totals.length; tick++) {
            for (int hit = 0; hit < 100; hit++) totals[tick] += budget.take(tick, tick % 3 == 0 ? 1 : 1e30, 75000);
            double window = 0;
            for (int recent = Math.max(0, tick - 19); recent <= tick; recent++) window += totals[recent];
            assertTrue("sliding second at tick " + tick, window <= 75000 + 1e-6);
        }
    }

    @Test public void invalidNumbersAndLimitChangesCannotCreateCredit() {
        var budget = new BossDifficulty.Budget();
        assertEquals(0, budget.take(1, Double.POSITIVE_INFINITY, 10), 0);
        assertEquals(0, budget.take(1, -10, 10), 0);
        assertEquals(8, budget.take(1, 8, 10), 0);
        assertEquals(0, budget.take(2, 10, 5), 0);
        assertEquals(2, budget.take(2, 10, 10), 0);
        assertEquals(8, budget.take(21, 10, 10), 0);
        assertEquals(0, budget.take(21, 10, Double.NaN), 0);
    }
}
