package dev.guildquests.service;

import dev.guildquests.config.ModConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandRulesTest {
    @Test
    void defaultsFollowTheConfig() {
        ModConfig config = new ModConfig();
        int mask = LandRules.defaults(config);
        assertTrue(LandRules.has(mask, LandRules.BREAK));
        assertTrue(LandRules.has(mask, LandRules.PLACE));
        assertTrue(LandRules.has(mask, LandRules.CONTAINERS));
        assertTrue(LandRules.has(mask, LandRules.INTERACT));
        assertTrue(LandRules.has(mask, LandRules.ENTITY));
        assertTrue(LandRules.has(mask, LandRules.MOB_DAMAGE));
        assertFalse(LandRules.has(mask, LandRules.PVP));
        assertFalse(LandRules.has(mask, LandRules.ENTRY));

        config.landProtectBreak = false;
        config.landProtectEntry = true;
        int changed = LandRules.defaults(config);
        assertFalse(LandRules.has(changed, LandRules.BREAK));
        assertTrue(LandRules.has(changed, LandRules.ENTRY));
    }

    @Test
    void aStoredMaskOverridesTheDefaults() {
        ModConfig config = new ModConfig();
        assertEquals(LandRules.defaults(config), LandRules.resolve(LandRules.UNSET, config));
        int stored = LandRules.ENTRY | LandRules.PVP;
        assertEquals(stored, LandRules.resolve(stored, config));
        // Unknown bits must not leak through.
        assertEquals(LandRules.BREAK, LandRules.resolve(LandRules.BREAK | 0xF00, config));
    }

    @Test
    void toggleFlipsOnlyThatRule() {
        int mask = LandRules.BREAK | LandRules.PVP;
        int flipped = LandRules.toggle(mask, LandRules.BREAK);
        assertFalse(LandRules.has(flipped, LandRules.BREAK));
        assertTrue(LandRules.has(flipped, LandRules.PVP));
        assertEquals(mask, LandRules.toggle(flipped, LandRules.BREAK));
    }

    @Test
    void summaryListsShortNames() {
        assertEquals("保護: 対象なし", LandRules.summary(0));
        String text = LandRules.summary(LandRules.BREAK | LandRules.ENTRY);
        assertTrue(text.contains("破壊"));
        assertTrue(text.contains("侵入"));
        assertFalse(text.contains("PvP"));
    }

    @Test
    void ruleIndexLookupIsBoundsChecked() {
        assertEquals(LandRules.RULES.get(0), LandRules.byIndex(0));
        assertEquals(LandRules.ENTRY, LandRules.byIndex(LandRules.RULES.size() - 1).bit());
        assertNull(LandRules.byIndex(-1));
        assertNull(LandRules.byIndex(LandRules.RULES.size()));
    }
}
