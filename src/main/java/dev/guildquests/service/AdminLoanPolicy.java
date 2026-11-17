package dev.guildquests.service;

import dev.guildquests.data.PlayerGuildData;

/** Shared mutations for the command and the chest menu; consumer loans remain separate from ribo debt. */
public final class AdminLoanPolicy {
    private AdminLoanPolicy() { }
    public static void add(PlayerGuildData data, long amount, long dueAt) {
        if (amount < 0) throw new IllegalArgumentException("金額は0以上です。");
        set(data, data.loan > Long.MAX_VALUE - amount ? Long.MAX_VALUE : data.loan + amount, dueAt);
    }
    public static void set(PlayerGuildData data, long amount, long dueAt) {
        if (amount < 0) throw new IllegalArgumentException("金額は0以上です。");
        data.loan = amount;
        if (amount == 0) { data.loanCreditMillis = 0; data.nextLoanInterestAt = 0; }
        else if (data.nextLoanInterestAt <= 0) data.nextLoanInterestAt = dueAt;
    }
    public static void clear(PlayerGuildData data) { set(data, 0, 0); }
    public static void delinquent(PlayerGuildData data, boolean enabled) {
        data.adminDelinquent = enabled; data.delinquent = enabled;
    }
}
