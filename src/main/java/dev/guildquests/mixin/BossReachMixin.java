package dev.guildquests.mixin;

import dev.guildquests.service.BossReach;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends the melee attack range of emergency bosses by the tier bonus (in blocks).
 * 1.21 replaced {@code getMeleeAttackRangeSqr} with a bounding box, so the bonus inflates
 * {@code getAttackBoundingBox} instead.
 */
@Mixin(Mob.class)
public abstract class BossReachMixin {
    @Inject(method = "getAttackBoundingBox", at = @At("RETURN"), cancellable = true)
    private void guildquests$extendBossReach(CallbackInfoReturnable<AABB> cir) {
        double bonus = BossReach.bonus((LivingEntity) (Object) this);
        if (bonus > 0) {
            // Horizontal inflation only, matching vanilla's reach box (no vertical bonus).
            cir.setReturnValue(cir.getReturnValue().inflate(bonus, 0.0, bonus));
        }
    }
}
