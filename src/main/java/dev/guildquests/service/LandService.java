package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.gui.LandScreenHandler;
import dev.guildquests.util.Numbers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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
        state.setDirty();
        GuildService.message(player, "チャンク (" + chunkX + ", " + chunkZ + ") を "
                + Numbers.format(price) + " コインで購入しました。次の価格: "
                + Numbers.format(price(config, owned + 1)) + " コイン", ChatFormatting.GREEN);
        if (player.containerMenu instanceof LandScreenHandler menu) menu.refresh();
        return true;
    }

    // ------------------------------------------------------------------
    // Protection
    // ------------------------------------------------------------------

    public static boolean allowBreak(ServerPlayer player, BlockPos pos, BlockState state) {
        return allowModify(player, pos);
    }

    public static boolean allowPlace(ServerPlayer player, BlockPos pos, BlockState state) {
        return allowModify(player, pos);
    }

    public static boolean allowUse(ServerPlayer player, BlockPos pos) {
        return allowModify(player, pos);
    }

    private static boolean allowModify(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return true;
        ModConfig config = ConfigManager.config();
        if (!config.landProtectionEnabled) return true;
        if (player.hasPermissions(2)) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return true;

        GuildPersistentState state = GuildPersistentState.get(server);
        UUID owner = state.landOwners.get(key(player.level(), pos.getX() >> 4, pos.getZ() >> 4));
        if (owner == null || owner.equals(player.getUUID())) return true;

        long now = System.currentTimeMillis();
        Long last = LAST_DENIED_AT.get(player.getUUID());
        if (last == null || now - last >= 1000) {
            LAST_DENIED_AT.put(player.getUUID(), now);
            player.displayClientMessage(Component.literal("このチャンクは " + ownerName(state, owner)
                    + " が所有しています").withStyle(ChatFormatting.RED), true);
        }
        return false;
    }

    private static String ownerName(GuildPersistentState state, UUID owner) {
        if (owner == null) return "他のプレイヤー";
        String name = state.get(owner).playerName;
        return name == null || name.isBlank() ? "他のプレイヤー" : name;
    }

    /** Clears the denial-message throttle when a player leaves. */
    public static void forget(UUID player) {
        if (player != null) LAST_DENIED_AT.remove(player);
    }
}
