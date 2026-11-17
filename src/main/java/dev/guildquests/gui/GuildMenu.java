package dev.guildquests.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

/** Server-owned menu state shared by both loaders' button presentation. */
public abstract class GuildMenu extends AbstractContainerMenu {
    public final GuildMenuKind kind;
    private long nextRefresh;

    protected GuildMenu(GuildMenuKind kind, int containerId) {
        super(GuildMenus.type(kind), containerId);
        this.kind = kind;
    }

    protected abstract void populate();
    public boolean setGuiAmount(long amount) { return false; }

    @Override public void broadcastChanges() {
        // Keep quest progress, balances and player availability current while the world runs.
        if (kind == GuildMenuKind.GUILD || kind == GuildMenuKind.BANK || kind == GuildMenuKind.TRANSFER
                || kind == GuildMenuKind.LOAN || kind == GuildMenuKind.AUCTION_BROWSE
                || kind == GuildMenuKind.AUCTION_MINE || kind == GuildMenuKind.DEPOSIT_SETTINGS) {
            if (!slots.isEmpty() && slots.get(0).container instanceof net.minecraft.world.SimpleContainer) {
                long now = System.currentTimeMillis();
                if (now >= nextRefresh) { nextRefresh = now + 1000; populate(); }
            }
        }
        super.broadcastChanges();
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        // These inventories are UI snapshots. Hidden inventory slots must never move real items.
    }

    @Override public boolean clickMenuButton(Player player, int button) {
        if (button != 0 || !(player instanceof ServerPlayer serverPlayer)
                || serverPlayer.containerMenu != this || !stillValid(player)) return false;
        switch (kind) {
            case GUILD -> { return false; }
            case BLACKJACK, CHINCHIRORIN, COIN_FLIP, ROULETTE, SLOT, HORSE_RACE -> GambleScreenHandler.open(serverPlayer);
            case LOAN -> BankScreenHandler.open(serverPlayer);
            case AUCTION_SELL, AUCTION_MINE -> AuctionBrowseScreenHandler.open(serverPlayer);
            default -> GuildScreenHandler.openQuests(serverPlayer);
        }
        return true;
    }
}
