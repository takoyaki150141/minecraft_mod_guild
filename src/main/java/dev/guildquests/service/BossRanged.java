package dev.guildquests.service;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bow attacks for emergency bosses, driven from the server tick.
 * <p>
 * This deliberately does not touch the mob's goal selector: other mods (Lithium and friends) mix
 * into {@code GoalSelector}, and adding a goal from outside corrupted its running set on live
 * servers. An explicit per-tick check is duller but safe.
 */
public final class BossRanged {
    private static final double MIN_RANGE = 6.0;
    private static final double MAX_RANGE = 24.0;

    private static final class State {
        int cooldown;
        ItemStack melee;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private BossRanged() {
    }

    /** Called once per server tick for every live boss. */
    public static void tick(LivingEntity entity, BossTier tier, double power) {
        if (!(entity instanceof Mob mob) || tier == null || !mob.isAlive()) {
            return;
        }
        LivingEntity target = mob.getTarget();
        double distance = target == null ? 0 : mob.distanceTo(target);
        boolean inRange = target != null && target.isAlive()
                && distance > MIN_RANGE && distance <= MAX_RANGE
                && mob.hasLineOfSight(target);
        UUID id = mob.getUUID();
        if (!inRange) {
            State state = STATES.remove(id);
            if (state != null && state.melee != null) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, state.melee);
            }
            return;
        }

        State state = STATES.get(id);
        if (state == null) {
            state = new State();
            state.cooldown = Math.max(10, (int) (40 / Math.max(0.1, tier.attackSpeedMultiplier()))) / 2;
            state.melee = mob.getMainHandItem().copy();
            STATES.put(id, state);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
        }
        if (state.cooldown > 0) {
            state.cooldown--;
            return;
        }
        state.cooldown = Math.max(10, (int) (40 / Math.max(0.1, tier.attackSpeedMultiplier())));
        shoot(mob, target, tier.arrowDamage() * Math.max(0.05, Math.min(1.0, power)));
    }

    /** Drops any ranged state for a boss that died or was removed. */
    public static void clear(UUID id) {
        if (id != null) {
            STATES.remove(id);
        }
    }

    public static void reset() {
        STATES.clear();
    }

    private static void shoot(Mob mob, LivingEntity target, double damage) {
        Arrow arrow = new Arrow(EntityType.ARROW, mob.level());
        arrow.setOwner(mob);
        arrow.setPos(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());
        Vec3 aim = new Vec3(target.getX() - mob.getX(),
                target.getY(0.333) - arrow.getY(),
                target.getZ() - mob.getZ());
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        arrow.shoot(aim.x, aim.y + horizontal * 0.2, aim.z, 1.6f, 4.0f);
        arrow.setBaseDamage(Math.max(0, damage));
        mob.level().addFreshEntity(arrow);
        mob.playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f);
    }
}
