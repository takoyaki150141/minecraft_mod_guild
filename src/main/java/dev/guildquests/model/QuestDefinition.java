package dev.guildquests.model;

public final class QuestDefinition {
    public String id = "quest";
    public String title = "Quest";
    public String description = "";
    public QuestType type = QuestType.KILL;

    /** Entity ID for KILL, item ID for SUBMIT / COLLECT. Used when targets is empty. */
    public String target = "minecraft:zombie";
    /** Optional pool of entity IDs for KILL. One is rolled at accept time. */
    public String[] targets;
    /** Fixed count used when no range is set. */
    public int amount = 1;
    /** Optional random count range (inclusive) rolled at accept time. */
    public int amountMin = 0;
    public int amountMax = 0;
    public long reward = 1;

    /** Total completed quests required before this quest can be accepted. */
    public int requiredCompleted = 0;
    public boolean repeatable = true;
    /**
     * COLLECT only: minimum in-game days between claims per player (2 = once every two days).
     * Combined with the consumed items, this caps how much a player can gain from any item
     * duplication on the server.
     */
    public int cooldownDays = 2;

    /** EXPLORE only. */
    public ExploreMode exploreMode = ExploreMode.COORDINATE;
    public String dimension = "minecraft:overworld";
    /** EXPLORE + BIOME only: pool of biome ids; one is drawn on the daily board. Empty = fixed target. */
    public String[] biomes;
    public double x = 0;
    public double y = 64;
    public double z = 0;
    public double radius = 16;
    /** EXPLORE + COORDINATE only: roll x/z randomly at accept time. */
    public boolean randomCoordinates = false;
    /** Half-size of the random coordinate box around (x, z). 0 = use config exploreRandomRange. */
    public int coordRange = 0;

    public QuestDefinition() {
    }
}
