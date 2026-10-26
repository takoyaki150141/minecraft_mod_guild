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
 * Roulette (ルーレット): single-zero wheel (0-36).
 * Bet on 赤/黒 (pays 2x) or ゼロ (pays 36x).
 * <p>
 * The outcome is settled immediately on spin; the SPINNING state is a display animation
 * advanced from {@link #tickAll(MinecraftServer)} so closing the GUI never affects payout.
 */
public final class RouletteScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private static final Random RANDOM = new Random();
    private static final int SPIN_DURATION = 30;

    /** Standard roulette red pockets. */
    private static final int[] RED_NUMBERS = {
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    };

    private static final Map<UUID, RouletteScreenHandler> ANIMATING = new ConcurrentHashMap<>();

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
    private int choice = -1;      // 0 = 赤, 1 = 黒, 2 = ゼロ
    private int rolled = -1;
    private int spinTicks;
    private boolean playerWon;
    private long payout;
    private String result = "";

    public RouletteScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.ROULETTE, syncId);
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
                (syncId, playerInventory, ignored) -> new RouletteScreenHandler(syncId, playerInventory, player),
                Component.literal("ルーレット")
        ));
    }

    /** Advances every in-flight spin animation. Called once per server tick. */
    public static void tickAll(MinecraftServer server) {
        if (ANIMATING.isEmpty()) return;
        ANIMATING.entrySet().removeIf(entry -> {
            RouletteScreenHandler h = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.containerMenu != h) {
                return true;
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

    private static boolean isRed(int n) {
        for (int r : RED_NUMBERS) {
            if (r == n) return true;
        }
        return false;
    }

    private static String colorName(int n) {
        if (n == 0) return "緑 (0)";
        return isRed(n) ? "赤" : "黒";
    }

    private void spin() {
        if (betAmount <= 0 || choice < 0) return;
        if (!GuildService.payCoins(owner, betAmount)) return;

        rolled = RANDOM.nextInt(37); // 0..36
        payout = 0;
        playerWon = false;
        boolean won = switch (choice) {
            case 0 -> rolled != 0 && isRed(rolled);
            case 1 -> rolled != 0 && !isRed(rolled);
            default -> rolled == 0;
        };
        if (won) {
            playerWon = true;
            payout = betAmount * (choice == 2 ? 36 : 2);
            result = "的中！";
            GuildService.creditCoins(owner.getServer(), owner.getUUID(), payout);
        } else {
            result = "ハズレ…";
        }

        state = State.SPINNING;
        spinTicks = 0;
        ANIMATING.put(owner.getUUID(), this);
    }

    private void newRound() {
        state = State.BETTING;
        choice = -1;
        rolled = -1;
        playerWon = false;
        payout = 0;
        result = "";
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: result pocket (flickers while spinning, settles on the final number)
        switch (state) {
            case BETTING -> set(4, item(Items.COMPASS, "ルーレット", "赤・黒は2倍、ゼロは36倍！"));
            case SPINNING -> {
                int shown = RANDOM.nextInt(37);
                Item pocket = shown == 0 ? Items.LIME_CONCRETE : (isRed(shown) ? Items.RED_CONCRETE : Items.BLACK_CONCRETE);
                set(4, item(pocket, "回転中… " + shown, colorName(shown)));
            }
            case DONE -> {
                Item pocket = rolled == 0 ? Items.LIME_CONCRETE : (isRed(rolled) ? Items.RED_CONCRETE : Items.BLACK_CONCRETE);
                set(4, item(pocket, "出目: " + rolled, colorName(rolled)));
            }
        }

        // Row 1: bets
        if (state == State.BETTING) {
            setAction(11, item(choice == 0 ? Items.RED_CONCRETE : Items.RED_DYE,
                            (choice == 0 ? "● " : "") + "赤 (2倍)", "クリックで選択"),
                    (p, b, t) -> { if (b == 0) choice = 0; });
            setAction(13, item(choice == 2 ? Items.LIME_CONCRETE : Items.LIME_DYE,
                            (choice == 2 ? "● " : "") + "ゼロ (36倍)", "クリックで選択"),
                    (p, b, t) -> { if (b == 0) choice = 2; });
            setAction(15, item(choice == 1 ? Items.BLACK_CONCRETE : Items.BLACK_DYE,
                            (choice == 1 ? "● " : "") + "黒 (2倍)", "クリックで選択"),
                    (p, b, t) -> { if (b == 0) choice = 1; });
        } else {
            set(11, item(choice == 0 ? Items.RED_CONCRETE : Items.RED_DYE, "赤", choice == 0 ? "あなたの選択" : " "));
            set(13, item(choice == 2 ? Items.LIME_CONCRETE : Items.LIME_DYE, "ゼロ", choice == 2 ? "あなたの選択" : " "));
            set(15, item(choice == 1 ? Items.BLACK_CONCRETE : Items.BLACK_DYE, "黒", choice == 1 ? "あなたの選択" : " "));
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
                if (betAmount > 0 && betAmount <= balance && choice >= 0) {
                    setAction(25, item(Items.LIME_CONCRETE, "ルーレットを回す", "クリックで勝負！"),
                            (p, b, t) -> { if (b == 0) spin(); });
                } else {
                    String reason = choice < 0 ? "賭ける色を選んでください"
                            : betAmount <= 0 ? "賭け金を設定してください" : "所持コインが不足";
                    set(25, item(Items.GRAY_CONCRETE, "ルーレットを回す", reason));
                }
            }
            case SPINNING -> set(25, item(Items.GRAY_CONCRETE, "回転中…", "お待ちください"));
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
        view.putInt("number", state == State.DONE ? rolled : -1);
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
