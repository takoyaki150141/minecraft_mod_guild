package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
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

/**
 * Consumer finance (消費者金融): borrow coins against a rank-based limit and repay manually.
 * Interest accrues on the outstanding loan periodically; there is no automatic collection.
 */
public final class LoanScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public LoanScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.LOAN, syncId);
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
                (syncId, playerInventory, ignored) -> new LoanScreenHandler(syncId, playerInventory, player),
                Component.literal("消費者金融")
        ));
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && !owner.isRemoved();
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

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        var cfg = ConfigManager.config();
        PlayerGuildData data = GuildService.data(owner);
        long limit = GuildService.loanLimit(data);
        long borrowable = Math.max(0, limit - data.loan);
        long minutesLeft = Math.max(0, (data.nextLoanInterestAt - System.currentTimeMillis()) / 60_000L);

        set(0, item(Items.EMERALD, "借入上限: " + Numbers.format(limit),
                "ギルドランク「" + GuildService.rank(data) + "」の上限",
                "高ランクほど上限が上がります"));
        set(1, item(Items.REDSTONE_BLOCK, "現在の債務: " + Numbers.format(data.loan),
                data.loan > 0 ? "利息 " + cfg.consumerLoanInterestPercent + "% / "
                        + Numbers.format(cfg.consumerLoanIntervalMinutes) + "分ごと" : "債務はありません"));
        set(2, item(Items.GOLD_INGOT, "借入可能: " + Numbers.format(borrowable),
                "上限 - 現在の債務"));
        set(3, item(ModItems.COIN, "残高: " + Numbers.format(data.coins),
                "借入は残高に加算されます"));
        set(4, item(Items.CLOCK, "次回利息まで: " + (data.loan > 0 ? Numbers.format(minutesLeft) + " 分" : "-"),
                "利息: " + cfg.consumerLoanInterestPercent + "% / " + Numbers.format(cfg.consumerLoanIntervalMinutes) + " 分"));
        set(5, item(Items.WRITABLE_BOOK, "消費者金融",
                "借入: 残高に加算され、債務になります",
                cfg.miningDebtRepaymentOnly ? "返済: 設定したブロックの採掘で自動返済" : "返済: 残高から手動で返済",
                cfg.miningDebtRepaymentOnly ? "採掘返済のみ" : "返済は自動ではありません"));

        // Row 1: borrow (9-12) / repay all (13) / repay (14-17).
        if (borrowable > 0) {
            borrowButton(9, 100, borrowable);
            borrowButton(10, 500, borrowable);
            borrowButton(11, 1000, borrowable);
            borrowButton(12, 5000, borrowable);
        } else {
            set(9, item(Items.GRAY_CONCRETE, "借入不可", "上限に達しています"));
        }

        if (data.loan > 0) {
            if (cfg.miningDebtRepaymentOnly) {
                set(13, item(Items.GRAY_CONCRETE, "返済は採掘のみ",
                        "設定したブロックを掘って返済します",
                        "残債: " + Numbers.format(data.loan)));
            } else if (data.coins > 0) {
                setAction(13, item(Items.LIME_CONCRETE, "全額返済",
                                "残高の範囲で最大まで返済",
                                "債務 " + Numbers.format(data.loan) + " / 残高 " + Numbers.format(data.coins)),
                        (p, b, t) -> { if (b == 0) GuildService.repayLoan(p, 0); });
                repayButton(14, 100);
                repayButton(15, 500);
                repayButton(16, 1000);
                repayButton(17, 5000);
            } else {
                set(13, item(Items.GRAY_CONCRETE, "返済不可", "残高がありません"));
            }
        } else {
            set(13, item(Items.GRAY_CONCRETE, "返済", "債務がありません"));
        }

        setAction(22, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeContainer());
    }

    private void borrowButton(int slot, long amount, long borrowable) {
        long take = Math.min(amount, borrowable);
        setAction(slot, item(Items.LIME_DYE, "借入 +" + Numbers.format(amount),
                        "クリックで " + Numbers.format(take) + " コイン借り入れ",
                        "残高 +" + Numbers.format(take) + " / 債務 +" + Numbers.format(take)),
                (p, b, t) -> { if (b == 0) GuildService.borrowLoan(p, take); });
    }

    private void repayButton(int slot, long amount) {
        PlayerGuildData data = GuildService.data(owner);
        long pay = Math.min(amount, Math.min(data.loan, data.coins));
        setAction(slot, item(Items.RED_DYE, "返済 -" + Numbers.format(amount),
                        "クリックで " + Numbers.format(pay) + " コイン返済",
                        "残高 -" + Numbers.format(pay) + " / 債務 -" + Numbers.format(pay)),
                (p, b, t) -> { if (b == 0) GuildService.repayLoan(p, amount); });
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
}
