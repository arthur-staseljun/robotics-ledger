package com.robotics.demo.ledger.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Currency;

@Getter
public class Account {
    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("EUR");

    @Setter
    private Long id;
    @Setter
    private Money balance;
    private Instant createdAt;
    @Setter
    private Instant updatedAt;

    public Account() {
        this((Long) null);
    }

    public Account(Long id) {
        this.id = id;
        this.balance = Money.zero(DEFAULT_CURRENCY);
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    private Account(Account other) {
        this.id = other.id;
        this.balance = other.balance;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
    }

    public Currency getCurrency() {
        return balance.currency();
    }

    public Account copy() {
        return new Account(this);
    }
}
