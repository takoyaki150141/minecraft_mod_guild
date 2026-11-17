package dev.guildquests.service;

import dev.guildquests.data.PlayerGuildData;

/** Shared debt fields are consumed by the existing repayment, quest tribute and lock code. */
public final class GuildDebtPolicy {
    private GuildDebtPolicy() { }
    public static void addDebt(PlayerGuildData data, long amount, long dueAt, boolean overdue) {
        if (amount <= 0) return;
        data.debt = data.debt > Long.MAX_VALUE - amount ? Long.MAX_VALUE : data.debt + amount;
        if (data.nextRiboDueAt <= 0) data.nextRiboDueAt = dueAt;
        if (overdue) data.delinquent = true;
    }
    public static long repay(PlayerGuildData data, long requested) {
        long paid = Math.min(Math.max(0, requested), Math.min(data.coins, data.debt));
        data.coins -= paid; data.debt -= paid;
        if (data.debt <= 0) { data.debt = 0; data.delinquent = data.adminDelinquent; data.nextRiboDueAt = 0; }
        return paid;
    }
}
