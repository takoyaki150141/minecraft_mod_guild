package dev.guildquests.data;

import dev.guildquests.service.LandRules;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerGuildDataLandRulesTest {
    @Test
    void ruleMaskSurvivesSaveAndLoad() {
        PlayerGuildData data = new PlayerGuildData();
        data.landRuleMask = LandRules.BREAK | LandRules.ENTRY;

        CompoundTag tag = data.toNbt(RegistryAccess.EMPTY);
        PlayerGuildData loaded = PlayerGuildData.fromNbt(tag, RegistryAccess.EMPTY);

        assertEquals(LandRules.BREAK | LandRules.ENTRY, loaded.landRuleMask);
    }

    @Test
    void oldSavesFallBackToTheConfigDefaults() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Coins", 100);
        PlayerGuildData loaded = PlayerGuildData.fromNbt(tag, RegistryAccess.EMPTY);
        assertEquals(LandRules.UNSET, loaded.landRuleMask);
    }

    @Test
    void outOfRangeMasksAreClamped() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("LandRuleMask", 0x7FFF);
        assertEquals(LandRules.ALL, PlayerGuildData.fromNbt(tag, RegistryAccess.EMPTY).landRuleMask);

        CompoundTag negative = new CompoundTag();
        negative.putInt("LandRuleMask", -99);
        assertEquals(LandRules.UNSET, PlayerGuildData.fromNbt(negative, RegistryAccess.EMPTY).landRuleMask);
    }
}
