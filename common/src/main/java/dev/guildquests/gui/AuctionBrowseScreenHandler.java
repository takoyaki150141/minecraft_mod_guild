package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.AuctionListing;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.AuctionService;
import dev.guildquests.service.GuildService;
import dev.guildquests.util.Numbers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global auction market. Buyout listings are purchased with a left click;
 * auction listings are bid on using the digit-head amount in the bottom row.
 */
public final class AuctionBrowseScreenHandler extends GuildMenu {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayer player, int button, ClickType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    private static final int PAGE_SIZE = 36;
    private static final int FIRST_ITEM_SLOT = 9;
    private static final int MAX_BID = 9_999_999;

    private final SimpleContainer display = new SimpleContainer(DISPLAY_SLOTS);
    private final ServerPlayer owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    private int page;
    private long bidAmount;

    public AuctionBrowseScreenHandler(int syncId, Inventory playerInventory, ServerPlayer owner) {
        super(GuildMenuKind.AUCTION_BROWSE, syncId);
        this.owner = owner;

        for (int row = 0; row < 6; row++) {
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
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 198));
        }

        populate();
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new AuctionBrowseScreenHandler(syncId, playerInventory, player),
                Component.literal("ギルドオークション")
        ));
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && !owner.isRemoved();
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS && player instanceof ServerPlayer serverPlayer) {
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
        super.clicked(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override public boolean setGuiAmount(long value) {
        if (value < 0 || value > MAX_BID) return false;
        bidAmount = value;
        populate();
        return true;
    }
    @Override protected void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, ItemStack.EMPTY);
        fillBackground();

        PlayerGuildData data = GuildService.data(owner);
        List<AuctionListing> all = AuctionService.listings(owner.getServer());

        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;

        set(0, GuildItems.item(Items.GOLD_INGOT, "残高: " + Numbers.format(data.coins) + " コイン",
                "所持コイン: " + Numbers.format(GuildService.countHeldCoins(owner)),
                "購入・入札は銀行残高から"));
        setAction(1, GuildItems.item(Items.EMERALD, "出品する",
                        "手持ちのアイテムを売りに出す",
                        "固定価格 / オークションを選択"),
                (p, b, t) -> AuctionSellScreenHandler.open(p));
        setAction(2, GuildItems.item(Items.CHEST, "自分の出品・受取ボックス",
                        "出品の取消や、落札品の受け取り"),
                (p, b, t) -> AuctionMineScreenHandler.open(p));
        setAction(3, GuildItems.item(Items.CLOCK, "更新",
                        "最新の出品状況を表示"),
                (p, b, t) -> { });

        set(4, GuildItems.item(Items.PAPER, "ページ " + (page + 1) + " / " + pages,
                "出品数: " + Numbers.format(all.size()) + " 件",
                "手数料: " + feeText() + "%"));

        if (page > 0) {
            setAction(5, GuildItems.item(Items.ARROW, "前のページ"), (p, b, t) -> {
                if (b == 0) page--;
            });
        } else {
            set(5, GuildItems.item(Items.GRAY_DYE, "前のページ", "最初のページです"));
        }
        if (page < pages - 1) {
            setAction(6, GuildItems.item(Items.ARROW, "次のページ"), (p, b, t) -> {
                if (b == 0) page++;
            });
        } else {
            set(6, GuildItems.item(Items.GRAY_DYE, "次のページ", "最後のページです"));
        }

        set(7, GuildItems.item(Items.KNOWLEDGE_BOOK, "使い方",
                "固定価格: 左クリックで即購入",
                "オークション: 下段で入札額を決めて左クリック",
                "落札・期限切れの品は受取ボックスへ"));
        setAction(8, GuildItems.item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeContainer());

        // Listings (rows 1-4).
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = page * PAGE_SIZE + i;
            if (idx >= all.size()) break;
            renderListing(FIRST_ITEM_SLOT + i, all.get(idx));
        }

        // Row 5: bid amount controls.
        int[] places = {1000, 100, 10, 1};
        String[] labels = {"千の位", "百の位", "十の位", "一の位"};
        for (int i = 0; i < 4; i++) {
            int slot = 45 + i;
            int place = places[i];
            int digit = (int) ((bidAmount / place) % 10);
            setAction(slot, GuildItems.digitHead(digit, labels[i]), (p, b, t) -> {
                if (b != 0 && b != 1) return;
                long cur = (bidAmount / place) % 10;
                long next = Math.floorMod(cur + (b == 0 ? 1 : -1), 10);
                bidAmount = Math.min(MAX_BID, Math.max(0, bidAmount - cur * place + next * place));
            });
        }
        set(49, GuildItems.item(ModItems.COIN, "入札額: " + Numbers.format(bidAmount),
                "オークション出品に入札する金額",
                "現在の最高額より高い額を指定"));
        addPaper(50, 1);
        addPaper(51, 10);
        addPaper(52, 100);
        setAction(53, GuildItems.item(Items.BARRIER, "入札額クリア"), (p, b, t) -> {
            if (b == 0) bidAmount = 0;
        });
    }

    private void renderListing(int slot, AuctionListing listing) {
        boolean mine = owner.getUUID().equals(listing.sellerId);
        String label = listing.item.getHoverName().getString() + " ×" + listing.item.getCount();
        String remaining = AuctionService.formatRemaining(listing.expireAt);

        String[] lore;
        if (listing.isAuction()) {
            long min = listing.minNextBid(ConfigManager.config().auctionBidStep);
            if (listing.hasBid()) {
                lore = new String[]{
                        "出品者: " + listing.sellerName,
                        "形式: オークション",
                        "現在の最高入札: " + Numbers.format(listing.highestBid) + "（" + listing.highestBidderName + "）",
                        "最低入札額: " + Numbers.format(min),
                        "残り時間: " + remaining,
                        mine ? "自分の出品です" : "左クリック: " + Numbers.format(bidAmount) + " コインで入札"
                };
            } else {
                lore = new String[]{
                        "出品者: " + listing.sellerName,
                        "形式: オークション",
                        "開始価格: " + Numbers.format(listing.price),
                        "最低入札額: " + Numbers.format(min),
                        "残り時間: " + remaining,
                        mine ? "自分の出品です" : "左クリック: " + Numbers.format(bidAmount) + " コインで入札"
                };
            }
        } else {
            lore = new String[]{
                    "出品者: " + listing.sellerName,
                    "形式: 固定価格",
                    "価格: " + Numbers.format(listing.price) + " コイン",
                    "残り時間: " + remaining,
                    mine ? "自分の出品です" : "左クリック: 購入"
            };
        }

        long id = listing.id;
        ItemStack icon = GuildItems.decorateCopy(listing.item, ChatFormatting.WHITE, label, lore);
        if (mine) {
            set(slot, icon);
            return;
        }
        setAction(slot, icon, (p, b, t) -> {
            if (b == 0) AuctionService.interact(p, id, bidAmount);
        });
    }

    private void addPaper(int slot, int amount) {
        long preview = Math.min(MAX_BID, bidAmount + amount);
        setAction(slot, GuildItems.item(Items.PAPER, "＋" + amount,
                        "クリックで " + amount + " 加算",
                        "現在: " + Numbers.format(bidAmount) + " → " + Numbers.format(preview)),
                (p, b, t) -> {
                    if (b == 0) bidAmount = Math.min(MAX_BID, bidAmount + amount);
                });
    }

    private String feeText() {
        double pct = ConfigManager.config().auctionFeePercent;
        if (pct == Math.floor(pct)) return String.valueOf((long) pct);
        return String.valueOf(pct);
    }

    private void fillBackground() {
        ItemStack pane = GuildItems.item(Items.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setItem(i, pane.copy());
    }

    private void set(int slot, ItemStack stack) {
        if (slot == 49) GuildItems.amount(stack, bidAmount, MAX_BID, "入札額");
        display.setItem(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, GuildItems.action(stack));
        actions.put(slot, action);
    }
}
