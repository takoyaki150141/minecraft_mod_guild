package dev.guildquests.gui;

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
import java.util.Random;

/**
 * Chinchirorin (チンチロ): roll 3 dice vs the house.
 * Hand ranking (high to low): アリ (1-1-1) &gt; ゾロ目 6 &gt; 5 &gt; 4 &gt; 3 &gt; 2 &gt; 目9 &gt; … &gt; 目1 &gt; バン (目0).
 * Win pays 2x (3x if the winner rolled a triple); equal hands refund the bet.
 */
public final class ChinchirorinScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private static final Random RANDOM = new Random();
    private static final String[] DICE_FACES = {"", "⚀", "⚁", "⚂", "⚃", "⚄", "⚅"};

    private enum State { BETTING, DONE }

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private State state = State.BETTING;
    private long betAmount;
    private final int[] playerDice = new int[3];
    private final int[] dealerDice = new int[3];
    private boolean rolled;
    private String result = "";
    private boolean playerWon;
    private boolean push;
    private long payout;

    public ChinchirorinScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.CHINCHIRORIN, syncId);
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
                (syncId, playerInventory, ignored) -> new ChinchirorinScreenHandler(syncId, playerInventory, player),
                Component.literal("チンチロ")
        ));
    }

    /** Comparable hand rank; higher wins. アリ=100, ゾロ目=50+n, 目=0-9. */
    private static int handRank(int a, int b, int c) {
        if (a == b && b == c) {
            return a == 1 ? 100 : 50 + a;
        }
        return (a + b + c) % 10;
    }

    private static String handName(int a, int b, int c) {
        if (a == b && b == c) {
            return a == 1 ? "アリ！" : "ゾロ目 " + a + "×3";
        }
        int me = (a + b + c) % 10;
        return me == 0 ? "バン" : "目: " + me;
    }

    private static boolean isTriple(int a, int b, int c) {
        return a == b && b == c;
    }

    private void roll() {
        if (betAmount <= 0) return;
        if (!GuildService.payCoins(owner, betAmount)) return;
        for (int i = 0; i < 3; i++) {
            playerDice[i] = 1 + RANDOM.nextInt(6);
            dealerDice[i] = 1 + RANDOM.nextInt(6);
        }
        rolled = true;
        int pr = handRank(playerDice[0], playerDice[1], playerDice[2]);
        int dr = handRank(dealerDice[0], dealerDice[1], dealerDice[2]);
        state = State.DONE;
        payout = 0;
        if (pr > dr) {
            playerWon = true;
            push = false;
            // Flat 2x on a win. (A 3x "triple bonus" made the expected value exceed 1 —
            // ties refund and P(win)=P(lose), so 2x keeps EV at exactly 1.0.)
            payout = betAmount * 2;
            result = "勝ち！";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else if (pr == dr) {
            playerWon = false;
            push = true;
            payout = betAmount;
            result = "引き分け（返金）";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else {
            playerWon = false;
            push = false;
            result = "負け…";
        }
    }

    private void newRound() {
        state = State.BETTING;
        rolled = false;
        result = "";
        playerWon = false;
        push = false;
        payout = 0;
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: dealer dice (slots 0-2), dealer hand (slot 4)
        if (rolled) {
            for (int i = 0; i < 3; i++) {
                set(i, dieItem(dealerDice[i]));
            }
            set(4, item(Items.COMPARATOR, "相手: " + handName(dealerDice[0], dealerDice[1], dealerDice[2])));
        }

        // Row 1: player dice (slots 9-11), player hand (slot 13)
        if (rolled) {
            for (int i = 0; i < 3; i++) {
                set(9 + i, dieItem(playerDice[i]));
            }
            set(13, item(Items.COMPARATOR, "自分: " + handName(playerDice[0], playerDice[1], playerDice[2])));
        }

        // Row 2: controls
        switch (state) {
            case BETTING -> {
                setAction(18, item(Items.PAPER, "賭け ±10", "所持: " + Numbers.format(balance), "右クリックで -10"),
                        (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 10); else if (b == 1) betAmount = Math.max(0, betAmount - 10); });
                setAction(19, item(Items.PAPER, "賭け ±50", "所持: " + Numbers.format(balance), "右クリックで -50"),
                        (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 50); else if (b == 1) betAmount = Math.max(0, betAmount - 50); });
                setAction(20, item(Items.PAPER, "賭け ±100", "所持: " + Numbers.format(balance), "右クリックで -100"),
                        (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 100); else if (b == 1) betAmount = Math.max(0, betAmount - 100); });
                setAction(21, item(Items.PAPER, "賭け ±500", "所持: " + Numbers.format(balance), "右クリックで -500"),
                        (p, b, t) -> { if (b == 0) betAmount = Math.min(balance, betAmount + 500); else if (b == 1) betAmount = Math.max(0, betAmount - 500); });
                set(23, item(Items.GOLD_INGOT, "賭け: " + Numbers.format(betAmount)));
                if (betAmount > 0 && betAmount <= balance) {
                    setAction(25, item(Items.LIME_CONCRETE, "サイコロを投げる", "クリックで勝負！"),
                            (p, b, t) -> { if (b == 0) roll(); });
                } else {
                    set(25, item(Items.GRAY_CONCRETE, "サイコロを投げる",
                            betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足"));
                }
            }
            case DONE -> {
                set(21, item(playerWon ? Items.LIME_CONCRETE : push ? Items.YELLOW_CONCRETE : Items.RED_CONCRETE,
                        result, payout > 0 ? "+" + Numbers.format(payout) : "-" + Numbers.format(betAmount)));
                setAction(25, item(Items.LIME_CONCRETE, "もう一回", "新しいラウンドを開始"),
                        (p, b, t) -> { if (b == 0) newRound(); });
            }
        }

        setAction(26, item(Items.BARRIER, "閉じる"),
                (p, b, t) -> p.closeContainer());

        var view = GuildGamblingView.round(state.name(), betAmount, balance, result, payout);
        view.putIntArray("dealerDice", rolled ? dealerDice : new int[0]);
        view.putIntArray("playerDice", rolled ? playerDice : new int[0]);
        view.putString("dealerHand", rolled ? handName(dealerDice[0], dealerDice[1], dealerDice[2]) : "相手");
        view.putString("playerHand", rolled ? handName(playerDice[0], playerDice[1], playerDice[2]) : "あなた");
        GuildGamblingView.publish(display, view);
    }

    private static ItemStack dieItem(int value) {
        return item(Items.WHITE_CONCRETE, DICE_FACES[value] + " " + value, "サイコロ");
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
