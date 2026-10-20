package dev.guildquests.mixin;

import dev.guildquests.service.BossAttackSnapshot;
import dev.guildquests.service.BossProjectileData;
import dev.guildquests.service.BossProjectiles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class BossProjectileMixin implements BossProjectileData {
    @Unique private BossAttackSnapshot guildquests$shot;

    @Override public BossAttackSnapshot guildquests$attackSnapshot() { return guildquests$shot; }
    @Override public void guildquests$attackSnapshot(BossAttackSnapshot snapshot) { guildquests$shot = snapshot; }

    @Inject(method = "setOwner", at = @At("RETURN"))
    private void guildquests$captureOwner(Entity owner, CallbackInfo ci) {
        BossProjectiles.assigned((Projectile) (Object) this, owner);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void guildquests$saveAttack(CompoundTag tag, CallbackInfo ci) {
        if (guildquests$shot != null) tag.put("guildquests:boss_attack", guildquests$shot.save());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void guildquests$loadAttack(CompoundTag tag, CallbackInfo ci) {
        guildquests$shot = BossAttackSnapshot.load(tag.getCompound("guildquests:boss_attack"));
    }
}
