package dev.guildquests.service;

import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Difficulty tiers for emergency bosses. A tier defines how much health the boss gets, which
 * mobs can appear, how strong its gear and buffs are, and how much extra reward it pays out.
 * Weights are configured through {@code bossTierWeights} (one entry per tier, in declaration order).
 */
public enum BossTier {
    WEAK("弱", 0.5, 0.5, BossBarColor.BLUE, -1, -1, -1,
            List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.SKELETON, EntityType.STRAY, EntityType.SPIDER, EntityType.CAVE_SPIDER)),
    NORMAL("並", 1.0, 1.0, BossBarColor.RED, -1, -1, -1,
            List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER,
                    EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN,
                    EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
                    EntityType.EVOKER, EntityType.RAVAGER)),
    STRONG("強", 2.0, 2.0, BossBarColor.YELLOW, 0, 0, 0,
            List.of(EntityType.ENDERMAN, EntityType.SKELETON, EntityType.STRAY,
                    EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
                    EntityType.EVOKER, EntityType.RAVAGER)),
    ELITE("最強", 4.0, 5.0, BossBarColor.PURPLE, 1, 1, 0,
            List.of(EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER,
                    EntityType.WITCH)),
    WEAKEST("最弱", 0.25, 0.25, BossBarColor.WHITE, -1, -1, -1,
            List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.SKELETON, EntityType.STRAY, EntityType.SPIDER, EntityType.CAVE_SPIDER));

    private final String label;
    private final double healthMultiplier;
    private final double rewardMultiplier;
    private final BossBarColor barColor;
    private final int strengthAmplifier;
    private final int speedAmplifier;
    private final int resistanceAmplifier;
    private final List<EntityType<?>> mobs;

    BossTier(String label, double healthMultiplier, double rewardMultiplier, BossBarColor barColor,
             int strengthAmplifier, int speedAmplifier, int resistanceAmplifier, List<EntityType<?>> mobs) {
        this.label = label;
        this.healthMultiplier = healthMultiplier;
        this.rewardMultiplier = rewardMultiplier;
        this.barColor = barColor;
        this.strengthAmplifier = strengthAmplifier;
        this.speedAmplifier = speedAmplifier;
        this.resistanceAmplifier = resistanceAmplifier;
        this.mobs = mobs;
    }

    public String label() {
        return label;
    }

    public double healthMultiplier() {
        return healthMultiplier;
    }

    /** Attack damage grows with the square root of health, so elite bosses hit about twice as hard. */
    public double attackMultiplier() {
        return Math.sqrt(healthMultiplier);
    }

    /** Extra melee reach in blocks; weaker tiers keep the vanilla range. */
    public double reachBonus() {
        return Math.max(0, Math.min(2, healthMultiplier - 1));
    }

    /** Attack speed grows with the tier, so elite bosses swing roughly twice as often. */
    public double attackSpeedMultiplier() {
        return Math.min(2.0, 1.0 + (healthMultiplier - 1) * 0.33);
    }

    /** Minimum attack damage; only the elite tier reaches far enough to one-shot the ender dragon (200 HP). */
    public double attackFloor() {
        return this == ELITE ? 250.0 : 0.0;
    }

    /** Cost position of this tier in the protection price table (weakest to elite). */
    public int priceIndex() {
        return switch (this) {
            case WEAKEST -> 0;
            case WEAK -> 1;
            case NORMAL -> 2;
            case STRONG -> 3;
            case ELITE -> 4;
        };
    }

    /** Coin price to buy protection against this tier, from the configured table. */
    public int protectionPrice(int[] prices) {
        int index = priceIndex();
        return prices != null && index < prices.length ? Math.max(0, prices[index]) : 0;
    }

    /** Arrow damage: lower than melee, but still meaningful for every tier. */
    public double arrowDamage() {
        return switch (this) {
            case WEAKEST -> 3;
            case WEAK -> 5;
            case NORMAL -> 7;
            case STRONG -> 9;
            case ELITE -> 60;
        };
    }

    /** The weakest boss revives as the elite with this chance when killed. */
    public double promoteChance(double configured) {
        return this == WEAKEST ? Math.max(0, Math.min(1, configured)) : 0;
    }

    public double rewardMultiplier() {
        return rewardMultiplier;
    }

    public BossBarColor barColor() {
        return barColor;
    }

    public int strengthAmplifier() {
        return strengthAmplifier;
    }

    public int speedAmplifier() {
        return speedAmplifier;
    }

    public int resistanceAmplifier() {
        return resistanceAmplifier;
    }

    public EntityType<?> randomMob(Random random) {
        return mobs.get(random.nextInt(mobs.size()));
    }

    /** Command id: weak / normal / strong / elite. */
    public static BossTier byId(String id) {
        if (id == null) return null;
        for (BossTier tier : values()) {
            if (tier.name().toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT))) return tier;
        }
        return null;
    }

    /** Weighted roll. Missing or empty weights fall back to an equal distribution. */
    public static BossTier roll(Random random, int[] weights) {
        return roll(random, weights, Double.MAX_VALUE, null);
    }

    /**
     * Weighted roll limited to the tiers unlocked by progression. {@code unlockDays} is ordered
     * [weak, normal, strong, elite]; missing entries mean "no limit". When every unlocked tier
     * has weight 0, the strongest unlocked tier is returned instead of an early-game tier.
     */
    public static BossTier roll(Random random, int[] weights, double playedDays, int[] unlockDays) {
        BossTier[] rollable = rollable();
        int[] effective = new int[rollable.length];
        int total = 0;
        for (int i = 0; i < rollable.length; i++) {
            if (isUnlocked(i, playedDays, unlockDays)) {
                effective[i] = weight(weights, i);
                total += effective[i];
            }
        }
        if (total <= 0) return highestUnlocked(playedDays, unlockDays);
        int pick = random.nextInt(total);
        for (int i = 0; i < rollable.length; i++) {
            pick -= effective[i];
            if (pick < 0) return rollable[i];
        }
        return WEAK;
    }

    /** Tiers reachable by a random roll. WEAKEST only appears through the promotion rule. */
    public static BossTier[] rollable() {
        return new BossTier[]{WEAK, NORMAL, STRONG, ELITE};
    }

    /** True when the tier at {@code index} (weak..elite) may appear at this progression. */
    public static boolean isUnlocked(int index, double playedDays, int[] unlockDays) {
        if (unlockDays == null || index >= unlockDays.length) return true;
        return playedDays >= Math.max(0, unlockDays[index]);
    }

    /** True when this tier may appear at the given average played days. */
    public static boolean isUnlocked(BossTier tier, double playedDays, int[] unlockDays) {
        int index = tier.rollIndex();
        return index < 0 || isUnlocked(index, playedDays, unlockDays);
    }

    /** Strongest tier whose unlock day count has been reached (never null). */
    public static BossTier highestUnlocked(double playedDays, int[] unlockDays) {
        BossTier best = WEAK;
        BossTier[] rollable = rollable();
        for (int i = 0; i < rollable.length; i++) {
            if (isUnlocked(i, playedDays, unlockDays)) best = rollable[i];
        }
        return best;
    }

    /** True when at least one rollable tier is unlocked at this progression. */
    public static boolean anyUnlocked(double playedDays, int[] unlockDays) {
        BossTier[] rollable = rollable();
        for (int i = 0; i < rollable.length; i++) {
            if (isUnlocked(i, playedDays, unlockDays)) return true;
        }
        return false;
    }

    /**
     * Power multiplier for a tier: {@code startPercent} on its unlock day, rising linearly to
     * 1.0 when the next tier unlocks (elite uses the gap of the tier before it). A freshly
     * unlocked tier therefore never arrives at full strength.
     */
    public static double powerFactor(BossTier tier, double playedDays, int[] unlockDays, double startPercent) {
        int index = tier.rollIndex();
        if (index < 0) return 1.0;

        int start = unlockDay(unlockDays, index);
        int end;
        if (unlockDays != null && index + 1 < unlockDays.length) {
            end = unlockDay(unlockDays, index + 1);
        } else {
            int previous = index > 0 ? unlockDay(unlockDays, index - 1) : start;
            end = start + Math.max(1, start - previous);
        }

        double floor = Math.max(0.05, Math.min(1.0, startPercent));
        if (end <= start || playedDays >= end) return 1.0;
        if (playedDays <= start) return floor;
        return floor + (1.0 - floor) * ((playedDays - start) / (double) (end - start));
    }

    private static int unlockDay(int[] unlockDays, int index) {
        if (unlockDays == null || index >= unlockDays.length) return 0;
        return Math.max(0, unlockDays[index]);
    }

    /** Position in the rollable list (weak..elite), or -1 for WEAKEST. */
    public int rollIndex() {
        return switch (this) {
            case WEAKEST -> -1;
            case WEAK -> 0;
            case NORMAL -> 1;
            case STRONG -> 2;
            case ELITE -> 3;
        };
    }

    private static int weight(int[] weights, int index) {
        if (weights == null || index >= weights.length) return 0;
        return Math.max(0, Math.min(1000, weights[index]));
    }

    /** Restores the tier of a boss that survived a restart, based on its display name. */
    public static BossTier fromDisplayName(String name) {
        if (name != null) {
            for (BossTier tier : values()) {
                if (name.startsWith("【緊急ボス・" + tier.label + "】")) return tier;
            }
        }
        return NORMAL;
    }
}
