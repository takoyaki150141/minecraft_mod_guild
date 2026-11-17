package dev.guildquests.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.platform.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge implementation of the loader-neutral {@link PlatformClient.ClientBackend}.
 * <p>
 * Created during mod construction (see {@link NeoForgeClientBootstrap}) because NeoForge fires
 * {@link RegisterKeyMappingsEvent} before {@code FMLClientSetupEvent}.
 */
final class NeoForgeClientBackend implements PlatformClient.ClientBackend {
    private final List<BoundKey> keys = new ArrayList<>();
    private boolean keysRegistered;
    private final List<Runnable> disconnectHandlers = new ArrayList<>();
    private final List<PlatformClient.WorldRenderHandler> worldRenderHandlers = new ArrayList<>();
    private final List<java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>> clientTickHandlers = new ArrayList<>();

    NeoForgeClientBackend(IEventBus modBus) {
        modBus.addListener(this::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> onClientTick());
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (keysRegistered) {
            return;
        }
        keysRegistered = true;
        for (BoundKey bound : keys) {
            event.register(bound.key());
        }
    }

    private void onClientTick() {
        for (BoundKey bound : keys) {
            while (bound.key().consumeClick()) {
                bound.onPress().run();
            }
        }
        for (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> handler : clientTickHandlers) {
            handler.accept(Minecraft.getInstance().screen);
        }
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        for (Runnable handler : disconnectHandlers) {
            handler.run();
        }
    }

    private void onRenderLevel(RenderLevelStageEvent event) {
        if (worldRenderHandlers.isEmpty()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        WorldRenderContext context = new NeoForgeWorldRenderContext(event.getCamera(), event.getPoseStack());
        for (PlatformClient.WorldRenderHandler handler : worldRenderHandlers) {
            handler.render(context);
        }
    }

    @Override
    public void onBlockRenderCutout(Block block) {
        ItemBlockRenderTypes.setRenderLayer(block, RenderType.cutout());
    }

    @Override
    public KeyMapping registerKeyBinding(String translationKey, int defaultKey, Runnable onPress) {
        KeyMapping key = new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey,
                "key.categories.guildquests");
        keys.add(new BoundKey(key, onPress));
        return key;
    }

    @Override
    public void sendOpenGuild(ResourceLocation channel) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        if (!NetworkRegistry.hasChannel(connection.getConnection(), ConnectionProtocol.PLAY,
                NeoForgeNetwork.OpenGuildC2S.TYPE.id())) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new NeoForgeNetwork.OpenGuildC2S());
    }

    @Override
    public void onBossPin(PlatformClient.BossPinHandler handler) {
        NeoForgeNetwork.setBossPinSink(handler::accept);
    }

    @Override
    public void onDisconnect(Runnable handler) {
        disconnectHandlers.add(handler);
    }

    @Override
    public void onClientTick(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen> handler) {
        clientTickHandlers.add(handler);
    }

    @Override
    public void onWorldRender(PlatformClient.WorldRenderHandler handler) {
        worldRenderHandlers.add(handler);
    }

    private record BoundKey(KeyMapping key, Runnable onPress) {
    }

    private record NeoForgeWorldRenderContext(Camera camera, PoseStack poseStack) implements WorldRenderContext {
    }
}
