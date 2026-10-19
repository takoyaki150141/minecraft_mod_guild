package dev.guildquests.mixin;

import dev.guildquests.service.BossCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class BossTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void guildquests$protectRespawn(LivingEntity target, CallbackInfo ci) {
        if (target instanceof ServerPlayer player && BossCombat.isBoss(BossCombat.owner((Mob) (Object) this))
                && BossCombat.isProtected(player)) ci.cancel();
    }
}
