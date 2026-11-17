package dev.guildquests.neoforge;

import dev.guildquests.client.gui.GuildGuiActions;
import dev.guildquests.client.gui.GuildMenuScreen;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.gui.GuildMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Kept separate so dedicated servers never load Minecraft client classes. */
final class NeoForgeGuildGuiClient {
    private NeoForgeGuildGuiClient() { }

    static void init(IEventBus bus) {
        bus.addListener((RegisterMenuScreensEvent event) -> {
            for (GuildMenuKind kind : GuildMenuKind.values()) {
                event.register(GuildMenus.clientType(kind), GuildMenuScreen::create);
            }
            GuildGuiActions.amountSender = NeoForgeNetwork::sendGuiAmount;
        });
    }
}
