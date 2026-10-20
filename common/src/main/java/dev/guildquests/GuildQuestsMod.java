package dev.guildquests;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.gui.BankScreenHandler;
import dev.guildquests.gui.GuildScreenHandler;
import dev.guildquests.item.ModItems;
import dev.guildquests.platform.ContentRegistrar;
import dev.guildquests.platform.Platform;
import dev.guildquests.service.BossEvent;
import dev.guildquests.service.GuildService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuildQuestsMod {
    public static final String MOD_ID = "guildquests";
    public static final String VERSION = "26.13.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static int exploreTicker = 0;
    private static int riboTicker = 0;
    private static int auctionTicker = 0;

    private GuildQuestsMod() {
    }

    /**
     * Registers this mod's content (blocks, items, block entities, creative tab).
     * <p>
     * Fabric calls this once from its initializer; Forge instead calls the individual
     * {@code register*} methods from per-registry {@code RegisterEvent} handlers,
     * because its vanilla registries are locked by the time mods construct.
     */
    public static void registerContent(ContentRegistrar registrar) {
        ModBlocks.registerBlocks(registrar);
        ModBlocks.registerBlockItems(registrar);
        ModBlocks.registerBlockEntityTypes(registrar);
        ModItems.registerItems(registrar);
        ModItems.registerCreativeTab(registrar);
    }

    public static void init() {
        ConfigManager.loadAll();
        dev.guildquests.update.ServerUpdateService.register();
        dev.guildquests.fx.FxService.init();
        BossEvent.init();

        Platform.onOpenGuildRequest(GuildScreenHandler::openQuests);

        // The player-published quest (bounty board) system was removed; clean up old saves.
        Platform.onServerStarted(server -> GuildService.purgePublishedQuests(server));

        // Clean up old JARs from a previous auto-update at mod load (no world join needed).
        int cleaned = dev.guildquests.update.UpdateChecker.cleanupOldJars(Platform.getModsDir(), VERSION);
        if (cleaned > 0) {
            LOGGER.info("Cleaned up {} old guildquests JAR(s) after update.", cleaned);
        }

        // Re-schedule a pending update swap if the game was killed before the post-exit swap ran.
        dev.guildquests.update.UpdateChecker.recoverPendingSwap(Platform.getModsDir(), Platform.getLoaderName(), VERSION);

        Platform.onServerStopped(server -> {
            dev.guildquests.gui.HorseRaceScreenHandler.clearAll();
            dev.guildquests.gui.SlotScreenHandler.clearAll();
            dev.guildquests.gui.RouletteScreenHandler.clearAll();
            dev.guildquests.gui.CoinFlipScreenHandler.clearAll();
        });

        Platform.onPlayerKill(GuildService::onKill);

        // Mining-based loan repayment (only while a consumer-finance loan is owed).
        Platform.onBlockBreak(GuildService::onBlockBreak);

        Platform.onServerTick(server -> {
            GuildService.ensureDailyBoard(server);
            dev.guildquests.gui.HorseRaceScreenHandler.tickAll(server);
            dev.guildquests.gui.SlotScreenHandler.tickAll(server);
            dev.guildquests.gui.RouletteScreenHandler.tickAll(server);
            dev.guildquests.gui.CoinFlipScreenHandler.tickAll(server);

            int interval = ConfigManager.config().exploreCheckIntervalTicks;
            exploreTicker++;
            if (exploreTicker >= interval) {
                exploreTicker = 0;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    GuildService.checkExplore(player);
                }
            }

            riboTicker++;
            if (riboTicker >= 100) {
                riboTicker = 0;
                GuildService.runRiboCycles(server);
                GuildService.runLoanInterest(server);
            }

            auctionTicker++;
            if (auctionTicker >= 100) {
                auctionTicker = 0;
                dev.guildquests.service.AuctionService.tick(server);
            }
        });

        Platform.onCommands((dispatcher, registryAccess) -> {
            dispatcher.register(Commands.literal("guild")
                    .executes(context -> {
                        GuildScreenHandler.openQuests(context.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("status")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                PlayerGuildData data = GuildService.data(player);
                                player.sendSystemMessage(Component.literal("[Guild] ").withStyle(ChatFormatting.GOLD)
                                        .append(Component.literal("Rank " + GuildService.rank(data)
                                                + " | Coins " + data.coins
                                                + " | Total " + data.completedTotal
                                                + " | Pay " + GuildService.paymentModeLabel(GuildService.paymentMode(data))
                                                + (data.debt > 0 ? " | Debt " + data.debt + (data.delinquent ? "（延滞中・ロック）" : "") : "")
                                        ).withStyle(ChatFormatting.YELLOW)));
                                return 1;
                            }))
                    .then(Commands.literal("bank")
                            .executes(context -> {
                                BankScreenHandler.open(context.getSource().getPlayerOrException());
                                return 1;
                            }))
                    .then(Commands.literal("auction")
                            .executes(context -> {
                                dev.guildquests.gui.AuctionBrowseScreenHandler.open(context.getSource().getPlayerOrException());
                                return 1;
                            })));

            var guildAdmin = Commands.literal("guildadmin")
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> { dev.guildquests.gui.AdminScreenHandler.open(context.getSource().getPlayerOrException()); return 1; });

            guildAdmin.then(Commands.literal("reload")
                    .executes(context -> {
                        ConfigManager.loadAll();
                        context.getSource().sendSuccess(() -> Component.literal("GuildQuests configuration reloaded."), true);
                        return 1;
                    }));

            guildAdmin.then(Commands.literal("boss")
                    .then(Commands.literal("stop").executes(context -> {
                        boolean stopped = BossEvent.stopNow(context.getSource().getServer());
                        context.getSource().sendSuccess(() -> Component.literal(stopped
                                ? "読み込み済みの緊急ボスをすべて報酬なしで終了しました。" : "終了できる緊急ボスはいません。"), true);
                        return stopped ? 1 : 0;
                    }))
                    .executes(context -> spawnBosses(context.getSource(), 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, BossEvent.MAX_ACTIVE))
                            .executes(context -> spawnBosses(context.getSource(),
                                    IntegerArgumentType.getInteger(context, "count")))));

            var coins = Commands.literal("coins");
            coins.then(Commands.literal("set")
                    .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                    .executes(context -> {
                                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                        long amount = LongArgumentType.getLong(context, "amount");
                                        GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                        PlayerGuildData data = state.get(target.getUUID());
                                        data.coins = amount;
                                        state.setDirty();
                                        context.getSource().sendSuccess(() -> Component.literal("Set " + target.getName().getString() + " coins to " + amount), true);
                                        return 1;
                                    }))));
            coins.then(Commands.literal("add")
                    .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                    .executes(context -> {
                                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                        long amount = LongArgumentType.getLong(context, "amount");
                                        GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                        PlayerGuildData data = state.get(target.getUUID());
                                        data.coins = data.coins > Long.MAX_VALUE - amount ? Long.MAX_VALUE : data.coins + amount;
                                        state.setDirty();
                                        context.getSource().sendSuccess(() -> Component.literal("Added " + amount + " coins to " + target.getName().getString()), true);
                                        return 1;
                                    }))));
            guildAdmin.then(coins);

            // Consumer-finance loan admin commands.
            var loan = Commands.literal("loan");
            loan.then(Commands.literal("add")
                    .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                    .executes(context -> {
                                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                        long amount = LongArgumentType.getLong(context, "amount");
                                        GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                        PlayerGuildData data = state.get(target.getUUID());
                                        dev.guildquests.service.AdminLoanPolicy.add(data, amount, System.currentTimeMillis() + ConfigManager.config().consumerLoanIntervalMinutes * 60_000L);
                                        state.setDirty();
                                        context.getSource().sendSuccess(() -> Component.literal(
                                                "Gave " + target.getName().getString() + " a loan of " + amount
                                                        + "（現在の債務: " + data.loan + "）"), true);
                                        return 1;
                                    }))));
            loan.then(Commands.literal("set")
                    .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                    .executes(context -> {
                                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                        long amount = LongArgumentType.getLong(context, "amount");
                                        GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                        PlayerGuildData data = state.get(target.getUUID());
                                        dev.guildquests.service.AdminLoanPolicy.set(data, amount, System.currentTimeMillis() + ConfigManager.config().consumerLoanIntervalMinutes * 60_000L);
                                        state.setDirty();
                                        context.getSource().sendSuccess(() -> Component.literal(
                                                "Set " + target.getName().getString() + " loan to " + data.loan), true);
                                        return 1;
                                    }))));
            loan.then(Commands.literal("clear")
                    .then(Commands.argument("player", EntityArgument.player())
                            .executes(context -> {
                                ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                PlayerGuildData data = state.get(target.getUUID());
                                dev.guildquests.service.AdminLoanPolicy.clear(data);
                                state.setDirty();
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Wrote off " + target.getName().getString() + "'s loan（帳消し）"), true);
                                return 1;
                            })));
            guildAdmin.then(loan);

            // Delinquency (滞納) toggle.
            guildAdmin.then(Commands.literal("delinquent")
                    .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.literal("on").executes(context -> {
                                ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                PlayerGuildData data = state.get(target.getUUID());
                                dev.guildquests.service.AdminLoanPolicy.delinquent(data, true);
                                state.setDirty();
                                context.getSource().sendSuccess(() -> Component.literal(
                                        target.getName().getString() + " を滞納状態（ロック）にしました。"), true);
                                return 1;
                            }))
                            .then(Commands.literal("off").executes(context -> {
                                ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                PlayerGuildData data = state.get(target.getUUID());
                                dev.guildquests.service.AdminLoanPolicy.delinquent(data, false);
                                state.setDirty();
                                context.getSource().sendSuccess(() -> Component.literal(
                                        target.getName().getString() + " の滞納状態を解除しました。"), true);
                                return 1;
                            }))));

            dispatcher.register(guildAdmin);
        });

        LOGGER.info("Guild Quests & Economy initialized (multi-loader 1.20.1)");
    }

    /** `/guildadmin boss [count]`: spawns bosses immediately, skipping the chance roll. */
    private static int spawnBosses(CommandSourceStack source, int count) {
        int spawned = BossEvent.spawn(source.getServer(), count);
        String message;
        if (spawned == 0) {
            message = "Boss spawn failed: no eligible overworld players or the active boss limit ("
                    + BossEvent.MAX_ACTIVE + ") was reached.";
        } else if (spawned < count) {
            message = "Spawned " + spawned + " / " + count + " emergency bosses (active limit "
                    + BossEvent.MAX_ACTIVE + ").";
        } else if (spawned == 1) {
            message = "Emergency boss spawned (chance roll skipped).";
        } else {
            message = "Spawned " + spawned + " emergency bosses (chance roll skipped).";
        }
        source.sendSuccess(() -> Component.literal(message), false);
        return spawned;
    }
}
