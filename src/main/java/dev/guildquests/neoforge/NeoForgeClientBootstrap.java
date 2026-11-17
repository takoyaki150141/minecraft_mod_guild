package dev.guildquests.neoforge;

import dev.guildquests.client.GuildQuestsClient;
import dev.guildquests.platform.PlatformClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only bootstrap. Referenced from {@link GuildQuestsNeoForge} only when
 * {@code FMLEnvironment.dist} is the client, so this class is never loaded on a server.
 */
final class NeoForgeClientBootstrap {
    private NeoForgeClientBootstrap() {
    }

    static void init(IEventBus modBus) {
        NeoForgeGuildGuiClient.init(modBus);
        PlatformClient.bind(new NeoForgeClientBackend(modBus));
        GuildQuestsClient.init();
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(GuildQuestsClient::initRenderLayers));
    }
}
