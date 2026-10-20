package com.robotics.demo.ledger.model.domain;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import lombok.Getter;

import java.time.Instant;
import java.util.Currency;

@Getter
public class Account {

    private final Long id;
    private final Long ownerId;
    private Money balance;
    private AccountStatus status;
    private long lastSeq;
    private final Instant createdAt;
    private Instant updatedAt;

    public enum AccountStatus {
        ACTIVE,
        FROZEN,
        DISABLED
    }

    private Account(Long id, Long ownerId, Money balance, AccountStatus status, long lastSeq, Instant createdAt,
                    Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.balance = balance;
        this.status = status;
        this.lastSeq = lastSeq;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Account openFor(Long ownerId) {
        Instant now = Instant.now();
        return new Account(null, ownerId, Money.zero(Money.DEFAULT_CURRENCY), AccountStatus.ACTIVE, 0, now, now);
    }

    public static Account of(Long id, Long ownerId, Money balance, AccountStatus status, long lastSeq,
                             Instant createdAt, Instant updatedAt) {
        return new Account(id, ownerId, balance, status, lastSeq, createdAt, updatedAt);
    }

    public void apply(Money change) {
        if (status != AccountStatus.ACTIVE) {
            throw new LedgerServiceException(ErrorClassification.ACCOUNT_NOT_ACTIVE);
        }
        if (!getCurrency().equals(change.currency())) {
            throw new LedgerServiceException(ErrorClassification.CURRENCY_MISMATCH);
        }
        Money newBalance = balance.plus(change);
        if (newBalance.isNegative()) {
            throw new LedgerServiceException(ErrorClassification.INSUFFICIENT_FUNDS);
        }
        balance = newBalance;
        updatedAt = Instant.now();
    }

    public void freeze() {
        status = AccountStatus.FROZEN;
        updatedAt = Instant.now();
    }

    public void advanceSeq() {
        lastSeq++;
    }

    public Currency getCurrency() {
        return balance.currency();
    }
}
