package dev.guildquests.client;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.platform.Platform;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.update.UpdateChecker;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

public final class GuildQuestsClient {
    private static volatile UpdateChecker.UpdateInfo pendingUpdate;
    private static volatile boolean fallbackScreenPending;
    private static boolean updateScreenShown = false;

    private GuildQuestsClient() {
    }

    public static void init() {
        GuildQuestsMod.LOGGER.info("[Client] GuildQuestsClient.init() called");

        PlatformClient.registerKeyBinding("key.guildquests.openguild", GLFW.GLFW_KEY_G,
                PlatformClient::sendOpenGuild);

        BossPinRenderer.init();
        PlatformClient.onBossPin(BossPinRenderer::set);
        PlatformClient.onDisconnect(BossPinRenderer::clear);

        // Check for a newer version before the game starts. When one exists the standalone
        // updater window is shown first and the game waits for 「無視して起動」or「更新して再起動」.
        String currentVer = GuildQuestsMod.VERSION;
        String loader = Platform.getLoaderName();
        GuildQuestsMod.LOGGER.info("[Client] Starting update check: current=v{}, loader={}", currentVer, loader);
        if (!Boolean.getBoolean("guildquests.disableUpdateCheck")) {
            UpdateChecker.UpdateInfo info = checkBeforeStart(currentVer, loader, 6000);
            if (info != null) {
                pendingUpdate = info;
                GuildQuestsMod.LOGGER.info("[Client] Update available: v{} (current: v{})", info.latestVersion(), currentVer);
                if (!UpdateWindowLauncher.offer(currentVer, loader, info)) {
                    // Dev runs and unusual installs fall back to the in-game screen.
                    fallbackScreenPending = true;
                }
            } else {
                GuildQuestsMod.LOGGER.info("[Client] No update found or check timed out");
            }
        }

        // Only used when the standalone window could not be started.
        PlatformClient.onClientTick(screen -> {
            if (!fallbackScreenPending || updateScreenShown) return;
            if (screen instanceof TitleScreen) {
                UpdateChecker.UpdateInfo info = pendingUpdate;
                if (info != null) {
                    updateScreenShown = true;
                    GuildQuestsMod.LOGGER.info("[Client] Showing the in-game update screen for v{}", info.latestVersion());
                    net.minecraft.client.Minecraft.getInstance().setScreen(new UpdateScreen(screen, currentVer, info));
                }
            }
        });
        GuildQuestsMod.LOGGER.info("[Client] GuildQuestsClient.init() done");
    }

    /** Runs the GitHub check on a worker thread so a slow network cannot stall startup for long. */
    private static UpdateChecker.UpdateInfo checkBeforeStart(String currentVersion, String loader, long timeoutMillis) {
        java.util.concurrent.CompletableFuture<UpdateChecker.UpdateInfo> result =
                new java.util.concurrent.CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(UpdateChecker.check(currentVersion, loader));
            } catch (Throwable e) {
                result.complete(null);
            }
        }, "GuildQuests-UpdateCheck");
        thread.setDaemon(true);
        thread.start();
        try {
            return result.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Content registries must be populated before referencing the guild blocks. */
    public static void initRenderLayers() {
        PlatformClient.onBlockRenderCutout(ModBlocks.GUILD_BANK);
    }
}
