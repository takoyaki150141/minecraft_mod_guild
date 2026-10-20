package dev.guildquests.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * ItemStack builders shared by all screen handlers.
 * Item-stack display data (custom name, lore, skull texture) is version-sensitive
 * (NBT on 1.20.1, data components on 1.20.5+); keep all of it here so a version
 * port only rewrites this one class.
 */
public final class GuildItems {
    /** mcheads.ru "Quartz 0".."Quartz 9" texture payloads (index = digit). */
    private static final String[] DIGIT_TEXTURES = {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWY4ODZkOWM0MGVmN2Y1MGMyMzg4MjQ3OTJjNDFmYmZiNTRmNjY1ZjE1OWJmMWJjYjBiMjdiM2VhZDM3M2IifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTBhMTllMjNkMjFmMmRiMDYzY2M1NWU5OWFlODc0ZGM4YjIzYmU3NzliZTM0ZTUyZTdjN2I5YTI1In19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2M1OTZhNDFkYWVhNTFiZTJlOWZlYzdkZTJkODkwNjhlMmZhNjFjOWQ1N2E4YmRkZTQ0YjU1OTM3YjYwMzcifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjg1ZDRmZGE1NmJmZWI4NTEyNDQ2MGZmNzJiMjUxZGNhOGQxZGViNjU3ODA3MGQ2MTJiMmQzYWRiZjVhOCJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzg1MmEyNWZlNjljYTg2ZmI5ODJmYjNjYzdhYzk3OTNmNzM1NmI1MGI5MmNiMGUxOTNkNmI0NjMyYTliZDYyOSJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzRlZTdkOTU0ZWIxNGE1Y2NkMzQ2MjY2MjMxYmY5YTY3MTY1MjdiNTliYmNkNzk1NmNlZjA0YTlkOWIifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjY4MmEzYWU5NDgzNzRlMDM3ZTNkN2RkNjg3ZDU5ZDE4NWRkMmNjOGZjMDlkZmViNDJmOThmOGQyNTllNWMzIn19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGVhMzBjMjRjNjBiM2JjMWFmNjU4ZWY2NjFiNzcxYzQ4ZDViOWM5ZTI4MTg4Y2Y5ZGU5ZjgzMjQyMmU1MTAifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZhYmFmZDAyM2YyMzBlNDQ4NWFhZjI2ZTE5MzY4ZjU5ODBkNGYxNGE1OWZjYzZkMTFhNDQ2Njk5NDg5MiJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ3OTEwZTEwMzM0Zjg5MGE2MjU0ODNhYzBjODI0YjVlNGExYTRiMTVhOTU2MzI3YTNlM2FlNDU4ZDllYTQifX19"
    };

    private GuildItems() {
    }

    public static ItemStack item(Item item, String name, String... lore) {
        return item(item, ChatFormatting.WHITE, name, lore);
    }

    public static ItemStack item(Item item, ChatFormatting nameFormat, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.literal(name).withStyle(nameFormat));
        Component[] lines = new Component[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Component.literal(lore[i]).withStyle(ChatFormatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    /** Player head showing the owner's skin (resolved client-side from the profile id). */
    public static ItemStack headItem(String ownerName, UUID ownerId, String name, String... lore) {
        return headItemWithTexture(ownerName, ownerId, null, name, lore);
    }

    /**
     * Player head rendered from the player's real skin. The skin texture is embedded as
     * {@code SkullOwner.Properties.textures} — the same mechanism {@link #digitHead} uses — so the
     * client draws the actual skin instead of falling back to the default (Steve) head, which is
     * what happens when only the id/name are set and the client fails to resolve the profile.
     * Online players are read live; offline players use {@link SkinCache} (fetched from Mojang once).
     */
    public static ItemStack playerHead(MinecraftServer server, UUID ownerId, String ownerName, String name, String... lore) {
        return headItemWithTexture(ownerName, ownerId, SkinCache.texture(server, ownerId, ownerName), name, lore);
    }

    /**
     * Player head with an optional custom texture. When {@code textureValue} is non-null it is written as
     * SkullOwner.Properties.Textures so the client renders that exact texture instead of resolving the id.
     */
    public static ItemStack headItemWithTexture(String ownerName, UUID ownerId, String textureValue, String name, String... lore) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag skull = stack.getOrCreateTagElement("SkullOwner");
        if (ownerName != null && !ownerName.isEmpty()) skull.putString("Name", ownerName);
        if (ownerId != null) skull.putUUID("Id", ownerId);
        if (textureValue != null) {
            // Vanilla (NbtUtils#readGameProfile) reads the property value from the "Value" key
            // and takes the property *name* from the enclosing map key, so the inner tag must
            // not repeat "name" and must use a capital "Value" — otherwise the client sees an
            // empty texture value and falls back to the default Steve/Alex head.
            CompoundTag property = new CompoundTag();
            property.putString("Value", textureValue);
            ListTag textures = new ListTag();
            textures.add(property);
            CompoundTag properties = new CompoundTag();
            properties.put("textures", textures);
            skull.put("Properties", properties);
        }
        stack.setHoverName(Component.literal(name).withStyle(ChatFormatting.WHITE));
        Component[] lines = new Component[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Component.literal(lore[i]).withStyle(ChatFormatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    public static ItemStack digitHead(int digit, String placeLabel) {
        return digitHead(digit, "送金額", placeLabel);
    }

    /** Digit head with a custom title, e.g. "入力額 千の位: 3". */
    public static ItemStack digitHead(int digit, String title, String placeLabel) {
        return headItemWithTexture("digit", new UUID(0, digit), DIGIT_TEXTURES[digit],
                title + " " + placeLabel + ": " + digit,
                "左クリックで +1（0-9 で循環）",
                "右クリックで -1");
    }

    /**
     * Copy of {@code base} with an overridden name and extra gray lore lines appended
     * after the item's existing lore (so enchantments/custom lore stay visible).
     * Used to render escrowed/mailbox items without mutating the stored stack.
     */
    public static ItemStack decorateCopy(ItemStack base, ChatFormatting nameFormat, String name, String... extraLore) {
        ItemStack stack = base.copy();
        stack.setHoverName(Component.literal(name).withStyle(nameFormat));
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        if (display.contains("Lore", Tag.TAG_LIST)) {
            ListTag existing = display.getList("Lore", Tag.TAG_STRING);
            for (int i = 0; i < existing.size(); i++) lore.add(existing.get(i));
        }
        for (String line : extraLore) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line).withStyle(ChatFormatting.GRAY))));
        }
        display.put("Lore", lore);
        return stack;
    }

    private static void setLore(ItemStack stack, Component... lines) {
        if (lines.length == 0) return;
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        for (Component line : lines) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        }
        display.put("Lore", lore);
    }
}
