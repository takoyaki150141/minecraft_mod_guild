package dev.guildquests.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Marker effect: while a player has it, emergency bosses ignore them and cannot damage them.
 * The effect itself has no attribute modifiers — {@code BossProtection} reads it as the single
 * source of truth for the protected state, so the HUD countdown and the gameplay rule can never
 * disagree.
 */
public final class BossProtectionEffect extends MobEffect {
    /** Light blue, matching the shield icon in {@code textures/mob_effect/boss_protection.png}. */
    public static final int COLOR = 0x58C7FF;

    public BossProtectionEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }

    /** No periodic tick: the effect only needs to exist. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
