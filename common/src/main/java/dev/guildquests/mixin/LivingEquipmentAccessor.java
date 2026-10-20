package dev.guildquests.mixin;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEquipmentAccessor {
    @Invoker("getLastHandItem") ItemStack guildquests$lastHandItem(EquipmentSlot slot);
    @Invoker("getLastArmorItem") ItemStack guildquests$lastArmorItem(EquipmentSlot slot);
}
