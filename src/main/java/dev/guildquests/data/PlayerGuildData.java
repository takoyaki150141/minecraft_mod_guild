package dev.guildquests.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlayerGuildData {
    public long coins = 0;
    public int completedTotal = 0;
    /** In-game days this player was online for; averaged to pick the emergency-boss tier. */
    public int playedDays = 0;
    /** In-game day number already counted into {@link #playedDays}; -1 = never counted. */
    public long lastPlayedDay = -1;
    /** Last known player name; used for the ranking when the player is offline. */
    public String playerName = "";
    /** Payment mode for Create shop purchases: RIBO / DEBIT / DISABLED. */
    public String paymentMode = "DEBIT";
    /** Outstanding revolving-credit (ribo) debt in coins, including accrued interest. */
    public long debt = 0;
    /** True when a scheduled ribo collection failed; locks guild features until debt == 0. */
    public boolean delinquent = false;
    /** Explicit administrator lock, retained until the administrator turns it off. */
    public boolean adminDelinquent = false;
    /** Epoch millis of the next scheduled ribo collection. 0 when no debt. */
    public long nextRiboDueAt = 0;
    /** Outstanding consumer-finance loan (principal + accrued interest); repaid manually. */
    public long loan = 0;
    /** Epoch millis of the next loan interest charge. 0 when no loan. */
    public long nextLoanInterestAt = 0;
    /** Mining-based debt repayment credit, in thousandths of a coin (0.001 coin = 1). */
    public long loanCreditMillis = 0;
    public final List<ActiveQuest> activeQuests = new ArrayList<>();
    public final Set<String> completedQuestIds = new HashSet<>();
    /** Board day (floor(dayTime/24000)) that claimedExploreQuestIds belongs to; -1 when unset. */
    public long claimedBoardDay = -1;
    /** EXPLORE quest ids already claimed on claimedBoardDay; blocked from re-acceptance until the next draw. */
    public final Set<String> claimedExploreQuestIds = new HashSet<>();
    /** Auction-house mailbox: items owed to this player (winnings, refunds, expired listings). */
    public final List<ItemStack> mailbox = new ArrayList<>();
    /** Mailbox entries whose item tag could not be decoded; saved verbatim for recovery. */
    public final List<CompoundTag> unreadableMailbox = new ArrayList<>();
    /** COLLECT quest id -> board day it was last claimed; enforces the per-quest cooldown. */
    public final java.util.Map<String, Long> collectClaimedDay = new java.util.HashMap<>();
    /** Land protection switches chosen in game; -1 means "use the server defaults". */
    public int landRuleMask = dev.guildquests.service.LandRules.UNSET;

    /** Players allowed to build and interact on this player's land. */
    public final java.util.Set<java.util.UUID> landTrusted = new java.util.HashSet<>();

    public ActiveQuest findActive(String questId) {
        for (ActiveQuest active : activeQuests) {
            if (active.questId.equals(questId)) return active;
        }
        return null;
    }

    /** Records an explore-quest claim for the given board day, resetting when the day changes. */
    public void markExploreClaimed(long boardDay, String questId) {
        if (claimedBoardDay != boardDay) {
            claimedBoardDay = boardDay;
            claimedExploreQuestIds.clear();
        }
        claimedExploreQuestIds.add(questId);
    }

    /** True when this explore quest was already claimed on the given board day. */
    public boolean exploreClaimedToday(long boardDay, String questId) {
        return claimedBoardDay == boardDay && claimedExploreQuestIds.contains(questId);
    }

    /**
     * True when a COLLECT quest is still on cooldown for the given board day.
     * {@code cooldownDays <= 0} disables the limit.
     */
    public boolean collectOnCooldown(long boardDay, String questId, int cooldownDays) {
        if (cooldownDays <= 0) return false;
        Long last = collectClaimedDay.get(questId);
        return last != null && boardDay - last < cooldownDays;
    }

    /** Records a COLLECT claim on the given board day. */
    public void markCollectClaimed(long boardDay, String questId) {
        collectClaimedDay.put(questId, boardDay);
    }

    /**
     * Counts the given in-game day for this player. Only a day that immediately follows the
     * last counted one is added, so days spent offline are never counted. Returns true when
     * {@link #playedDays} changed.
     */
    public boolean countPlayedDay(long day) {
        if (lastPlayedDay == day) return false;
        boolean counted = lastPlayedDay < 0 || day == lastPlayedDay + 1;
        lastPlayedDay = day;
        if (!counted) return false;
        playedDays++;
        return true;
    }

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        nbt.putLong("Coins", coins);
        nbt.putInt("CompletedTotal", completedTotal);
        nbt.putInt("PlayedDays", playedDays);
        nbt.putLong("LastPlayedDay", lastPlayedDay);
        nbt.putString("PlayerName", playerName == null ? "" : playerName);
        nbt.putString("PaymentMode", paymentMode == null ? "DEBIT" : paymentMode);
        nbt.putLong("Debt", debt);
        nbt.putBoolean("Delinquent", delinquent);
        nbt.putBoolean("AdminDelinquent", adminDelinquent);
        nbt.putLong("NextRiboDueAt", nextRiboDueAt);
        nbt.putLong("Loan", loan);
        nbt.putLong("NextLoanInterestAt", nextLoanInterestAt);
        nbt.putLong("LoanCreditMillis", loanCreditMillis);
        nbt.putInt("LandRuleMask", landRuleMask);
        ListTag trustedList = new ListTag();
        for (java.util.UUID id : landTrusted) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            trustedList.add(entry);
        }
        nbt.put("LandTrusted", trustedList);

        ListTag activeList = new ListTag();
        for (ActiveQuest active : activeQuests) activeList.add(active.toNbt());
        nbt.put("ActiveQuests", activeList);

        ListTag completedList = new ListTag();
        for (String id : completedQuestIds) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", id);
            completedList.add(entry);
        }
        nbt.put("CompletedQuestIds", completedList);

        nbt.putLong("ClaimedBoardDay", claimedBoardDay);
        ListTag claimedList = new ListTag();
        for (String id : claimedExploreQuestIds) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", id);
            claimedList.add(entry);
        }
        nbt.put("ClaimedExploreQuestIds", claimedList);

        ListTag mailboxList = new ListTag();
        for (ItemStack stack : mailbox) {
            if (stack != null && !stack.isEmpty()) mailboxList.add(stack.save(registries, new CompoundTag()));
        }
        for (CompoundTag raw : unreadableMailbox) {
            mailboxList.add(raw.copy());
        }
        nbt.put("AuctionMailbox", mailboxList);

        CompoundTag collectNbt = new CompoundTag();
        for (java.util.Map.Entry<String, Long> entry : collectClaimedDay.entrySet()) {
            collectNbt.putLong(entry.getKey(), entry.getValue());
        }
        nbt.put("CollectClaimedDays", collectNbt);
        return nbt;
    }

    public static PlayerGuildData fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        PlayerGuildData data = new PlayerGuildData();
        data.coins = Math.max(0L, nbt.getLong("Coins"));
        data.completedTotal = Math.max(0, nbt.getInt("CompletedTotal"));
        data.playedDays = Math.max(0, nbt.getInt("PlayedDays"));
        // Absent on old saves: -1 so the next tick starts counting from the current day.
        data.lastPlayedDay = nbt.contains("LastPlayedDay") ? nbt.getLong("LastPlayedDay") : -1;
        data.playerName = nbt.getString("PlayerName");
        data.paymentMode = nbt.getString("PaymentMode");
        if (data.paymentMode.isBlank()) data.paymentMode = "DEBIT";
        data.debt = Math.max(0L, nbt.getLong("Debt"));
        data.adminDelinquent = nbt.getBoolean("AdminDelinquent");
        data.delinquent = nbt.getBoolean("Delinquent") || data.adminDelinquent;
        data.nextRiboDueAt = Math.max(0L, nbt.getLong("NextRiboDueAt"));
        if (data.debt <= 0) {
            if (!data.adminDelinquent && nbt.getLong("Loan") <= 0) data.delinquent = false;
            data.nextRiboDueAt = 0;
        }
        data.loan = Math.max(0L, nbt.getLong("Loan"));
        data.nextLoanInterestAt = Math.max(0L, nbt.getLong("NextLoanInterestAt"));
        data.landRuleMask = nbt.contains("LandRuleMask", Tag.TAG_INT)
                ? (nbt.getInt("LandRuleMask") < 0 ? dev.guildquests.service.LandRules.UNSET
                        : nbt.getInt("LandRuleMask") & dev.guildquests.service.LandRules.ALL)
                : dev.guildquests.service.LandRules.UNSET;
        ListTag trustedList = nbt.getList("LandTrusted", Tag.TAG_COMPOUND);
        for (int i = 0; i < trustedList.size(); i++) {
            CompoundTag entry = trustedList.getCompound(i);
            if (entry.hasUUID("Id")) data.landTrusted.add(entry.getUUID("Id"));
        }
        if (data.loan <= 0) {
            data.nextLoanInterestAt = 0;
        }
        data.loanCreditMillis = Math.max(0L, nbt.getLong("LoanCreditMillis"));
        if (data.loan <= 0 && data.debt <= 0) {
            data.loanCreditMillis = 0;
        }

        ListTag activeList = nbt.getList("ActiveQuests", Tag.TAG_COMPOUND);
        for (int i = 0; i < activeList.size(); i++) {
            ActiveQuest active = ActiveQuest.fromNbt(activeList.getCompound(i));
            if (!active.questId.isBlank()) data.activeQuests.add(active);
        }

        ListTag completedList = nbt.getList("CompletedQuestIds", Tag.TAG_COMPOUND);
        for (int i = 0; i < completedList.size(); i++) {
            String id = completedList.getCompound(i).getString("Id");
            if (!id.isBlank()) data.completedQuestIds.add(id);
        }

        // Absent on old saves: keep the -1 default so nothing is treated as claimed.
        if (nbt.contains("ClaimedBoardDay")) data.claimedBoardDay = nbt.getLong("ClaimedBoardDay");
        ListTag claimedList = nbt.getList("ClaimedExploreQuestIds", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimedList.size(); i++) {
            String id = claimedList.getCompound(i).getString("Id");
            if (!id.isBlank()) data.claimedExploreQuestIds.add(id);
        }

        ListTag mailboxList = nbt.getList("AuctionMailbox", Tag.TAG_COMPOUND);
        for (int i = 0; i < mailboxList.size(); i++) {
            CompoundTag raw = mailboxList.getCompound(i);
            ItemStack stack = ItemStack.parseOptional(registries, raw);
            if (!stack.isEmpty()) {
                data.mailbox.add(stack);
            } else if (!raw.isEmpty()) {
                data.unreadableMailbox.add(raw.copy());
                dev.guildquests.GuildQuestsMod.LOGGER.warn(
                        "[Auction] Mailbox item could not be decoded ({}); kept raw for recovery",
                        raw.getString("id"));
            }
        }

        if (nbt.contains("CollectClaimedDays", Tag.TAG_COMPOUND)) {
            CompoundTag collectNbt = nbt.getCompound("CollectClaimedDays");
            for (String key : collectNbt.getAllKeys()) {
                data.collectClaimedDay.put(key, collectNbt.getLong(key));
            }
        }
        return data;
    }
}
