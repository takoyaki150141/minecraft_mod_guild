package dev.guildquests.service;

/** Implemented on vanilla Projectile, including modded subclasses such as Botania's weapons. */
public interface BossProjectileData {
    BossAttackSnapshot guildquests$attackSnapshot();
    void guildquests$attackSnapshot(BossAttackSnapshot snapshot);
}
