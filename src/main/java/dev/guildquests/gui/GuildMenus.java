package dev.guildquests.gui;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.inventory.MenuType;

/** Both loaders register distinct menu types so ordinary chests are never replaced. */
public final class GuildMenus {
    private static final Map<GuildMenuKind, MenuType<GuildClientMenu>> TYPES = new EnumMap<>(GuildMenuKind.class);
    private GuildMenus() { }
    public static void bind(GuildMenuKind kind, MenuType<GuildClientMenu> type) { TYPES.put(kind, type); }
    public static MenuType<GuildClientMenu> clientType(GuildMenuKind kind) { return TYPES.get(kind); }
    public static MenuType<?> type(GuildMenuKind kind) {
        return java.util.Objects.requireNonNull(TYPES.get(kind), "Unregistered guild menu: " + kind);
    }
}
