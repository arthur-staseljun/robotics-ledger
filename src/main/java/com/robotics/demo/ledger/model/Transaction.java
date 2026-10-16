package com.robotics.demo.ledger.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
public class Transaction {

    private final Long accountId;
    private final Long transferId;
    private final long seqId;
    private final TransactionType transactionType;
    private final Money amount;
    private final Money balanceAfter;
    @Setter
    private Instant createdAt;

    public Transaction(Long accountId, Long transferId, long seqId, Money amount, Money balanceAfter) {
        this(accountId, transferId, seqId, amount.isPositive() ? TransactionType.DEPOSIT : TransactionType.WITHDRAWAL,
                amount, balanceAfter, null);
    }

    private Transaction(Long accountId, Long transferId, long seqId, TransactionType transactionType, Money amount,
                        Money balanceAfter, Instant createdAt) {
        this.accountId = accountId;
        this.transferId = transferId;
        this.seqId = seqId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }

    public static Transaction of(Long accountId, Long transferId, long seqId, TransactionType transactionType,
                                 Money amount, Money balanceAfter, Instant createdAt) {
        return new Transaction(accountId, transferId, seqId, transactionType, amount, balanceAfter, createdAt);
    }

    public enum TransactionType {
        DEPOSIT,
        WITHDRAWAL
    }
}
