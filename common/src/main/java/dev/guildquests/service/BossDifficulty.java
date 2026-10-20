package dev.guildquests.service;

/** Bounded combat math, independent of Minecraft so extreme modded stats can be tested. */
public final class BossDifficulty {
    private BossDifficulty() { }

    public record Profile(int guildLevel, double attack, double attackSpeed, double health,
                          double armor, double toughness, double beneficial, double harmful) {
        public double power() {
            // Attribute modifiers already include equipment, Strength, Weakness and modded stats.
            // Compress differences without removing the benefit of progression.
            double value = Math.pow(positive(attack, 1) / 7, .10)
                    * Math.pow(positive(attackSpeed, 4) / 4, .04)
                    * Math.pow(positive(health, 20) / 20, .06)
                    * Math.pow(1 + nonnegative(armor) / 20, .04)
                    * Math.pow(1 + nonnegative(toughness) / 8, .02)
                    * Math.pow(1 + Math.max(0, Math.min(20, guildLevel)), .04)
                    * clamp(1 + nonnegative(beneficial) * .02 - nonnegative(harmful) * .02, .8, 1.2);
            return clamp(value, .75, 1.75);
        }
    }

    public static int guildLevel(int completions, java.util.Collection<Integer> thresholds) {
        return (int) thresholds.stream().filter(java.util.Objects::nonNull)
                .filter(value -> value > 0 && completions >= value).distinct().count();
    }

    public static double outgoing(double raw, double expected, double bossHealth, int targetHits, Profile profile) {
        if (!(raw > 0) || !Double.isFinite(raw)) return 0;
        double quality = Math.sqrt(clamp(raw / positive(expected, 1), .01, 4));
        double unit = positive(bossHealth, 300) / Math.max(20, targetHits);
        return Math.min(unit * profile.power() * quality, unit * 2);
    }

    public static double incoming(double raw, double expected, double maxHealth, int survivalHits, Profile profile) {
        if (!(raw > 0) || !Double.isFinite(raw)) return 0;
        // Armor, Resistance, Protection and absorption have already reduced raw damage.
        // A small surviving hit stays small; defensive gear and effects remain useful.
        double quality = Math.pow(clamp(raw / positive(expected, 10), .0001, 4), .65);
        double pressure = clamp(1 - nonnegative(profile.harmful()) * .04, .6, 1);
        double health = positive(maxHealth, 20);
        return Math.min(health / Math.max(4, survivalHits) * quality * pressure, health / 4);
    }

    /** A per-player 20-tick window also bounds multi-hit weapons and bypassed cooldowns. */
    public static final class Budget {
        private long start = Long.MIN_VALUE;
        private double spent;

        public double take(long tick, double amount, double limit) {
            if (start == Long.MIN_VALUE || tick < start || tick - start >= 20) {
                start = tick;
                spent = 0;
            }
            double accepted = Math.max(0, Math.min(nonnegative(amount), nonnegative(limit) - spent));
            spent += accepted;
            return accepted;
        }
    }

    public static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private static double nonnegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
