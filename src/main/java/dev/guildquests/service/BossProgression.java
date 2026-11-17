package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Random;

/**
 * Tracks how many in-game days each player has been online for. The average across the players
 * online at spawn time decides which emergency-boss tiers may appear, so a fresh server never
 * starts with the strongest boss.
 */
public final class BossProgression {
    /** Vanilla day length in ticks. */
    private static final long TICKS_PER_DAY = 24000L;
    /** A day rolls over once every 24000 ticks; sampling once per second is plenty. */
    private static final int SAMPLE_INTERVAL = 20;

    private static int ticker;

    private BossProgression() {
    }

    /** Counts a new played day for every online player when the in-game day rolls over. */
    public static void tick(MinecraftServer server) {
        if (++ticker < SAMPLE_INTERVAL) return;
        ticker = 0;

        GuildPersistentState state = GuildPersistentState.get(server);
        // The overworld clock is the shared one; other dimensions keep their own static time.
        long day = server.overworld().getDayTime() / TICKS_PER_DAY;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerGuildData data = state.get(player.getUUID());
            if (data.countPlayedDay(day)) {
                state.setDirty();
            }
        }
    }

    /** Average played days across online players; 0 when nobody is online. */
    public static double averagePlayedDays(MinecraftServer server) {
        GuildPersistentState state = GuildPersistentState.get(server);
        int sum = 0;
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sum += Math.max(0, state.get(player.getUUID()).playedDays);
            count++;
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    /**
     * Rolls a tier for a new boss. With progression enabled the weights only apply to tiers
     * unlocked at the current average; otherwise the old behaviour is kept.
     */
    public static BossTier roll(MinecraftServer server, Random random) {
        ModConfig config = ConfigManager.config();
        if (!config.bossDifficultyProgression) {
            return BossTier.roll(random, config.bossTierWeights);
        }
        return BossTier.roll(random, config.bossTierWeights,
                averagePlayedDays(server), config.bossTierUnlockDays);
    }

    /**
     * True when the average has reached the first unlock day. Before that no boss spawns at all,
     * so a fresh server never gets an emergency boss in its first days.
     */
    public static boolean canSpawn(MinecraftServer server) {
        ModConfig config = ConfigManager.config();
        if (!config.bossDifficultyProgression) return true;
        return BossTier.anyUnlocked(averagePlayedDays(server), config.bossTierUnlockDays);
    }

    /** True when the given tier is unlocked at the current average. */
    public static boolean isUnlocked(MinecraftServer server, BossTier tier) {
        ModConfig config = ConfigManager.config();
        if (!config.bossDifficultyProgression) return true;
        return BossTier.isUnlocked(tier, averagePlayedDays(server), config.bossTierUnlockDays);
    }

    /**
     * Power multiplier for a tier at the current average. 1.0 when progression is disabled.
     * A tier unlocked today fights at {@code bossTierRampStartPercent} and grows to full.
     */
    public static double powerFactor(MinecraftServer server, BossTier tier) {
        ModConfig config = ConfigManager.config();
        if (!config.bossDifficultyProgression) return 1.0;
        return BossTier.powerFactor(tier, averagePlayedDays(server), config.bossTierUnlockDays,
                config.bossTierRampStartPercent / 100.0);
    }

    /** Admin-facing summary of the current progression and unlocked tiers. */
    public static String describeProgression(MinecraftServer server) {
        ModConfig config = ConfigManager.config();
        double average = averagePlayedDays(server);
        StringBuilder out = new StringBuilder();
        out.append("Average played days: ").append(String.format(java.util.Locale.ROOT, "%.2f", average));
        if (server.getPlayerList().getPlayers().isEmpty()) {
            out.append(" (no players online)");
        }
        if (!config.bossDifficultyProgression) {
            return out.append(" (progression disabled; all tiers available)").toString();
        }
        int[] days = config.bossTierUnlockDays;
        out.append(" | unlock days: 弱").append(days[0])
                .append(" / 並").append(days[1])
                .append(" / 強").append(days[2])
                .append(" / 最強").append(days[3])
                .append(" | ramp ").append(config.bossTierRampStartPercent).append("%→100%")
                .append(" | unlocked: ");
        StringBuilder unlocked = new StringBuilder();
        for (BossTier tier : BossTier.rollable()) {
            if (BossTier.isUnlocked(tier, average, days)) {
                if (unlocked.length() > 0) unlocked.append(", ");
                unlocked.append(tier.label());
            }
        }
        return out.append(unlocked.length() == 0 ? "なし" : unlocked.toString()).toString();
    }
}
