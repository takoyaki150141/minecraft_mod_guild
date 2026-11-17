package dev.guildquests.service;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Extra melee reach granted to emergency bosses; read by {@code BossReachMixin}. */
public final class BossReach {
    private static final Map<UUID, Double> BONUS = new ConcurrentHashMap<>();

    private BossReach() {
    }

    public static void set(LivingEntity entity, double bonus) {
        if (entity == null) {
            return;
        }
        if (bonus > 0) {
            BONUS.put(entity.getUUID(), bonus);
        } else {
            BONUS.remove(entity.getUUID());
        }
    }

    public static double bonus(LivingEntity entity) {
        return entity == null ? 0 : BONUS.getOrDefault(entity.getUUID(), 0.0);
    }

    public static void clear(LivingEntity entity) {
        if (entity != null) {
            BONUS.remove(entity.getUUID());
        }
    }

    public static void clearAll() {
        BONUS.clear();
    }
}
