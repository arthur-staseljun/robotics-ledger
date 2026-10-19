package com.robotics.demo.ledger.model.response;

import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.model.domain.Transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(Long accountId, Long transferId, long seqId, TransactionType transactionType,
                                  BigDecimal amount, BigDecimal balanceAfter, Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(transaction.getAccountId(), transaction.getTransferId(),
                transaction.getSeqId(), transaction.getTransactionType(), transaction.getAmount().toDecimal(),
                transaction.getBalanceAfter().toDecimal(), transaction.getCreatedAt());
    }
}
