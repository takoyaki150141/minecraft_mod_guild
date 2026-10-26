package dev.guildquests.gui;

import dev.guildquests.service.GuildService;
import dev.guildquests.util.Numbers;
import dev.guildquests.item.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.Map;

public final class GambleScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public GambleScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.GAMBLE, syncId);
        this.owner = owner;

        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            addSlot(new Slot(display, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });
        }
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));

        populate();
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new GambleScreenHandler(syncId, playerInventory, player),
                Component.literal("ギャンブル")
        ));
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: balance info
        set(4, item(Items.GOLD_INGOT, "所持コイン: " + Numbers.format(balance)));

        // Row 1: game selection
        setAction(10, item(Items.AMETHYST_SHARD, "チンチロ", "クリックでプレイ", "サイコロで勝負！"),
                (p, b, t) -> { if (b == 0) ChinchirorinScreenHandler.open(p); });
        setAction(11, item(Items.PLAYER_HEAD, "ブラックジャック", "クリックでプレイ", "21に近い方が勝ち"),
                (p, b, t) -> { if (b == 0) BlackjackScreenHandler.open(p); });
        setAction(13, item(Items.HAY_BLOCK, "競馬", "クリックでプレイ", "馬に賭けて大勝負！"),
                (p, b, t) -> { if (b == 0) HorseRaceScreenHandler.open(p); });
        setAction(12, item(Items.LEVER, "スロット", "クリックでプレイ", "リールを揃えて大当たり！"),
                (p, b, t) -> { if (b == 0) SlotScreenHandler.open(p); });
        setAction(14, item(Items.COMPASS, "ルーレット", "クリックでプレイ", "赤・黒・ゼロに賭ける"),
                (p, b, t) -> { if (b == 0) RouletteScreenHandler.open(p); });
        setAction(15, item(ModItems.COIN, "コイントス", "クリックでプレイ", "表か裏か、2倍勝負"),
                (p, b, t) -> { if (b == 0) CoinFlipScreenHandler.open(p); });
        setAction(16, item(Items.EMERALD, "Guild FX — 仮想通貨取引",
                "クリックでFXを開きます", "成行・指値・逆指値 / Mod専用の架空通貨",
                "F8の有効・無効にかかわらず利用できます"),
                (p, b, t) -> { if (b == 0) dev.guildquests.fx.FxService.open(p); });

        // Row 2: close
        setAction(22, item(Items.BARRIER, "閉じる"),
                (p, b, t) -> p.closeContainer());
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return GuildItems.item(item, name, lore);
    }



    private void set(int slot, ItemStack stack) {
        display.setItem(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, GuildItems.action(stack));
        actions.put(slot, action);
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS && player instanceof ServerPlayer serverPlayer
                && actionType == ClickType.PICKUP) {
            MenuAction action = actions.get(slotIndex);
            if (action != null) {
                action.run(serverPlayer, button, actionType);
                if (serverPlayer.containerMenu == this) {
                    populate();
                    broadcastChanges();
                }
            }
            return;
        }
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS) return;
        super.clicked(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) { return player == owner && !owner.isRemoved(); }
}
