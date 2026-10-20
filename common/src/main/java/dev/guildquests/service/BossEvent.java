package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.platform.Platform;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.BossEvent.BossBarColor;
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
    private static final String BOSS_TAG = BossCombat.BOSS_TAG;
    private static final String BOSS_NAME_PREFIX = "【緊急ボス】";
    private static final int TICKS_PER_MINUTE = 1200;
    /** S2C: show/clear the client-side boss pin (x,y,z + lifetime ticks; lifetime 0 = clear). */
    public static final ResourceLocation BOSS_PIN = new ResourceLocation("guildquests", "boss_pin");
    /** Upper bound so a command typo cannot flood the server with bosses. */
    public static final int MAX_ACTIVE = 32;
    private static final int PIN_LIFETIME_TICKS = 6000;
    /** Viewers only need re-checking once per second. */
    private static final int VIEW_RECONCILE_INTERVAL = 20;

    private static final List<EntityType<?>> BOSSES = List.of(
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER,
            EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN,
            EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
            EntityType.EVOKER, EntityType.RAVAGER);

    /** Boss weapon: always diamond tier (sword or axe). */
    private static final Item[] WEAPONS = {Items.DIAMOND_SWORD, Items.DIAMOND_AXE};

    /** Full diamond armor, always all four slots. */
    private static final Item[] ARMOR_SET = {
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static int ticker;
    private static int viewTicker;
    private static int adoptTicker;
    /** Living bosses keyed by entity UUID, in spawn order. */
    private static final Map<UUID, BossInstance> bosses = new LinkedHashMap<>();
    /** Boss the shared client-side pin currently points at, or null. */
    private static UUID pinned;
    private static int pinLife;

    private BossEvent() {
    }

    public static void init() {
        Platform.onServerTick(BossEvent::tick);
        Platform.onServerStarted(BossEvent::adoptPersisted);
        Platform.onServerStopped(server -> {
            clearBosses();
            BossCombat.reset();
            BossProjectiles.reset();
            ticker = viewTicker = adoptTicker = pinLife = 0;
            pinned = null;
        });
        Platform.onAllowDamage((entity, source, amount) -> {
            if (!BossCombat.allowDamage(entity, source, amount)) return false;
            // Environmental kill cheese (lava, void, fall, etc.) is ignored for the boss.
            if (entity.getTags().contains(BOSS_TAG) && !(BossCombat.owner(source) instanceof ServerPlayer)
                    && isEnvironmental(source)) {
                return false;
            }
            return true;
        });
        Platform.onEntityDeath(BossCombat::onDeath);
        Platform.onEntityDeath(BossEvent::onDeath);
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
                || source.is(DamageTypes.FALLING_ANVIL)
                || source.is(DamageTypes.FALLING_STALACTITE)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.DRAGON_BREATH)
                || source.is(DamageTypes.CRAMMING)
                || source.is(DamageTypes.WITHER_SKULL);
    }

    private static void tick(MinecraftServer server) {
        BossCombat.tick(server);
        for (BossInstance instance : bosses.values()) {
            if (instance.entity instanceof Mob mob && mob.getTarget() instanceof ServerPlayer player
                    && BossCombat.isProtected(player)) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
        }
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
        if (count <= 0) return 0;
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) return 0;

        List<ServerPlayer> players = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.level() == world && p.isAlive() && !p.isSpectator() && !BossCombat.isProtected(p))
                .toList();
        if (players.isEmpty()) return 0;

        adoptExisting(world);

        int spawned = 0;
        for (int i = 0; i < count && bosses.size() < MAX_ACTIVE; i++) {
            if (spawnOne(server, world, players)) {
                spawned++;
            }
        }
        return spawned;
    }

    private static boolean spawnOne(MinecraftServer server, ServerLevel world, List<ServerPlayer> players) {
        ServerPlayer anchor = players.get(RANDOM.nextInt(players.size()));
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = 50 + RANDOM.nextDouble() * 50;
        int x = Mth.clamp((int) Math.floor(anchor.getX() + Math.cos(angle) * distance),
                -29999000, 29999000);
        int z = Mth.clamp((int) Math.floor(anchor.getZ() + Math.sin(angle) * distance),
                -29999000, 29999000);
        world.getChunk(x >> 4, z >> 4);
        int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

        EntityType<?> type = BOSSES.get(RANDOM.nextInt(BOSSES.size()));
        if (!(type.create(world) instanceof Mob mob)) return false;

        ModConfig config = ConfigManager.config();
        String mobName = type.getDescription().getString();
        String bossName = BOSS_NAME_PREFIX + mobName;
        mob.moveTo(x + 0.5, y, z + 0.5, RANDOM.nextFloat() * 360.0f, 0.0f);
        mob.setCustomName(Component.literal(bossName).withStyle(ChatFormatting.RED));
        mob.setCustomNameVisible(true);
        mob.addTag(BOSS_TAG);
        mob.setPersistenceRequired();

        double health = Math.max(1, config.bossEventHealth);
        if (mob.getAttribute(Attributes.MAX_HEALTH) != null) {
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        }
        if (mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE) != null) {
            mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        }
        mob.setHealth((float) health);
        equip(mob);
        world.addFreshEntity(mob);

        adopt(mob);

        // Show a client-rendered pin at the newest boss (5 minutes or until death).
        pinLife = PIN_LIFETIME_TICKS;
        pinned = mob.getUUID();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendBossPin(p, x, y, z, pinLife);
        }
        GuildService.broadcast(server, "緊急ボス【" + mobName + "】が出現！座標: (" + x + ", " + y + ", " + z
                + ")  与えたダメージの割合で報酬 " + Math.max(0, config.bossEventReward)
                + " コインを分配します。", ChatFormatting.RED);
        return true;
    }

    /** Operators can remove all loaded emergency bosses without damage or death rewards. */
    public static boolean stopNow(MinecraftServer server) {
        List<Entity> targets = new ArrayList<>();
        for (ServerLevel world : server.getAllLevels()) {
            world.getAllEntities().forEach(entity -> { if (BossCombat.isBoss(entity)) targets.add(entity); });
        }
        for (BossInstance instance : bosses.values()) {
            if (!instance.entity.isRemoved() && !targets.contains(instance.entity)) targets.add(instance.entity);
        }
        if (targets.isEmpty() && bosses.isEmpty()) return false;
        targets.forEach(Entity::discard);
        clearBossPin(server);
        clearBosses();
        ticker = 0;
        return true;
    }

    private static void clearBosses() {
        bosses.forEach((id, instance) -> {
            instance.bar.removeAllPlayers();
            BossCombat.forgetBoss(id);
        });
        bosses.clear();
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
            if (p.isRemoved() || !p.isAlive() || p.isSpectator() || BossCombat.isProtected(p)) continue;
            if (p.level() != from.level()) continue;
            double d = p.distanceToSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
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

    /** Equips the boss with a unified, always-full enchanted diamond set. */
    private static void equip(Mob mob) {
        if (mob.getType() == EntityType.PILLAGER) {
            // Pillagers only have a crossbow AI goal; giving them a melee weapon
            // makes them unable to attack (they stand around "non-hostile").
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.enchant(Enchantments.QUICK_CHARGE, 3);
            crossbow.enchant(Enchantments.MULTISHOT, 1);
            crossbow.enchant(Enchantments.UNBREAKING, 3);
            mob.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
            mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
        } else {
            Item weaponItem = WEAPONS[RANDOM.nextInt(WEAPONS.length)];
            ItemStack weapon = new ItemStack(weaponItem);
            weapon.enchant(Enchantments.SHARPNESS, 5);
            weapon.enchant(Enchantments.FIRE_ASPECT, 2);
            weapon.enchant(Enchantments.UNBREAKING, 3);
            if (weaponItem instanceof SwordItem) {
                weapon.enchant(Enchantments.KNOCKBACK, 2);
                weapon.enchant(Enchantments.SWEEPING_EDGE, 2);
            }
            mob.setItemSlot(EquipmentSlot.MAINHAND, weapon);
            mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
        }

        for (int i = 0; i < ARMOR_SET.length; i++) {
            ItemStack armor = new ItemStack(ARMOR_SET[i]);
            armor.enchant(Enchantments.ALL_DAMAGE_PROTECTION, 4);
            armor.enchant(Enchantments.THORNS, 3);
            armor.enchant(Enchantments.UNBREAKING, 3);
            mob.setItemSlot(ARMOR_SLOTS[i], armor);
            mob.setDropChance(ARMOR_SLOTS[i], 0f);
        }
    }

    public static void recordDamage(LivingEntity entity, ServerPlayer attacker, float amount) {
        if (!entity.getTags().contains(BOSS_TAG)) return;
        if (!(amount > 0) || !Float.isFinite(amount) || entity.isDeadOrDying()) return;
        // Runs inside actuallyHurt after mitigation, absorption, cooldown and overkill clamping.
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
        BossCombat.forgetBoss(entity.getUUID());
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
    }

    /** Removes the "【緊急ボス】" prefix so chat messages do not nest it twice. */
    private static String stripBossPrefix(String name) {
        return name.startsWith(BOSS_NAME_PREFIX) ? name.substring(BOSS_NAME_PREFIX.length()) : name;
    }

    private static void payout(MinecraftServer server, String name, BossInstance instance) {
        Map<UUID, Double> hits = instance == null ? new HashMap<>() : new HashMap<>(instance.damage);
        double total = 0;
        for (double value : hits.values()) total += value;
        long reward = Math.max(0, ConfigManager.config().bossEventReward);

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
                    GuildService.message(online, "緊急ボス討伐報酬: +" + shares[i]
                            + " コイン + 参加ボーナス +" + bonus
                            + "（計 " + totalPaid + " / 与ダメージ: "
                            + (int) Math.round(dealt) + "）", ChatFormatting.GREEN);
                }
            }
            if (detail.length() > 0) detail.append(" / ");
            detail.append(instance.damageNames.getOrDefault(uuid, uuid.toString().substring(0, 8)))
                    .append(" ").append(shares[i] + bonus).append("コイン");
        }
        GuildService.broadcast(server, "緊急ボス【" + name + "】を討伐！報酬 " + reward
                + " コイン + 参加ボーナス各 +" + bonus + " を分配: " + detail, ChatFormatting.GOLD);
    }

    private static BossInstance adopt(LivingEntity entity) {
        BossInstance existing = bosses.get(entity.getUUID());
        if (existing != null) return existing;

        String name = entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : entity.getDisplayName().getString();
        BossInstance instance = new BossInstance(entity, name, stripBossPrefix(name));
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
                BossCombat.forgetBoss(entry.getKey());
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
            double angle = RANDOM.nextDouble() * Math.PI * 2;
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

    /** Per-boss state: bar, damage ledger and rescue tracking. */
    private static final class BossInstance {
        final LivingEntity entity;
        /** Full custom name shown on the boss bar (includes the prefix). */
        final String name;
        /** Mob name without the prefix, used in chat messages. */
        final String shortName;
        final ServerBossEvent bar;
        final Map<UUID, Double> damage = new HashMap<>();
        final Map<UUID, String> damageNames = new HashMap<>();
        final Map<UUID, ServerPlayer> viewers = new HashMap<>();
        double lastStuckX;
        double lastStuckY;
        double lastStuckZ;
        int stuckTicks;

        BossInstance(LivingEntity entity, String name, String shortName) {
            this.entity = entity;
            this.name = name;
            this.shortName = shortName;
            this.bar = new ServerBossEvent(Component.literal(name).withStyle(ChatFormatting.RED),
                    BossBarColor.RED, BossBarOverlay.PROGRESS);
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
