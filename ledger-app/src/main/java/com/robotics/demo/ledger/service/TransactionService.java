package com.robotics.demo.ledger.service;


import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.persistence.jpa.TransactionRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    @Transactional(readOnly = true)
    public List<Transaction> getAllTransactions(Long accountId) {
        accountService.getExistingAccount(accountId);
        return transactionRepository.findByAccountIdOrderBySeqIdDesc(accountId).stream()
                .map(TransactionService::toDomain)
                .toList();
    }

    public Transaction save(Transaction transaction) {
        TransactionEntity entity = TransactionEntity.builder()
                .accountId(transaction.getAccountId())
                .transferId(transaction.getTransferId())
                .seqId(transaction.getSeqId())
                .transactionType(transaction.getTransactionType())
                .amount(transaction.getAmount().amount())
                .balanceAfter(transaction.getBalanceAfter().amount())
                .currency(transaction.getAmount().currency().getCurrencyCode())
                .createdAt(Instant.now())
                .build();
        return toDomain(transactionRepository.save(entity));
    }

    private static Transaction toDomain(TransactionEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        return Transaction.of(entity.getAccountId(), entity.getTransferId(), entity.getSeqId(),
                entity.getTransactionType(), new Money(entity.getAmount(), currency),
                new Money(entity.getBalanceAfter(), currency), entity.getCreatedAt());
    }
}
