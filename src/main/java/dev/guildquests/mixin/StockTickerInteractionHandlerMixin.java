package dev.guildquests.mixin;

import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import dev.guildquests.GuildQuestsMod;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(value = StockTickerInteractionHandler.class)
public class StockTickerInteractionHandlerMixin {
    private static Player guildquests$buyer;
    private static long guildquests$pendingCharge;
    private static boolean guildquests$pendingRibo;
    private static int guildquests$fullPrice;
    private static boolean guildquests$allCoin;

    @Inject(method = "interactWithShop", at = @At("HEAD"))
    private static void guildquests$onInteractWithShop(Player player, Level world, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (world.isClientSide) return;
        guildquests$buyer = player;
        guildquests$pendingCharge = 0;
        guildquests$pendingRibo = false;
        guildquests$fullPrice = 0;
        guildquests$allCoin = false;
    }

    @Redirect(
            method = "interactWithShop",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/logistics/packager/InventorySummary;copy()Lcom/simibubi/create/content/logistics/packager/InventorySummary;")
    )
    private static InventorySummary guildquests$capturePrice(InventorySummary summary) {
        guildquests$fullPrice = summary.getTotalCount();
        guildquests$allCoin = guildquests$fullPrice > 0
                && summary.getCountOf(new ItemStack(ModItems.COIN)) == guildquests$fullPrice;
        return summary.copy();
    }

    @Redirect(
            method = "interactWithShop",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/logistics/packager/InventorySummary;getTotalCount()I")
    )
    private static int guildquests$coverShortfallFromBalance(InventorySummary tally) {
        int shortfall = tally.getTotalCount();
        if (!(guildquests$buyer instanceof ServerPlayer buyer)) return shortfall;
        if (!ConfigManager.config().createIntegrationEnabled) return shortfall;
        int price = guildquests$fullPrice;
        if (price <= 0 || !guildquests$allCoin) return shortfall;

        PlayerGuildData data = GuildService.data(buyer);
        if (data.delinquent) {
            GuildService.riboLockMessage(buyer, data);
            return shortfall;
        }
        String mode = GuildService.paymentMode(data);
        if (GuildService.MODE_DISABLED.equals(mode)) {
            GuildService.message(buyer, "支払い方法が「無効」のため、残高からは支払えません。手持ちのコインで支払ってください。", ChatFormatting.YELLOW);
            return shortfall;
        }
        if (GuildService.MODE_RIBO.equals(mode)) {
            if (!guildquests$injectCoins(buyer, price)) {
                GuildService.message(buyer, "インベントリにコインを置けないため購入できませんでした（空きスロットを作ってください）。", ChatFormatting.RED);
                return shortfall;
            }
            guildquests$pendingCharge = price;
            guildquests$pendingRibo = true;
            return 0;
        }

        long balance = data.coins;
        if (balance < price) {
            if (shortfall <= 0) return 0;
            GuildService.message(buyer, "ギルドコイン残高が不足しています。必要: " + price + " / 残高: " + balance, ChatFormatting.RED);
            return shortfall;
        }
        if (!guildquests$injectCoins(buyer, price)) {
            if (shortfall <= 0) return 0;
            GuildService.message(buyer, "インベントリにコインを置けないため残高からの支払いができませんでした（空きスロットを作ってください）。", ChatFormatting.RED);
            return shortfall;
        }
        guildquests$pendingCharge = price;
        return 0;
    }

    @Inject(
            method = "interactWithShop",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/logistics/stockTicker/StockTickerBlockEntity;broadcastPackageRequest(Lcom/simibubi/create/content/logistics/packagerLink/LogisticallyLinkedBehaviour$RequestType;Lcom/simibubi/create/content/logistics/stockTicker/PackageOrder;Lcom/simibubi/create/content/logistics/packager/IdentifiedInventory;Ljava/lang/String;)Z"
            )
    )
    private static void guildquests$chargeBalance(Player player, Level world, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        long charge = guildquests$pendingCharge;
        boolean ribo = guildquests$pendingRibo;
        guildquests$pendingCharge = 0;
        guildquests$pendingRibo = false;
        if (charge <= 0) return;
        if (world.isClientSide) return;
        if (!(player instanceof ServerPlayer buyer)) return;

        if (ribo) {
            GuildService.addDebt(buyer, charge);
            long debt = GuildService.data(buyer).debt;
            GuildService.message(buyer, "リボ払いで購入しました（信用: " + charge
                    + " コイン）。総債務: " + debt + " コイン", ChatFormatting.LIGHT_PURPLE);
            return;
        }

        if (!GuildService.payCoins(buyer, charge)) {
            GuildService.addDebt(buyer, charge);
            GuildQuestsMod.LOGGER.error("Guild coin charge of {} failed for {}; recorded as debt instead",
                    charge, buyer.getName().getString());
            GuildService.message(buyer, "残高からの引き落としに失敗したため " + charge + " コインを債務として計上しました。", ChatFormatting.RED);
            return;
        }
        GuildService.message(buyer, "支払い完了: " + charge + " コインを残高から引き落としました。", ChatFormatting.AQUA);
    }

    private static boolean guildquests$injectCoins(ServerPlayer buyer, int amount) {
        if (amount <= 0) return true;
        Inventory inv = buyer.getInventory();
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> previous = new ArrayList<>();
        int left = amount;

        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.is(ModItems.COIN)) continue;
            int space = stack.getMaxStackSize() - stack.getCount();
            if (space <= 0) continue;
            int add = Math.min(space, left);
            slots.add(i);
            previous.add(stack.copy());
            stack.grow(add);
            left -= add;
        }

        int maxCount = new ItemStack(ModItems.COIN).getMaxStackSize();
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            if (!inv.getItem(i).isEmpty()) continue;
            int add = Math.min(maxCount, left);
            slots.add(i);
            previous.add(ItemStack.EMPTY);
            inv.setItem(i, new ItemStack(ModItems.COIN, add));
            left -= add;
        }

        if (left > 0) {
            for (int i = 0; i < slots.size(); i++) {
                inv.setItem(slots.get(i), previous.get(i));
            }
            inv.setChanged();
            return false;
        }
        inv.setChanged();
        return true;
    }
}
