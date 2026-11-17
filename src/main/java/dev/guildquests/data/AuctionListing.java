package dev.guildquests.data;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * One auction-house listing. The offered item is escrowed inside this object
 * (removed from the seller's inventory at listing time) so it survives restarts
 * and is never lost if the seller logs off.
 */
public final class AuctionListing {
    public static final String MODE_BUYOUT = "BUYOUT";
    public static final String MODE_AUCTION = "AUCTION";

    public long id;
    public UUID sellerId;
    public String sellerName = "";
    public ItemStack item = ItemStack.EMPTY;
    /** Raw NBT kept when a stack fails to decode, so the escrowed item is not lost on save. */
    public CompoundTag rawItem;
    /** BUYOUT or AUCTION. */
    public String mode = MODE_BUYOUT;
    /** Fixed buyout price, or the starting bid for auctions. */
    public long price;
    /** Current highest bid (0 when no bids yet). */
    public long highestBid;
    public UUID highestBidderId;
    public String highestBidderName = "";
    public long createdAt;
    public long expireAt;

    public boolean isAuction() {
        return MODE_AUCTION.equals(mode);
    }

    public boolean hasBid() {
        return highestBidderId != null && highestBid > 0;
    }

    /** Lowest acceptable next bid given the configured step. */
    public long minNextBid(long step) {
        if (hasBid()) return highestBid + Math.max(1, step);
        return Math.max(0, price);
    }

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        nbt.putLong("Id", id);
        if (sellerId != null) nbt.putUUID("SellerId", sellerId);
        nbt.putString("SellerName", sellerName);
        nbt.put("Item", item.isEmpty() && rawItem != null ? rawItem : item.save(registries, new CompoundTag()));
        nbt.putString("Mode", mode);
        nbt.putLong("Price", price);
        nbt.putLong("HighestBid", highestBid);
        if (highestBidderId != null) nbt.putUUID("HighestBidderId", highestBidderId);
        nbt.putString("HighestBidderName", highestBidderName);
        nbt.putLong("CreatedAt", createdAt);
        nbt.putLong("ExpireAt", expireAt);
        return nbt;
    }

    public static AuctionListing fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        AuctionListing listing = new AuctionListing();
        listing.id = nbt.getLong("Id");
        listing.sellerId = nbt.contains("SellerId") ? nbt.getUUID("SellerId") : null;
        listing.sellerName = nbt.getString("SellerName");
        if (nbt.contains("Item", Tag.TAG_COMPOUND)) {
            listing.rawItem = nbt.getCompound("Item");
            listing.item = ItemStack.parseOptional(registries, listing.rawItem);
        }
        listing.mode = nbt.contains("Mode") ? nbt.getString("Mode") : MODE_BUYOUT;
        listing.price = Math.max(0, nbt.getLong("Price"));
        listing.highestBid = Math.max(0, nbt.getLong("HighestBid"));
        listing.highestBidderId = nbt.contains("HighestBidderId") ? nbt.getUUID("HighestBidderId") : null;
        listing.highestBidderName = nbt.getString("HighestBidderName");
        listing.createdAt = nbt.getLong("CreatedAt");
        listing.expireAt = nbt.getLong("ExpireAt");
        return listing;
    }
}
