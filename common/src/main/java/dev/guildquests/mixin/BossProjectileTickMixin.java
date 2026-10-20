package dev.guildquests.mixin;

import dev.guildquests.service.BossProjectiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLevel.class)
public abstract class BossProjectileTickMixin {
    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void guildquests$projectileContext(Entity entity) {
        // Finally restores context even if another mod throws or starts a nested entity tick.
        BossProjectiles.tick(entity);
    }
}
