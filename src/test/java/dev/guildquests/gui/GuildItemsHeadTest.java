package dev.guildquests.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the player-head profile component: 1.21 replaced the old {@code SkullOwner} NBT with
 * {@link DataComponents#PROFILE}, and the texture must live as a "textures" property so the
 * client renders the real skin instead of a default Steve/Alex head.
 */
public class GuildItemsHeadTest {
    private static final String TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv"
                    + "MWY4ODZkOWM0MGVmN2Y1MGMyMzg4MjQ3OTJjNDFmYmZiNTRmNjY1ZjE1OWJmMWJjYjBiMjdiM2VhZDM3M2IifX19";

    @Test
    public void headProfileCarriesNameIdAndTexture() {
        UUID owner = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        ItemStack head = GuildItems.headItemWithTexture("Notch", owner, TEXTURE, "Notch");

        ResolvableProfile profile = head.get(DataComponents.PROFILE);
        assertNotNull(profile, "profile component must be present");
        assertEquals("Notch", profile.name().orElse(null));
        assertEquals(owner, profile.id().orElse(null));
        assertTrue(profile.properties().containsKey("textures"));
        assertEquals(TEXTURE, profile.properties().get("textures").iterator().next().value());
    }
}
