package dev.guildquests.service;

import dev.guildquests.config.ModConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-owner land protection rules. The config values are the defaults; once a player
 * changes a rule in game their own mask is stored and takes precedence.
 */
public final class LandRules {
    public static final int BREAK = 1;
    public static final int PLACE = 2;
    public static final int CONTAINERS = 4;
    public static final int INTERACT = 8;
    public static final int ENTITY = 16;
    public static final int MOB_DAMAGE = 32;
    public static final int PVP = 64;
    public static final int ALL = 127;

    /** Stored value meaning "the player never changed anything; use the config". */
    public static final int UNSET = -1;

    /** One switch shown to the player. {@code shortName} fits the map footer. */
    public record Rule(int bit, String shortName, String label) { }

    public static final List<Rule> RULES = List.of(
            new Rule(BREAK, "破壊", "ブロックの破壊"),
            new Rule(PLACE, "設置", "ブロックの設置"),
            new Rule(CONTAINERS, "チェスト", "チェスト・かまどの開閉"),
            new Rule(INTERACT, "操作", "ボタン・ドアなどの操作"),
            new Rule(ENTITY, "生物", "動物・村人などへの操作"),
            new Rule(MOB_DAMAGE, "生物攻撃", "動物・モンスターへの攻撃"),
            new Rule(PVP, "PvP", "プレイヤーへの攻撃")
    );

    private LandRules() { }

    /** Mask built from the config defaults. */
    public static int defaults(ModConfig config) {
        if (config == null) return ALL;
        int mask = 0;
        if (config.landProtectBreak) mask |= BREAK;
        if (config.landProtectPlace) mask |= PLACE;
        if (config.landProtectContainers) mask |= CONTAINERS;
        if (config.landProtectInteract) mask |= INTERACT;
        if (config.landProtectEntity) mask |= ENTITY;
        if (config.landProtectMobDamage) mask |= MOB_DAMAGE;
        if (config.landProtectPvp) mask |= PVP;
        return mask;
    }

    /** Stored mask when set, otherwise the config defaults. Unknown bits are dropped. */
    public static int resolve(int storedMask, ModConfig config) {
        return storedMask < 0 ? defaults(config) : storedMask & ALL;
    }

    public static boolean has(int mask, int bit) {
        return (mask & bit) != 0;
    }

    public static int toggle(int mask, int bit) {
        return (mask ^ bit) & ALL;
    }

    public static Rule byIndex(int index) {
        return index >= 0 && index < RULES.size() ? RULES.get(index) : null;
    }

    /** Short list of the enabled rules, e.g. "破壊 / 設置 / チェスト". */
    public static String names(int mask) {
        List<String> names = new ArrayList<>();
        for (Rule rule : RULES) {
            if (has(mask, rule.bit())) names.add(rule.shortName());
        }
        return names.isEmpty() ? "対象なし" : String.join(" / ", names);
    }

    /** Same list with a "保護: " prefix. */
    public static String summary(int mask) {
        return "保護: " + names(mask);
    }
}
