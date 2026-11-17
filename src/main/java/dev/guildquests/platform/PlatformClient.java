package dev.guildquests.platform;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Loader-neutral client-side hooks (keybinding, S2C packets, render layer, world render).
 * The concrete {@link ClientBackend} is bound by each loader's client entrypoint before
 * {@code GuildQuestsClient.init()} runs.
 */
public final class PlatformClient {
    private static ClientBackend backend;

    private PlatformClient() {
    }

    public static void bind(ClientBackend implementation) {
        backend = implementation;
    }

    private static ClientBackend b() {
        if (backend == null) {
            throw new IllegalStateException("GuildQuests client backend was not bound");
        }
        return backend;
    }

    public static void onBlockRenderCutout(Block block) {
        b().onBlockRenderCutout(block);
    }

    public static KeyMapping registerKeyBinding(String translationKey, int defaultKey, Runnable onPress) {
        return b().registerKeyBinding(translationKey, defaultKey, onPress);
    }

    public static void sendOpenGuild() {
        b().sendOpenGuild(Platform.OPEN_GUILD);
    }

    public static void onBossPin(BossPinHandler handler) {
        b().onBossPin(handler);
    }

    public static void onDisconnect(Runnable handler) {
        b().onDisconnect(handler);
    }

    /** Called every client tick. currentScreen is the active screen, or null. */
    public static void onClientTick(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> handler) {
        b().onClientTick(handler);
    }

    public static void onWorldRender(WorldRenderHandler handler) {
        b().onWorldRender(handler);
    }

    @FunctionalInterface
    public interface BossPinHandler {
        void accept(int x, int y, int z, int lifetimeTicks);
    }

    @FunctionalInterface
    public interface WorldRenderHandler {
        void render(WorldRenderContext context);
    }

    public interface ClientBackend {
        void onBlockRenderCutout(Block block);

        KeyMapping registerKeyBinding(String translationKey, int defaultKey, Runnable onPress);

        void sendOpenGuild(ResourceLocation channel);

        void onBossPin(BossPinHandler handler);

        void onDisconnect(Runnable handler);

        void onClientTick(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> handler);

        void onWorldRender(WorldRenderHandler handler);
    }
}
