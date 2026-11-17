package dev.guildquests.model;

public enum QuestType {
    KILL,
    SUBMIT,
    /** Collect-and-deliver (consumes items), but rate-limited per player by {@code cooldownDays}. */
    COLLECT,
    EXPLORE
}
