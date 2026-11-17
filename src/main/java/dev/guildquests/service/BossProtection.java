package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.effect.ModEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Respawn protection for players killed during an emergency-boss fight.
 * <p>
 * The protected state is <b>the {@code guildquests:boss_protection} effect itself</b>: the HUD
 * countdown and every gameplay rule (damage immunity, AI target filtering, spawn anchoring) read
 * the same effect, so they cannot drift apart. The effect ends when the player attacks a boss,
 * when no boss is within {@code bossProtectionReleaseRange} for a few seconds, or when its
 * duration runs out.
 */
public final class BossProtection {
    /** How long a dead player may take to respawn before the queued protection is dropped. */
    private static final int RESPAWN_QUEUE_TICKS = 1200;
    /** Consecutive checks (once per second) without a nearby boss before the effect is removed. */
    private static final int OUT_OF_RANGE_CHECKS = 10;
    private static final int CHECK_INTERVAL = 20;

    private static final Map<UUID, Integer> pendingRespawn = new HashMap<>();
    private static final Map<UUID, Integer> lastBossHit = new HashMap<>();
    private static final Map<UUID, Integer> outOfRange = new HashMap<>();

    private BossProtection() {
    }

    /** The single protection predicate used by every gameplay rule. */
    public static boolean isProtected(ServerPlayer player) {
        return player != null && player.hasEffect(ModEffects.bossProtection());
    }

    /** Applies (or extends) the protection effect and shows the remaining time. */
    public static void grant(ServerPlayer player) {
        int seconds = ConfigManager.config().bossProtectionSeconds;
        if (seconds <= 0) return;
        int duration = seconds * 20;
        MobEffectInstance current = player.getEffect(ModEffects.bossProtection());
        if (current != null) duration = Math.max(duration, current.getDuration());
        player.addEffect(new MobEffectInstance(ModEffects.bossProtection(), duration, 0, false, true, true));
        GuildService.message(player, "緊急ボス保護: " + (duration / 20) + "秒（ボスを攻撃すると解除）",
                ChatFormatting.AQUA);
    }

    /** Removes the effect; called when the player attacks a boss or leaves the area. */
    public static void revoke(ServerPlayer player) {
        if (player.removeEffect(ModEffects.bossProtection())) {
            GuildService.message(player, "緊急ボス保護を解除しました。", ChatFormatting.GRAY);
        }
        outOfRange.remove(player.getUUID());
    }

    /** True when the damage originates from an emergency boss (directly or through a projectile/pet). */
    public static boolean isBossDamage(DamageSource source) {
        if (source == null) return false;
        return BossEvent.isBoss(source.getEntity())
                || BossEvent.isBoss(source.getDirectEntity())
                || BossEvent.isBoss(attacker(source));
    }

    /** Resolves who is responsible for a damage source, walking projectile/pet owner chains. */
    public static Entity attacker(DamageSource source) {
        Entity resolved = owner(source.getEntity());
        if (resolved == null) resolved = owner(source.getDirectEntity());
        return resolved;
    }

    private static Entity owner(Entity entity) {
        for (int i = 0; entity != null && i < 4; i++) {
            if (BossEvent.isBoss(entity) || entity instanceof ServerPlayer) return entity;
            Entity next = entity instanceof Projectile projectile ? projectile.getOwner()
                    : entity instanceof OwnableEntity ownable ? ownable.getOwner()
                    : entity instanceof net.minecraft.world.entity.TraceableEntity traceable ? traceable.getOwner()
                    : null;
            if (next == null || next == entity) return entity;
            entity = next;
        }
        return entity;
    }

    /** Remembers that a boss damaged this player, so a later death counts as a boss death. */
    public static void noteBossHit(ServerPlayer player) {
        if (player.getServer() == null) return;
        lastBossHit.put(player.getUUID(), player.getServer().getTickCount());
    }

    public static void onPlayerDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer player) || player.getServer() == null) return;
        int now = player.getServer().getTickCount();
        int last = lastBossHit.getOrDefault(player.getUUID(), Integer.MIN_VALUE);
        if (isBossDamage(source) || (long) now - last <= 100) {
            pendingRespawn.put(player.getUUID(), now);
        }
    }

    /** Runs every server tick: applies queued protection on respawn and expires it safely. */
    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % CHECK_INTERVAL != 0) return;

        pendingRespawn.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) {
                return now - (long) entry.getValue() > RESPAWN_QUEUE_TICKS;
            }
            grant(player);
            return true;
        });

        int range = Math.max(1, ConfigManager.config().bossProtectionReleaseRange);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isProtected(player)) {
                outOfRange.remove(player.getUUID());
                continue;
            }
            // Boss DoT (fire aspect, witch poison) has no entity on its later ticks, so clear it
            // while the effect is active instead of relying on the damage-source filter.
            if (player.isOnFire()) player.clearFire();
            player.removeEffect(net.minecraft.world.effect.MobEffects.POISON);
            player.removeEffect(net.minecraft.world.effect.MobEffects.WITHER);
            if (BossEvent.hasBossWithin(player, range)) {
                outOfRange.remove(player.getUUID());
                continue;
            }
            int checks = outOfRange.merge(player.getUUID(), 1, Integer::sum);
            if (checks >= OUT_OF_RANGE_CHECKS) {
                revoke(player);
            }
        }

        lastBossHit.entrySet().removeIf(entry -> now - (long) entry.getValue() > RESPAWN_QUEUE_TICKS);
    }

    /** Clears transient state on shutdown; effects themselves are saved with the player. */
    public static void reset() {
        pendingRespawn.clear();
        lastBossHit.clear();
        outOfRange.clear();
    }
}
