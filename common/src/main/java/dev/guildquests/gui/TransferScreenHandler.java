package dev.guildquests.gui;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TransferScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    /** Currently selected recipient; null = unselected. */
    private UUID transferTargetId;
    private int recipientPage;
    /** Amount to send, 0..9999, set via the client keypad. */
    private long transferAmount;
    /** Sender's bank balance, published to the keypad as the spendable wallet. */
    private long walletBalance;

    public TransferScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.TRANSFER, syncId);
        this.owner = owner;

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
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new TransferScreenHandler(syncId, playerInventory, player),
                Component.literal("ギルド送金")
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
        if (value < 0 || value > 9999) return false;
        transferAmount = value;
        populate();
        return true;
    }
    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        long balance = data.coins;
        walletBalance = balance;
        if (transferAmount > 9999) transferAmount = 9999;
        ServerPlayer target = currentTransferTarget();
        List<ServerPlayer> candidates = transferCandidates();
        int recipientPages = Math.max(1, (candidates.size() + 11) / 12);
        recipientPage = Math.min(recipientPage, recipientPages - 1);

        // Columns 0-3 (slots 0-3, 9-12, 18-21): player heads for recipient selection.
        for (int i = 0; i < 12; i++) {
            int slot = headSlot(i);
            int candidateIndex = recipientPage * 12 + i;
            if (candidateIndex < candidates.size()) {
                ServerPlayer candidate = candidates.get(candidateIndex);
                boolean selected = target != null && target.getUUID().equals(candidate.getUUID());
                String name = candidate.getName().getString();
                UUID id = candidate.getUUID();
                setAction(slot, GuildItems.playerHead(owner.getServer(), id, name, (selected ? "● " : "") + name,
                                selected ? "現在の送金先" : "クリックで送金先に選択",
                                "残高: " + Numbers.format(GuildService.data(candidate).coins)),
                        (p, b, t) -> {
                            if (b == 0) transferTargetId = id;
                        });
            } else {
                set(slot, item(Items.PLAYER_HEAD, " "));
            }
        }
        if (candidates.isEmpty()) {
            set(0, item(Items.PLAYER_HEAD, "オンラインの他プレイヤーがいません",
                    "送金先は同じサーバーの",
                    "オンラインプレイヤーのみ"));
        }

        if (recipientPage > 0) setAction(23, item(Items.ARROW, "前の送金先ページ"),
                (p, b, t) -> { if (b == 0) recipientPage--; });
        if (recipientPage + 1 < recipientPages) setAction(24, item(Items.ARROW, "次の送金先ページ"),
                (p, b, t) -> { if (b == 0) recipientPage++; });

        // Column 4 (slots 4, 13, 22): center info / close.
        set(4, item(ModItems.COIN, "所持コイン: " + Numbers.format(GuildService.countHeldCoins(owner)),
                "銀行残高: " + Numbers.format(balance),
                "送金は銀行残高から引き落とし",
                "手数料は無料"));
        set(13, item(Items.PAPER, "送金予定額: " + Numbers.format(transferAmount),
                target == null ? "送金先: 未選択（左の頭を選択）" : "送金先: " + target.getName().getString(),
                "金額は右の紙・数字頭で調整",
                "最大 " + Numbers.format(9999) + " まで"));

        // Confirm button at bottom-right (slot 26).
        if (target == null) {
            setAction(26, item(Items.LIME_CONCRETE, "送金決定",
                            "送金先が選ばれていません",
                            "左のプレイヤー頭から選択してください"),
                    (p, b, t) -> { });
        } else if (transferAmount <= 0) {
            setAction(26, item(Items.LIME_CONCRETE, "送金決定",
                            "金額が 0 です",
                            "紙ボタンまたは数字頭で金額を設定"),
                    (p, b, t) -> { });
        } else if (balance < transferAmount) {
            setAction(26, item(Items.LIME_CONCRETE, "送金決定",
                            "銀行残高が不足しています（残高: " + Numbers.format(balance) + "）",
                            "送金先: " + target.getName().getString()),
                    (p, b, t) -> { });
        } else {
            String name = target.getName().getString();
            UUID id = target.getUUID();
            long amount = transferAmount;
            setAction(26, item(Items.LIME_CONCRETE, "送金決定: " + Numbers.format(amount),
                            "クリックで " + name + " へ送金",
                            "残高 -" + Numbers.format(amount) + " → " + name + " +" + Numbers.format(amount),
                            "手数料: 無料"),
                    (p, b, t) -> {
                        if (b == 0) send(p, id, amount);
                    });
        }

        // Row 2, column 4: close.
        setAction(22, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeContainer());
    }

    /** Map a 0..11 head index to its display slot (columns 0-3, all three rows). */
    private static int headSlot(int index) {
        int row = index / 4;
        int col = index % 4;
        return col + row * 9;
    }

    /** Online players other than the owner, sorted by name. */
    private List<ServerPlayer> transferCandidates() {
        List<ServerPlayer> list = new ArrayList<>();
        for (ServerPlayer p : owner.getServer().getPlayerList().getPlayers()) {
            if (p != owner && !p.isRemoved()) list.add(p);
        }
        list.sort(Comparator.comparing(p -> p.getName().getString()));
        return list;
    }

    private ServerPlayer findPlayer(UUID id) {
        if (id == null) return null;
        for (ServerPlayer p : transferCandidates()) {
            if (p.getUUID().equals(id)) return p;
        }
        return null;
    }

    private ServerPlayer currentTransferTarget() {
        return findPlayer(transferTargetId);
    }

    /** Resolve the recipient at click time so a logged-off player is never used. */
    private void send(ServerPlayer sender, UUID targetId, long amount) {
        ServerPlayer target = findPlayer(targetId);
        if (target == null) {
            sender.sendSystemMessage(Component.literal("送金先がオンラインではありません").withStyle(ChatFormatting.RED), false);
            return;
        }
        GuildService.transferCoins(sender, target, amount);
    }

    private void set(int slot, ItemStack stack) {
        // Publish the keypad payload only after a recipient is chosen, so no amount row
        // (and no keypad) appears while the target is still unselected.
        if (slot == 13 && transferTargetId != null) GuildItems.amount(stack, transferAmount, 9999, walletBalance, "送金額");
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
