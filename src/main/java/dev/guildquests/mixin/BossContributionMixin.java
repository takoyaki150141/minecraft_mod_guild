package dev.guildquests.mixin;

import dev.guildquests.service.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Credits emergency-boss contribution with the HP the boss actually lost.
 * <p>
 * The server damage event exposes the <em>pre-mitigation</em> amount, which over-credits armored
 * bosses and counts full overkill (a 1000-damage hit on a boss with 5 HP would otherwise add 1000).
 * Measuring {@code healthBefore - healthAfter} inside {@code actuallyHurt} gives the real number
 * that the boss bar moved by.
 * <p>
 * {@code Player} overrides {@code actuallyHurt} and never calls super, so this mixin only applies
 * to the mobs that can be emergency bosses.
 */
@Mixin(LivingEntity.class)
public abstract class BossContributionMixin {
    @Unique
    private float guildquests$healthBefore;

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void guildquests$captureHealth(DamageSource source, float amount, CallbackInfo ci) {
        guildquests$healthBefore = ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void guildquests$creditActualDamage(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float dealt = guildquests$healthBefore - self.getHealth();
        if (dealt > 0) {
            BossEvent.recordActualDamage(self, source, dealt);
        }
    }
}
