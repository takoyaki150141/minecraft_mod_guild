package dev.guildquests.neoforge;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.gui.GuildMenus;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the same server-owned menus as the Fabric backend. */
public final class NeoForgeGuildGui {
    private static final DeferredRegister<MenuType<?>> TYPES =
            DeferredRegister.create(Registries.MENU, GuildQuestsNeoForge.MOD_ID);

    private NeoForgeGuildGui() { }

    public static void register(IEventBus bus) {
        for (GuildMenuKind kind : GuildMenuKind.values()) {
            TYPES.register("gui_" + kind.id(), () -> {
                MenuType<GuildClientMenu> type = IMenuTypeExtension.create(
                        (id, inventory, data) -> new GuildClientMenu(kind, id, inventory));
                GuildMenus.bind(kind, type);
                return type;
            });
        }
        TYPES.register(bus);
    }
}
