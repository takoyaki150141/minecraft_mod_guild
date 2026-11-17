package dev.guildquests.mixin;

import dev.guildquests.service.BossEvent;
import dev.guildquests.service.BossProtection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla {@code TargetingConditions#test} consults {@code LivingEntity#canAttack} when the
 * conditions are combat conditions, so returning false here stops every targeting goal from
 * ever considering a protected player.
 */
@Mixin(LivingEntity.class)
public abstract class LivingBossTargetMixin {
    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void guildquests$skipProtectedTarget(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (target instanceof ServerPlayer player
                && BossProtection.isProtected(player)
                && BossEvent.isBoss((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
