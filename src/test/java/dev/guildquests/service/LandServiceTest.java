package dev.guildquests.service;

import dev.guildquests.config.ModConfig;
import dev.guildquests.data.GuildPersistentState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LandServiceTest {
    @Test
    void priceGrowsWithEveryOwnedChunk() {
        ModConfig config = new ModConfig();
        config.landBasePrice = 1000;
        config.landPriceStep = 250;
        assertEquals(1000, LandService.price(config, 0));
        assertEquals(1250, LandService.price(config, 1));
        assertEquals(1500, LandService.price(config, 2));
        config.landPriceStep = 0;
        assertEquals(1000, LandService.price(config, 5));
    }

    @Test
    void keysIncludeDimensionAndCoordinates() {
        assertEquals("minecraft:overworld|3|-2", LandService.key("minecraft:overworld", 3, -2));
    }

    @Test
    void instantSellPaysTheConfiguredShareOfThePurchasePrice() {
        GuildPersistentState state = new GuildPersistentState();
        state.landPaid.put("minecraft:overworld|1|1", 2000L);
        ModConfig config = new ModConfig();
        config.landSellPercent = 50;
        assertEquals(1000L, LandService.instantSellPrice(state, config, "minecraft:overworld|1|1"));

        config.landSellPercent = 30;
        assertEquals(600L, LandService.instantSellPrice(state, config, "minecraft:overworld|1|1"));

        // Chunks bought before paid prices were stored fall back to the base price.
        config.landSellPercent = 50;
        config.landBasePrice = 1200;
        assertEquals(600L, LandService.instantSellPrice(state, config, "minecraft:overworld|9|9"));
    }

    @Test
    void ownedCountOnlyCountsThatPlayer() {
        GuildPersistentState state = new GuildPersistentState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        state.landOwners.put("minecraft:overworld|0|0", first);
        state.landOwners.put("minecraft:overworld|1|0", first);
        state.landOwners.put("minecraft:the_nether|0|0", second);
        assertEquals(2, LandService.ownedCount(state, first));
        assertEquals(1, LandService.ownedCount(state, second));
        assertEquals(0, LandService.ownedCount(state, UUID.randomUUID()));
    }
}
