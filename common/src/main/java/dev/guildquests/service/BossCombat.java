package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.tags.DamageTypeTags;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-only adaptation of accepted, post-mitigation health damage. */
public final class BossCombat {
    public static final String BOSS_TAG = "guildquests_emergency_boss";
    private static final Map<UUID, BossDifficulty.Budget> outgoing = new HashMap<>();
    private static final Map<UUID, BossDifficulty.Budget> incoming = new HashMap<>();
    private static final Map<UUID, Integer> protectedUntil = new HashMap<>();
    private static final Map<UUID, Integer> lastContact = new HashMap<>();
    private static final Map<UUID, Integer> pendingRespawn = new HashMap<>();
    private static final Map<UUID, Map<String, Integer>> residual = new HashMap<>();

    private BossCombat() { }

    public static boolean isBoss(Entity entity) {
        return entity != null && entity.getTags().contains(BOSS_TAG);
    }

    /** Arrows, potions, evoker fangs (via DamageSource), vexes and owned pets retain attribution. */
    public static Entity owner(Entity entity) {
        for (int i = 0; entity != null && i < 4; i++) {
            if (isBoss(entity) || entity instanceof ServerPlayer) return entity;
            Entity next = entity instanceof Projectile p ? p.getOwner()
                    : entity instanceof Vex vex ? vex.getOwner()
                    : entity instanceof TamableAnimal pet ? pet.getOwner() : null;
            if (next == null || next == entity) break;
            entity = next;
        }
        return entity;
    }

    public static Entity owner(DamageSource source) {
        Entity causing = owner(source.getEntity());
        return causing != null ? causing : owner(source.getDirectEntity());
    }

