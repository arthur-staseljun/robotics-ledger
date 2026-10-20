package com.robotics.demo.ledger.model.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");

    @ParameterizedTest
    @CsvSource({"10.50, 1050", "10.5, 1050", "10, 1000", "0.01, 1", "0, 0", "-3.25, -325", "1E+2, 10000"})
    void anAmountIsKeptInMinorUnitsOfTheCurrency(String amount, long minorUnits) {
        assertThat(Money.of(new BigDecimal(amount), EUR).amount()).isEqualTo(minorUnits);
    }

    @Test
    void aCurrencyWithoutMinorUnitsKeepsWholeUnits() {
        assertThat(Money.of(new BigDecimal("100"), JPY).amount()).isEqualTo(100);
    }

    @ParameterizedTest
    @CsvSource({"10.005, EUR", "0.001, EUR", "100.5, JPY"})
    void anAmountMoreAccurateThanTheCurrencyAllowsIsRejected(String amount, String currency) {
        assertThatThrownBy(() -> Money.of(new BigDecimal(amount), Currency.getInstance(currency)))
                .isInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest
    @CsvSource({"92233720368547758.08", "99999999999999999999", "1E+30"})
    void anAmountBeyondWhatTheLedgerCanHoldIsRejected(String amount) {
        assertThatThrownBy(() -> Money.of(new BigDecimal(amount), EUR)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void theLargestAmountTheLedgerCanHoldIsAccepted() {
        assertThat(Money.of(new BigDecimal("92233720368547758.07"), EUR).amount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void toDecimalGivesBackTheOriginalAmount() {
        assertThat(Money.of(new BigDecimal("12.30"), EUR).toDecimal()).isEqualByComparingTo("12.30");
        assertThat(Money.of(new BigDecimal("100"), JPY).toDecimal()).isEqualByComparingTo("100");
    }

    @Test
    void addingMoneyOfAnotherCurrencyIsRejected() {
        assertThatThrownBy(() -> new Money(100, EUR).plus(new Money(100, JPY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additionAndNegationAreExact() {
        assertThat(new Money(700, EUR).plus(new Money(-300, EUR))).isEqualTo(new Money(400, EUR));
        assertThat(new Money(700, EUR).negate()).isEqualTo(new Money(-700, EUR));
    }

    @Test
    void aSumBeyondWhatTheLedgerCanHoldIsNotWrappedAround() {
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, EUR).plus(new Money(1, EUR)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void signsAreReported() {
        assertThat(new Money(1, EUR).isPositive()).isTrue();
        assertThat(new Money(0, EUR).isPositive()).isFalse();
        assertThat(new Money(0, EUR).isNegative()).isFalse();
        assertThat(new Money(-1, EUR).isNegative()).isTrue();
    }
}
