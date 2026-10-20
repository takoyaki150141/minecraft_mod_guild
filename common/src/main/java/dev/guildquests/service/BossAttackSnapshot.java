package dev.guildquests.service;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Immutable offensive context. No entity/item references, safe to persist with a projectile. */
public record BossAttackSnapshot(UUID owner, UUID life, BossDifficulty.Profile profile) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putUUID("owner", owner); tag.putUUID("life", life);
        tag.putInt("rank", profile.guildLevel());
        tag.putDouble("attack", profile.attack()); tag.putDouble("speed", profile.attackSpeed());
        tag.putDouble("health", profile.health()); tag.putDouble("armor", profile.armor());
        tag.putDouble("toughness", profile.toughness());
        tag.putDouble("beneficial", profile.beneficial()); tag.putDouble("harmful", profile.harmful());
        return tag;
    }

    public static BossAttackSnapshot load(CompoundTag tag) {
        if (tag.getInt("version") != 1 || !tag.hasUUID("owner") || !tag.hasUUID("life")) return null;
        for (String name : new String[]{"attack", "speed", "health", "armor", "toughness", "beneficial", "harmful"}) {
            if (!tag.contains(name, 99) || !Double.isFinite(tag.getDouble(name))) return null;
        }
        return new BossAttackSnapshot(tag.getUUID("owner"), tag.getUUID("life"),
                new BossDifficulty.Profile(tag.getInt("rank"), tag.getDouble("attack"), tag.getDouble("speed"),
                        tag.getDouble("health"), tag.getDouble("armor"), tag.getDouble("toughness"),
                        tag.getDouble("beneficial"), tag.getDouble("harmful")));
    }
}
