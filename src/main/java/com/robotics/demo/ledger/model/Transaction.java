package com.robotics.demo.ledger.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class Transaction {
    @Setter
    private UUID transactionId;
    private Long accountId;
    private TransactionType transactionType;
    private Money amount;
    private Money balanceAfter;
    private Long transferId;
    @Setter
    private Instant createdAt;

    public enum TransactionType {
        DEPOSIT,
        WITHDRAWAL
    }

    public Transaction(Long accountId, Money amount, Money balanceAfter) {
        this(accountId, amount, balanceAfter, null);
    }

    public Transaction(Long accountId, Money amount, Money balanceAfter, Long transferId) {
        this.accountId = accountId;
        this.transferId = transferId;
        this.transactionType = amount.isPositive() ? TransactionType.DEPOSIT : TransactionType.WITHDRAWAL;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    private Transaction(Transaction other) {
        this.transactionId = other.transactionId;
        this.accountId = other.accountId;
        this.transactionType = other.transactionType;
        this.amount = other.amount;
        this.balanceAfter = other.balanceAfter;
        this.transferId = other.transferId;
        this.createdAt = other.createdAt;
    }

    public static Transaction of(UUID transactionId, Long accountId, TransactionType transactionType,
                                  Money amount, Money balanceAfter, Long transferId, Instant createdAt) {
        Transaction transaction = new Transaction(accountId, amount, balanceAfter, transferId);
        transaction.transactionId = transactionId;
        transaction.transactionType = transactionType;
        transaction.createdAt = createdAt;
        return transaction;
    }

    public Transaction copy() {
        return new Transaction(this);
    }
}
