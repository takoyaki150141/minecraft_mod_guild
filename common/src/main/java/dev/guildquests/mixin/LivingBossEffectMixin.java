package dev.guildquests.mixin;

import dev.guildquests.service.BossCombat;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingBossEffectMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void guildquests$bossEffect(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        if (!BossCombat.allowEffect((LivingEntity) (Object) this, effect, source)) cir.setReturnValue(false);
    }
}
