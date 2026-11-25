package dev.guildquests.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.component.CustomData;

/** Land-map presentation state, transported by the existing menu slot synchronization. */
public final class LandView {
    public static final String TAG = "GuildLandView";
    public static final byte UNOWNED = 0;
    public static final byte SELF = 1;
    public static final byte OTHER = 2;

    private LandView() { }

    public static void publish(Container display, CompoundTag view) {
        CustomData.update(DataComponents.CUSTOM_DATA, display.getItem(0), nbt -> nbt.put(TAG, view));
    }
}
