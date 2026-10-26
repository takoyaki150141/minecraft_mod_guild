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

public final class BankScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final boolean feeWaived;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    /** Amount selected via the client keypad, 0..Integer.MAX_VALUE. */
    private long bankAmount;
    /** Guild coins currently held, published to the keypad as the spendable wallet. */
    private long walletHeld;

    public BankScreenHandler(int syncId, Container playerInventory, ServerPlayer owner, boolean feeWaived) {
        super(GuildMenuKind.BANK, syncId);
        this.owner = owner;
        this.feeWaived = feeWaived;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                addSlot(new Slot(display, index, 8 + col * 18, 18 + row * 18) {
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
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        populate();
    }

    public static void open(ServerPlayer player) {
        open(player, false);
    }

    public static void openFromBlock(ServerPlayer player) {
        open(player, true);
    }

    private static void open(ServerPlayer player, boolean feeWaived) {
        String title = feeWaived ? "ギルド銀行（手数料無料）" : "ギルド銀行";
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new BankScreenHandler(syncId, playerInventory, player, feeWaived),
                Component.literal(title)
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
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override public boolean setGuiAmount(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) return false;
        bankAmount = value;
        populate();
        return true;
    }
    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        long held = GuildService.countHeldCoins(owner);
        walletHeld = held;
        long balance = data.coins;
        if (bankAmount < 0) bankAmount = 0;

        // Slot 5 is background only; it supplies the summary line above the row list.
        set(5, GuildItems.header(item(Items.NAME_TAG, "銀行サマリー"),
                "残高 " + Numbers.gc(balance) + " / 借入 " + Numbers.gc(data.loan)
                        + " / FX・リボ " + Numbers.gc(data.debt)
                        + " / 滞納 " + (data.delinquent ? "あり" : "なし")));

        // Row 0: held coins / fee / transfer / loan. (Bank balance lives in the header.)
        set(1, item(ModItems.COIN, "所持コイン: " + Numbers.format(held), "インベントリ内のギルドコイン"));
        set(2, feeWaived
                ? item(Items.EMERALD, "手数料: 無料", "ギルド銀行ブロック経由のため", "すべての取引が無料です")
                : item(Items.PAPER, "手数料: " + feePercentText() + "%",
                        "預け入れ・引き出し時に課金されます",
                        "ギルド銀行ブロック経由なら無料です"));
        setAction(4, item(Items.PLAYER_HEAD, "送金（新メニュー）",
                "クリックで送金メニューを開きます",
                "送金先の選択・金額指定はこちらで",
                "手数料: 無料"),
                (p, b, t) -> TransferScreenHandler.open(p));
        setAction(3, item(Items.REDSTONE, "返済",
                "クリックで借入・返済メニューを開きます",
                "ギルドランクに応じた上限まで借入できます",
                "返済は手動（利息が付きます）"),
                (p, b, t) -> LoanScreenHandler.open(p));

        // Ribo debt detail moved into the header; the amount book follows below.
        set(13, item(Items.WRITABLE_BOOK, "ギルド銀行",
                "数字頭・紙で金額を選択してから",
                "下段の入金・出金ボタンで実行",
                "所持金全部ボタンで全額をまとめて選択",
                "支払い方法: 引き落とし / リボ / 無効"));

        // Row 2: payment modes / blank / close / deposit / withdraw.
        String mode = GuildService.paymentMode(data);
        paymentModeButton(18, GuildService.MODE_DEBIT, "引き落とし", mode);
        paymentModeButton(19, GuildService.MODE_RIBO, "リボ", mode);
        paymentModeButton(20, GuildService.MODE_DISABLED, "無効", mode);
        setAction(22, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeContainer());
        depositAction(25, held);
        withdrawAction(26, balance);
    }

    private void depositAction(int slot, long held) {
        if (bankAmount <= 0) {
            setAction(slot, item(ModItems.COIN, "入金",
                            "金額が 0 です",
                            "数字頭または紙で金額を選択"),
                    (p, b, t) -> { });
            return;
        }
        if (bankAmount > held) {
            setAction(slot, item(ModItems.COIN, "入金",
                            "所持コインが不足しています",
                            "所持: " + Numbers.format(held) + " / 選択: " + Numbers.format(bankAmount)),
                    (p, b, t) -> { });
            return;
        }
        long fee = GuildService.bankFee(bankAmount, feeWaived);
        setAction(slot, item(ModItems.COIN, "入金: " + Numbers.format(bankAmount),
                        "クリックで預け入れ",
                        "手持ち -" + Numbers.format(bankAmount) + " → 残高 +" + Numbers.format(bankAmount - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankDeposit(p, bankAmount, feeWaived);
                });
    }

    private void withdrawAction(int slot, long balance) {
        if (bankAmount <= 0) {
            setAction(slot, item(Items.GOLD_NUGGET, "出金",
                            "金額が 0 です",
                            "数字頭または紙で金額を選択"),
                    (p, b, t) -> { });
            return;
        }
        if (bankAmount > balance) {
            setAction(slot, item(Items.GOLD_NUGGET, "出金",
                            "銀行残高が不足しています",
                            "残高: " + Numbers.format(balance) + " / 選択: " + Numbers.format(bankAmount)),
                    (p, b, t) -> { });
            return;
        }
        long fee = GuildService.bankFee(bankAmount, feeWaived);
        setAction(slot, item(Items.GOLD_NUGGET, "出金: " + Numbers.format(bankAmount),
                        "クリックで引き出し",
                        "残高 -" + Numbers.format(bankAmount) + " → 手持ち +" + Numbers.format(bankAmount - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankWithdraw(p, bankAmount, feeWaived);
                });
    }

    private void paymentModeButton(int slot, String id, String label, String current) {
        boolean active = id.equals(current);
        Item icon = active ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE;
        String name = (active ? "● " : "") + "支払い: " + label;
        String[] lore = switch (id) {
            case GuildService.MODE_RIBO -> new String[]{
                    "クリックでリボ払いに切替",
                    "残高がなくても購入でき、価格は債務化",
                    ConfigManager.config().miningDebtRepaymentOnly
                            ? "返済は採掘のみ。利息 " + ConfigManager.config().riboInterestPercent + "% / "
                            + Numbers.format(ConfigManager.config().riboIntervalMinutes) + "分ごと"
                            : "利息 " + ConfigManager.config().riboInterestPercent + "% / "
                            + Numbers.format(ConfigManager.config().riboIntervalMinutes) + "分ごとに "
                            + Numbers.format(ConfigManager.config().riboInstallment) + " コインを自動徴収",
                    ConfigManager.config().miningDebtRepaymentOnly
                            ? "延滞ロック → 採掘で解除"
                            : "返済不能で延滞ロック → クエストで返済"
            };
            case GuildService.MODE_DISABLED -> new String[]{
                    "クリックで無効に切替",
                    "残高からの支払いを禁止",
                    "手持ちのコインのみで支払う"
            };
            default -> new String[]{
                    "クリックで引き落としに切替",
                    "購入時に残高から即時引き落とし",
                    "コインはストックティッカーに入金され",
                    "店主が引き出せる"
            };
        };
        setAction(slot, item(icon, name, lore), (p, b, t) -> {
            if (b == 0) GuildService.setPaymentMode(p, id);
        });
    }

    private void riboDebtInfo(int startSlot, PlayerGuildData data) {
        boolean hasDebt = data.debt > 0;
        boolean delinquent = data.delinquent;
        long minutesLeft = Math.max(0, (data.nextRiboDueAt - System.currentTimeMillis()) / 60_000L);
        var cfg = ConfigManager.config();

        if (delinquent) {
            set(startSlot, item(Items.REDSTONE_BLOCK, "債務: " + Numbers.format(data.debt),
                    "⚠ 延滞中：ギルド機能ロック中",
                    cfg.miningDebtRepaymentOnly ? "採掘で返済" : "クエスト報酬は全額自動で返済に充当",
                    "完済するまで銀行・残高支払い不可"));
        } else if (hasDebt) {
            set(startSlot, item(Items.REDSTONE, "債務: " + Numbers.format(data.debt),
                    "次回の返済期限まで約 " + Numbers.format(minutesLeft) + " 分",
                    cfg.miningDebtRepaymentOnly ? "返済は採掘のみ" : "残高から " + Numbers.format(cfg.riboInstallment) + " コインを定期徴収"));
        } else {
            set(startSlot, item(Items.GOLD_NUGGET, "債務: なし",
                    "支払い方法をリボにすると",
                    "後払い購入（債務）が使える"));
        }

        set(startSlot + 1, item(Items.CLOCK, "次回期限まで: " + Numbers.format(minutesLeft) + " 分",
                "周期: " + Numbers.format(cfg.riboIntervalMinutes) + " 分ごと",
                hasDebt ? (cfg.miningDebtRepaymentOnly ? "採掘で返済" : "自動で残高から徴収") : "現在債務なし"));
        set(startSlot + 2, item(Items.COPPER_INGOT, cfg.miningDebtRepaymentOnly ? "返済方法: 採掘" : "自動徴収額: " + Numbers.format(cfg.riboInstallment),
                cfg.miningDebtRepaymentOnly ? "設定したブロックを掘って返済" : "周期ごとに徴収される金額",
                "利息 " + cfg.riboInterestPercent + "% が周期ごとに加算"));
        set(startSlot + 3, item(Items.AMETHYST_SHARD, "利息: " + cfg.riboInterestPercent + "%",
                "周期ごとに債務へ加算",
                cfg.miningDebtRepaymentOnly ? "延滞でロック → 採掘で返済" : "延滞でロック → クエストで返済"));
    }

    private String feeLine(long fee) {
        if (feeWaived) return "手数料: 無料（ブロック経由）";
        return fee > 0 ? "手数料: -" + Numbers.format(fee) + " コイン" : "手数料: 0 コイン";
    }

    private String feePercentText() {
        double pct = ConfigManager.config().bankFeePercent;
        if (pct == Math.floor(pct)) return String.valueOf((long) pct);
        return String.valueOf(pct);
    }

    private void set(int slot, ItemStack stack) {
        if (slot == 13) GuildItems.amount(stack, bankAmount, Integer.MAX_VALUE, walletHeld, "入出金額");
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
