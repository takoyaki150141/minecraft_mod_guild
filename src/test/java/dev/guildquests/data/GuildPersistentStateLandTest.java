package dev.guildquests.data;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuildPersistentStateLandTest {
    @Test
    void landOwnersSurviveSaveAndLoad() {
        GuildPersistentState state = new GuildPersistentState();
        UUID owner = UUID.randomUUID();
        state.landOwners.put("minecraft:overworld|4|-7", owner);
        state.landOwners.put("minecraft:the_nether|0|0", owner);

        CompoundTag tag = state.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertTrue(tag.contains("LandOwners"));

        GuildPersistentState loaded = GuildPersistentState.fromNbt(tag, RegistryAccess.EMPTY);
        assertEquals(2, loaded.landOwners.size());
        assertEquals(owner, loaded.landOwners.get("minecraft:overworld|4|-7"));
        assertEquals(owner, loaded.landOwners.get("minecraft:the_nether|0|0"));
    }

    @Test
    void malformedLandEntriesAreIgnored() {
        CompoundTag tag = new CompoundTag();
        CompoundTag bad = new CompoundTag();
        bad.putString("Dim", "minecraft:overworld");
        bad.putString("X", "not-a-number");
        net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();
        entries.add(bad);
        tag.put("LandOwners", entries);

        GuildPersistentState loaded = GuildPersistentState.fromNbt(tag, RegistryAccess.EMPTY);
        assertTrue(loaded.landOwners.isEmpty());
        assertFalse(tag.contains("Missing"));
    }

    @Test
    void listingsAndPaidPricesSurviveSaveAndLoad() {
        GuildPersistentState state = new GuildPersistentState();
        UUID owner = UUID.randomUUID();
        state.landOwners.put("minecraft:overworld|2|3", owner);
        state.landPaid.put("minecraft:overworld|2|3", 1750L);
        state.landListings.put("minecraft:overworld|2|3", new GuildPersistentState.LandListing(
                "minecraft:overworld", 2, 3, owner, 4000L, 123456789L));

        CompoundTag tag = state.save(new CompoundTag(), RegistryAccess.EMPTY);
        GuildPersistentState loaded = GuildPersistentState.fromNbt(tag, RegistryAccess.EMPTY);

        assertEquals(1750L, loaded.landPaid.get("minecraft:overworld|2|3"));
        GuildPersistentState.LandListing listing = loaded.landListings.get("minecraft:overworld|2|3");
        assertEquals(owner, listing.seller);
        assertEquals(4000L, listing.price);
    }

    @Test
    void listingsWithoutTheirOwnerAreDropped() {
        GuildPersistentState state = new GuildPersistentState();
        UUID owner = UUID.randomUUID();
        state.landListings.put("minecraft:overworld|0|0", new GuildPersistentState.LandListing(
                "minecraft:overworld", 0, 0, owner, 100L, 1L));

        CompoundTag tag = state.save(new CompoundTag(), RegistryAccess.EMPTY);
        GuildPersistentState loaded = GuildPersistentState.fromNbt(tag, RegistryAccess.EMPTY);
        assertTrue(loaded.landListings.isEmpty());
    }
}
