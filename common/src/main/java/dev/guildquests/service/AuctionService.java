package dev.guildquests.service;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.AuctionListing;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.util.Numbers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Guild auction house. Listings are global and server-side; offered items are
 * escrowed in {@link AuctionListing} and delivered to a per-player mailbox
 * (coins settle directly into the bank balance).
 */
public final class AuctionService {
    private AuctionService() {
    }

    // ---------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------

    /** All active listings, oldest first. */
    public static List<AuctionListing> listings(MinecraftServer server) {
        GuildPersistentState state = GuildPersistentState.get(server);
        List<AuctionListing> list = new ArrayList<>(state.auctions);
        list.sort(Comparator.comparingLong(l -> l.id));
        return list;
    }

    /** Active listings owned by the given player. */
    public static List<AuctionListing> listingsBy(ServerPlayer player) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        List<AuctionListing> list = new ArrayList<>();
        for (AuctionListing listing : state.auctions) {
            if (player.getUUID().equals(listing.sellerId)) list.add(listing);
        }
        list.sort(Comparator.comparingLong(l -> l.id));
        return list;
    }

    // ---------------------------------------------------------------------
    // Selling
    // ---------------------------------------------------------------------

    /** Escrows one inventory stack as a listing. Returns the new listing id, or -1 on failure. */
    public static long createListing(ServerPlayer seller, ItemStack stack, String mode, long price) {
        if (stack == null || stack.isEmpty()) {
            GuildService.message(seller, "出品するアイテムが選択されていません。", ChatFormatting.YELLOW);
            return -1;
        }
        GuildPersistentState state = GuildPersistentState.get(seller.getServer());
        if (listingsBy(seller).size() >= ConfigManager.config().auctionMaxListingsPerPlayer) {
            GuildService.message(seller, "出品数の上限（" + Numbers.format(ConfigManager.config().auctionMaxListingsPerPlayer) + " 件）に達しています。", ChatFormatting.RED);
            return -1;
        }

        boolean auction = AuctionListing.MODE_AUCTION.equals(mode);
        if (price < 0) price = 0;
        if (auction && price <= 0) {
            GuildService.message(seller, "オークションの開始価格は 1 コイン以上にしてください。", ChatFormatting.RED);
            return -1;
        }

        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing();
        listing.id = state.nextAuctionId++;
        listing.sellerId = seller.getUUID();
        listing.sellerName = seller.getName().getString();
        listing.item = stack.copy();
        listing.mode = auction ? AuctionListing.MODE_AUCTION : AuctionListing.MODE_BUYOUT;
        listing.price = price;
        listing.createdAt = now;
        listing.expireAt = now + ConfigManager.config().auctionDurationMinutes * 60_000L;
        state.auctions.add(listing);
        state.setDirty();

        GuildService.message(seller, "「" + itemLabel(listing) + "」を"
                + (auction ? "オークション（開始 " + Numbers.format(price) + " コイン）" : "固定価格 " + Numbers.format(price) + " コイン")
                + "で出品しました。", ChatFormatting.GREEN);
        GuildService.broadcast(seller.getServer(), seller.getName().getString() + " が「" + itemLabel(listing) + "」を"
                + (auction ? "オークション（開始 " + Numbers.format(price) + " コイン）" : "固定価格 " + Numbers.format(price) + " コイン")
                + "で出品しました。", ChatFormatting.GOLD);
        return listing.id;
    }

    // ---------------------------------------------------------------------
    // Buying / bidding
    // ---------------------------------------------------------------------

    /** Resolves the listing at click time and dispatches to buy or bid. */
    public static void interact(ServerPlayer player, long listingId, long bidAmount) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        AuctionListing listing = find(state, listingId);
        if (listing == null) {
            GuildService.message(player, "その出品は既に終了しています。", ChatFormatting.YELLOW);
            return;
        }
        if (listing.isAuction()) {
            bid(player, listingId, bidAmount);
        } else {
            buy(player, listingId);
        }
    }

    public static boolean buy(ServerPlayer buyer, long listingId) {
        GuildPersistentState state = GuildPersistentState.get(buyer.getServer());
        AuctionListing listing = find(state, listingId);
        if (listing == null || listing.isAuction()) return false;
        if (buyer.getUUID().equals(listing.sellerId)) {
            GuildService.message(buyer, "自分の出品は購入できません。", ChatFormatting.YELLOW);
            return false;
        }
        PlayerGuildData data = state.get(buyer.getUUID());
        if (data.delinquent) {
            GuildService.riboLockMessage(buyer, data);
            return false;
        }
        long price = listing.price;
        if (data.coins < price) {
            GuildService.message(buyer, "残高が不足しています。価格: " + Numbers.format(price) + " / 残高: " + Numbers.format(data.coins), ChatFormatting.RED);
            return false;
        }

        data.coins -= price;
        long fee = fee(price);
        PlayerGuildData sellerData = state.get(listing.sellerId);
        sellerData.coins = safeAdd(sellerData.coins, price - fee);

        deliver(listing.item, data);
        state.auctions.remove(listing);
        state.setDirty();

        String label = itemLabel(listing);
        GuildService.message(buyer, "「" + label + "」を " + Numbers.format(price) + " コインで購入しました。受取ボックスをご確認ください。", ChatFormatting.GREEN);
        notify(buyer.getServer(), listing.sellerId, buyer.getName().getString()
                + " が「" + label + "」を " + Numbers.format(price) + " コインで購入しました（手数料 " + Numbers.format(fee) + "）。");
        return true;
    }

    public static boolean bid(ServerPlayer bidder, long listingId, long amount) {
        GuildPersistentState state = GuildPersistentState.get(bidder.getServer());
        AuctionListing listing = find(state, listingId);
        if (listing == null || !listing.isAuction()) return false;
        if (bidder.getUUID().equals(listing.sellerId)) {
            GuildService.message(bidder, "自分の出品には入札できません。", ChatFormatting.YELLOW);
            return false;
        }
        PlayerGuildData data = state.get(bidder.getUUID());
        if (data.delinquent) {
            GuildService.riboLockMessage(bidder, data);
            return false;
        }
        long min = listing.minNextBid(ConfigManager.config().auctionBidStep);
        if (amount < min) {
            GuildService.message(bidder, "入札額は " + Numbers.format(min) + " コイン以上にしてください。", ChatFormatting.RED);
            return false;
        }
        if (data.coins < amount) {
            GuildService.message(bidder, "残高が不足しています。入札: " + Numbers.format(amount) + " / 残高: " + Numbers.format(data.coins), ChatFormatting.RED);
            return false;
        }

        if (listing.hasBid()) {
            PlayerGuildData previous = state.get(listing.highestBidderId);
            previous.coins = safeAdd(previous.coins, listing.highestBid);
            notify(bidder.getServer(), listing.highestBidderId, "「" + itemLabel(listing) + "」の入札が更新されました。"
                    + Numbers.format(listing.highestBid) + " コインを残高へ返金しました。");
        }

        data.coins -= amount;
        listing.highestBid = amount;
        listing.highestBidderId = bidder.getUUID();
        listing.highestBidderName = bidder.getName().getString();
        state.setDirty();

        GuildService.message(bidder, "「" + itemLabel(listing) + "」に " + Numbers.format(amount) + " コインで入札しました。", ChatFormatting.GREEN);
        notify(bidder.getServer(), listing.sellerId, bidder.getName().getString()
                + " が「" + itemLabel(listing) + "」に " + Numbers.format(amount) + " コインで入札しました。");
        return true;
    }

    // ---------------------------------------------------------------------
    // Cancelling
    // ---------------------------------------------------------------------

    public static boolean cancel(ServerPlayer seller, long listingId) {
        GuildPersistentState state = GuildPersistentState.get(seller.getServer());
        AuctionListing listing = find(state, listingId);
        if (listing == null || !seller.getUUID().equals(listing.sellerId)) return false;

        if (listing.hasBid()) {
            PlayerGuildData bidder = state.get(listing.highestBidderId);
            bidder.coins = safeAdd(bidder.coins, listing.highestBid);
            notify(seller.getServer(), listing.highestBidderId,
                    "出品が取り消されたため、" + Numbers.format(listing.highestBid) + " コインを残高へ返金しました。");
        }
        deliver(listing.item, state.get(seller.getUUID()));
        state.auctions.remove(listing);
        state.setDirty();
        GuildService.message(seller, "出品を取り消しました。アイテムは受取ボックスに戻っています。", ChatFormatting.YELLOW);
        return true;
    }

    // ---------------------------------------------------------------------
    // Settlement
    // ---------------------------------------------------------------------

    /** Settles every expired listing. Safe to call periodically from the server tick. */
    public static void tick(MinecraftServer server) {
        GuildPersistentState state = GuildPersistentState.get(server);
        if (state.auctions.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (AuctionListing listing : new ArrayList<>(state.auctions)) {
            if (listing.expireAt > now) continue;
            settle(server, state, listing);
            changed = true;
        }
        if (changed) state.setDirty();
    }

    private static void settle(MinecraftServer server, GuildPersistentState state, AuctionListing listing) {
        String label = itemLabel(listing);
        if (listing.hasBid()) {
            long price = listing.highestBid;
            long fee = fee(price);
            PlayerGuildData sellerData = state.get(listing.sellerId);
            sellerData.coins = safeAdd(sellerData.coins, price - fee);
            PlayerGuildData winner = state.get(listing.highestBidderId);
            deliver(listing.item, winner);
            notify(server, listing.highestBidderId, "「" + label + "」のオークションに " + Numbers.format(price)
                    + " コインで落札しました。受取ボックスをご確認ください。");
            notify(server, listing.sellerId, "「" + label + "」が " + Numbers.format(price)
                    + " コインで落札されました（手数料 " + Numbers.format(fee) + "）。残高へ入金しました。");
        } else {
            deliver(listing.item, state.get(listing.sellerId));
            notify(server, listing.sellerId, "「" + label + "」は入札がなく期限切れとなりました。受取ボックスに戻しています。");
        }
        state.auctions.remove(listing);
    }

    // ---------------------------------------------------------------------
    // Mailbox
    // ---------------------------------------------------------------------

    /** Claims one mailbox entry into the player's inventory. */
    public static boolean claimMailbox(ServerPlayer player, int index) {
        PlayerGuildData data = GuildService.data(player);
        if (index < 0 || index >= data.mailbox.size()) return false;
        ItemStack stack = data.mailbox.get(index);
        if (stack == null || stack.isEmpty()) {
            data.mailbox.remove(index);
            return true;
        }
        if (!GuildService.canFitStack(player.getInventory(), stack)) {
            GuildService.message(player, "インベントリに空きがありません。", ChatFormatting.RED);
            return false;
        }
        ItemStack remaining = GuildService.giveStack(player.getInventory(), stack);
        data.mailbox.remove(index);
        if (!remaining.isEmpty()) data.mailbox.add(remaining);
        GuildService.message(player, stack.getHoverName().getString() + " ×"
                + (stack.getCount() - remaining.getCount()) + " を受け取りました。", ChatFormatting.GREEN);
        return true;
    }

    /** Claims every mailbox entry that fits. */
    public static void claimAllMailbox(ServerPlayer player) {
        PlayerGuildData data = GuildService.data(player);
        int claimed = 0;
        for (int i = data.mailbox.size() - 1; i >= 0; i--) {
            ItemStack stack = data.mailbox.get(i);
            if (stack == null || stack.isEmpty()) {
                data.mailbox.remove(i);
                continue;
            }
            if (!GuildService.canFitStack(player.getInventory(), stack)) continue;
            ItemStack remaining = GuildService.giveStack(player.getInventory(), stack);
            data.mailbox.remove(i);
            if (!remaining.isEmpty()) data.mailbox.add(remaining);
            claimed++;
        }
        if (claimed == 0) {
            GuildService.message(player, "受け取れるアイテムがありません（インベントリの空きをご確認ください）。", ChatFormatting.YELLOW);
        } else {
            GuildService.message(player, claimed + " 件のアイテムを受け取りました。", ChatFormatting.GREEN);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Human readable time until a listing expires, e.g. "42分" or "1時間3分" or "期限切れ". */
    public static String formatRemaining(long expireAt) {
        long millis = expireAt - System.currentTimeMillis();
        if (millis <= 0) return "期限切れ";
        long minutes = millis / 60_000L;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) return Numbers.format(days) + "日" + Numbers.format(hours % 24) + "時間";
        if (hours > 0) return Numbers.format(hours) + "時間" + Numbers.format(minutes % 60) + "分";
        return Numbers.format(Math.max(1, minutes)) + "分";
    }

    private static AuctionListing find(GuildPersistentState state, long id) {
        for (AuctionListing listing : state.auctions) {
            if (listing.id == id) return listing;
        }
        return null;
    }

    private static long fee(long price) {
        double pct = ConfigManager.config().auctionFeePercent;
        if (pct <= 0 || price <= 0) return 0;
        return Math.min(price, Math.round(price * pct / 100.0));
    }

    private static String itemLabel(AuctionListing listing) {
        return listing.item.getHoverName().getString() + " ×" + listing.item.getCount();
    }

    private static void deliver(ItemStack stack, PlayerGuildData data) {
        if (stack != null && !stack.isEmpty()) data.mailbox.add(stack.copy());
    }

    private static void notify(MinecraftServer server, UUID uuid, String text) {
        if (uuid == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) GuildService.message(player, text, ChatFormatting.GOLD);
    }

    private static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}
