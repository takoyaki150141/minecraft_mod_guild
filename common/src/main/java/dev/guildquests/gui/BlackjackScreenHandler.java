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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BlackjackScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;

    private enum State { BETTING, PLAYER_TURN, DONE }

    private record Card(String rank, String suit, int value) {
        String display() { return rank + suit; }
    }

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private State state = State.BETTING;
    private long betAmount;
    private final List<Card> playerHand = new ArrayList<>();
    private final List<Card> dealerHand = new ArrayList<>();
    private String result = "";
    private long payout;
    private final List<Card> deck = new ArrayList<>();

    public BlackjackScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.BLACKJACK, syncId);
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

        initDeck();
        populate();
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new BlackjackScreenHandler(syncId, playerInventory, player),
                Component.literal("ブラックジャック")
        ));
    }

    private void initDeck() {
        deck.clear();
        String[] suits = {"♠", "♥", "♦", "♣"};
        String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        for (String suit : suits)
            for (String rank : ranks) {
                int value = switch (rank) {
                    case "A" -> 11;
                    case "J", "Q", "K" -> 10;
                    default -> Integer.parseInt(rank);
                };
                deck.add(new Card(rank, suit, value));
            }
        Collections.shuffle(deck);
    }

    private Card drawCard() {
        if (deck.isEmpty()) initDeck();
        return deck.remove(deck.size() - 1);
    }

    private int handValue(List<Card> hand) {
        int sum = 0;
        int aces = 0;
        for (Card c : hand) {
            sum += c.value();
            if (c.rank().equals("A")) aces++;
        }
        while (sum > 21 && aces > 0) { sum -= 10; aces--; }
        return sum;
    }

    private void deal() {
        if (betAmount <= 0) return;
        if (!GuildService.payCoins(owner, betAmount)) return;
        playerHand.clear();
        dealerHand.clear();
        result = "";
        payout = 0;
        playerHand.add(drawCard());
        playerHand.add(drawCard());
        dealerHand.add(drawCard());
        dealerHand.add(drawCard());
        int pv = handValue(playerHand);
        int dv = handValue(dealerHand);
        if (pv == 21 && dv == 21) {
            state = State.DONE;
            payout = betAmount;
            result = "引き分け（返金）";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else if (pv == 21) {
            state = State.DONE;
            payout = (long) (betAmount * 2.5);
            result = "ブラックジャック！";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else if (dv == 21) {
            state = State.DONE;
            result = "ディーラーのブラックジャック…";
        } else {
            state = State.PLAYER_TURN;
        }
    }

    private void hit() {
        if (state != State.PLAYER_TURN) return;
        playerHand.add(drawCard());
        int pv = handValue(playerHand);
        if (pv > 21) {
            state = State.DONE;
            result = "バースト！";
        }
    }

    private void stand() {
        if (state != State.PLAYER_TURN) return;
        while (handValue(dealerHand) < 17) dealerHand.add(drawCard());
        int pv = handValue(playerHand);
        int dv = handValue(dealerHand);
        state = State.DONE;
        if (dv > 21 || pv > dv) {
            payout = betAmount * 2;
            result = "勝ち！";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else if (pv == dv) {
            payout = betAmount;
            result = "引き分け（返金）";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else {
            result = "負け…";
        }
    }

    private void newRound() {
        state = State.BETTING;
        playerHand.clear();
        dealerHand.clear();
        result = "";
        payout = 0;
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: dealer cards (slots 0-4), dealer total (slot 6)
        for (int i = 0; i < dealerHand.size() && i < 5; i++) {
            Card c = dealerHand.get(i);
            if (state == State.PLAYER_TURN && i == 1) {
                set(0 + i, item(Items.PAPER, "？？？", " "));
            } else {
                boolean red = c.suit().equals("♥") || c.suit().equals("♦");
                set(0 + i, item(red ? Items.RED_CONCRETE : Items.BLACK_CONCRETE, c.display(), " "));
            }
        }
        if (state == State.PLAYER_TURN) {
            set(6, item(Items.COMPARATOR, "ディーラー: " + dealerHand.get(0).value() + " + ?"));
        } else if (!dealerHand.isEmpty()) {
            set(6, item(Items.COMPARATOR, "ディーラー: " + handValue(dealerHand)));
        }

        // Row 1: player cards (slots 9-13), player total (slot 15)
        for (int i = 0; i < playerHand.size() && i < 5; i++) {
            Card c = playerHand.get(i);
            boolean red = c.suit().equals("♥") || c.suit().equals("♦");
            set(9 + i, item(red ? Items.RED_CONCRETE : Items.BLACK_CONCRETE, c.display(), " "));
        }
        if (!playerHand.isEmpty()) {
            set(15, item(Items.COMPARATOR, "自分: " + handValue(playerHand)));
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
                    setAction(25, item(Items.LIME_CONCRETE, "スタート", "クリックでカードを配る"),
                            (p, b, t) -> { if (b == 0) deal(); });
                } else {
                    set(25, item(Items.GRAY_CONCRETE, "スタート",
                            betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足"));
                }
            }
            case PLAYER_TURN -> {
                setAction(18, item(Items.LIME_CONCRETE, "ヒット", "カードを1枚引く"),
                        (p, b, t) -> { if (b == 0) hit(); });
                setAction(19, item(Items.YELLOW_CONCRETE, "スタンド", "現在の手で勝負"),
                        (p, b, t) -> { if (b == 0) stand(); });
                set(23, item(Items.GOLD_INGOT, "賭け: " + Numbers.format(betAmount)));
            }
            case DONE -> {
                boolean won = payout > betAmount;
                boolean push = payout == betAmount && payout > 0;
                set(21, item(won ? Items.LIME_CONCRETE : push ? Items.YELLOW_CONCRETE : Items.RED_CONCRETE,
                        result, payout > 0 ? "+" + Numbers.format(payout) : "-" + Numbers.format(betAmount)));
                setAction(25, item(Items.LIME_CONCRETE, "もう一回", "新しいラウンドを開始"),
                        (p, b, t) -> { if (b == 0) newRound(); });
            }
        }

        setAction(26, item(Items.BARRIER, "閉じる"),
                (p, b, t) -> p.closeContainer());

        var view = GuildGamblingView.round(state.name(), betAmount, balance, result, payout);
        var playerCards = new net.minecraft.nbt.ListTag();
        var dealerCards = new net.minecraft.nbt.ListTag();
        for (Card card : playerHand) playerCards.add(GuildGamblingView.card(card.rank(), card.suit(), false));
        for (int i = 0; i < dealerHand.size(); i++) {
            Card card = dealerHand.get(i);
            dealerCards.add(GuildGamblingView.card(card.rank(), card.suit(), state == State.PLAYER_TURN && i == 1));
        }
        view.put("playerCards", playerCards);
        view.put("dealerCards", dealerCards);
        view.putInt("playerTotal", handValue(playerHand));
        view.putInt("dealerTotal", state == State.PLAYER_TURN ? dealerHand.get(0).value() : handValue(dealerHand));
        GuildGamblingView.publish(display, view);
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
