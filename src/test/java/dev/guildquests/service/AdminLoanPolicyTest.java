package dev.guildquests.service;

import dev.guildquests.data.PlayerGuildData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AdminLoanPolicyTest {
    @Test public void imposedLoanChangesOnlyConsumerLoanAndSchedulesInterest() {
        var data = new PlayerGuildData(); data.coins = 100; data.debt = 250;
        AdminLoanPolicy.add(data, 2000, 123); AdminLoanPolicy.add(data, 50, 999);
        assertEquals(2050, data.loan); assertEquals(123, data.nextLoanInterestAt);
        assertEquals(100, data.coins); assertEquals(250, data.debt);
    }
    @Test public void additionSaturatesWithoutWrappingNegative() {
        var data = new PlayerGuildData(); data.loan = Long.MAX_VALUE - 2;
        AdminLoanPolicy.add(data, 10, 1); assertEquals(Long.MAX_VALUE, data.loan);
    }
    @Test public void setAndClearResetInterestAndMiningCreditOnlyAtZero() {
        var data = new PlayerGuildData(); data.debt = 10; data.loanCreditMillis = 456;
        AdminLoanPolicy.set(data, 999, 100); assertEquals(456, data.loanCreditMillis);
        AdminLoanPolicy.clear(data); assertEquals(0, data.loan); assertEquals(0, data.loanCreditMillis);
        assertEquals(0, data.nextLoanInterestAt); assertEquals(10, data.debt);
    }
    @Test public void negativeAmountIsRejectedWithoutMutation() {
        var data = new PlayerGuildData(); data.loan = 7;
        assertThrows(IllegalArgumentException.class, () -> AdminLoanPolicy.add(data, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> AdminLoanPolicy.set(data, -1, 1)); assertEquals(7, data.loan);
    }
    @Test public void manualLockSurvivesSaveEvenWhenThereIsNoDebt() {
        var data = new PlayerGuildData(); AdminLoanPolicy.delinquent(data, true);
        var loaded = PlayerGuildData.fromNbt(data.toNbt(null), null);
        assertTrue(loaded.delinquent); assertTrue(loaded.adminDelinquent);
    }
    @Test public void repayingRiboDebtDoesNotOverrideAdministratorLock() {
        var data = new PlayerGuildData(); data.coins = 50; data.debt = 20;
        AdminLoanPolicy.delinquent(data, true); GuildDebtPolicy.repay(data, 20);
        assertEquals(0, data.debt); assertTrue(data.delinquent);
        AdminLoanPolicy.delinquent(data, false); assertFalse(data.delinquent); assertFalse(data.adminDelinquent);
    }
    @Test public void unlockingDoesNotForgiveEitherDebtBalance() {
        var data = new PlayerGuildData(); data.loan = 1000; data.debt = 500;
        AdminLoanPolicy.delinquent(data, true); AdminLoanPolicy.delinquent(data, false);
        assertEquals(1000, data.loan); assertEquals(500, data.debt); assertFalse(data.delinquent);
    }
    @Test public void legacyConsumerLoanDelinquencySurvivesSave() {
        var data = new PlayerGuildData(); data.loan = 500; data.delinquent = true;
        assertTrue(PlayerGuildData.fromNbt(data.toNbt(null), null).delinquent);
    }
}
