package dev.guildquests.gui;

import dev.guildquests.block.GuildDepositBlockEntity;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import dev.guildquests.util.Numbers;
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

public final class DepositSettingsScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 9;
    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final GuildDepositBlockEntity deposit;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public DepositSettingsScreenHandler(int syncId, Container playerInventory,
                                        ServerPlayer owner, GuildDepositBlockEntity deposit) {
        super(GuildMenuKind.DEPOSIT_SETTINGS, syncId);
        this.owner = owner;
        this.deposit = deposit;

        for (int col = 0; col < DISPLAY_SLOTS; col++) {
            addSlot(new Slot(display, col, 8 + col * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player playerEntity) {
                    return false;
                }
            });
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 49 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 107));
        }

        populate();
    }

    public static void open(ServerPlayer player, GuildDepositBlockEntity deposit) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) ->
                        new DepositSettingsScreenHandler(syncId, playerInventory, player, deposit),
                Component.literal("入金ブロック設定")
        ));
    }

    @Override
    public boolean stillValid(Player player) {
        if (player != owner || owner.isRemoved()) return false;
        return deposit.getLevel() != null
                && deposit.getLevel().getBlockEntity(deposit.getBlockPos()) == deposit;
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
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        long held = GuildService.countHeldCoins(owner);

        set(0, item(Items.GOLD_BLOCK, "入金ブロック設定",
                "設置者: " + deposit.getOwnerName(),
                "残高: " + Numbers.format(data.coins),
                "所持コイン: " + Numbers.format(held)));

        // Notification toggle.
        boolean notify = deposit.isNotifyOnDeposit();
        setAction(1, item(notify ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE,
                        (notify ? "● " : "") + "入金通知: " + (notify ? "ON" : "OFF"),
                        "クリックで切り替え",
                        notify ? "入金されるとチャットで通知します" : "入金通知は無効です"),
                (p, b, t) -> {
                    if (b == 0) deposit.setNotifyOnDeposit(!deposit.isNotifyOnDeposit());
                });

        depositButton(2, 1, held);
        depositButton(3, 10, held);
        depositButton(4, 64, held);
        depositAllButton(5, held);

        // Redstone pulse toggle.
        boolean redstone = deposit.isRedstoneOnDeposit();
        setAction(6, item(redstone ? Items.REDSTONE_BLOCK : Items.GRAY_CONCRETE,
                        (redstone ? "● " : "") + "レッドストーン出力: " + (redstone ? "ON" : "OFF"),
                        "クリックで切り替え",
                        redstone ? "コイン入金時にレッドストーン信号を出します" : "レッドストーン出力は無効です"),
                (p, b, t) -> {
                    if (b == 0) deposit.setRedstoneOnDeposit(!deposit.isRedstoneOnDeposit());
                });

        setAction(8, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeContainer());
    }

    private void depositButton(int slot, int amount, long held) {
        String label = "預け入れ: " + Numbers.format(amount);
        if (held <= 0) {
            setAction(slot, item(ModItems.COIN, label, "所持コインがありません"),
                    (p, b, t) -> GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), amount));
            return;
        }
        int take = (int) Math.min(amount, held);
        setAction(slot, item(ModItems.COIN, label,
                        "クリックで預け入れ",
                        "手持ち -" + Numbers.format(take) + " → 残高 +" + Numbers.format(take),
                        "手数料: 無料"),
                (p, b, t) -> {
                    if (b == 0 && GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), amount)) {
                        deposit.pulseRedstone();
                    }
                });
    }

    private void depositAllButton(int slot, long held) {
        String label = "預け入れ: 全部";
        if (held <= 0) {
            setAction(slot, item(ModItems.COIN, label, "所持コインがありません"),
                    (p, b, t) -> GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), Long.MAX_VALUE));
            return;
        }
        setAction(slot, item(ModItems.COIN, label,
                        "クリックで全部預け入れ",
                        "手持ち -" + Numbers.format(held) + " → 残高 +" + Numbers.format(held),
                        "手数料: 無料"),
                (p, b, t) -> {
                    if (b == 0 && GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), Long.MAX_VALUE)) {
                        deposit.pulseRedstone();
                    }
                });
    }

    private void set(int slot, ItemStack stack) {
        display.setItem(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, GuildItems.action(stack));
        actions.put(slot, action);
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return GuildItems.item(item, name, lore);
    }


}
