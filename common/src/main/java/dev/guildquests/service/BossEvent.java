package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.platform.Platform;
import dev.guildquests.util.Numbers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Emergency boss event: while players are online, every configured interval a chance roll
 * spawns a buffed random overworld hostile mob 50-100 blocks from a random player, shown on
 * a boss bar. On death the configured reward is split among players by damage share.
 * <p>
 * Multiple bosses can be alive at the same time (admin command / GUI). Every boss owns its
 * own bar, damage ledger and rescue state, so a second spawn no longer just re-creates the
 * bar of the first one.
 */
public final class BossEvent {
    private static final Random RANDOM = new Random();
    private static final String BOSS_TAG = "guildquests_emergency_boss";
    private static final int TICKS_PER_MINUTE = 1200;
    /** S2C: show/clear the client-side boss pin (x,y,z + lifetime ticks; lifetime 0 = clear). */
    public static final ResourceLocation BOSS_PIN = new ResourceLocation("guildquests", "boss_pin");
    /** Upper bound so a command typo cannot flood the server with bosses. */
    public static final int MAX_ACTIVE = 32;
    private static final int PIN_LIFETIME_TICKS = 6000;
    /** Viewers only need re-checking once per second. */
    private static final int VIEW_RECONCILE_INTERVAL = 20;
    /** Vanilla's generic.max_health attribute cannot exceed 1024. */
    private static final double MAX_HEALTH_VALUE = 1024.0;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static int ticker;
    private static int viewTicker;
    private static int adoptTicker;
    /** Living bosses keyed by entity UUID, in spawn order. */
    private static final Map<UUID, BossInstance> bosses = new LinkedHashMap<>();
    private static Mob lastSpawned;
    /** Boss the shared client-side pin currently points at, or null. */
    private static UUID pinned;
    private static int pinLife;

    private BossEvent() {
    }

    public static void init() {
        Platform.onServerTick(BossEvent::tick);
        Platform.onServerStarted(BossEvent::adoptPersisted);
        Platform.onServerStopped(server -> BossProtection.reset());
        Platform.onAllowDamage((entity, source, amount) -> {
            // A protected player (the guildquests:boss_protection effect) is untouchable for bosses.
            if (entity instanceof ServerPlayer player && BossProtection.isProtected(player)
                    && BossProtection.isBossDamage(source)) {
                return false;
            }
            if (entity.getTags().contains(BOSS_TAG)) {
                // Attacking a boss is consent: drop the protection before the hit lands.
                if (BossProtection.attacker(source) instanceof ServerPlayer player) {
                    BossProtection.revoke(player);
                }
                // Environmental kill cheese (lava, void, fall, etc.) is ignored for the boss.
                if (isEnvironmental(source)) {
                    return false;
                }
                return true;
            }
            if (entity instanceof ServerPlayer player && BossProtection.isBossDamage(source)) {
                BossProtection.noteBossHit(player);
            }
            return true;
        });
        Platform.onEntityDeath((entity, source) -> {
            BossProtection.onPlayerDeath(entity, source);
            onDeath(entity, source);
        });
    }

