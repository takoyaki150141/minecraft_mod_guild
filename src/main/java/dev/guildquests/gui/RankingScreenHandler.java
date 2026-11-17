package dev.guildquests.gui;

import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide ranking of guild members by coin balance or quest completions.
 * Entries for offline players use the persisted playerName.
 */
public final class RankingScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private static final int TOP_N = 8;

    private enum Mode { COINS, QUESTS }

    private record Entry(UUID id, String name, long coins, int completed) {}

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private Mode mode = Mode.COINS;

    public RankingScreenHandler(int syncId, Container playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.RANKING, syncId);
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
                (syncId, playerInventory, ignored) -> new RankingScreenHandler(syncId, playerInventory, player),
                Component.literal("ギルドランキング")
        ));
    }

    /** Builds the sorted top-N entries. Skips players with zero activity on both metrics. */
    private List<Entry> topEntries() {
        MinecraftServer server = owner.getServer();
        GuildPersistentState state = GuildPersistentState.get(server);
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<UUID, PlayerGuildData> e : state.players().entrySet()) {
            PlayerGuildData d = e.getValue();
            if (d.coins <= 0 && d.completedTotal <= 0) continue;
            String name = resolveName(server, e.getKey(), d);
            list.add(new Entry(e.getKey(), name, d.coins, d.completedTotal));
        }
        Comparator<Entry> byCoins = Comparator.comparingLong(Entry::coins).reversed()
                .thenComparing(Comparator.comparingInt(Entry::completed).reversed());
        Comparator<Entry> byQuests = Comparator.comparingInt(Entry::completed).reversed()
                .thenComparing(Comparator.comparingLong(Entry::coins).reversed());
        list.sort(mode == Mode.COINS ? byCoins : byQuests);
        if (list.size() > TOP_N) list = list.subList(0, TOP_N);
        return list;
    }

    private String resolveName(MinecraftServer server, UUID id, PlayerGuildData data) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (!data.playerName.isBlank()) return data.playerName;
        return id.toString().substring(0, 8);
    }

    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        List<Entry> entries = topEntries();
        UUID selfId = owner.getUUID();

        // Header.
        set(4, item(Items.NETHER_STAR, "ギルドランキング",
                mode == Mode.COINS ? "ソート: 所持コイン" : "ソート: クエスト達成数",
                "上位 " + TOP_N + " 名を表示"));

        // Toggle button.
        setAction(8, item(mode == Mode.COINS ? Items.GOLD_INGOT : Items.WRITABLE_BOOK,
                        mode == Mode.COINS ? "ソート切替 → クエスト" : "ソート切替 → コイン",
                        "クリックで並び替え"),
                (p, b, t) -> { if (b == 0) mode = mode == Mode.COINS ? Mode.QUESTS : Mode.COINS; });

        // Ranking rows (slots 9-16 = top 8).
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean self = e.id().equals(selfId);
            String rankLabel = switch (i) {
                case 0 -> "1位 ★";
                case 1 -> "2位";
                case 2 -> "3位";
                default -> (i + 1) + "位";
            };
            String name = (self ? "▶ " : "") + rankLabel + " " + e.name();
            PlayerGuildData temp = new PlayerGuildData();
            temp.completedTotal = e.completed();
            set(9 + i, GuildItems.playerHead(owner.getServer(), e.id(), e.name(), name,
                    "所持コイン: " + Numbers.format(e.coins),
                    "クエスト達成: " + Numbers.format(e.completed) + " 件",
                    "ランク: " + GuildService.rank(temp)));
        }

        // Personal stats.
        PlayerGuildData selfData = GuildService.data(owner);
        set(17, item(Items.COMPASS, "自分: " + GuildService.rank(selfData),
                "所持コイン: " + Numbers.format(selfData.coins),
                "クエスト達成: " + Numbers.format(selfData.completedTotal) + " 件"));

        setAction(26, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeContainer());
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
