package com.robotics.demo.ledger.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.util.Currency;

public record Money(long amount, Currency currency) {

    public static Money of(BigDecimal amount, Currency currency) {
        BigDecimal plainAmount = amount.movePointRight(currency.getDefaultFractionDigits());
        return new Money(plainAmount.longValueExact(), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amount), currency);
    }

    public boolean isPositive() {
        return amount > 0;
    }

    public boolean isNegative() {
        return amount < 0;
    }

    @JsonValue
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(amount, currency.getDefaultFractionDigits());
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch");
        }
    }
}
