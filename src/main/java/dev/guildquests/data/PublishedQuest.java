package dev.guildquests.data;

import dev.guildquests.model.QuestDefinition;
import dev.guildquests.model.QuestType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Legacy model of a player-published quest (bounty board). The publishing feature was
 * removed; this class is kept only so old saves can be loaded, purged, and refunded on
 * startup (see GuildService.purgePublishedQuests).
 */
public final class PublishedQuest {
    public String id = "";
    public UUID publisherId;
    public String publisherName = "";
    /** KILL or SUBMIT only (EXPLORE cannot be specified through the GUI). */
    public QuestType type = QuestType.KILL;
    public String target = "";
    public int amount = 1;
    public long reward = 1;
    /** In-game day the quest was published; used for expiry. */
    public long createdAtDay;
    /** Null while open; set to the taker's uuid while taken. */
    public UUID takerId;
    public String takerName = "";
    /** Custom quest title; blank = auto-generated ("依頼：討伐 …"). */
    public String title = "";
    /** Custom quest description; blank = default text. */
    public String description = "";

    public PublishedQuest() {
    }

    public PublishedQuest(String id) {
        this.id = id;
    }

    public boolean isTaken() {
        return takerId != null;
    }

    public static String questId(String id) {
        return "pub:" + id;
    }

    public boolean isQuestId(String questId) {
        return questId().equals(questId);
    }

    public String questId() {
        return questId(id);
    }

    /** Synthesized definition so the normal accept/progress/claim pipeline works. */
    public QuestDefinition toDefinition() {
        QuestDefinition def = new QuestDefinition();
        def.id = questId();
        def.type = type;
        def.target = target;
        def.amount = Math.max(1, amount);
        def.reward = Math.max(0, reward);
        def.repeatable = false;
        def.requiredCompleted = 0;
        def.dimension = "minecraft:overworld";
        if (type == QuestType.KILL) {
            def.title = "依頼：討伐 " + entityNameSafe(target) + " × " + amount;
        } else {
            def.title = "依頼：納品 " + itemNameSafe(target) + " × " + amount;
        }
        def.description = publisherName + " が発注した依頼クエスト";
        if (title != null && !title.isBlank()) def.title = title;
        if (description != null && !description.isBlank()) def.description = description;
        return def;
    }

    /** Definition title used in messages (needs registry access, so built lazily). */
    public String title(MinecraftServer server) {
        return toDefinition().title;
    }

    private static String entityNameSafe(String id) {
        try {
            return dev.guildquests.service.GuildService.entityName(id);
        } catch (RuntimeException e) {
            return id;
        }
    }

    private static String itemNameSafe(String id) {
        try {
            return dev.guildquests.service.GuildService.itemName(id);
        } catch (RuntimeException e) {
            return id;
        }
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("Id", id);
        nbt.putString("PublisherId", publisherId == null ? "" : publisherId.toString());
        nbt.putString("PublisherName", publisherName == null ? "" : publisherName);
        nbt.putString("Type", type == QuestType.SUBMIT ? "SUBMIT" : "KILL");
        nbt.putString("Target", target == null ? "" : target);
        nbt.putInt("Amount", Math.max(1, amount));
        nbt.putLong("Reward", Math.max(1, reward));
        nbt.putLong("CreatedAtDay", createdAtDay);
        nbt.putBoolean("Taken", isTaken());
        nbt.putString("TakerId", takerId == null ? "" : takerId.toString());
        nbt.putString("TakerName", takerName == null ? "" : takerName);
        nbt.putString("CustomTitle", title == null ? "" : title);
        nbt.putString("CustomDescription", description == null ? "" : description);
        return nbt;
    }

    public static PublishedQuest fromNbt(CompoundTag nbt) {
        PublishedQuest quest = new PublishedQuest(nbt.getString("Id"));
        try {
            String pub = nbt.getString("PublisherId");
            quest.publisherId = pub.isEmpty() ? null : UUID.fromString(pub);
        } catch (IllegalArgumentException ignored) {
            quest.publisherId = null;
        }
        quest.publisherName = nbt.getString("PublisherName");
        quest.type = "SUBMIT".equals(nbt.getString("Type")) ? QuestType.SUBMIT : QuestType.KILL;
        quest.target = nbt.getString("Target");
        quest.amount = Math.max(1, nbt.getInt("Amount"));
        quest.reward = Math.max(1, nbt.getLong("Reward"));
        quest.createdAtDay = nbt.getLong("CreatedAtDay");
        try {
            String taker = nbt.getString("TakerId");
            if (!taker.isEmpty()) quest.takerId = UUID.fromString(taker);
        } catch (IllegalArgumentException ignored) {
            quest.takerId = null;
        }
        quest.takerName = nbt.getString("TakerName");
        quest.title = nbt.getString("CustomTitle");
        quest.description = nbt.getString("CustomDescription");
        return quest;
    }
}
