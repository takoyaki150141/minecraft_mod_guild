package dev.guildquests.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GuildPersistentState extends SavedData {
    private static final String SAVE_ID = "guildquests_players";
    private final Map<UUID, PlayerGuildData> players = new HashMap<>();

    /** In-game day number the current daily board was drawn for. -1 = never drawn. */
    public long dailyBoardDay = -1;
    /** Today's server-wide drawn quests. Empty until the first draw. */
    public final List<DailyQuest> dailyBoard = new ArrayList<>();
    /** Player-published quests (bounty board). */
    public final List<PublishedQuest> publishedQuests = new ArrayList<>();
    /** Active auction-house listings (items escrowed server-side). */
    public final List<AuctionListing> auctions = new ArrayList<>();
    /** Next auction listing id to hand out. */
    public long nextAuctionId = 1;

    public PlayerGuildData get(UUID uuid) {
        return players.computeIfAbsent(uuid, ignored -> new PlayerGuildData());
    }

    /** Live view used for scheduled jobs such as ribo collection. */
    public Map<UUID, PlayerGuildData> players() {
        return players;
    }

    /** Today's drawn quest by quest-definition id, or null when not on the board. */
    public DailyQuest findDaily(String questId) {
        for (DailyQuest quest : dailyBoard) {
            if (quest.questId.equals(questId)) {
                return quest;
            }
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag playersNbt = new CompoundTag();
        for (Map.Entry<UUID, PlayerGuildData> entry : players.entrySet()) {
            playersNbt.put(entry.getKey().toString(), entry.getValue().toNbt(registries));
        }
        nbt.put("Players", playersNbt);
        nbt.putLong("DailyBoardDay", dailyBoardDay);
        ListTag dailyNbt = new ListTag();
        for (DailyQuest quest : dailyBoard) {
            dailyNbt.add(quest.toNbt());
        }
        nbt.put("DailyQuests", dailyNbt);
        ListTag publishedNbt = new ListTag();
        for (PublishedQuest quest : publishedQuests) {
            publishedNbt.add(quest.toNbt());
        }
        nbt.put("PublishedQuests", publishedNbt);
        ListTag auctionNbt = new ListTag();
        for (AuctionListing listing : auctions) {
            auctionNbt.add(listing.toNbt(registries));
        }
        nbt.put("Auctions", auctionNbt);
        nbt.putLong("NextAuctionId", Math.max(1, nextAuctionId));
        return nbt;
    }

    public static GuildPersistentState fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        GuildPersistentState state = new GuildPersistentState();
        CompoundTag playersNbt = nbt.getCompound("Players");
        for (String key : playersNbt.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                state.players.put(uuid, PlayerGuildData.fromNbt(playersNbt.getCompound(key), registries));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID keys rather than preventing the world from loading.
            }
        }
        state.dailyBoardDay = nbt.getLong("DailyBoardDay");
        ListTag dailyNbt = nbt.getList("DailyQuests", Tag.TAG_COMPOUND);
        for (int i = 0; i < dailyNbt.size(); i++) {
            state.dailyBoard.add(DailyQuest.fromNbt(dailyNbt.getCompound(i)));
        }
        ListTag publishedNbt = nbt.getList("PublishedQuests", Tag.TAG_COMPOUND);
        for (int i = 0; i < publishedNbt.size(); i++) {
            state.publishedQuests.add(PublishedQuest.fromNbt(publishedNbt.getCompound(i)));
        }
        ListTag auctionNbt = nbt.getList("Auctions", Tag.TAG_COMPOUND);
        for (int i = 0; i < auctionNbt.size(); i++) {
            AuctionListing listing = AuctionListing.fromNbt(auctionNbt.getCompound(i), registries);
            if (listing.sellerId != null && !listing.item.isEmpty()) state.auctions.add(listing);
        }
        state.nextAuctionId = Math.max(1, nbt.getLong("NextAuctionId"));
        return state;
    }

    public static GuildPersistentState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GuildPersistentState::new, GuildPersistentState::fromNbt, DataFixTypes.LEVEL),
                SAVE_ID
        );
    }
}
