package dev.guildquests.item;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.platform.ContentRegistrar;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ModItems {
    public static final Item COIN = new Item(new Item.Properties());
    public static final ResourceKey<CreativeModeTab> GUILD_TAB_KEY =
            ResourceKey.create(BuiltInRegistries.CREATIVE_MODE_TAB.key(), ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "guild"));

    private ModItems() {
    }

    /** Registers the coin item through the loader-specific registrar. */
    public static void registerItems(ContentRegistrar registrar) {
        registrar.registerItem(ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "coin"), COIN);
    }

    /** Builds and registers the guild creative tab through the loader-specific registrar. */
    public static void registerCreativeTab(ContentRegistrar registrar) {
        CreativeModeTab guildTab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, nextFreeTopColumn())
                .title(Component.literal("GuildQuests"))
                .icon(() -> new ItemStack(COIN))
                .displayItems((params, output) -> {
                    output.accept(new ItemStack(COIN));
                    output.accept(ModBlocks.GUILD_BANK);
                    output.accept(ModBlocks.GUILD_DEPOSIT);
                })
                .build();
        registrar.registerCreativeModeTab(GUILD_TAB_KEY.location(), guildTab);
    }

    /** First TOP-row column not taken yet, so the tab never collides with another mod's. */
    private static int nextFreeTopColumn() {
        int next = 0;
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            if (tab.row() == CreativeModeTab.Row.TOP) {
                next = Math.max(next, tab.column() + 1);
            }
        }
        return next;
    }

    public static void addItemToTab(Item entry) {
        // Items are added to the dedicated guild tab at registration; this method kept for compatibility.
    }
}
