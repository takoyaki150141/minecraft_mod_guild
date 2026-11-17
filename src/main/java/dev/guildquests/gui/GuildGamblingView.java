package dev.guildquests.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.Container;

/** Public presentation state, transported by the existing menu slot synchronization. */
public final class GuildGamblingView {
    public static final String TAG = "GuildGamblingView";
    private GuildGamblingView() { }

    public static CompoundTag round(String phase, long bet, long balance, String result, long payout) {
        CompoundTag view = new CompoundTag();
        view.putString("phase", phase);
        view.putLong("bet", bet);
        view.putLong("balance", balance);
        // Outcomes are withheld until the server has finished the presentation phase.
        if (phase.equals("DONE")) {
            view.putString("result", result);
            view.putLong("payout", payout);
        }
        return view;
    }

    public static CompoundTag card(String rank, String suit, boolean hidden) {
        CompoundTag card = new CompoundTag();
        card.putBoolean("hidden", hidden);
        if (!hidden) {
            card.putString("rank", rank);
            card.putString("suit", suit);
        }
        return card;
    }

    public static int[] visibleReels(int[] reels, int ticks, int[] stops, boolean spinning, boolean done) {
        int[] shown = new int[reels.length];
        for (int i = 0; i < shown.length; i++)
            shown[i] = done || spinning && ticks >= stops[i] ? reels[i] : -1;
        return shown;
    }

    public static void publish(Container display, CompoundTag view) {
        CustomData.update(DataComponents.CUSTOM_DATA, display.getItem(0), nbt -> nbt.put(TAG, view));
    }
}
