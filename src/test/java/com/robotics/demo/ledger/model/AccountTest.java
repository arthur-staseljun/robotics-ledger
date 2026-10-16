package com.robotics.demo.ledger.model;

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
        return Account.of(1L, new Money(cents, EUR), now, now);
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
}
