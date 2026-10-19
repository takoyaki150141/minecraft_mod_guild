package dev.guildquests.mixin;

import dev.guildquests.service.BossCombat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Forge 1.20.1 keeps final damage in slot 3, after absorption and LivingDamageEvent. */
@Mixin(LivingEntity.class)
public abstract class LivingBossDamageMixin {
    @ModifyVariable(method = "actuallyHurt", index = 3,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getCombatTracker()Lnet/minecraft/world/damagesource/CombatTracker;"))
    private float guildquests$adaptDamage(float amount, DamageSource source, float original) {
        return BossCombat.adjustDamage((LivingEntity) (Object) this, source, amount);
    }
}
