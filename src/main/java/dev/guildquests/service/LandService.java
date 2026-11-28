package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.gui.LandScreenHandler;
import dev.guildquests.util.Numbers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Land ownership: one chunk at a time, protected from other players. Ownership lives in
 * {@link GuildPersistentState#landOwners}, keyed by dimension and chunk coordinates.
 * Prices grow with the number of chunks the buyer already owns.
 */
public final class LandService {
    private static final Map<UUID, Long> LAST_DENIED_AT = new ConcurrentHashMap<>();

    private LandService() { }

    public static String key(Level level, int chunkX, int chunkZ) {
        return key(level.dimension().location().toString(), chunkX, chunkZ);
    }

    public static String key(String dimension, int chunkX, int chunkZ) {
        return dimension + "|" + chunkX + "|" + chunkZ;
    }

    public static UUID ownerAt(GuildPersistentState state, String key) {
        return state == null ? null : state.landOwners.get(key);
    }

    public static int ownedCount(GuildPersistentState state, UUID player) {
        if (state == null || player == null) return 0;
        int count = 0;
        for (UUID owner : state.landOwners.values()) {
            if (player.equals(owner)) count++;
        }
        return count;
    }

    /** Price of the next chunk: a base price plus a step per already-owned chunk. */
    public static long price(ModConfig config, int ownedChunks) {
        long base = Math.max(0, config.landBasePrice);
        long step = Math.max(0, config.landPriceStep);
        return base + step * Math.max(0, ownedChunks);
    }

    public static long nextPrice(ServerPlayer player) {
        if (player == null || player.getServer() == null) return price(ConfigManager.config(), 0);
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        return price(ConfigManager.config(), ownedCount(state, player.getUUID()));
    }

    /** Handles a purchase request from the map screen. All validation stays on the server. */
    public static boolean buy(ServerPlayer player, int chunkX, int chunkZ) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ModConfig config = ConfigManager.config();
        GuildPersistentState state = GuildPersistentState.get(server);

        ChunkPos center = new ChunkPos(player.blockPosition());
        int radius = Math.max(1, Math.min(8, config.landMapRadius));
        if (Math.abs(chunkX - center.x) > radius || Math.abs(chunkZ - center.z) > radius) {
            GuildService.message(player, "購入できるのはマップに表示されているチャンクだけです。", ChatFormatting.RED);
            return false;
        }

        String key = key(player.level(), chunkX, chunkZ);
        UUID owner = state.landOwners.get(key);
        if (player.getUUID().equals(owner)) {
            GuildService.message(player, "このチャンクはすでにあなたの所有です。", ChatFormatting.YELLOW);
            return false;
        }
        if (owner != null) {
            GuildService.message(player, "このチャンクは " + ownerName(state, owner) + " が所有しています。", ChatFormatting.RED);
            return false;
        }

        int owned = ownedCount(state, player.getUUID());
        if (config.landMaxChunks > 0 && owned >= config.landMaxChunks) {
            GuildService.message(player, "所有できるチャンクの上限（" + Numbers.format(config.landMaxChunks) + "）に達しています。",
                    ChatFormatting.RED);
            return false;
        }

        long price = price(config, owned);
        if (price > 0 && !GuildService.payCoins(player, price)) {
            GuildService.message(player, "コインが足りません（必要 " + Numbers.format(price)
                    + " / 所持 " + Numbers.format(GuildService.data(player).coins) + "）。", ChatFormatting.RED);
            return false;
        }

        state.landOwners.put(key, player.getUUID());
        state.landPaid.put(key, Math.max(0, price));
        state.setDirty();
        GuildService.message(player, "チャンク (" + chunkX + ", " + chunkZ + ") を "
                + Numbers.format(price) + " コインで購入しました。次の価格: "
                + Numbers.format(price(config, owned + 1)) + " コイン", ChatFormatting.GREEN);
        refreshMenu(player);
        return true;
    }

    // ------------------------------------------------------------------
    // Selling
    // ------------------------------------------------------------------

    /** Payout when selling back to the system: a percentage of the price the owner paid. */
    public static long instantSellPrice(GuildPersistentState state, ModConfig config, String key) {
        if (state == null || config == null) return 0;
        Long paid = state.landPaid.get(key);
        long base = paid != null && paid > 0 ? paid : Math.max(0, config.landBasePrice);
        int percent = Math.max(0, Math.min(100, config.landSellPercent));
        return base * percent / 100;
    }

    /** Sells an owned chunk back to the system at the reduced rate. */
    public static boolean sellToSystem(ServerPlayer player, int chunkX, int chunkZ) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) return false;
        GuildPersistentState state = GuildPersistentState.get(server);
        String key = key(player.level(), chunkX, chunkZ);
        if (!player.getUUID().equals(state.landOwners.get(key))) {
            GuildService.message(player, "自分のチャンクだけ売却できます。", ChatFormatting.RED);
            return false;
        }
        long payout = instantSellPrice(state, ConfigManager.config(), key);
        state.landOwners.remove(key);
        state.landPaid.remove(key);
        state.landListings.remove(key);
        state.setDirty();
        GuildService.creditCoins(server, player.getUUID(), payout);
        GuildService.message(player, "チャンク (" + chunkX + ", " + chunkZ + ") をシステムに "
                + Numbers.format(payout) + " コインで売却しました。", ChatFormatting.GREEN);
        refreshMenu(player);
        return true;
    }

    /** Offers an owned chunk to other players at a fixed price. */
    public static boolean listForSale(ServerPlayer player, int chunkX, int chunkZ, long price) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) return false;
        GuildPersistentState state = GuildPersistentState.get(server);
        String key = key(player.level(), chunkX, chunkZ);
        if (!player.getUUID().equals(state.landOwners.get(key))) {
            GuildService.message(player, "自分のチャンクだけ出品できます。", ChatFormatting.RED);
            return false;
        }
        if (price <= 0 || price > 1_000_000_000L) {
            GuildService.message(player, "価格は 1 〜 1,000,000,000 コインで指定してください。", ChatFormatting.RED);
            return false;
        }
        state.landListings.put(key, new GuildPersistentState.LandListing(
                player.level().dimension().location().toString(), chunkX, chunkZ,
                player.getUUID(), price, System.currentTimeMillis()));
        state.setDirty();
        GuildService.message(player, "チャンク (" + chunkX + ", " + chunkZ + ") を "
                + Numbers.format(price) + " コインで出品しました。", ChatFormatting.GREEN);
        refreshMenu(player);
        return true;
    }

    public static boolean cancelListing(ServerPlayer player, int chunkX, int chunkZ) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) return false;
        GuildPersistentState state = GuildPersistentState.get(server);
        String key = key(player.level(), chunkX, chunkZ);
        GuildPersistentState.LandListing listing = state.landListings.get(key);
        if (listing == null || !listing.seller.equals(player.getUUID())) {
            GuildService.message(player, "このチャンクの出品はありません。", ChatFormatting.RED);
            return false;
        }
        state.landListings.remove(key);
        state.setDirty();
        GuildService.message(player, "チャンク (" + chunkX + ", " + chunkZ + ") の出品を取り消しました。",
                ChatFormatting.YELLOW);
        refreshMenu(player);
        return true;
    }

    /** Buys a listed chunk from another player at the listed price. */
    public static boolean buyListing(ServerPlayer player, int chunkX, int chunkZ) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) return false;
        GuildPersistentState state = GuildPersistentState.get(server);
        String key = key(player.level(), chunkX, chunkZ);
        GuildPersistentState.LandListing listing = state.landListings.get(key);
        if (listing == null) {
            GuildService.message(player, "このチャンクは出品されていません。", ChatFormatting.RED);
            return false;
        }
        if (listing.seller.equals(player.getUUID())) {
            GuildService.message(player, "自分の出品は購入できません。", ChatFormatting.RED);
            return false;
        }
        if (!listing.seller.equals(state.landOwners.get(key))) {
            // The owner changed while the listing stayed behind.
            state.landListings.remove(key);
            state.setDirty();
            GuildService.message(player, "この出品は無効になっていました。", ChatFormatting.RED);
            refreshMenu(player);
            return false;
        }
        if (!GuildService.payCoins(player, listing.price)) {
            GuildService.message(player, "コインが足りません（必要 " + Numbers.format(listing.price)
                    + " / 所持 " + Numbers.format(GuildService.data(player).coins) + "）。", ChatFormatting.RED);
            return false;
        }

        state.landOwners.put(key, player.getUUID());
        state.landPaid.put(key, listing.price);
        state.landListings.remove(key);
        state.setDirty();
        GuildService.creditCoins(server, listing.seller, listing.price);
        GuildService.message(player, "チャンク (" + chunkX + ", " + chunkZ + ") を "
                + Numbers.format(listing.price) + " コインで購入しました。", ChatFormatting.GREEN);
        ServerPlayer seller = server.getPlayerList().getPlayer(listing.seller);
        if (seller != null) {
            GuildService.message(seller, "チャンク (" + chunkX + ", " + chunkZ + ") が "
                    + Numbers.format(listing.price) + " コインで売れました。", ChatFormatting.GREEN);
            if (seller.containerMenu instanceof LandScreenHandler menu) menu.refresh();
        }
        refreshMenu(player);
        return true;
    }

    private static void refreshMenu(ServerPlayer player) {
        if (player.containerMenu instanceof LandScreenHandler menu) menu.refresh();
    }

    // ------------------------------------------------------------------
    // Protection
    // ------------------------------------------------------------------

    /** Blocks from being broken, if the owner protects breaking. */
    public static boolean allowBreak(ServerPlayer player, BlockPos pos, BlockState state) {
        return allow(player, player == null ? null : player.level(), pos, LandRules.BREAK, "破壊");
    }

    /** Blocks from being placed. */
    public static boolean allowPlace(ServerPlayer player, BlockPos pos, BlockState state) {
        return allow(player, player == null ? null : player.level(), pos, LandRules.PLACE, "設置");
    }

    /** Containers and interactive blocks, split into two independent rules. */
    public static boolean allowUse(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return true;
        boolean container = isContainer(player.level(), pos);
        return allow(player, player.level(), pos,
                container ? LandRules.CONTAINERS : LandRules.INTERACT,
                container ? "コンテナの開閉" : "ブロックの操作");
    }

    /** Animals, villagers, item frames and armour stands. */
    public static boolean allowEntityUse(ServerPlayer player, Entity target) {
        if (player == null || target == null) return true;
        return allow(player, target.level(), target.blockPosition(), LandRules.ENTITY, "生物の操作");
    }

    /** Incoming damage hook: PvP and mob damage inside another player's land. */
    public static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        if (victim == null || source == null) return true;
        if (!(source.getEntity() instanceof ServerPlayer attacker)) return true;
        boolean pvp = victim instanceof Player;
        return allow(attacker, victim.level(), victim.blockPosition(),
                pvp ? LandRules.PVP : LandRules.MOB_DAMAGE, pvp ? "PvP" : "生物への攻撃");
    }

    /** Flips one switch for the player's own land and saves it. */
    public static boolean toggleRule(ServerPlayer player, int bit) {
        if (player == null || player.getServer() == null) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        data.landRuleMask = LandRules.toggle(effectiveRules(state, player.getUUID()), bit);
        state.setDirty();
        return true;
    }

    /** The player's own rules, already merged with the config defaults. */
    public static int ownRules(ServerPlayer player) {
        if (player == null || player.getServer() == null) return LandRules.defaults(ConfigManager.config());
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        return effectiveRules(state, player.getUUID());
    }

    /** True when {@code owner} allows {@code other} to build and interact on their land. */
    public static boolean isTrusted(GuildPersistentState state, UUID owner, UUID other) {
        if (state == null || owner == null || other == null || owner.equals(other)) return false;
        return state.get(owner).landTrusted.contains(other);
    }

    /** Adds or removes one player from the owner's whitelist. */
    public static boolean toggleTrusted(ServerPlayer owner, UUID target) {
        if (owner == null || owner.getServer() == null || target == null || target.equals(owner.getUUID())) {
            return false;
        }
        GuildPersistentState state = GuildPersistentState.get(owner.getServer());
        PlayerGuildData data = state.get(owner.getUUID());
        if (!data.landTrusted.remove(target)) data.landTrusted.add(target);
        state.setDirty();
        return true;
    }

    /** One whitelist row: the target player, their name and whether they are allowed. */
    public record TrustEntry(UUID id, String name, boolean allowed) { }

    /**
     * Players that can appear on the whitelist: everyone online plus already-trusted
     * players who are offline. Sorted by name so the client and server agree on indices.
     */
    public static List<TrustEntry> trustEntries(ServerPlayer owner, GuildPersistentState state) {
        if (owner == null || owner.getServer() == null || state == null) return List.of();
        PlayerGuildData data = state.get(owner.getUUID());
        Map<UUID, String> names = new java.util.LinkedHashMap<>();
        for (ServerPlayer online : owner.getServer().getPlayerList().getPlayers()) {
            if (online.getUUID().equals(owner.getUUID())) continue;
            names.put(online.getUUID(), online.getGameProfile().getName());
        }
        for (UUID id : data.landTrusted) {
            names.putIfAbsent(id, trustName(state, id));
        }
        return names.entrySet().stream()
                .map(entry -> new TrustEntry(entry.getKey(), entry.getValue(),
                        data.landTrusted.contains(entry.getKey())))
                .sorted(java.util.Comparator.comparing(TrustEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Footer text for the map screen. */
    public static String rulesSummary(ServerPlayer player) {
        ModConfig config = ConfigManager.config();
        if (!config.landProtectionEnabled) return "保護: 無効（サーバー設定）";
        return "自分の保護: " + LandRules.names(ownRules(player));
    }

    private static boolean allow(ServerPlayer player, Level level, BlockPos pos, int rule, String action) {
        if (player == null || level == null || pos == null) return true;
        UUID owner = blockingOwner(player, level, pos);
        if (owner == null) return true;
        if (!hasRule(player, owner, rule)) return true;
        deny(player, owner, action);
        return false;
    }

    /** True when the land owner protects this kind of action. */
    private static boolean hasRule(ServerPlayer player, UUID owner, int rule) {
        GuildPersistentState state = player.getServer() == null
                ? null : GuildPersistentState.get(player.getServer());
        if (state == null) return false;
        return LandRules.has(effectiveRules(state, owner), rule);
    }

    private static int effectiveRules(GuildPersistentState state, UUID owner) {
        return LandRules.resolve(state.get(owner).landRuleMask, ConfigManager.config());
    }

    private static UUID blockingOwner(ServerPlayer player, Level level, BlockPos pos) {
        ModConfig config = ConfigManager.config();
        if (!config.landProtectionEnabled) return null;
        if (player.hasPermissions(2)) return null;
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        GuildPersistentState state = GuildPersistentState.get(server);
        UUID owner = state.landOwners.get(key(level, pos.getX() >> 4, pos.getZ() >> 4));
        if (owner == null || owner.equals(player.getUUID())) return null;
        if (isTrusted(state, owner, player.getUUID())) return null;
        return owner;
    }

    private static String trustName(GuildPersistentState state, UUID id) {
        String name = state.get(id).playerName;
        if (name != null && !name.isBlank()) return name;
        String raw = id.toString();
        return raw.substring(0, 8);
    }

    private static boolean isContainer(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (level.getBlockEntity(pos) instanceof Container) return true;
        return level.getBlockState(pos).getMenuProvider(level, pos) != null;
    }

    private static void deny(ServerPlayer player, UUID owner, String action) {
        long now = System.currentTimeMillis();
        Long last = LAST_DENIED_AT.get(player.getUUID());
        if (last != null && now - last < 1000) return;
        LAST_DENIED_AT.put(player.getUUID(), now);
        MinecraftServer server = player.getServer();
        String name = server == null ? "他のプレイヤー" : ownerName(GuildPersistentState.get(server), owner);
        player.displayClientMessage(Component.literal("このチャンクは " + name + " が所有しています（"
                + action + "は禁止）").withStyle(ChatFormatting.RED), true);
    }

    private static String ownerName(GuildPersistentState state, UUID owner) {
        if (owner == null) return "他のプレイヤー";
        String name = state.get(owner).playerName;
        return name == null || name.isBlank() ? "他のプレイヤー" : name;
    }

    /** Clears per-player throttles when a player leaves. */
    public static void forget(UUID player) {
        if (player == null) return;
        LAST_DENIED_AT.remove(player);
    }

    public static void forgetPlayer(ServerPlayer player) {
        if (player != null) forget(player.getUUID());
    }
}
