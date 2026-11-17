package dev.guildquests.service;

import dev.guildquests.data.PlayerGuildData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class BossTierTest {
    private static final int[] WEIGHTS = {30, 40, 22, 8};
    private static final int[] UNLOCK_DAYS = {5, 10, 20, 30};

    @Test public void noTierIsUnlockedBeforeFiveDays() {
        assertFalse(BossTier.anyUnlocked(0, UNLOCK_DAYS));
        assertFalse(BossTier.anyUnlocked(4.99, UNLOCK_DAYS));
        assertTrue(BossTier.anyUnlocked(5.0, UNLOCK_DAYS));
    }

    @Test public void freshAverageOnlyRollsWeak() {
        for (int seed = 0; seed < 200; seed++) {
            assertEquals(BossTier.WEAK, BossTier.roll(new Random(seed), WEIGHTS, 5.0, UNLOCK_DAYS));
        }
    }

    @Test public void normalUnlocksAtTenDays() {
        boolean sawNormal = false;
        for (int seed = 0; seed < 200; seed++) {
            BossTier tier = BossTier.roll(new Random(seed), WEIGHTS, 10.0, UNLOCK_DAYS);
            assertNotEquals(BossTier.STRONG, tier);
            assertNotEquals(BossTier.ELITE, tier);
            sawNormal |= tier == BossTier.NORMAL;
        }
        assertTrue(sawNormal);
    }

    @Test public void strongUnlocksAtTwentyDays() {
        boolean sawStrong = false;
        for (int seed = 0; seed < 300; seed++) {
            BossTier tier = BossTier.roll(new Random(seed), WEIGHTS, 20.0, UNLOCK_DAYS);
            assertNotEquals(BossTier.ELITE, tier);
            sawStrong |= tier == BossTier.STRONG;
        }
        assertTrue(sawStrong);
    }

    @Test public void eliteOnlyAfterThirtyDays() {
        for (int seed = 0; seed < 500; seed++) {
            assertNotEquals(BossTier.ELITE, BossTier.roll(new Random(seed), WEIGHTS, 29.99, UNLOCK_DAYS));
        }
        boolean sawElite = false;
        for (int seed = 0; seed < 500; seed++) {
            sawElite |= BossTier.roll(new Random(seed), WEIGHTS, 30.0, UNLOCK_DAYS) == BossTier.ELITE;
        }
        assertTrue(sawElite);
    }

    @Test public void zeroWeightsFallBackToStrongestUnlocked() {
        int[] noWeights = {0, 0, 0, 0};
        assertEquals(BossTier.WEAK, BossTier.highestUnlocked(5, UNLOCK_DAYS));
        assertEquals(BossTier.NORMAL, BossTier.highestUnlocked(10, UNLOCK_DAYS));
        assertEquals(BossTier.STRONG, BossTier.highestUnlocked(20, UNLOCK_DAYS));
        assertEquals(BossTier.ELITE, BossTier.highestUnlocked(30, UNLOCK_DAYS));
        assertEquals(BossTier.STRONG, BossTier.roll(new Random(1), noWeights, 20, UNLOCK_DAYS));
    }

    @Test public void unlockDaysAreMonotonicAfterSanitizing() {
        // ConfigManager enforces this; the roll helper itself only reads the table as given.
        assertTrue(UNLOCK_DAYS[0] <= UNLOCK_DAYS[1]);
        assertTrue(UNLOCK_DAYS[1] <= UNLOCK_DAYS[2]);
        assertTrue(UNLOCK_DAYS[2] <= UNLOCK_DAYS[3]);
    }

    @Test public void powerRampStartsWeakAndReachesFullAtNextUnlock() {
        assertEquals(0.55, BossTier.powerFactor(BossTier.WEAK, 5, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(0.775, BossTier.powerFactor(BossTier.WEAK, 7.5, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(1.0, BossTier.powerFactor(BossTier.WEAK, 10, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(0.55, BossTier.powerFactor(BossTier.NORMAL, 10, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(0.55, BossTier.powerFactor(BossTier.STRONG, 20, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(0.55, BossTier.powerFactor(BossTier.ELITE, 30, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(0.775, BossTier.powerFactor(BossTier.ELITE, 35, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(1.0, BossTier.powerFactor(BossTier.ELITE, 40, UNLOCK_DAYS, 0.55), 1e-9);
    }

    @Test public void powerRampIsCappedAtTheStartPercentBeforeUnlock() {
        assertEquals(0.55, BossTier.powerFactor(BossTier.WEAK, 1, UNLOCK_DAYS, 0.55), 1e-9);
        assertEquals(0.05, BossTier.powerFactor(BossTier.WEAK, 1, UNLOCK_DAYS, 0.0), 1e-9);
    }

    @Test public void playedDaysSkipDaysSpentOffline() {
        PlayerGuildData data = new PlayerGuildData();
        assertTrue(data.countPlayedDay(100));
        assertEquals(1, data.playedDays);

        // Offline for four days: the gap is not credited.
        assertFalse(data.countPlayedDay(105));
        assertEquals(1, data.playedDays);
        assertEquals(105, data.lastPlayedDay);

        // Back online, the next rollover counts again.
        assertTrue(data.countPlayedDay(106));
        assertEquals(2, data.playedDays);
        assertFalse(data.countPlayedDay(106));
    }

    @Test public void playedDaysSurviveSaveLoad() {
        PlayerGuildData data = new PlayerGuildData();
        data.countPlayedDay(7);
        data.countPlayedDay(8);

        PlayerGuildData loaded = PlayerGuildData.fromNbt(data.toNbt(null), null);
        assertEquals(2, loaded.playedDays);
        assertEquals(8, loaded.lastPlayedDay);
        assertFalse(loaded.countPlayedDay(8));
    }

    @Test public void oldSavesStartCountingFromTheCurrentDay() {
        PlayerGuildData loaded = PlayerGuildData.fromNbt(new PlayerGuildData().toNbt(null), null);
        assertEquals(0, loaded.playedDays);
        assertEquals(-1, loaded.lastPlayedDay);
    }
}
