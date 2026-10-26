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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slot machine (スロット): spin 3 reels.
 * Three of a kind pays the symbol's multiplier; exactly two of a kind refunds the bet.
 * Symbols are weighted so high payouts are rarer.
 * <p>
 * The outcome is settled immediately on spin (bet paid, winnings credited); the SPINNING
 * state is purely a display animation driven from {@link #tickAll(MinecraftServer)}, so
 * closing the GUI mid-spin never affects the payout.
 */
public final class SlotScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private static final Random RANDOM = new Random();

    private static final Item[] SYMBOL_ICONS = {
            Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.APPLE, Items.STICK
    };
    private static final String[] SYMBOL_NAMES = {"ダイヤ", "エメラルド", "金塊", "鉄", "りんご", "棒"};
    private static final long[] TRIPLE_PAYOUT = {80, 50, 25, 12, 6, 4};
    private static final int[] WEIGHTS = {1, 2, 4, 6, 8, 10};

    /** Ticks until each reel locks (progressive stop). */
    private static final int[] STOP_TICKS = {18, 26, 34};
    private static final int SPIN_DURATION = STOP_TICKS[2];

    /** Handlers currently animating a spin, so the server tick loop can advance them. */
    private static final Map<UUID, SlotScreenHandler> ANIMATING = new ConcurrentHashMap<>();

    /** Clears animation state; called on server stop. */
    public static void clearAll() {
        ANIMATING.clear();
    }

    private enum State { BETTING, SPINNING, DONE }

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private State state = State.BETTING;
    private long betAmount;
    private final int[] reels = {0, 0, 0};
    private int spinTicks;
    private boolean playerWon;
    private boolean refunded;
    private long payout;
    private String result = "";

    public SlotScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.SLOT, syncId);
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
                (syncId, playerInventory, ignored) -> new SlotScreenHandler(syncId, playerInventory, player),
                Component.literal("スロット")
        ));
    }

    /** Advances every in-flight spin animation. Called once per server tick. */
    public static void tickAll(MinecraftServer server) {
        if (ANIMATING.isEmpty()) return;
        ANIMATING.entrySet().removeIf(entry -> {
            SlotScreenHandler h = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.containerMenu != h) {
                return true; // GUI closed or player gone; outcome already settled
            }
            h.spinTicks++;
            if (h.spinTicks >= SPIN_DURATION) {
                h.state = State.DONE;
                h.populate();
                h.broadcastChanges();
                return true;
            }
            h.populate();
            h.broadcastChanges();
            return false;
        });
    }

    private static int pickSymbol() {
        int total = 0;
        for (int w : WEIGHTS) total += w;
        int r = RANDOM.nextInt(total);
        for (int i = 0; i < WEIGHTS.length; i++) {
            r -= WEIGHTS[i];
            if (r < 0) return i;
        }
        return WEIGHTS.length - 1;
    }

    private void spin() {
        if (betAmount <= 0) return;
        if (!GuildService.payCoins(owner, betAmount)) return;

        // Determine the outcome and settle it now.
        for (int i = 0; i < 3; i++) reels[i] = pickSymbol();
        payout = 0;
        refunded = false;
        playerWon = false;
        if (reels[0] == reels[1] && reels[1] == reels[2]) {
            playerWon = true;
            payout = betAmount * TRIPLE_PAYOUT[reels[0]];
            result = SYMBOL_NAMES[reels[0]] + " 揃い！";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else if (reels[0] == reels[1] || reels[1] == reels[2] || reels[0] == reels[2]) {
            refunded = true;
            payout = betAmount;
            result = "2つ揃い（返金）";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else {
            result = "ハズレ…";
        }

        // Play the spin animation before revealing the result.
        state = State.SPINNING;
        spinTicks = 0;
        ANIMATING.put(owner.getUUID(), this);
    }

    private void newRound() {
        state = State.BETTING;
        playerWon = false;
        refunded = false;
        payout = 0;
        result = "";
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: three reels (flicker while spinning, lock progressively)
        for (int i = 0; i < 3; i++) {
            int sym;
            if (state == State.SPINNING) {
                sym = spinTicks >= STOP_TICKS[i] ? reels[i] : RANDOM.nextInt(SYMBOL_ICONS.length);
            } else if (state == State.DONE) {
                sym = reels[i];
            } else {
                set(3 + i, item(Items.GRAY_DYE, "?", "スピンで抽選"));
                continue;
            }
            boolean locked = state == State.DONE || spinTicks >= STOP_TICKS[i];
            set(3 + i, item(SYMBOL_ICONS[sym], SYMBOL_NAMES[sym],
                    locked ? "3つ揃い: " + TRIPLE_PAYOUT[sym] + "倍" : "回転中…"));
        }

        // Row 1: status / paytable
        switch (state) {
            case SPINNING -> set(13, item(Items.CLOCK, "回転中…", "リールが止まるまでお待ちください"));
            case DONE -> set(13, item(playerWon ? Items.LIME_CONCRETE : refunded ? Items.YELLOW_CONCRETE : Items.RED_CONCRETE,
                    result, payout > 0 ? "+" + Numbers.format(payout) : "-" + Numbers.format(betAmount)));
            case BETTING -> set(13, item(Items.BOOK, "配当表",
                    "ダイヤ×80 / エメラルド×50 / 金塊×25",
                    "鉄×12 / りんご×6 / 棒×4",
                    "2つ揃いで賭け金返金"));
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
                    setAction(25, item(Items.LEVER, "スピン", "クリックで回す！"),
                            (p, b, t) -> { if (b == 0) spin(); });
                } else {
                    set(25, item(Items.GRAY_CONCRETE, "スピン",
                            betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足"));
                }
            }
            case SPINNING -> set(25, item(Items.GRAY_CONCRETE, "回転中…", "お待ちください"));
            case DONE -> setAction(25, item(Items.LIME_CONCRETE, "もう一回", "新しいラウンドを開始"),
                    (p, b, t) -> { if (b == 0) newRound(); });
        }

        setAction(26, item(Items.BARRIER, "閉じる"),
                (p, b, t) -> p.closeContainer());

        var view = GuildGamblingView.round(state.name(), betAmount, balance, result, payout);
        view.putIntArray("reels", GuildGamblingView.visibleReels(reels, spinTicks, STOP_TICKS,
                state == State.SPINNING, state == State.DONE));
        view.putInt("ticks", spinTicks);
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
    public void removed(Player player) {
        super.removed(player);
        ANIMATING.remove(owner.getUUID());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) { return player == owner && !owner.isRemoved(); }
}
