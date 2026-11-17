package dev.guildquests.data;

import net.minecraft.nbt.CompoundTag;

public final class DailyQuest {
    public String questId = "";
    /** Rolled concrete target (entity id / item id) for today's draw. */
    public String target = "";
    /** Rolled concrete amount for today's draw. */
    public int amount = 1;
    /** EXPLORE COORDINATE: true when x/y/z were drawn for today. */
    public boolean hasCoords = false;
    public double x;
    public double y;
    public double z;

    public DailyQuest() {
    }

    public DailyQuest(String questId) {
        this.questId = questId;
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("QuestId", questId);
        nbt.putString("Target", target == null ? "" : target);
        nbt.putInt("Amount", amount);
        nbt.putBoolean("HasCoords", hasCoords);
        nbt.putDouble("X", x);
        nbt.putDouble("Y", y);
        nbt.putDouble("Z", z);
        return nbt;
    }

    public static DailyQuest fromNbt(CompoundTag nbt) {
        DailyQuest quest = new DailyQuest(nbt.getString("QuestId"));
        quest.target = nbt.getString("Target");
        quest.amount = Math.max(1, nbt.getInt("Amount"));
        quest.hasCoords = nbt.getBoolean("HasCoords");
        quest.x = nbt.getDouble("X");
        quest.y = nbt.getDouble("Y");
        quest.z = nbt.getDouble("Z");
        return quest;
    }
}