    public static BossDifficulty.Profile profile(ServerPlayer player) {
        double beneficial = 0, harmful = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                beneficial += Math.min(5, effect.getAmplifier() + 1);
            } else if (effect.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
                harmful += Math.min(5, effect.getAmplifier() + 1);
            }
        }
        int guildLevel = BossDifficulty.guildLevel(GuildService.data(player).completedTotal,
                ConfigManager.config().rankThresholds.values());
        return new BossDifficulty.Profile(guildLevel,
                BossEquipment.value(player, Attributes.ATTACK_DAMAGE), BossEquipment.value(player, Attributes.ATTACK_SPEED),
                BossEquipment.value(player, Attributes.MAX_HEALTH), BossEquipment.value(player, Attributes.ARMOR),
                BossEquipment.value(player, Attributes.ARMOR_TOUGHNESS),
                beneficial, harmful);
    }

    private static double attack(LivingEntity entity, LivingEntity target) {
        double value = entity.getAttribute(Attributes.ATTACK_DAMAGE) == null ? 6
                : BossEquipment.value(entity, Attributes.ATTACK_DAMAGE);
        return BossDifficulty.positive(value + EnchantmentHelper.getDamageBonus(entity.getMainHandItem(), target.getMobType()), 1);
    }

    private static double expectedPlayerHit(ServerPlayer player, LivingEntity target, DamageSource source, BossAttackSnapshot shot) {
        double reference = BossProjectiles.ranged(source, player, shot) ? BossProjectiles.REFERENCE_DAMAGE : attack(player, target);
        float value = (float) Math.min(Float.MAX_VALUE, reference);
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            value = CombatRules.getDamageAfterAbsorb(value, target.getArmorValue(),
                    (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }
        if (!source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            MobEffectInstance resistance = target.getEffect(MobEffects.DAMAGE_RESISTANCE);
            if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
                value *= Math.max(0, 1 - .2f * (resistance.getAmplifier() + 1));
            }
            if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
                value = CombatRules.getDamageAfterMagicAbsorb(value,
                        EnchantmentHelper.getDamageProtection(target.getArmorSlots(), source));
            }
        }
        return BossDifficulty.positive(value, .01);
    }

    /** Called only after armor/effects/absorption and vanilla cooldown rejection. */
    public static float adjustDamage(LivingEntity victim, DamageSource source, float amount) {
        if (victim.level().isClientSide) return amount;
        ModConfig config = ConfigManager.config();
        Entity attacker = owner(source);
        int now = victim.getServer().getTickCount();
        if (!(amount > 0) || !Float.isFinite(amount)) {
            return isBoss(victim) || isBoss(attacker) ? 0 : amount;
        }
        if (isBoss(victim) && attacker instanceof ServerPlayer player) {
            if (BossProjectiles.intentional(source, player)) protectedUntil.remove(player.getUUID());
            lastContact.put(player.getUUID(), now);
            double dealt = amount;
            if (config.bossAdaptiveDifficultyEnabled) {
                BossAttackSnapshot shot = BossProjectiles.attack(source, player);
                dealt = BossDifficulty.outgoing(amount, expectedPlayerHit(player, victim, source, shot),
                        victim.getMaxHealth(), config.bossTargetPlayerHits, shot == null ? profile(player) : shot.profile());
                dealt = outgoing.computeIfAbsent(player.getUUID(), id -> new BossDifficulty.Budget())
                        .take(now, dealt, victim.getMaxHealth() * 2.0 / config.bossTargetPlayerHits);
            }
            float after = Math.max(0, victim.getHealth() - (float) Math.min(victim.getHealth(), dealt));
            if (config.bossAdaptiveDifficultyEnabled && victim.getHealth() - after > dealt) after = Math.nextUp(after);
            // Minecraft stores HP as float; credit exactly the HP difference it can represent.
            float accepted = Math.max(0, victim.getHealth() - after);
            BossEvent.recordDamage(victim, player, accepted);
            return accepted;
        }
        if (victim instanceof ServerPlayer player && (isBoss(attacker) || isResidual(player, source, now))) {
            if (isProtected(player)) return 0;
            lastContact.put(player.getUUID(), now);
            if (isBoss(attacker) && player.isOnFire()) remember(player, "fire", now + 200);
            if (!config.bossAdaptiveDifficultyEnabled) return amount;
            double expected = attacker instanceof LivingEntity living ? attack(living, player) : 10;
            double received = BossDifficulty.incoming(amount, expected, player.getMaxHealth(),
                    config.bossPlayerSurvivalHits, profile(player));
            return (float) incoming.computeIfAbsent(player.getUUID(), id -> new BossDifficulty.Budget())
                    .take(now, received, player.getMaxHealth() / 4.0);
        }
        return amount;
    }

    public static boolean allowDamage(LivingEntity victim, DamageSource source) {
        Entity attacker = owner(source);
        if (victim instanceof ServerPlayer player && isProtected(player)
                && (isBoss(attacker) || isResidual(player, source, player.getServer().getTickCount()))) return false;
        if (isBoss(victim) && attacker instanceof ServerPlayer player && BossProjectiles.intentional(source, player)) {
            protectedUntil.remove(player.getUUID());
        }
        return true;
    }

    public static boolean allowEffect(LivingEntity victim, MobEffectInstance effect, Entity source) {
        if (!(victim instanceof ServerPlayer player) || !isBoss(owner(source))) return true;
        if (isProtected(player) && effect.getEffect().getCategory() == MobEffectCategory.HARMFUL) return false;
        String type = effect.getEffect() == MobEffects.POISON ? "poison"
                : effect.getEffect() == MobEffects.WITHER ? "wither" : null;
        if (type != null) remember(player, type, player.getServer().getTickCount() + Math.min(72000, effect.getDuration()));
        return true;
    }

    private static void remember(ServerPlayer player, String type, int until) {
        residual.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).merge(type, until, Math::max);
    }

    private static boolean isResidual(ServerPlayer player, DamageSource source, int now) {
        if (source.getEntity() != null || source.getDirectEntity() != null) return false;
        String type = source.is(DamageTypes.ON_FIRE) ? "fire"
                : source.is(DamageTypes.MAGIC) ? "poison" : source.is(DamageTypes.WITHER) ? "wither" : "";
        return residual.getOrDefault(player.getUUID(), Map.of()).getOrDefault(type, Integer.MIN_VALUE) > now;
    }

    public static boolean isProtected(ServerPlayer player) {
        return protectedUntil.getOrDefault(player.getUUID(), Integer.MIN_VALUE) > player.getServer().getTickCount();
    }

    public static void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer player)) return;
        int now = player.getServer().getTickCount();
        if (isBoss(owner(source)) || now - (long) lastContact.getOrDefault(player.getUUID(), Integer.MIN_VALUE) < 400) {
            pendingRespawn.put(player.getUUID(), now);
        }
    }

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        pendingRespawn.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) return now - (long) entry.getValue() > 72000;
            int seconds = ConfigManager.config().bossRespawnProtectionSeconds;
            if (seconds > 0) {
                protectedUntil.put(entry.getKey(), now + seconds * 20);
                GuildService.message(player, "ボスからの復活保護: " + seconds + "秒（ボスへの攻撃で解除）", ChatFormatting.YELLOW);
            }
            residual.remove(entry.getKey());
            incoming.remove(entry.getKey());
            return true;
        });
        if (now % 200 != 0) return;
        BossProjectiles.clean();
        protectedUntil.values().removeIf(until -> until <= now);
        lastContact.entrySet().removeIf(entry -> {
            if (now - (long) entry.getValue() < 1200) return false;
            outgoing.remove(entry.getKey());
            incoming.remove(entry.getKey());
            return true;
        });
        residual.values().forEach(effects -> effects.values().removeIf(until -> until <= now));
        residual.values().removeIf(Map::isEmpty);
    }

    public static void reset() {
        outgoing.clear(); incoming.clear(); protectedUntil.clear(); lastContact.clear(); pendingRespawn.clear(); residual.clear();
    }

    public static void beginEncounter() {
        outgoing.clear();
        incoming.clear();
    }
}
