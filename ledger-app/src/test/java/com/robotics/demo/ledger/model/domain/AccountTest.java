package com.robotics.demo.ledger.model.domain;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    private static Account accountWithBalance(long cents) {
        Instant now = Instant.now();
        return Account.of(1L, 7L, new Money(cents, EUR), Account.AccountStatus.ACTIVE, 0, now, now);
    }

    @Test
    void depositIncreasesTheBalance() {
        Account account = accountWithBalance(1000);

        account.apply(new Money(500, EUR));

        assertThat(account.getBalance()).isEqualTo(new Money(1500, EUR));
    }

    @Test
    void withdrawalDecreasesTheBalanceDownToZero() {
        Account account = accountWithBalance(1000);

        account.apply(new Money(-1000, EUR));

        assertThat(account.getBalance()).isEqualTo(Money.zero(EUR));
    }

    @Test
    void overdraftIsRejectedAndTheBalanceStaysUntouched() {
        Account account = accountWithBalance(1000);

        assertThatThrownBy(() -> account.apply(new Money(-1001, EUR)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS);
        assertThat(account.getBalance()).isEqualTo(new Money(1000, EUR));
    }

    @Test
    void anotherCurrencyIsRejectedAndTheBalanceStaysUntouched() {
        Account account = accountWithBalance(1000);

        assertThatThrownBy(() -> account.apply(new Money(100, USD)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.CURRENCY_MISMATCH);
        assertThat(account.getBalance()).isEqualTo(new Money(1000, EUR));
    }

    @Test
    void frozenAccountRejectsAnyChange() {
        Instant now = Instant.now();
        Account account = Account.of(1L, 7L, new Money(1000, EUR), Account.AccountStatus.FROZEN, 0, now, now);

        assertThatThrownBy(() -> account.apply(new Money(100, EUR)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE);
        assertThat(account.getBalance()).isEqualTo(new Money(1000, EUR));
    }

    @Test
    void nextSeqContinuesFromTheLastSequenceNumber() {
        Instant now = Instant.now();
        Account account = Account.of(1L, 7L, new Money(0, EUR), Account.AccountStatus.ACTIVE, 41, now, now);

        assertThat(account.nextSeq()).isEqualTo(42);
        assertThat(account.nextSeq()).isEqualTo(43);
        assertThat(account.getLastSeq()).isEqualTo(43);
    }

    @Test
    void freezingBlocksTheAccount() {
        Account account = accountWithBalance(1000);

        account.freeze();

        assertThat(account.getStatus()).isEqualTo(Account.AccountStatus.FROZEN);
        assertThatThrownBy(() -> account.apply(new Money(100, EUR)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE);
    }
}
