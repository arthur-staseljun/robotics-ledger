package com.robotics.demo.ledger.persistence.jpa.mapper;

import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;

import java.time.Instant;
import java.util.Currency;

public final class TransactionMapper {

    private TransactionMapper() {
    }

    public static TransactionEntity toEntity(Transaction transaction) {
        return TransactionEntity.builder()
                .accountId(transaction.getAccountId())
                .transferId(transaction.getTransferId())
                .seqId(transaction.getSeqId())
                .transactionType(transaction.getTransactionType())
                .amount(transaction.getAmount().amount())
                .balanceAfter(transaction.getBalanceAfter().amount())
                .currency(transaction.getAmount().currency().getCurrencyCode())
                .createdAt(Instant.now())
                .build();
    }

    public static Transaction toDomain(TransactionEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        return Transaction.of(entity.getAccountId(), entity.getTransferId(), entity.getSeqId(),
                entity.getTransactionType(), new Money(entity.getAmount(), currency),
                new Money(entity.getBalanceAfter(), currency), entity.getCreatedAt());
    }
}
