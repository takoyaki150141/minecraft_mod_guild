package dev.guildquests.block;

import dev.guildquests.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the deposit block's item-handler behavior for capability-based transports.
 * Vanilla hoppers reach the block via the WorldlyContainer path, but Create chutes/funnels
 * (and other capability transports) query Capabilities.ItemHandler.BLOCK; the registration
 * in GuildQuestsNeoForge hands them exactly the wrapper used here. If this test breaks,
 * Create chutes stop recognizing the deposit block.
 */
class GuildDepositCapabilityTest {

    /** The same provider GuildQuestsNeoForge registers for Capabilities.ItemHandler.BLOCK. */
    private static IItemHandler handler(GuildDepositBlockEntity be, Direction side) {
        return new SidedInvWrapper(be, side);
    }

    @Test
    void chuteLikeInsertionAcceptsOnlyCoins() {
        GuildDepositBlockEntity be = new GuildDepositBlockEntity(
                BlockPos.ZERO, ModBlocks.GUILD_DEPOSIT.defaultBlockState());
        be.setOwner(UUID.randomUUID(), "owner");

        // A chute above the block inserts through the target's UP face.
        IItemHandler handler = handler(be, Direction.UP);
        assertNotNull(handler);

        ItemStack rejected = handler.insertItem(0, new ItemStack(Items.DIRT, 3), false);
        assertEquals(3, rejected.getCount(), "non-coin items must be refused");
        assertTrue(be.getItem(0).isEmpty(), "nothing may land in the slot");

        ItemStack leftover = handler.insertItem(0, new ItemStack(ModItems.COIN, 5), false);
        assertEquals(0, leftover.getCount(), "coins must be accepted");
        assertEquals(5, be.getItem(0).getCount(), "coins must be stored in the deposit slot");
    }

    @Test
    void unlinkedBlockRefusesEverything() {
        GuildDepositBlockEntity be = new GuildDepositBlockEntity(
                BlockPos.ZERO, ModBlocks.GUILD_DEPOSIT.defaultBlockState());

        IItemHandler handler = handler(be, Direction.DOWN);
        ItemStack leftover = handler.insertItem(0, new ItemStack(ModItems.COIN, 1), false);
        assertEquals(1, leftover.getCount(), "coins must be refused while no owner is linked");
        assertTrue(be.getItem(0).isEmpty());
    }
}
