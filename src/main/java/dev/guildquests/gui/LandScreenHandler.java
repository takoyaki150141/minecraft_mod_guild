package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.service.GuildService;
import dev.guildquests.service.LandService;
import dev.guildquests.util.Numbers;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * Server half of the land map: publishes the chunk ownership/pricing snapshot on slot 0,
 * exactly like the gambling view, so the client needs no extra sync channel.
 */
public final class LandScreenHandler extends GuildMenu {
    private static final int DISPLAY_SLOTS = 54;
    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;

    public LandScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.LAND, syncId);
        this.owner = owner;
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                addSlot(new Slot(display, index, 8 + col * 18, 18 + row * 18) {
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                    @Override public boolean mayPickup(Player player) { return false; }
                });
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 198));
        }
        populate();
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new LandScreenHandler(syncId, playerInventory, player),
                Component.literal("土地保護 - チャンク購入")
        ));
    }

    /** Re-reads ownership and prices after a purchase. */
    public void refresh() {
        populate();
        broadcastChanges();
    }

    @Override protected void populate() {
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        ItemStack pane = GuildItems.item(Items.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, pane.copy());

        GuildPersistentState state = GuildPersistentState.get(owner.getServer());
        ModConfig config = ConfigManager.config();
        ChunkPos center = new ChunkPos(owner.blockPosition());
        int radius = Math.max(1, Math.min(8, config.landMapRadius));
        int side = radius * 2 + 1;
        byte[] cells = new byte[side * side];
        CompoundTag owners = new CompoundTag();
        int owned = LandService.ownedCount(state, owner.getUUID());

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int index = (dz + radius) * side + (dx + radius);
                UUID ownerId = state.landOwners.get(LandService.key(owner.level(), center.x + dx, center.z + dz));
                if (ownerId == null) {
                    cells[index] = LandView.UNOWNED;
                } else if (ownerId.equals(owner.getUUID())) {
                    cells[index] = LandView.SELF;
                } else {
                    cells[index] = LandView.OTHER;
                    owners.putString(Integer.toString(index), ownerName(state, ownerId));
                }
            }
        }

        CompoundTag view = new CompoundTag();
        view.putInt("cx", center.x);
        view.putInt("cz", center.z);
        view.putInt("radius", radius);
        view.putString("dim", owner.level().dimension().location().toString());
        view.putLong("coins", GuildService.data(owner).coins);
        view.putInt("owned", owned);
        view.putLong("price", LandService.price(config, owned));
        view.putInt("max", config.landMaxChunks);
        view.putBoolean("protection", config.landProtectionEnabled);
        view.putByteArray("cells", cells);
        view.put("owners", owners);

        display.setItem(0, GuildItems.item(Items.FILLED_MAP, "土地マップ",
                "購入するチャンクをクリックしてから「購入」を押してください。",
                "所持チャンク: " + Numbers.format(owned),
                "次の価格: " + Numbers.format(LandService.price(config, owned)) + " コイン"));
        LandView.publish(display, view);
    }

    private static String ownerName(GuildPersistentState state, UUID owner) {
        String name = state.get(owner).playerName;
        return name == null || name.isBlank() ? "他のプレイヤー" : name;
    }

    @Override public boolean stillValid(Player player) { return player == owner && !owner.isRemoved(); }

    @Override public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS) return;
        super.clicked(slotIndex, button, actionType, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
