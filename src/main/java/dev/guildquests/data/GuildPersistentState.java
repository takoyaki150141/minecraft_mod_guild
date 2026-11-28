package dev.guildquests.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /** Listings whose item tag could not be decoded; kept verbatim for recovery. */
    public final List<CompoundTag> unreadableAuctions = new ArrayList<>();
    /** Purchased land, keyed by "dimension|chunkX|chunkZ" to the owner UUID. */
    public final Map<String, UUID> landOwners = new LinkedHashMap<>();
    /** Chunks currently offered for sale by their owner. */
    public final Map<String, LandListing> landListings = new LinkedHashMap<>();
    /** Price each chunk was bought for; the instant-sell payout is a percentage of it. */
    public final Map<String, Long> landPaid = new LinkedHashMap<>();
    /** Next auction listing id to hand out. */
    public long nextAuctionId = 1;

    /** A chunk a player offers to other players at a fixed price. */
    public static final class LandListing {
        public final String dimension;
        public final int chunkX;
        public final int chunkZ;
        public final UUID seller;
        public final long price;
        public final long listedAt;

        public LandListing(String dimension, int chunkX, int chunkZ, UUID seller, long price, long listedAt) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.seller = seller;
            this.price = price;
            this.listedAt = listedAt;
        }

        public String key() {
            return dimension + "|" + chunkX + "|" + chunkZ;
        }
    }

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
        ListTag unreadableNbt = new ListTag();
        for (CompoundTag raw : unreadableAuctions) {
            unreadableNbt.add(raw.copy());
        }
        nbt.put("UnreadableAuctions", unreadableNbt);
        ListTag landNbt = new ListTag();
        for (Map.Entry<String, UUID> entry : landOwners.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            if (parts.length != 3) continue;
            try {
                CompoundTag land = new CompoundTag();
                land.putString("Dim", parts[0]);
                land.putInt("X", Integer.parseInt(parts[1]));
                land.putInt("Z", Integer.parseInt(parts[2]));
                land.putUUID("Owner", entry.getValue());
                landNbt.add(land);
            } catch (RuntimeException ignored) {
                // Malformed key: skip rather than preventing the world from loading.
            }
        }
        nbt.put("LandOwners", landNbt);
        ListTag listingNbt = new ListTag();
        for (LandListing listing : landListings.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dim", listing.dimension);
            tag.putInt("X", listing.chunkX);
            tag.putInt("Z", listing.chunkZ);
            tag.putUUID("Seller", listing.seller);
            tag.putLong("Price", Math.max(0, listing.price));
            tag.putLong("ListedAt", listing.listedAt);
            listingNbt.add(tag);
        }
        nbt.put("LandListings", listingNbt);
        CompoundTag paidNbt = new CompoundTag();
        for (Map.Entry<String, Long> entry : landPaid.entrySet()) {
            paidNbt.putLong(entry.getKey(), Math.max(0, entry.getValue()));
        }
        nbt.put("LandPaid", paidNbt);
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
            if (listing.sellerId == null) continue;
            if (listing.item.isEmpty()) {
                CompoundTag raw = listing.rawItem != null ? listing.rawItem.copy() : auctionNbt.getCompound(i).copy();
                state.unreadableAuctions.add(raw);
                dev.guildquests.GuildQuestsMod.LOGGER.warn(
                        "[Auction] Listing {} has an undecodable item ({}); kept raw for recovery",
                        listing.id, raw.getString("id"));
                continue;
            }
            state.auctions.add(listing);
        }
        ListTag unreadableNbt = nbt.getList("UnreadableAuctions", Tag.TAG_COMPOUND);
        for (int i = 0; i < unreadableNbt.size(); i++) {
            state.unreadableAuctions.add(unreadableNbt.getCompound(i).copy());
        }
        ListTag landNbt = nbt.getList("LandOwners", Tag.TAG_COMPOUND);
        for (int i = 0; i < landNbt.size(); i++) {
            CompoundTag land = landNbt.getCompound(i);
            if (!land.contains("Dim", Tag.TAG_STRING) || !land.contains("X", Tag.TAG_INT)
                    || !land.contains("Z", Tag.TAG_INT) || !land.contains("Owner")) continue;
            state.landOwners.put(land.getString("Dim") + "|" + land.getInt("X") + "|" + land.getInt("Z"),
                    land.getUUID("Owner"));
        }
        ListTag listingNbt = nbt.getList("LandListings", Tag.TAG_COMPOUND);
        for (int i = 0; i < listingNbt.size(); i++) {
            CompoundTag tag = listingNbt.getCompound(i);
            if (!tag.contains("Dim", Tag.TAG_STRING) || !tag.contains("X", Tag.TAG_INT)
                    || !tag.contains("Z", Tag.TAG_INT) || !tag.contains("Seller")) continue;
            String key = tag.getString("Dim") + "|" + tag.getInt("X") + "|" + tag.getInt("Z");
            // A listing without an owner would let a stale entry survive forever.
            if (!tag.getUUID("Seller").equals(state.landOwners.get(key))) continue;
            state.landListings.put(key, new LandListing(tag.getString("Dim"), tag.getInt("X"), tag.getInt("Z"),
                    tag.getUUID("Seller"), Math.max(0, tag.getLong("Price")), tag.getLong("ListedAt")));
        }
        CompoundTag paidNbt = nbt.getCompound("LandPaid");
        for (String key : paidNbt.getAllKeys()) {
            state.landPaid.put(key, Math.max(0, paidNbt.getLong(key)));
        }
        state.nextAuctionId = Math.max(1, nbt.getLong("NextAuctionId"));
        return state;
    }

    public static GuildPersistentState get(MinecraftServer server) {
        // Minimal test servers (fml-junit's ephemeral server) have no overworld; return an
        // unpersisted state instead of crashing the ServerStarted handler.
        if (server.overworld() == null) {
            return new GuildPersistentState();
        }
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GuildPersistentState::new, GuildPersistentState::fromNbt),
                SAVE_ID
        );
    }
}
