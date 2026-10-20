package dev.guildquests.gui;

import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the NBT layout of player heads: the client parses {@code SkullOwner} with
 * {@link NbtUtils#readGameProfile(CompoundTag)}, which expects each property entry to hold its
 * base64 payload under a capitalised {@code Value} key. Writing {@code value} (lowercase) leaves
 * an empty texture and the client silently renders the default Steve/Alex head instead.
 */
public class GuildItemsHeadTest {
    private static final String TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv"
                    + "MWY4ODZkOWM0MGVmN2Y1MGMyMzg4MjQ3OTJjNDFmYmZiNTRmNjY1ZjE1OWJmMWJjYjBiMjdiM2VhZDM3M2IifX19";

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void headTextureIsReadBackByTheVanillaProfileParser() {
        UUID owner = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        ItemStack head = GuildItems.headItemWithTexture("Notch", owner, TEXTURE, "Notch");

        CompoundTag skull = head.getTagElement("SkullOwner");
        assertNotNull("SkullOwner tag must be present", skull);

        GameProfile profile = NbtUtils.readGameProfile(skull);
        assertNotNull(profile);
        assertEquals("Notch", profile.getName());
        assertEquals(owner, profile.getId());
        assertEquals("vanilla reads the texture from Properties->textures[0].Value",
                TEXTURE, profile.getProperties().get("textures").iterator().next().getValue());
    }

    @Test
    public void propertyTagDoesNotUseTheBrokenLowercaseKeys() {
        ItemStack head = GuildItems.headItemWithTexture("Notch", UUID.randomUUID(), TEXTURE, "Notch");
        CompoundTag textureEntry = head.getTagElement("SkullOwner")
                .getCompound("Properties")
                .getList("textures", 10)
                .getCompound(0);

        assertTrue("texture payload lives in Value", textureEntry.contains("Value", 8));
        assertFalse("stray lowercase 'value' key", textureEntry.contains("value", 8));
        assertFalse("property name comes from the map key, not the entry", textureEntry.contains("name", 8));
    }
}
