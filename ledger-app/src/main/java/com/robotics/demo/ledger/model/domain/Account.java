package com.robotics.demo.ledger.model.domain;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import lombok.Getter;

import java.time.Instant;
import java.util.Currency;

@Getter
public class Account {

    private Long id;
    private Long ownerId;
    private Money balance;
    private AccountStatus status;
    private long lastSeq;
    private Instant createdAt;
    private Instant updatedAt;

    public enum AccountStatus {
        ACTIVE,
        FROZEN,
        DISABLED
    }

    public Account(Long id) {
        this.id = id;
        this.balance = Money.zero(Money.DEFAULT_CURRENCY);
        this.status = AccountStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static Account openFor(Long ownerId) {
        Account account = new Account((Long) null);
        account.ownerId = ownerId;
        return account;
    }

    public static Account of(Long id, Long ownerId, Money balance, AccountStatus status, long lastSeq,
                             Instant createdAt, Instant updatedAt) {
        Account account = new Account(id);
        account.ownerId = ownerId;
        account.balance = balance;
        account.status = status;
        account.lastSeq = lastSeq;
        account.createdAt = createdAt;
        account.updatedAt = updatedAt;
        return account;
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

    public long nextSeq() {
        lastSeq++;
        return lastSeq;
    }

    public Currency getCurrency() {
        return balance.currency();
    }
}
