package dev.guildquests.debug;

import dev.guildquests.service.BossAttackSnapshot;
import dev.guildquests.service.BossCombat;
import dev.guildquests.service.BossProjectileData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.function.BiConsumer;

/** Optional real-Botania coverage, without adding Botania to the mod's build/runtime dependencies. */
final class BossProjectileChecks {
    interface BossFactory { Zombie create() throws Exception; }
    private final ServerLevel world;
    private final ServerPlayer player;
    private final BossFactory bosses;
    private final BiConsumer<Boolean, String> check;

    BossProjectileChecks(ServerLevel world, ServerPlayer player, BossFactory bosses, BiConsumer<Boolean, String> check) {
        this.world = world; this.player = player; this.bosses = bosses; this.check = check;
    }

    void run() throws Exception {
        clean();
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        check.accept(BossCombat.profile(player).attack() == 7, "new melee weapon is reflected before equipment tick: " + BossCombat.profile(player).attack());
        player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        check.accept(BossCombat.profile(player).attack() == 1, "empty hand is reflected immediately");

        Arrow arrow = new Arrow(world, player);
        BossAttackSnapshot original = shot(arrow);
        Zombie boss = bosses.create(); float hp = boss.getHealth();
        boss.hurt(world.damageSources().arrow(arrow, player), 6);
        double arrowDamage = hp - boss.getHealth();
        boost();
        boss = bosses.create(); hp = boss.getHealth();
        boss.hurt(world.damageSources().arrow(arrow, player), 6);
        check.accept(shot(arrow).equals(original) && hp - boss.getHealth() == arrowDamage && arrowDamage > 0,
                "arrow damage keeps launch profile after weapon, armor and buff switches");
        Arrow stronger = new Arrow(world, player);
        check.accept(shot(stronger).profile().power() > original.profile().power(), "next projectile uses new equipment and effects");

        CompoundTag saved = new CompoundTag(); arrow.saveWithoutId(saved);
        Arrow restored = new Arrow(world, player); restored.load(saved);
        check.accept(original.equals(shot(restored)), "projectile save/load preserves offensive snapshot");
        restored.setOwner(player);
        check.accept(original.equals(shot(restored)), "assigning the same shooter does not refresh an in-flight attack");
        CompoundTag invalid = original.save(); invalid.putDouble("attack", Double.NaN);
        check.accept(BossAttackSnapshot.load(invalid) == null, "invalid saved attack data is rejected");

        clean();
        if (!BuiltInRegistries.ITEM.containsKey(new ResourceLocation("botania", "king_key"))) return;
        ItemStack key = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("botania", "king_key")));
        player.setItemSlot(EquipmentSlot.MAINHAND, key);
        Projectile direct = weapon();
        BossAttackSnapshot keyProfile = shot(direct);
        double keyDamage = direct(direct);
        clean(); player.setItemSlot(EquipmentSlot.MAINHAND, key);
        Projectile switched = weapon(); boost();
        check.accept(shot(switched).equals(keyProfile), "real King's Law captures identical profiles before switching: " + keyProfile);
        double switchedDamage = direct(switched);
        check.accept(switchedDamage == keyDamage && keyDamage > 0,
                "real King's Law direct hit ignores held-weapon changes: " + keyDamage + " -> " + switchedDamage);

        clean(); player.setItemSlot(EquipmentSlot.OFFHAND, key);
        Projectile offhand = weapon();
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        check.accept(direct(offhand) == keyDamage, "King's Law offhand and empty-hand switch keep direct damage");

        clean(); player.setItemSlot(EquipmentSlot.MAINHAND, key);
        Projectile explosion = weapon();
        double explosionDamage = explode(explosion);
        clean(); player.setItemSlot(EquipmentSlot.MAINHAND, key);
        Projectile switchedExplosion = weapon(); boost();
        check.accept(explode(switchedExplosion) == explosionDamage && explosionDamage > 0,
                "real King's Law explosion keeps launch profile: " + explosionDamage);

        clean(); player.setItemSlot(EquipmentSlot.MAINHAND, key);
        boss = bosses.create(); hp = boss.getHealth();
        for (int i = 0; i < 20; i++) {
            Projectile weapon = weapon();
            boss.invulnerableTime = 0; boss.hurtTime = 0;
            place(weapon, boss); world.tickNonPassenger(weapon);
            player.setItemSlot(EquipmentSlot.MAINHAND, i % 2 == 0 ? new ItemStack(Items.STICK) : key);
        }
        check.accept(hp - boss.getHealth() > 0 && hp - boss.getHealth() <= hp / 40 + .001,
                "20 real King's Law direct hits and explosions share one budget despite switching: " + (hp - boss.getHealth()));

        clean(); player.setItemSlot(EquipmentSlot.MAINHAND, key);
        ItemStack tablet = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("botania", "mana_tablet")));
        tablet.getOrCreateTag().putInt("mana", 500000);
        player.getInventory().setItem(9, tablet);
        key.getItem().use(world, player, InteractionHand.MAIN_HAND);
        for (int i = 0; i < 20; i++) key.getItem().onUseTick(world, player, key, 71999 - i);
        var weapons = world.getEntitiesOfClass(Projectile.class, player.getBoundingBox().inflate(32),
                entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(new ResourceLocation("botania", "babylon_weapon")));
        check.accept(weapons.size() == 20 && weapons.stream().allMatch(entity -> shot(entity) != null),
                "normal King's Law charging creates 20 captured projectiles");
        check.accept(tablet.getOrCreateTag().getInt("mana") == 497000, "King's Law mana consumption remains 150 per weapon");
        key.getItem().releaseUsing(key, world, player, 71980);
        check.accept(!key.getOrCreateTag().getBoolean("charging"), "King's Law release behavior is unchanged");
        weapons.forEach(Projectile::discard);
        player.stopUsingItem(); player.getInventory().setItem(9, ItemStack.EMPTY); clean();
    }

    private static BossAttackSnapshot shot(Projectile projectile) {
        return ((BossProjectileData) projectile).guildquests$attackSnapshot();
    }

    private Projectile weapon() throws Exception {
        return (Projectile) Class.forName("vazkii.botania.common.entity.BabylonWeaponEntity")
                .getConstructor(LivingEntity.class, Level.class).newInstance(player, world);
    }

    private void place(Projectile weapon, Zombie boss) {
        weapon.setPos(boss.getX(), boss.getY() + .5, boss.getZ());
        world.addFreshEntity(weapon);
        // Zenith's default critical chance is random. Compare equipment changes with identical rolls.
        boss.getRandom().setSeed(12345);
    }

    private double direct(Projectile weapon) throws Exception {
        Zombie boss = bosses.create(); float hp = boss.getHealth();
        place(weapon, boss); world.tickNonPassenger(weapon);
        return hp - boss.getHealth();
    }

    private double explode(Projectile weapon) throws Exception {
        Zombie boss = bosses.create(); float hp = boss.getHealth(); place(weapon, boss);
        var method = weapon.getClass().getDeclaredMethod("explodeAndDie");
        method.setAccessible(true); method.invoke(weapon);
        return hp - boss.getHealth();
    }

    private void boost() {
        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
        sword.addAttributeModifier(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(UUID.fromString("a247225f-2b18-44de-a3d7-e0d98062e104"), "Boss debug power", 750000,
                        AttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
        player.setItemSlot(EquipmentSlot.MAINHAND, sword);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 1000, 2));
    }

    private void clean() {
        player.removeAllEffects(); player.clearFire(); player.setAbsorptionAmount(0);
        for (EquipmentSlot slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        player.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1);
    }
}
