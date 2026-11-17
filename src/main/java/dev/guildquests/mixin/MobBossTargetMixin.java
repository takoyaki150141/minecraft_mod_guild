package dev.guildquests.mixin;

import dev.guildquests.service.BossEvent;
import dev.guildquests.service.BossProtection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emergency bosses refuse to acquire a protected player as their target. */
@Mixin(Mob.class)
public abstract class MobBossTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void guildquests$skipProtectedTarget(LivingEntity target, CallbackInfo ci) {
        if (target instanceof ServerPlayer player
                && BossProtection.isProtected(player)
                && BossEvent.isBoss((Mob) (Object) this)) {
            ci.cancel();
        }
    }
}
