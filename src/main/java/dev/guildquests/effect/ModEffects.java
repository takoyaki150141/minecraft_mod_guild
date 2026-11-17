package dev.guildquests.effect;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.platform.ContentRegistrar;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

/** Status effects added by GuildQuests. */
public final class ModEffects {
    /** Held by players while emergency bosses must ignore them. */
    public static final MobEffect BOSS_PROTECTION_EFFECT = new BossProtectionEffect();

    public static final ResourceLocation BOSS_PROTECTION_ID =
            ResourceLocation.fromNamespaceAndPath(GuildQuestsMod.MOD_ID, "boss_protection");

    private static Holder<MobEffect> bossProtection;

    private ModEffects() {
    }

    /**
     * Registry holder for the protection effect. Resolved lazily because 1.21 effect APIs
     * (hasEffect / addEffect / MobEffectInstance) require a {@link Holder}, and the registry
     * entry only exists once registration has run.
     */
    public static Holder<MobEffect> bossProtection() {
        Holder<MobEffect> holder = bossProtection;
        if (holder == null) {
            // Resolve by key so the holder always carries the registry id; a keyless direct
            // holder would break hasEffect/removeEffect and effect save round-trips.
            holder = BuiltInRegistries.MOB_EFFECT
                    .getHolder(ResourceKey.create(Registries.MOB_EFFECT, BOSS_PROTECTION_ID))
                    .orElseThrow(() -> new IllegalStateException(
                            "guildquests:boss_protection is not registered yet"));
            bossProtection = holder;
        }
        return holder;
    }

    public static void registerEffects(ContentRegistrar registrar) {
        registrar.registerMobEffect(BOSS_PROTECTION_ID, BOSS_PROTECTION_EFFECT);
    }
}
