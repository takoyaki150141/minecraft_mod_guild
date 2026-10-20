package dev.guildquests.service;

import dev.guildquests.mixin.LivingEquipmentAccessor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;

/** Read current equipment even between an inventory change and vanilla's next equipment tick. */
final class BossEquipment {
    private BossEquipment() { }

    static double value(LivingEntity entity, Attribute attribute) {
        AttributeInstance actual = entity.getAttribute(attribute);
        if (actual == null) return attribute.getDefaultValue();
        AttributeInstance adjusted = null;
        LivingEquipmentAccessor equipment = (LivingEquipmentAccessor) entity;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack previous = slot.getType() == EquipmentSlot.Type.HAND
                    ? equipment.guildquests$lastHandItem(slot) : equipment.guildquests$lastArmorItem(slot);
            ItemStack current = entity.getItemBySlot(slot);
            if (ItemStack.matches(previous, current)) continue;
            if (adjusted == null) {
                adjusted = new AttributeInstance(attribute, ignored -> { });
                adjusted.setBaseValue(actual.getBaseValue());
                actual.getModifiers().forEach(adjusted::addTransientModifier);
            }
            for (var modifier : previous.getAttributeModifiers(slot).get(attribute)) adjusted.removeModifier(modifier.getId());
            for (var modifier : current.getAttributeModifiers(slot).get(attribute)) {
                adjusted.removeModifier(modifier.getId());
                adjusted.addTransientModifier(modifier);
            }
        }
        // Do not mutate the real attribute or run another mod's equipment tick ahead of time.
        return adjusted == null ? entity.getAttributeValue(attribute) : adjusted.getValue();
    }
}
