package dev.guildquests.gui;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Read-only client mirror; vanilla container packets synchronize every server change. */
public final class GuildClientMenu extends GuildMenu {
    public GuildClientMenu(GuildMenuKind kind, int id, Inventory inventory) {
        super(kind, id);
        SimpleContainer display = new SimpleContainer(kind.rows * 9);
        for (int i = 0; i < kind.rows * 9; i++) addSlot(new Slot(display, i, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, 9 + row * 9 + col, 0, 0));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 0, 0));
    }
    @Override protected void populate() { }
    @Override public void broadcastChanges() { }
    @Override public boolean stillValid(Player player) { return true; }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
