package dev.guildquests.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.guildquests.GuildQuestsMod;
import dev.guildquests.model.ExploreMode;
import dev.guildquests.model.QuestDefinition;
import dev.guildquests.model.QuestType;
import dev.guildquests.platform.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = Platform.configDir();
    private static final Path CONFIG = DIR.resolve("config.json");
    private static final Path QUESTS = DIR.resolve("quests.json");

    private static volatile ModConfig config = new ModConfig();
    private static volatile List<QuestDefinition> quests = List.of();
    private static final List<Runnable> reloadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ConfigManager() {
    }

    public static synchronized void loadAll() {
        dev.guildquests.fx.FxSettings.load();
        try {
            Files.createDirectories(DIR);
            if (!Files.exists(CONFIG)) writeJson(CONFIG, new ModConfig());
            if (!Files.exists(QUESTS)) writeDefaultQuests(QUESTS);

            ModConfig loadedConfig = readJson(CONFIG, ModConfig.class);
            QuestDefinition[] loadedQuests = readJson(QUESTS, QuestDefinition[].class);

            config = sanitizeConfig(loadedConfig == null ? new ModConfig() : loadedConfig);
            quests = Collections.unmodifiableList(validateQuests(loadedQuests == null ? new QuestDefinition[0] : loadedQuests));

            GuildQuestsMod.LOGGER.info("Loaded {} quests from {}", quests.size(), DIR);
        } catch (IOException | RuntimeException e) {
            GuildQuestsMod.LOGGER.error("Failed to load GuildQuests configuration. Keeping previous in-memory configuration.", e);
        }
        for (Runnable listener : reloadListeners) listener.run();
    }

    /** Loader-specific configuration can join command and admin-GUI reloads. */
    public static void onReload(Runnable listener) {
        reloadListeners.add(listener);
    }

    public static ModConfig config() {
        return config;
    }

    public static List<QuestDefinition> quests() {
        return quests;
    }

    public static QuestDefinition quest(String id) {
        for (QuestDefinition q : quests) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }

    private static ModConfig sanitizeConfig(ModConfig c) {
        c.maxActiveQuests = Math.max(1, Math.min(9, c.maxActiveQuests));
        c.exploreCheckIntervalTicks = Math.max(1, c.exploreCheckIntervalTicks);
        c.dailyQuestCountMin = Math.max(1, Math.min(9, c.dailyQuestCountMin));
        c.dailyQuestCountMax = Math.max(1, Math.min(9, c.dailyQuestCountMax));
        if (c.dailyQuestCountMax < c.dailyQuestCountMin) c.dailyQuestCountMax = c.dailyQuestCountMin;
        c.exploreRandomRange = Math.max(1, Math.min(100000, c.exploreRandomRange));
        c.bossEventIntervalMinutes = Math.max(1, Math.min(1440, c.bossEventIntervalMinutes));
        c.bossEventChancePercent = Math.max(0, Math.min(100, c.bossEventChancePercent));
        c.bossWeakestPromoteChance = Math.max(0, Math.min(1, Double.isFinite(c.bossWeakestPromoteChance) ? c.bossWeakestPromoteChance : 0.01));
        c.bossEventReward = Math.max(0, c.bossEventReward);
        c.bossEventHealth = Math.max(1, Math.min(100000, c.bossEventHealth));
        if (c.bossTierWeights == null || c.bossTierWeights.length != 4) {
            c.bossTierWeights = new int[]{30, 40, 22, 8};
        } else {
            boolean any = false;
            for (int i = 0; i < c.bossTierWeights.length; i++) {
                c.bossTierWeights[i] = Math.max(0, Math.min(1000, c.bossTierWeights[i]));
                any |= c.bossTierWeights[i] > 0;
            }
            if (!any) c.bossTierWeights = new int[]{30, 40, 22, 8};
        }
        c.bossProtectionSeconds = Math.max(0, Math.min(3600, c.bossProtectionSeconds));
        c.bossProtectionReleaseRange = Math.max(8, Math.min(128, c.bossProtectionReleaseRange));
        c.bankFeePercent = Math.max(0, Math.min(100, c.bankFeePercent));
        c.riboInstallment = Math.max(1, c.riboInstallment);
        c.riboInterestPercent = Math.max(0, Math.min(100, c.riboInterestPercent));
        // Forced: repayment always allows the balance first, and delinquency force-executes it.
        // Older config files may still carry true; it is ignored on purpose.
        c.miningDebtRepaymentOnly = false;
        c.riboIntervalMinutes = Math.max(1, c.riboIntervalMinutes);
        c.auctionFeePercent = Math.max(0, Math.min(100, c.auctionFeePercent));
        c.auctionDurationMinutes = Math.max(1, Math.min(10080, c.auctionDurationMinutes));
        c.auctionMaxListingsPerPlayer = Math.max(1, Math.min(45, c.auctionMaxListingsPerPlayer));
        c.auctionBidStep = Math.max(1, c.auctionBidStep);
        if (c.rankThresholds == null || c.rankThresholds.isEmpty()) c.rankThresholds = new ModConfig().rankThresholds;
        c.consumerLoanInterestPercent = Math.max(0, Math.min(100, c.consumerLoanInterestPercent));
        c.consumerLoanIntervalMinutes = Math.max(1, c.consumerLoanIntervalMinutes);
        if (c.consumerLoanLimits == null || c.consumerLoanLimits.isEmpty()) {
            c.consumerLoanLimits = new ModConfig().consumerLoanLimits;
        } else {
            c.consumerLoanLimits.replaceAll((k, v) -> Math.max(0L, v == null ? 0L : v));
        }
        if (c.loanMiningValues == null || c.loanMiningValues.isEmpty()) {
            c.loanMiningValues = new ModConfig().loanMiningValues;
        } else {
            c.loanMiningValues.replaceAll((k, v) -> v == null || !Double.isFinite(v) ? 0.0 : Math.max(0.0, v));
        }
        return c;
    }

    private static List<QuestDefinition> validateQuests(QuestDefinition[] source) {
        List<QuestDefinition> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (QuestDefinition q : source) {
            if (q == null || q.id == null || q.id.isBlank() || ids.contains(q.id)) continue;
            if (q.title == null || q.title.isBlank()) q.title = q.id;
            if (q.description == null) q.description = "";
            if (q.type == null) q.type = QuestType.KILL;
            if (q.target == null) q.target = "";
            q.amount = Math.max(1, q.amount);
            if (q.targets != null) {
                List<String> cleaned = new ArrayList<>();
                for (String t : q.targets) {
                    if (t != null && !t.isBlank()) cleaned.add(t.trim());
                }
                q.targets = cleaned.isEmpty() ? null : cleaned.toArray(new String[0]);
            }
            if (q.biomes != null) {
                List<String> cleaned = new ArrayList<>();
                for (String b : q.biomes) {
                    if (b != null && !b.isBlank()) cleaned.add(b.trim());
                }
                q.biomes = cleaned.isEmpty() ? null : cleaned.toArray(new String[0]);
            }
            if (q.amountMin > 0 || q.amountMax > 0) {
                int lo = q.amountMin > 0 ? q.amountMin : q.amount;
                int hi = q.amountMax > 0 ? q.amountMax : lo;
                if (lo > hi) {
                    int swap = lo;
                    lo = hi;
                    hi = swap;
                }
                q.amountMin = Math.max(1, lo);
                q.amountMax = Math.max(q.amountMin, hi);
            } else {
                q.amountMin = 0;
                q.amountMax = 0;
            }
            q.reward = Math.max(0, q.reward);
            q.requiredCompleted = Math.max(0, q.requiredCompleted);
            q.cooldownDays = Math.max(0, q.cooldownDays);
            if (q.exploreMode == null) q.exploreMode = ExploreMode.COORDINATE;
            if (q.dimension == null || q.dimension.isBlank()) q.dimension = "minecraft:overworld";
            q.radius = Math.max(0.5, q.radius);
            q.coordRange = Math.max(0, Math.min(100000, q.coordRange));
            ids.add(q.id);
            out.add(q);
        }
        return out;
    }

    private static <T> T readJson(Path path, Class<T> type) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            return GSON.fromJson(r, type);
        }
    }

    private static void writeJson(Path path, Object value) throws IOException {
        try (Writer w = Files.newBufferedWriter(path)) {
            GSON.toJson(value, w);
        }
    }

    /** Bundled default quest board, shipped inside the jar (not a user-facing file). */
    private static final String BUNDLED_QUESTS = "/guildquests_default_quests.json";

    /**
     * Seeds the player-facing quests.json on first run. Prefers the bundled resource so the
     * default board is maintained as JSON rather than Java; falls back to {@link #defaultQuests()}.
     */
    private static void writeDefaultQuests(Path target) throws IOException {
        try (InputStream in = ConfigManager.class.getResourceAsStream(BUNDLED_QUESTS)) {
            if (in != null) {
                Files.copy(in, target);
                return;
            }
        }
        GuildQuestsMod.LOGGER.warn("Bundled {} not found; writing built-in default quests", BUNDLED_QUESTS);
        writeJson(target, defaultQuests());
    }

    public static QuestDefinition[] defaultQuests() {
        QuestDefinition undead = new QuestDefinition();
        undead.id = "kill_undead";
        undead.title = "討伐：アンデッド";
        undead.description = "ゾンビやスケルトンなど、アンデッドの群れをまとめて討伐する";
        undead.type = QuestType.KILL;
        undead.target = "minecraft:zombie";
        undead.targets = new String[]{
                "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                "minecraft:zombie_villager", "minecraft:skeleton", "minecraft:stray", "minecraft:phantom"
        };
        undead.amount = 15;
        undead.amountMin = 10;
        undead.amountMax = 20;
        undead.reward = 4;

        QuestDefinition monsters = new QuestDefinition();
        monsters.id = "kill_monsters";
        monsters.title = "討伐：モンスター";
        monsters.description = "クリーパーやクモなど、危険な敵対モンスターをまとめて討伐する";
        monsters.type = QuestType.KILL;
        monsters.target = "minecraft:creeper";
        monsters.targets = new String[]{
                "minecraft:creeper", "minecraft:spider", "minecraft:cave_spider",
                "minecraft:slime", "minecraft:witch", "minecraft:enderman"
        };
        monsters.amount = 12;
        monsters.amountMin = 8;
        monsters.amountMax = 16;
        monsters.reward = 5;

        QuestDefinition nether = new QuestDefinition();
        nether.id = "kill_nether";
        nether.title = "討伐：ネザーの住人";
        nether.description = "ブラーズやウィザースケルトンなど、ネザーの住人たちを討伐する";
        nether.type = QuestType.KILL;
        nether.target = "minecraft:blaze";
        nether.targets = new String[]{
                "minecraft:blaze", "minecraft:ghast", "minecraft:magma_cube",
                "minecraft:wither_skeleton", "minecraft:piglin_brute", "minecraft:zombified_piglin", "minecraft:hoglin"
        };
        nether.amount = 10;
        nether.amountMin = 6;
        nether.amountMax = 14;
        nether.reward = 6;

        QuestDefinition illager = new QuestDefinition();
        illager.id = "kill_illagers";
        illager.title = "討伐：イルージョナー";
        illager.description = "ピルジャーやボレーなど、イルージョナーの集団を討伐する";
        illager.type = QuestType.KILL;
        illager.target = "minecraft:pillager";
        illager.targets = new String[]{
                "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:vex"
        };
        illager.amount = 7;
        illager.amountMin = 5;
        illager.amountMax = 10;
        illager.reward = 6;

        QuestDefinition ender = new QuestDefinition();
        ender.id = "kill_ender";
        ender.title = "討伐：エンダー系";
        ender.description = "エンダーマンやシャルカーなど、エンダー系の敵を討伐する";
        ender.type = QuestType.KILL;
        ender.target = "minecraft:enderman";
        ender.targets = new String[]{
                "minecraft:enderman", "minecraft:endermite", "minecraft:shulker"
        };
        ender.amount = 8;
        ender.amountMin = 6;
        ender.amountMax = 12;
        ender.reward = 6;

        QuestDefinition explore = new QuestDefinition();
        explore.id = "explore_target_area";
        explore.title = "指定エリア探索";
        explore.description = "ギルドが指定した目的地へ到達して報酬を受け取る";
        explore.type = QuestType.EXPLORE;
        explore.exploreMode = ExploreMode.COORDINATE;
        explore.target = "";
        explore.dimension = "minecraft:overworld";
        explore.x = 0;
        explore.y = 64;
        explore.z = 0;
        explore.radius = 32;
        explore.randomCoordinates = true;
        explore.coordRange = 0;
        explore.amount = 1;
        explore.reward = 8;

        QuestDefinition biome = new QuestDefinition();
        biome.id = "explore_biome_jungle";
        biome.title = "探索：各種バイオーム";
        biome.description = "ギルドが指定したバイオームへ到達する";
        biome.type = QuestType.EXPLORE;
        biome.exploreMode = ExploreMode.BIOME;
        biome.dimension = "minecraft:overworld";
        biome.target = "minecraft:jungle";
        biome.biomes = new String[]{
                "minecraft:jungle", "minecraft:bamboo_jungle", "minecraft:sparse_jungle",
                "minecraft:savanna", "minecraft:desert", "minecraft:taiga", "minecraft:snowy_taiga",
                "minecraft:swamp", "minecraft:dark_forest", "minecraft:flower_forest",
                "minecraft:badlands", "minecraft:plains", "minecraft:forest", "minecraft:birch_forest",
                "minecraft:beach", "minecraft:mushroom_fields", "minecraft:ice_spikes", "minecraft:jungle_hills"
        };
        biome.y = 64;
        biome.radius = 32;
        biome.amount = 1;
        biome.reward = 6;

        QuestDefinition structure = new QuestDefinition();
        structure.id = "explore_nether_fortress";
        structure.title = "探索：ネザーフォートレス";
        structure.description = "ネザーフォートレスを発見する";
        structure.type = QuestType.EXPLORE;
        structure.exploreMode = ExploreMode.STRUCTURE;
        structure.dimension = "minecraft:the_nether";
        structure.target = "minecraft:nether_fortress";
        structure.y = 64;
        structure.radius = 32;
        structure.amount = 1;
        structure.reward = 10;

        QuestDefinition boss = new QuestDefinition();
        boss.id = "boss_wither";
        boss.title = "高ランク：ウィザー討伐";
        boss.description = "ウィザーを1体討伐する";
        boss.type = QuestType.KILL;
        boss.target = "minecraft:wither";
        boss.amount = 1;
        boss.reward = 9;
        boss.requiredCompleted = 10;

        QuestDefinition dragon = new QuestDefinition();
        dragon.id = "boss_dragon";
        dragon.title = "最高ランク：エンダードラゴン討伐";
        dragon.description = "エンダードラゴンを1体討伐する";
        dragon.type = QuestType.KILL;
        dragon.target = "minecraft:ender_dragon";
        dragon.amount = 1;
        dragon.reward = 25;
        dragon.requiredCompleted = 30;

        QuestDefinition collect = new QuestDefinition();
        collect.id = "collect_iron";
        collect.title = "収集：鉄インゴット";
        collect.description = "鉄インゴットを集めてギルドに納品する";
        collect.type = QuestType.COLLECT;
        collect.target = "minecraft:iron_ingot";
        collect.amount = 32;
        collect.amountMin = 16;
        collect.amountMax = 48;
        collect.reward = 8;
        collect.repeatable = true;
        collect.cooldownDays = 2;

        return new QuestDefinition[]{undead, monsters, nether, illager, ender, collect, explore, biome, structure, boss, dragon};
    }
}
