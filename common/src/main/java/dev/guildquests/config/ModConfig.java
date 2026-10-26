package dev.guildquests.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModConfig {
    /** Maximum number of active quests a player may have. */
    public int maxActiveQuests = 5;

    /** Check explore quests every N server ticks. 20 ticks = about 1 second. */
    public int exploreCheckIntervalTicks = 20;

    /** Minimum number of quests drawn for the daily board. Clamped to 1..9. */
    public int dailyQuestCountMin = 1;

    /** Maximum number of quests drawn for the daily board. Clamped to 1..9. */
    public int dailyQuestCountMax = 4;

    /** Default half-size (blocks) of the random coordinate box when a quest sets coordRange = 0. */
    public int exploreRandomRange = 3000;

    /** Emergency boss: allow the periodic spawn event. */
    public boolean bossEventEnabled = true;

    /** Real minutes between boss event rolls; counts only while a player is online. */
    public int bossEventIntervalMinutes = 30;

    /** Percent chance per roll that the boss actually spawns. Clamped to 0..100. */
    public int bossEventChancePercent = 20;

    /** Total coins split among players by damage share when the boss dies. */
    public long bossEventReward = 100;

    /** Max health of the emergency boss (normal tier). */
    public double bossEventHealth = 300;

    /** Spawn weights for [weak, normal, strong, elite]. Zero weights disable that tier. */
    public int[] bossTierWeights = {20, 35, 28, 12, 5};

    /** Chance that killing the weakest boss revives it as the strongest. */
    public double bossWeakestPromoteChance = 0.01;

    /** Coin price to buy boss protection, ordered weakest, weak, normal, strong, elite. */
    public int[] bossProtectionPrices = {100, 100, 100, 100, 100};

    /** Seconds of boss protection granted after dying during a boss fight (0 disables). */
    public int bossProtectionSeconds = 300;

    /** Blocks; the protection effect is removed after leaving every boss by this distance. */
    public int bossProtectionReleaseRange = 48;

    /** Allow paying Create shops (table cloth price tags set to guild coins) from the virtual balance. */
    public boolean createIntegrationEnabled = true;

    /** Bank transaction fee in percent, charged on deposit and withdraw. 0 disables fees. */
    public double bankFeePercent = 5.0;

    /** Ribo (revolving credit): fixed amount collected from the balance each interval. */
    public long riboInstallment = 500;

    /** Interest percent applied to outstanding ribo debt on every collection cycle. */
    public double riboInterestPercent = 2.0;

    /** Minutes between scheduled ribo collections. */
    public int riboIntervalMinutes = 60;

    /** Auction house: percent fee taken from the seller's proceeds on a successful sale. */
    public double auctionFeePercent = 5.0;

    /** Minutes an auction listing stays active before it expires and settles. */
    public int auctionDurationMinutes = 60;

    /** Maximum concurrent active auction listings per player. Clamped to 1..45. */
    public int auctionMaxListingsPerPlayer = 5;

    /** Minimum increment a new bid must add over the current highest bid. */
    public long auctionBidStep = 1;

    /** Consumer finance (cash loans): allow borrowing against a rank-based credit limit. */
    public boolean consumerLoanEnabled = true;

    /**
     * Legacy switch kept in memory only (transient, never written to config.json).
     * Repayment always allows the balance first, and delinquency force-executes it.
     */
    public transient boolean miningDebtRepaymentOnly = false;

    /** Interest percent applied to an outstanding loan on every loan interval. */
    public double consumerLoanInterestPercent = 5.0;

    /** Minutes between loan interest charges. */
    public int consumerLoanIntervalMinutes = 60;

    /** Borrow limit per guild rank (rank name -> max outstanding loan). */
    public Map<String, Long> consumerLoanLimits = new LinkedHashMap<>();

    /**
     * Mining-based debt repayment: block id -> coin value credited per block mined.
     * Applies while a consumer loan or FX/ribo debt remains; the loan is repaid first, then debt.
     */
    public Map<String, Double> loanMiningValues = new LinkedHashMap<>();

    /** Rank name -> required total completions. Insertion order is used for display. */
    public Map<String, Integer> rankThresholds = new LinkedHashMap<>();

    public ModConfig() {
        rankThresholds.put("E", 0);
        rankThresholds.put("D", 5);
        rankThresholds.put("C", 15);
        rankThresholds.put("B", 30);
        rankThresholds.put("A", 50);
        rankThresholds.put("S", 80);

        consumerLoanLimits.put("E", 1_000L);
        consumerLoanLimits.put("D", 3_000L);
        consumerLoanLimits.put("C", 8_000L);
        consumerLoanLimits.put("B", 20_000L);
        consumerLoanLimits.put("A", 50_000L);
        consumerLoanLimits.put("S", 100_000L);

        loanMiningValues.put("minecraft:stone", 0.01);
        loanMiningValues.put("minecraft:cobblestone", 0.01);
        loanMiningValues.put("minecraft:coal_ore", 0.2);
        loanMiningValues.put("minecraft:deepslate_coal_ore", 0.2);
        loanMiningValues.put("minecraft:iron_ore", 0.3);
        loanMiningValues.put("minecraft:deepslate_iron_ore", 0.3);
        loanMiningValues.put("minecraft:gold_ore", 0.8);
        loanMiningValues.put("minecraft:deepslate_gold_ore", 0.8);
        loanMiningValues.put("minecraft:diamond_ore", 2.0);
        loanMiningValues.put("minecraft:deepslate_diamond_ore", 2.0);
    }
}
