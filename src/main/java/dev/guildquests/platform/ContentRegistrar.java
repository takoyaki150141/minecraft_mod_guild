package dev.guildquests.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Loader-specific registry writer. Fabric writes into the vanilla built-in registries
 * directly; Forge must go through its {@code RegisterEvent} helpers because the vanilla
 * registries are locked once mod loading starts.
 */
public interface ContentRegistrar {
    void registerItem(ResourceLocation id, Item item);

    void registerBlock(ResourceLocation id, Block block);

    void registerBlockEntityType(ResourceLocation id, BlockEntityType<?> type);

    void registerMobEffect(ResourceLocation id, MobEffect effect);

    void registerCreativeModeTab(ResourceLocation id, CreativeModeTab tab);
}
