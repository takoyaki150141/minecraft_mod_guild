package dev.guildquests.block;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.platform.ContentRegistrar;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;

public final class ModBlocks {
    public static final Block GUILD_BANK = new GuildBankBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .strength(3.5f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .noOcclusion());

    public static final Block GUILD_DEPOSIT = new GuildDepositBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .strength(2.5f, 6.0f)
                    .requiresCorrectToolForDrops());

    public static final BlockEntityType<GuildDepositBlockEntity> GUILD_DEPOSIT_BE =
            BlockEntityType.Builder.<GuildDepositBlockEntity>of(GuildDepositBlockEntity::new, GUILD_DEPOSIT).build(null);

    private ModBlocks() {
    }

    /** Registers the guild blocks through the loader-specific registrar. */
    public static void registerBlocks(ContentRegistrar registrar) {
        registrar.registerBlock(ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "guild_bank"), GUILD_BANK);
        registrar.registerBlock(ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "guild_deposit"), GUILD_DEPOSIT);
    }

    /** Registers the block items through the loader-specific registrar. */
    public static void registerBlockItems(ContentRegistrar registrar) {
        registrar.registerItem(ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "guild_bank"),
                new BlockItem(GUILD_BANK, new Item.Properties()));
        registrar.registerItem(ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "guild_deposit"),
                new BlockItem(GUILD_DEPOSIT, new Item.Properties()));
    }

    /** Registers the deposit block entity type through the loader-specific registrar. */
    public static void registerBlockEntityTypes(ContentRegistrar registrar) {
        registrar.registerBlockEntityType(ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "guild_deposit"),
                GUILD_DEPOSIT_BE);
    }
}