    private static boolean isEnvironmental(DamageSource source) {
        return source.is(DamageTypes.LAVA)
                || source.is(DamageTypes.IN_FIRE)
                || source.is(DamageTypes.ON_FIRE)
                || source.is(DamageTypes.FELL_OUT_OF_WORLD)
                || source.is(DamageTypes.FALL)
                || source.is(DamageTypes.DROWN)
                || source.is(DamageTypes.CACTUS)
                || source.is(DamageTypes.HOT_FLOOR)
                || source.is(DamageTypes.FREEZE)
                || source.is(DamageTypes.STALAGMITE)
                || source.is(DamageTypes.SWEET_BERRY_BUSH)
                || source.is(DamageTypes.IN_WALL)
                || source.is(DamageTypes.FALLING_BLOCK)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.DRAGON_BREATH)
                || source.is(DamageTypes.CRAMMING)
                || source.is(DamageTypes.WITHER_SKULL);
    }

    private static void tick(MinecraftServer server) {
        BossProtection.tick(server);
        ModConfig config = ConfigManager.config();
        if (!config.bossEventEnabled) {
            ticker = 0;
            return;
        }
        boolean anyoneOnline = !server.getPlayerList().getPlayers().isEmpty();
        if (anyoneOnline) ticker++;

        // Adopt bosses whose chunk was not loaded at server start (best effort, every 10s).
        if (++adoptTicker >= 200) {
            adoptTicker = 0;
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) adoptExisting(overworld);
        }

        updateBars(server);

        // Rescue bosses that end up in lava, the void, walled in, or stuck.
        for (BossInstance instance : new ArrayList<>(bosses.values())) {
            releaseProtectedTarget(instance);
            rescue(instance);
        }

        // Tick down the client-side pin.
        if (pinLife > 0) {
            pinLife--;
            if (pinLife == 0) {
                clearBossPin(server);
            }
        }

        int interval = Math.max(1, Math.min(1440, config.bossEventIntervalMinutes)) * TICKS_PER_MINUTE;
        if (ticker < interval) return;
        ticker = 0;
        if (!anyoneOnline) return;

        // Re-adopt bosses that survived a restart before deciding whether to spawn.
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld != null) adoptExisting(overworld);
        if (!bosses.isEmpty()) return;
        int chance = Math.max(0, Math.min(100, config.bossEventChancePercent));
        if (RANDOM.nextInt(100) >= chance) return;
        spawnNow(server);
    }

    /**
     * Spawns a single boss immediately with the normal placement rules (50-100 blocks from a
     * random online player), skipping the chance roll and ignoring other living bosses.
     */
    public static boolean spawnNow(MinecraftServer server) {
        return spawn(server, 1) > 0;
    }

    /**
     * Spawns up to {@code count} bosses at once. Returns how many were actually created.
     * Existing tagged bosses (for example after a server restart) are re-adopted first so
     * they regain a bar, but they are not counted as newly spawned.
     */
    public static int spawn(MinecraftServer server, int count) {
        return spawn(server, count, null);
    }

    /** Spawns bosses with a forced tier, or a weighted random tier when {@code forced} is null. */
    public static int spawn(MinecraftServer server, int count, BossTier forced) {
        if (count <= 0) return 0;
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) return 0;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;

        adoptExisting(world);

        int spawned = 0;
        for (int i = 0; i < count && bosses.size() < MAX_ACTIVE; i++) {
            if (spawnOne(server, world, players, forced, null)) {
                spawned++;
            }
        }
        return spawned;
    }

    /** Spawns one boss of the given tier at an exact position (used by the weakest-to-strongest promotion). */
    public static boolean spawnAt(MinecraftServer server, ServerLevel world,
                                  net.minecraft.core.BlockPos at, BossTier tier) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty() || at == null) return false;
        adoptExisting(world);
        return spawnOne(server, world, players, tier, at);
    }

    private static boolean spawnOne(MinecraftServer server, ServerLevel world, List<ServerPlayer> players,
                                    BossTier forced, net.minecraft.core.BlockPos at) {
        ModConfig config = ConfigManager.config();
        BossTier tier = forced != null ? forced : BossTier.roll(RANDOM, config.bossTierWeights);

        ServerPlayer anchor = players.get(RANDOM.nextInt(players.size()));
        int x;
        int z;
        if (at != null) {
            x = Mth.clamp(at.getX(), -29999000, 29999000);
            z = Mth.clamp(at.getZ(), -29999000, 29999000);
        } else {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = 50 + RANDOM.nextDouble() * 50;
            x = Mth.clamp((int) Math.floor(anchor.getX() + Math.cos(angle) * distance),
                    -29999000, 29999000);
            z = Mth.clamp((int) Math.floor(anchor.getZ() + Math.sin(angle) * distance),
                    -29999000, 29999000);
        }
        world.getChunk(x >> 4, z >> 4);
        int y = at != null ? at.getY() : world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

        EntityType<?> type = tier.randomMob(RANDOM);
        if (!(type.create(world) instanceof Mob mob)) return false;

        String mobName = type.getDescription().getString();
        String bossName = "【緊急ボス・" + tier.label() + "】" + mobName;
        mob.moveTo(x + 0.5, y, z + 0.5, RANDOM.nextFloat() * 360.0f, 0.0f);
        mob.setCustomName(Component.literal(bossName).withStyle(ChatFormatting.RED));
        mob.setCustomNameVisible(true);
        mob.addTag(BOSS_TAG);
        mob.setPersistenceRequired();

        // Vanilla caps the max-health attribute at 1024, so elite HP tops out there.
        double health = Math.max(1, Math.min(MAX_HEALTH_VALUE, config.bossEventHealth * tier.healthMultiplier()));
        if (mob.getAttribute(Attributes.MAX_HEALTH) != null) {
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        }
        if (mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE) != null) {
            mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        }
        mob.setHealth((float) health);
        // Higher tiers hit harder, swing faster and reach farther.
        if (mob.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            double base = mob.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue();
            mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Math.max(base * tier.attackMultiplier(), tier.attackFloor()));
        }
        if (mob.getAttribute(Attributes.ATTACK_SPEED) != null) {
            double base = mob.getAttribute(Attributes.ATTACK_SPEED).getBaseValue();
            mob.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(base * tier.attackSpeedMultiplier());
        }
        BossReach.set(mob, tier.reachBonus());
        ((dev.guildquests.mixin.MobGoalAccessor) mob).guildquests$goalSelector().addGoal(0,
                new BossRangedGoal(mob, 6.0, 24.0,
                        (int) Math.max(10, 40 / tier.attackSpeedMultiplier()), tier.arrowDamage()));
        equip(mob, tier);
        applyTierEffects(mob, tier);
        world.addFreshEntity(mob);
        lastSpawned = mob;

        adopt(mob, tier);

        // Show a client-rendered pin at the newest boss (5 minutes or until death).
        pinLife = PIN_LIFETIME_TICKS;
        pinned = mob.getUUID();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendBossPin(p, x, y, z, pinLife);
        }
        long reward = Math.max(0, Math.round(config.bossEventReward * tier.rewardMultiplier()));
        GuildService.broadcast(server, "緊急ボス【" + tier.label() + " " + mobName + "】が出現！座標: ("
                + x + ", " + y + ", " + z + ")  与えたダメージの割合で報酬 " + Numbers.format(reward)
                + " コインを分配します。", ChatFormatting.RED);
        return true;
    }

    /** Re-attaches bosses that survived a restart or lost their tracked state. */
    private static void adoptPersisted(MinecraftServer server) {
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world != null) adoptExisting(world);
    }

    private static void adoptExisting(ServerLevel world) {
        for (Entity entity : world.getAllEntities()) {
            if (entity instanceof LivingEntity living && living.isAlive()
                    && living.getTags().contains(BOSS_TAG)
                    && !bosses.containsKey(living.getUUID())) {
                adopt(living);
            }
        }
    }

    private static ServerPlayer nearestPlayer(LivingEntity from) {
        MinecraftServer server = from.level().getServer();
        if (server == null) return null;
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isRemoved() || !p.isAlive()) continue;
            if (BossProtection.isProtected(p)) continue;
            if (p.level() != from.level()) continue;
            double d = p.distanceToSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Clears an existing target when the player becomes protected. */
    private static void releaseProtectedTarget(BossInstance instance) {
        if (instance.entity instanceof Mob mob
                && mob.getTarget() instanceof ServerPlayer player
                && BossProtection.isProtected(player)) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
    }

    /** True for an emergency-boss entity. */
    public static boolean isBoss(Entity entity) {
        return entity != null && entity.getTags().contains(BOSS_TAG);
    }

    /** True when a living, tracked boss is within {@code range} blocks of the player. */
    static boolean hasBossWithin(ServerPlayer player, double range) {
        double limit = range * range;
        for (BossInstance instance : bosses.values()) {
            LivingEntity boss = instance.entity;
            if (boss.isRemoved() || boss.level() != player.level()) continue;
            if (boss.distanceToSqr(player) <= limit) return true;
        }
        return false;
    }

    /** One line per living boss: name, health and current AI target. */
    public static List<String> describeBosses() {
        List<String> lines = new ArrayList<>();
        for (BossInstance instance : bosses.values()) {
            LivingEntity boss = instance.entity;
            String target = boss instanceof Mob mob && mob.getTarget() != null
                    ? mob.getTarget().getName().getString() : "none";
            lines.add(instance.shortName + " " + Numbers.format(Math.round(boss.getHealth())) + "/"
                    + Numbers.format(Math.round(boss.getMaxHealth())) + " target=" + target);
        }
        return lines;
    }

    /** Sends the boss pin packet to one player. lifetimeTicks 0 clears the pin. */
    private static void sendBossPin(ServerPlayer player, int x, int y, int z, int lifetimeTicks) {
        if (!Platform.canSend(player, BOSS_PIN)) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
        buf.writeVarInt(lifetimeTicks);
        Platform.send(player, BOSS_PIN, buf);
    }

    private static void clearBossPin(MinecraftServer server) {
        pinLife = 0;
        pinned = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendBossPin(p, 0, 0, 0, 0);
        }
    }

    /** Points the pin at another living boss, or clears it when none is left. */
    private static void retargetPin(MinecraftServer server) {
        if (pinLife > 0 && !bosses.isEmpty()) {
            BossInstance next = null;
            for (BossInstance instance : bosses.values()) next = instance;
            if (next != null) {
                pinned = next.entity.getUUID();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    sendBossPin(p, Mth.floor(next.entity.getX()), Mth.floor(next.entity.getY()),
                            Mth.floor(next.entity.getZ()), pinLife);
                }
                return;
            }
        }
        clearBossPin(server);
    }

    /** Equips the boss with gear matching its tier (iron/chain up to netherite). */
    private static void equip(Mob mob, BossTier tier) {
        Item[] weapons;
        Item[] armorItems;
        int sharpness;
        int fireAspect;
        int knockback;
        int sweeping;
        int protection;
        int thorns;
        int unbreaking;
        int quickCharge;
        boolean multishot;

        switch (tier) {
            case WEAK -> {
                weapons = new Item[]{Items.IRON_SWORD, Items.IRON_AXE};
                armorItems = new Item[]{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE,
                        Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
                sharpness = 2; fireAspect = 0; knockback = 0; sweeping = 0;
                protection = 2; thorns = 0; unbreaking = 3; quickCharge = 1; multishot = false;
            }
            case STRONG -> {
                weapons = new Item[]{Items.DIAMOND_SWORD, Items.DIAMOND_AXE};
                armorItems = new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
                        Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
                sharpness = 7; fireAspect = 2; knockback = 3; sweeping = 3;
                protection = 6; thorns = 4; unbreaking = 5; quickCharge = 4; multishot = true;
            }
            case ELITE -> {
                weapons = new Item[]{Items.NETHERITE_SWORD, Items.NETHERITE_AXE};
                armorItems = new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
                        Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
                sharpness = 10; fireAspect = 3; knockback = 3; sweeping = 4;
                protection = 8; thorns = 6; unbreaking = 5; quickCharge = 5; multishot = true;
            }
            default -> {
                weapons = new Item[]{Items.DIAMOND_SWORD, Items.DIAMOND_AXE};
                armorItems = new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
                        Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
                sharpness = 5; fireAspect = 2; knockback = 2; sweeping = 2;
                protection = 4; thorns = 3; unbreaking = 3; quickCharge = 3; multishot = true;
            }
        }

        if (mob.getType() == EntityType.PILLAGER) {
            // Pillagers only have a crossbow AI goal; giving them a melee weapon
            // makes them unable to attack (they stand around "non-hostile").
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.enchant(Enchantments.QUICK_CHARGE, quickCharge);
            if (multishot) crossbow.enchant(Enchantments.MULTISHOT, 1);
            crossbow.enchant(Enchantments.UNBREAKING, unbreaking);
            mob.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
            mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
        } else {
            Item weaponItem = weapons[RANDOM.nextInt(weapons.length)];
            ItemStack weapon = new ItemStack(weaponItem);
            weapon.enchant(Enchantments.SHARPNESS, sharpness);
            if (fireAspect > 0) weapon.enchant(Enchantments.FIRE_ASPECT, fireAspect);
            if (weaponItem instanceof SwordItem) {
                if (knockback > 0) weapon.enchant(Enchantments.KNOCKBACK, knockback);
                if (sweeping > 0) weapon.enchant(Enchantments.SWEEPING_EDGE, sweeping);
            }
            weapon.enchant(Enchantments.UNBREAKING, unbreaking);
            mob.setItemSlot(EquipmentSlot.MAINHAND, weapon);
            mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
        }

        for (int i = 0; i < armorItems.length; i++) {
            ItemStack armor = new ItemStack(armorItems[i]);
            armor.enchant(Enchantments.ALL_DAMAGE_PROTECTION, protection);
            if (thorns > 0) armor.enchant(Enchantments.THORNS, thorns);
            armor.enchant(Enchantments.UNBREAKING, unbreaking);
            mob.setItemSlot(ARMOR_SLOTS[i], armor);
            mob.setDropChance(ARMOR_SLOTS[i], 0f);
        }
    }

    /** Tier buffs: strong and elite bosses get permanent potion effects. */
    private static void applyTierEffects(Mob mob, BossTier tier) {
        addTierEffect(mob, MobEffects.DAMAGE_BOOST, tier.strengthAmplifier());
        addTierEffect(mob, MobEffects.MOVEMENT_SPEED, tier.speedAmplifier());
        addTierEffect(mob, MobEffects.DAMAGE_RESISTANCE, tier.resistanceAmplifier());
    }

    private static void addTierEffect(Mob mob, net.minecraft.world.effect.MobEffect effect, int amplifier) {
        if (amplifier < 0) return;
        mob.addEffect(new MobEffectInstance(effect, Integer.MAX_VALUE, amplifier, false, false, false));
    }

    /** Called from the {@code actuallyHurt} mixin with the HP the boss actually lost. */
    public static void recordActualDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!entity.getTags().contains(BOSS_TAG)) return;
        if (!(BossProtection.attacker(source) instanceof ServerPlayer attacker)) return;
        if (!(amount > 0) || !Float.isFinite(amount)) return;
        BossInstance instance = bosses.get(entity.getUUID());
        if (instance == null) instance = adopt(entity);
        instance.damage.merge(attacker.getUUID(), (double) amount, Double::sum);
        instance.damageNames.put(attacker.getUUID(), attacker.getName().getString());
    }

    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (!entity.getTags().contains(BOSS_TAG)) return;
        MinecraftServer server = entity.level().getServer();
        if (server == null) return;

        BossInstance instance = bosses.remove(entity.getUUID());
        boolean wasPinned = entity.getUUID().equals(pinned);
        if (instance != null) {
            instance.bar.removeAllPlayers();
        }
        if (wasPinned) {
            retargetPin(server);
        }

        String name = instance != null
                ? instance.shortName
                : stripBossPrefix(entity.getCustomName() != null ? entity.getCustomName().getString()
                        : entity.getDisplayName().getString());
        payout(server, name, instance);

        // The weakest boss can come back as the strongest, right where it fell, with a power-up burst.
        if (instance != null && entity.level() instanceof ServerLevel world
                && RANDOM.nextDouble() < instance.tier.promoteChance(ConfigManager.config().bossWeakestPromoteChance)) {
            net.minecraft.core.BlockPos at = entity.blockPosition();
            if (spawnAt(server, world, at, BossTier.ELITE)) {
                world.sendParticles(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING,
                        at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 120, 1.5, 2.0, 1.5, 0.35);
                world.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
                        at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 1, 0, 0, 0, 0);
                world.playSound(null, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                        net.minecraft.sounds.SoundEvents.WITHER_SPAWN,
                        net.minecraft.sounds.SoundSource.HOSTILE, 2.0f, 0.6f);
                for (ServerPlayer nearby : world.getPlayers(p -> p.distanceToSqr(at.getX(), at.getY(), at.getZ()) < 48 * 48)) {
                    nearby.displayClientMessage(Component.literal("最強ボスが覚醒した！体力が増加する……")
                            .withStyle(ChatFormatting.DARK_PURPLE), true);
                }
                GuildService.broadcast(server, "【最弱】の怨念が【最強】を呼び覚ました！", ChatFormatting.DARK_PURPLE);
                // Nobody around the corpse? Send the awakened boss to a random unprotected player.
                Mob elite = lastSpawned;
                boolean anyoneNear = !world.getPlayers(p -> p.distanceToSqr(at.getX(), at.getY(), at.getZ()) < 48 * 48
                        && !p.isSpectator()).isEmpty();
                if (elite != null && !anyoneNear) {
                    ServerPlayer focus = randomUnprotected(server);
                    if (focus != null) {
                        elite.teleportTo(focus.getX(), focus.getY(), focus.getZ());
                        elite.getNavigation().stop();
                        retargetPin(server);
                    }
                }
            }
        }
    }

    /** A random alive player who is not under post-death boss protection. */
    private static ServerPlayer randomUnprotected(MinecraftServer server) {
        List<ServerPlayer> candidates = server.getPlayerList().getPlayers().stream()
                .filter(player -> player.isAlive() && !player.isSpectator() && !BossProtection.isProtected(player))
                .toList();
        return candidates.isEmpty() ? null : candidates.get(RANDOM.nextInt(candidates.size()));
    }

    /** Removes the "【緊急ボス…】" prefix so chat messages do not nest it twice. */
    private static String stripBossPrefix(String name) {
        if (!name.startsWith("【緊急ボス")) return name;
        int end = name.indexOf('】');
        return end >= 0 && end + 1 < name.length() ? name.substring(end + 1) : name;
    }

    private static void payout(MinecraftServer server, String name, BossInstance instance) {
        Map<UUID, Double> hits = instance == null ? new HashMap<>() : new HashMap<>(instance.damage);
        double total = 0;
        for (double value : hits.values()) total += value;
        double multiplier = instance == null ? 1.0 : instance.tier.rewardMultiplier();
        long reward = Math.max(0, Math.round(ConfigManager.config().bossEventReward * multiplier));

        if (hits.isEmpty() || total <= 0 || reward == 0) {
            GuildService.broadcast(server, "緊急ボス【" + name
                    + "】が倒された（有効ダメージがないため報酬の分配はありません）", ChatFormatting.YELLOW);
            return;
        }

        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(hits.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        long[] shares = new long[sorted.size()];
        long given = 0;
        for (int i = 1; i < sorted.size(); i++) {
            shares[i] = (long) Math.floor(reward * sorted.get(i).getValue() / total);
            given += shares[i];
        }
        shares[0] = reward - given;

        // Small flat participation bonus for everyone who dealt damage.
        long bonus = Math.max(1, reward / 20);

        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            UUID uuid = sorted.get(i).getKey();
            double dealt = sorted.get(i).getValue();
            if (shares[i] > 0 || dealt > 0) {
                long totalPaid = shares[i] + bonus;
                GuildService.creditCoins(server, uuid, totalPaid);
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                if (online != null) {
                    GuildService.message(online, "緊急ボス討伐報酬: +" + Numbers.format(shares[i])
                            + " コイン + 参加ボーナス +" + Numbers.format(bonus)
                            + "（計 " + Numbers.format(totalPaid) + " / 与ダメージ: "
                            + Numbers.format((int) Math.round(dealt)) + "）", ChatFormatting.GREEN);
                }
            }
            if (detail.length() > 0) detail.append(" / ");
            detail.append(instance.damageNames.getOrDefault(uuid, uuid.toString().substring(0, 8)))
                    .append(" ").append(Numbers.format(shares[i] + bonus)).append("コイン");
        }
        GuildService.broadcast(server, "緊急ボス【" + name + "】を討伐！報酬 " + Numbers.format(reward)
                + " コイン + 参加ボーナス各 +" + Numbers.format(bonus) + " を分配: " + detail, ChatFormatting.GOLD);
    }

    private static BossInstance adopt(LivingEntity entity) {
        String display = entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : entity.getDisplayName().getString();
        return adopt(entity, BossTier.fromDisplayName(display));
    }

    private static BossInstance adopt(LivingEntity entity, BossTier tier) {
        BossInstance existing = bosses.get(entity.getUUID());
        if (existing != null) return existing;

        String name = entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : entity.getDisplayName().getString();
        BossInstance instance = new BossInstance(entity, name, tier,
                tier.label() + " " + stripBossPrefix(name));
        bosses.put(entity.getUUID(), instance);

        // Ensure adopted bosses also resist knockback (anti-cheese).
        if (entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE) != null) {
            entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        }
        // Always glowing so the emergency boss is easy to spot.
        entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false, false));
        if (entity.getHealth() > 0) {
            instance.bar.setProgress(Mth.clamp(entity.getHealth() / entity.getMaxHealth(), 0f, 1f));
        }
        return instance;
    }

    private static void updateBars(MinecraftServer server) {
        if (bosses.isEmpty()) return;
        boolean reconcile = ++viewTicker >= VIEW_RECONCILE_INTERVAL;
        if (reconcile) viewTicker = 0;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        Iterator<Map.Entry<UUID, BossInstance>> iterator = bosses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BossInstance> entry = iterator.next();
            BossInstance instance = entry.getValue();
            LivingEntity entity = instance.entity;

            // Safety net: drop bosses whose death hook was missed.
            if (entity.isRemoved() || !entity.isAlive()) {
                instance.bar.removeAllPlayers();
                iterator.remove();
                if (entry.getKey().equals(pinned)) retargetPin(server);
                continue;
            }

            instance.bar.setProgress(Mth.clamp(entity.getHealth() / entity.getMaxHealth(), 0f, 1f));
            if (!reconcile) continue;

            for (ServerPlayer player : players) {
                ServerPlayer tracked = instance.viewers.get(player.getUUID());
                if (tracked == null) {
                    instance.bar.addPlayer(player);
                    instance.viewers.put(player.getUUID(), player);
                    // Late joiners need the map pin too.
                    if (pinLife > 0 && entry.getKey().equals(pinned)) {
                        sendBossPin(player, Mth.floor(entity.getX()), Mth.floor(entity.getY()),
                                Mth.floor(entity.getZ()), pinLife);
                    }
                } else if (tracked != player) {
                    instance.bar.removePlayer(tracked);
                    instance.bar.addPlayer(player);
                    instance.viewers.put(player.getUUID(), player);
                }
            }

            Iterator<Map.Entry<UUID, ServerPlayer>> viewers = instance.viewers.entrySet().iterator();
            while (viewers.hasNext()) {
                Map.Entry<UUID, ServerPlayer> viewer = viewers.next();
                if (server.getPlayerList().getPlayer(viewer.getKey()) == null) {
                    instance.bar.removePlayer(viewer.getValue());
                    viewers.remove();
                }
            }
        }
    }

    /** Teleports a boss out of lava/void/walls or when it has not moved for ~5 seconds. */
    private static void rescue(BossInstance instance) {
        LivingEntity boss = instance.entity;
        if (boss == null || boss.isRemoved() || !boss.isAlive()) return;
        ServerLevel world = boss.level() instanceof ServerLevel sw ? sw : null;
        if (world == null) return;

        boolean needsRescue = false;

        // Lava / void.
        if (boss.isInLava() || boss.getY() < -64) {
            needsRescue = true;
        }

        // Walled in: any solid block intersecting the boss's collision box.
        if (!needsRescue) {
            var bounds = boss.getBoundingBox();
            for (int bx = Mth.floor(bounds.minX); bx <= Mth.floor(bounds.maxX); bx++) {
                for (int by = Mth.floor(bounds.minY); by <= Mth.floor(bounds.maxY); by++) {
                    for (int bz = Mth.floor(bounds.minZ); bz <= Mth.floor(bounds.maxZ); bz++) {
                        var state = world.getBlockState(new BlockPos(bx, by, bz));
                        if (state.isSolidRender(world, new BlockPos(bx, by, bz))) {
                            needsRescue = true;
                            break;
                        }
                    }
                    if (needsRescue) break;
                }
                if (needsRescue) break;
            }
        }

        // Stuck: has not moved for ~5 seconds (100 ticks).
        if (!needsRescue) {
            double dx = boss.getX() - instance.lastStuckX;
            double dy = boss.getY() - instance.lastStuckY;
            double dz = boss.getZ() - instance.lastStuckZ;
            if (dx * dx + dy * dy + dz * dz < 0.01) {
                instance.stuckTicks++;
                if (instance.stuckTicks >= 100) {
                    needsRescue = true;
                }
            } else {
                instance.stuckTicks = 0;
                instance.lastStuckX = boss.getX();
                instance.lastStuckY = boss.getY();
                instance.lastStuckZ = boss.getZ();
            }
        }

        if (!needsRescue) return;

        ServerPlayer target = nearestPlayer(boss);
        int tx;
        int tz;
        if (target != null) {
            // Never drop the boss on top of the player: land ~12 blocks away on a random side.
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            tx = Mth.floor(target.getX() + Math.cos(angle) * 12);
            tz = Mth.floor(target.getZ() + Math.sin(angle) * 12);
        } else {
            tx = Mth.floor(boss.getX());
            tz = Mth.floor(boss.getZ());
        }
        int ty = world.getHeight(Heightmap.Types.MOTION_BLOCKING, tx, tz);
        boss.teleportTo(tx + 0.5, ty + 0.1, tz + 0.5);
        boss.clearFire();
        boss.fallDistance = 0;
        instance.resetStuckTracking();
    }

    /** Per-boss state: tier, bar, damage ledger and rescue tracking. */
    private static final class BossInstance {
        final LivingEntity entity;
        final BossTier tier;
        /** Full custom name shown on the boss bar (includes the prefix). */
        final String name;
        /** Tier label and mob name, used in chat messages. */
        final String shortName;
        final ServerBossEvent bar;
        final Map<UUID, Double> damage = new HashMap<>();
        final Map<UUID, String> damageNames = new HashMap<>();
        final Map<UUID, ServerPlayer> viewers = new HashMap<>();
        double lastStuckX;
        double lastStuckY;
        double lastStuckZ;
        int stuckTicks;

        BossInstance(LivingEntity entity, String name, BossTier tier, String shortName) {
            this.entity = entity;
            this.tier = tier;
            this.name = name;
            this.shortName = shortName;
            this.bar = new ServerBossEvent(Component.literal(name).withStyle(ChatFormatting.RED),
                    tier.barColor(), BossBarOverlay.PROGRESS);
            resetStuckTracking();
        }

        void resetStuckTracking() {
            stuckTicks = 0;
            lastStuckX = entity.getX();
            lastStuckY = entity.getY();
            lastStuckZ = entity.getZ();
        }
    }
}
