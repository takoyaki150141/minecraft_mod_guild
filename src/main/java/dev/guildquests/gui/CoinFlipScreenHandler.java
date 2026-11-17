package dev.guildquests.gui;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coin Flip (コイントス): call heads or tails, win 2x.
 * <p>
 * The outcome is settled immediately on flip; the FLIPPING state is a display animation
 * advanced from {@link #tickAll(MinecraftServer)} so closing the GUI never affects payout.
 */
public final class CoinFlipScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private static final Random RANDOM = new Random();
    private static final int FLIP_DURATION = 15;

    private static final Map<UUID, CoinFlipScreenHandler> ANIMATING = new ConcurrentHashMap<>();

    /** Clears animation state; called on server stop. */
    public static void clearAll() {
        ANIMATING.clear();
    }

    private enum State { BETTING, FLIPPING, DONE }

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private State state = State.BETTING;
    private long betAmount;
    private int choice = -1;      // 0 = 表(heads), 1 = 裏(tails)
    private int landedSide = -1;
    private int flipTicks;
    private boolean playerWon;
    private long payout;
    private String result = "";

    public CoinFlipScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.COIN_FLIP, syncId);
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
                (syncId, playerInventory, ignored) -> new CoinFlipScreenHandler(syncId, playerInventory, player),
                Component.literal("コイントス")
        ));
    }

    /** Advances every in-flight flip animation. Called once per server tick. */
    public static void tickAll(MinecraftServer server) {
        if (ANIMATING.isEmpty()) return;
        ANIMATING.entrySet().removeIf(entry -> {
            CoinFlipScreenHandler h = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.containerMenu != h) {
                return true;
            }
            h.flipTicks++;
            if (h.flipTicks >= FLIP_DURATION) {
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

    private void flip() {
        if (betAmount <= 0 || choice < 0) return;
        if (!GuildService.payCoins(owner, betAmount)) return;

        landedSide = RANDOM.nextInt(2);
        if (landedSide == choice) {
            playerWon = true;
            payout = betAmount * 2;
            result = "勝ち！";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else {
            playerWon = false;
            payout = 0;
            result = "負け…";
        }

        state = State.FLIPPING;
        flipTicks = 0;
        ANIMATING.put(owner.getUUID(), this);
    }

    private void newRound() {
        state = State.BETTING;
        choice = -1;
        landedSide = -1;
        playerWon = false;
        payout = 0;
        result = "";
    }

    private static Item faceIcon(int side) {
        return side == 0 ? Items.GOLD_INGOT : Items.IRON_INGOT;
    }

    private static String faceName(int side) {
        return side == 0 ? "表 (オモテ)" : "裏 (ウラ)";
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: title / result
        switch (state) {
            case BETTING -> set(4, item(ModItems.COIN, "コイントス", "表か裏を選んで 2倍！"));
            case FLIPPING -> {
                int shown = RANDOM.nextInt(2);
                set(4, item(faceIcon(shown), "投げている… " + faceName(shown), " "));
            }
            case DONE -> set(4, item(faceIcon(landedSide), faceName(landedSide), "出目: " + (landedSide == 0 ? "表" : "裏")));
        }

        // Row 1: heads / tails selection
        if (state == State.BETTING) {
            boolean headsSel = choice == 0;
            setAction(11, item(headsSel ? Items.GOLD_BLOCK : Items.GOLD_INGOT,
                            (headsSel ? "● " : "") + "表 (オモテ)", "クリックで選択"),
                    (p, b, t) -> { if (b == 0) choice = 0; });
            boolean tailsSel = choice == 1;
            setAction(15, item(tailsSel ? Items.IRON_BLOCK : Items.IRON_INGOT,
                            (tailsSel ? "● " : "") + "裏 (ウラ)", "クリックで選択"),
                    (p, b, t) -> { if (b == 0) choice = 1; });
        } else {
            set(11, item(choice == 0 ? Items.GOLD_BLOCK : Items.GOLD_INGOT,
                    (choice == 0 ? "● " : "") + "表 (オモテ)", choice == 0 ? "あなたの選択" : " "));
            set(15, item(choice == 1 ? Items.IRON_BLOCK : Items.IRON_INGOT,
                    (choice == 1 ? "● " : "") + "裏 (ウラ)", choice == 1 ? "あなたの選択" : " "));
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
                set(23, item(ModItems.COIN, "賭け: " + Numbers.format(betAmount)));
                if (betAmount > 0 && betAmount <= balance && choice >= 0) {
                    setAction(25, item(Items.LIME_CONCRETE, "コインを投げる", "クリックで勝負！"),
                            (p, b, t) -> { if (b == 0) flip(); });
                } else {
                    String reason = choice < 0 ? "表か裏を選んでください"
                            : betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足";
                    set(25, item(Items.GRAY_CONCRETE, "コインを投げる", reason));
                }
            }
            case FLIPPING -> set(25, item(Items.GRAY_CONCRETE, "投げている…", "お待ちください"));
            case DONE -> {
                set(23, item(playerWon ? Items.LIME_CONCRETE : Items.RED_CONCRETE,
                        result, payout > 0 ? "+" + Numbers.format(payout) : "-" + Numbers.format(betAmount)));
                setAction(25, item(Items.LIME_CONCRETE, "もう一回", "新しいラウンドを開始"),
                        (p, b, t) -> { if (b == 0) newRound(); });
            }
        }

        setAction(26, item(Items.BARRIER, "閉じる"),
                (p, b, t) -> p.closeContainer());

        var view = GuildGamblingView.round(state.name(), betAmount, balance, result, payout);
        view.putInt("choice", choice);
        view.putInt("side", state == State.DONE ? landedSide : -1);
        view.putInt("ticks", flipTicks);
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
