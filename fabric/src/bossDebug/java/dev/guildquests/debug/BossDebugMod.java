package dev.guildquests.debug;

import com.mojang.authlib.GameProfile;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.service.BossCombat;
import dev.guildquests.service.BossEvent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in headless tests using actual Minecraft damage, events, mixins and player respawn. */
public final class BossDebugMod implements ModInitializer {
    private final List<String> results = new ArrayList<>();
    private ServerPlayer rookie, veteran, respawned;
    private ServerLevel world;
    private int stage, expiry;
    private Zombie testBoss;

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            world = server.overworld();
            if (Boolean.getBoolean("guildquests.bossDebugExpandedHealth")) {
                // Test-only fixture: vanilla caps MAX_HEALTH at 1024. Exercise real 3M HP too.
                var attribute = (net.minecraft.world.entity.ai.attributes.RangedAttribute) Attributes.MAX_HEALTH;
                double previous = attribute.getMaxValue();
                try {
                    for (var field : attribute.getClass().getDeclaredFields()) {
                        if (field.getType() != double.class) continue;
                        field.setAccessible(true);
                        if (field.getDouble(attribute) == previous) field.setDouble(attribute, 1_000_000_000);
                    }
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }
            world.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            world.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
            rookie = player(server, "BossBeginner");
            veteran = player(server, "BossVeteran");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (stage == 0 && server.getTickCount() >= 100) {
                    run(server);
                    stage = 1;
                } else if (stage == 1 && server.getTickCount() >= expiry) {
                    check(!BossCombat.isProtected(respawned), "respawn protection expires");
                    finish(server);
                    stage = 2;
                }
            } catch (Throwable error) {
                error.printStackTrace();
                results.add("FAIL " + error);
                try { Files.write(Path.of("boss-debug-results.txt"), results); } catch (Exception ignored) { }
                stage = 2;
                server.halt(false);
            }
        });
    }

    private ServerPlayer player(MinecraftServer server, String name) {
        ServerPlayer player = new ServerPlayer(server, world,
                new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
        server.getPlayerList().placeNewPlayer(new Connection(PacketFlow.SERVERBOUND), player);
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(world, world.getSharedSpawnPos().getX() + 4, world.getSharedSpawnPos().getY() + 2,
                world.getSharedSpawnPos().getZ() + 4, 0, 0);
        return player;
    }

    private void run(MinecraftServer server) throws Exception {
        check(ConfigManager.config().bossAdaptiveDifficultyEnabled, "legacy config enables adaptive defaults");
        check(ConfigManager.config().bossEventHealth == 3_000_000, "three-million health config survives sanitization");
        ConfigManager.config().bossEventChancePercent = 0;
        rookie.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1);
        veteran.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(750000);
        veteran.getAttribute(Attributes.MAX_HEALTH).setBaseValue(5000);
        veteran.experienceLevel = 500;
        rookie.experienceLevel = 0;
        dev.guildquests.service.GuildService.data(rookie).completedTotal = 0;
        dev.guildquests.service.GuildService.data(veteran).completedTotal = 80;
        reset(rookie); reset(veteran);
        check(BossCombat.profile(rookie).guildLevel() == 0 && BossCombat.profile(veteran).guildLevel() == 5,
                "configured guild ranks E/S are read from persistent quest progression");
        double power = BossCombat.profile(rookie).power(); rookie.experienceLevel = 500;
        check(BossCombat.profile(rookie).power() == power, "vanilla XP spending does not change guild combat difficulty");
        rookie.experienceLevel = 0;

        Zombie boss = boss();
        float initial = boss.getHealth();
        if (Boolean.getBoolean("guildquests.bossDebugExpandedHealth")) check(initial == 3_000_000, "real three-million HP fixture is active");
        hit(boss, rookie, 1);
        double first = initial - boss.getHealth();
        check(first > initial / 200 && first < initial / 20, "beginner deals meaningful normalized damage: " + first + "/" + initial);
        check(Math.abs(credit(rookie) - first) < .001, "reward ledger matches post-armor HP loss");
        double before = credit(rookie);
        boss.hurt(world.damageSources().playerAttack(rookie), 1);
        check(credit(rookie) == before, "vanilla hurt cooldown earns no reward");

        boss = boss(); initial = boss.getHealth();
        for (int i = 0; i < 100; i++) hit(boss, veteran, 750000);
        check(boss.isAlive() && initial - boss.getHealth() <= initial / 40 + .001,
                "75万 hits and rapid multi-hit attacks stay within per-second budget");
        check(credit(veteran) <= initial / 40 + .001, "burst reward uses accepted damage only");

        boss = boss(); boss.setAbsorptionAmount(100); before = boss.getHealth();
        hit(boss, rookie, 1);
        check(boss.getHealth() == before && credit(rookie) == 0, "fully absorbed damage earns no reward");

        boss = boss(); initial = boss.getHealth();
        boss.hurt(world.damageSources().indirectMagic(null, rookie), 10);
        check(boss.getHealth() < initial && credit(rookie) > 0, "player magic participates in boss combat");
        boss.invulnerableTime = 0; before = boss.getHealth();
        boss.hurt(world.damageSources().lava(), 100);
        check(boss.getHealth() == before, "environmental boss protection remains active");

        boss = boss(); boss.setHealth(.5f); hit(boss, rookie, 1000);
        check(!boss.isAlive(), "overkill correctly completes the event");

        boss = boss(); reset(rookie);
        float health = rookie.getHealth();
        rookie.hurt(world.damageSources().mobAttack(boss), 750000);
        check(rookie.isAlive() && health - rookie.getHealth() > 0 && health - rookie.getHealth() <= health / 4 + .001,
                "beginner survives extreme boss attack");
        for (int i = 0; i < 50; i++) { rookie.invulnerableTime = 0; rookie.hurt(world.damageSources().mobAttack(boss), 750000); }
        check(rookie.getHealth() >= health * .75 - .001, "boss multi-hit damage is bounded per second");

        reset(rookie); rookie.hurt(world.damageSources().mobAttack(boss), 3);
        double naked = health - rookie.getHealth();
        reset(rookie);
        rookie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        rookie.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 1000, 1));
        rookie.hurt(world.damageSources().mobAttack(boss), 3);
        check(health - rookie.getHealth() < naked, "armor and Resistance still reduce adapted damage (" + naked + " -> " + (health - rookie.getHealth()) + ")");

        reset(veteran); health = veteran.getHealth();
        veteran.hurt(world.damageSources().mobAttack(boss), 100);
        check(health - veteran.getHealth() > 1 && veteran.isAlive(), "high-health veteran receives an appropriate challenge");

        reset(rookie); rookie.setAbsorptionAmount(100); before = rookie.getHealth();
        rookie.hurt(world.damageSources().mobAttack(boss), 10);
        check(rookie.getHealth() == before, "absorption remains effective for players");

        reset(rookie); health = rookie.getHealth();
        Zombie ordinary = EntityType.ZOMBIE.create(world); ordinary.setNoAi(true);
        rookie.hurt(world.damageSources().mobAttack(ordinary), 2);
        check(rookie.getHealth() < health && BossCombat.adjustDamage(rookie, world.damageSources().mobAttack(ordinary), 2) == 2,
                "ordinary mob damage passes through unchanged (actual HP loss " + (health - rookie.getHealth()) + ")");

        reset(rookie); health = rookie.getHealth();
        Arrow arrow = new Arrow(world, boss);
        rookie.hurt(world.damageSources().arrow(arrow, boss), 750000);
        check(rookie.isAlive() && health - rookie.getHealth() <= health / 4 + .001, "boss projectile damage is adapted");

        reset(rookie); rookie.setSecondsOnFire(10);
        rookie.hurt(world.damageSources().mobAttack(boss), 10);
        rookie.invulnerableTime = 0; health = rookie.getHealth();
        rookie.hurt(world.damageSources().onFire(), 750000);
        check(rookie.isAlive() && health - rookie.getHealth() <= rookie.getMaxHealth() / 4 + .001, "boss fire retains attribution");

        reset(rookie);
        rookie.addEffect(new MobEffectInstance(MobEffects.POISON, 1000, 1), boss);
        health = rookie.getHealth();
        rookie.hurt(world.damageSources().magic(), 750000);
        check(rookie.isAlive() && health - rookie.getHealth() <= rookie.getMaxHealth() / 4 + .001, "boss poison retains attribution");

        reset(rookie); ConfigManager.config().bossAdaptiveDifficultyEnabled = false;
        boss = boss(); boss.getTags().remove(BossCombat.BOSS_TAG);
        initial = boss.getHealth(); hit(boss, rookie, 10);
        double unadapted = initial - boss.getHealth();
        boss.setHealth(initial); boss.addTag(BossCombat.BOSS_TAG); hit(boss, rookie, 10);
        check(unadapted > 0 && Math.abs(initial - boss.getHealth() - unadapted) < .001,
                "disabled adaptation matches an identical untagged mob");
        ConfigManager.config().bossAdaptiveDifficultyEnabled = true;

        ConfigManager.config().bossRespawnProtectionSeconds = 1;
        reset(rookie); rookie.setHealth(.1f); rookie.hurt(world.damageSources().mobAttack(boss), 1000);
        check(!rookie.isAlive(), "boss can still defeat an injured player");
        respawned = server.getPlayerList().respawn(rookie, false);
        BossCombat.tick(server);
        check(BossCombat.isProtected(respawned), "protection starts on actual respawn");
        BossCombat.beginEncounter();
        check(BossCombat.isProtected(respawned), "new boss encounter preserves active respawn protection");
        check(!BossCombat.allowDamage(respawned, world.damageSources().mobAttack(boss)), "respawn protection blocks boss damage");
        check(!BossCombat.allowEffect(respawned, new MobEffectInstance(MobEffects.POISON, 100, 1), boss), "respawn protection blocks boss debuffs");
        boss.setTarget(respawned);
        check(boss.getTarget() != respawned, "boss cannot target protected respawn");
        hit(boss, respawned, 1);
        check(!BossCombat.isProtected(respawned), "attacking ends respawn protection");
        // Exercise the death handler again to check natural expiry on subsequent server ticks.
        BossCombat.reset(); respawned.invulnerableTime = 0;
        BossCombat.onDeath(respawned, world.damageSources().mobAttack(boss));
        BossCombat.tick(server);
        check(BossCombat.isProtected(respawned), "expiry test starts with active protection");
        expiry = server.getTickCount() + 22;
    }

    private Zombie boss() throws Exception {
        resetBoss();
        Zombie boss = EntityType.ZOMBIE.create(world);
        boss.addTag(BossCombat.BOSS_TAG); boss.setNoAi(true); boss.setPersistenceRequired();
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(3_000_000);
        boss.setHealth(boss.getMaxHealth());
        boss.moveTo(rookie.getX() + 10, rookie.getY(), rookie.getZ(), 0, 0);
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        chest.enchant(Enchantments.ALL_DAMAGE_PROTECTION, 4);
        boss.setItemSlot(EquipmentSlot.CHEST, chest);
        world.addFreshEntity(boss);
        testBoss = boss;
        return boss;
    }

    private void resetBoss() throws Exception {
        if (testBoss != null) testBoss.discard();
        var clear = BossEvent.class.getDeclaredMethod("clearBoss"); clear.setAccessible(true); clear.invoke(null);
        BossCombat.reset();
    }

    private void reset(ServerPlayer player) {
        BossCombat.reset(); player.removeAllEffects(); player.clearFire();
        player.setAbsorptionAmount(0); player.invulnerableTime = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        player.setHealth(player.getMaxHealth());
    }

    private void hit(Zombie boss, ServerPlayer player, float amount) {
        boss.invulnerableTime = 0;
        boss.hurt(world.damageSources().playerAttack(player), amount);
    }

    @SuppressWarnings("unchecked") private double credit(ServerPlayer player) throws Exception {
        var field = BossEvent.class.getDeclaredField("damage"); field.setAccessible(true);
        return ((Map<UUID, Double>) field.get(null)).getOrDefault(player.getUUID(), 0d);
    }

    private void check(boolean success, String name) {
        if (!success) throw new AssertionError(name);
        results.add("PASS " + name);
        System.out.println("BOSS DEBUG PASS " + name);
    }

    private void finish(MinecraftServer server) throws Exception {
        Files.write(Path.of("boss-debug-results.txt"), results);
        System.out.println("BOSS DEBUG COMPLETE: " + results.size() + " checks passed");
        server.halt(false);
    }
}
