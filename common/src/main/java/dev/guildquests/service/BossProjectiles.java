package dev.guildquests.service;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BossProjectiles {
    /** Ranged damage is independent of the currently held melee weapon's attack attribute. */
    public static final double REFERENCE_DAMAGE = 20;
    private static final UUID UNKNOWN_LIFE = new UUID(0, 0);
    private record Life(WeakReference<ServerPlayer> player, UUID token) { }
    private static final Map<UUID, Life> lives = new HashMap<>();
    private static final ThreadLocal<Projectile> ticking = new ThreadLocal<>();

    private BossProjectiles() { }

    private static UUID life(ServerPlayer player) {
        Life life = lives.get(player.getUUID());
        // Entity.equals compares runtime IDs, which Minecraft can reuse on respawn. Compare identity.
        if (life == null || life.player().get() != player) {
            life = new Life(new WeakReference<>(player), UUID.randomUUID());
            lives.put(player.getUUID(), life);
        }
        return life.token();
    }

    public static void assigned(Projectile projectile, Entity owner) {
        if (projectile.level().isClientSide) return;
        BossProjectileData data = (BossProjectileData) projectile;
        if (!(BossCombat.owner(owner) instanceof ServerPlayer player)) {
            data.guildquests$attackSnapshot(null);
        } else if (data.guildquests$attackSnapshot() == null
                || !data.guildquests$attackSnapshot().owner().equals(player.getUUID())) {
            data.guildquests$attackSnapshot(new BossAttackSnapshot(player.getUUID(), life(player), BossCombat.profile(player)));
        }
    }

    private static BossAttackSnapshot snapshot(Projectile projectile, ServerPlayer player) {
        BossProjectileData data = (BossProjectileData) projectile;
        BossAttackSnapshot snapshot = data.guildquests$attackSnapshot();
        if (snapshot == null || !snapshot.owner().equals(player.getUUID())) {
            // Old saves without a snapshot are frozen at first observation, never an intent to end respawn grace.
            snapshot = new BossAttackSnapshot(player.getUUID(), UNKNOWN_LIFE, BossCombat.profile(player));
            data.guildquests$attackSnapshot(snapshot);
        }
        return snapshot;
    }

    /** Some mods (including King's Law direct hits) report playerAttack and omit the projectile. */
    public static void tick(Entity entity) {
        if (!(entity instanceof Projectile projectile) || !(BossCombat.owner(projectile) instanceof ServerPlayer player)) {
            entity.tick();
            return;
        }
        snapshot(projectile, player);
        Projectile previous = ticking.get();
        ticking.set(projectile);
        try { entity.tick(); }
        finally {
            if (previous == null) ticking.remove(); else ticking.set(previous);
        }
    }

    public static BossAttackSnapshot attack(DamageSource source, ServerPlayer player) {
        if (source.getDirectEntity() instanceof Projectile projectile
                && BossCombat.owner(projectile) instanceof ServerPlayer owner && owner.getUUID().equals(player.getUUID())) {
            return snapshot(projectile, player);
        }
        Projectile current = ticking.get();
        if (current != null && source.is(DamageTypes.PLAYER_ATTACK)
                && source.getEntity() == player && source.getDirectEntity() == player
                && BossCombat.owner(current) instanceof ServerPlayer owner && owner.getUUID().equals(player.getUUID())) {
            return snapshot(current, player);
        }
        return null;
    }

    public static boolean ranged(DamageSource source, ServerPlayer player, BossAttackSnapshot snapshot) {
        return snapshot != null || source.getDirectEntity() != player
                || source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypeTags.IS_EXPLOSION);
    }

    public static boolean intentional(DamageSource source, ServerPlayer player) {
        if (!player.isAlive()) return false;
        BossAttackSnapshot snapshot = attack(source, player);
        return snapshot == null || snapshot.life().equals(life(player));
    }

    public static void reset() {
        ticking.remove();
        lives.clear();
    }

    public static void clean() {
        lives.values().removeIf(life -> life.player().get() == null);
    }
}
