package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.persistence.jpa.TransactionRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    @Transactional
    public Transaction createTransaction(Long accountId, Money money) {
        if (money.amount() == 0) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        Account account = accountService.getExistingAccountLocking(accountId);
        account.apply(money);
        accountService.save(account);
        return save(new Transaction(accountId, money, account.getBalance()));
    }

    @Transactional(readOnly = true)
    public List<Transaction> getAllTransactions(Long accountId) {
        accountService.getExistingAccount(accountId);
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(TransactionService::toDomain)
                .toList();
    }

    private Transaction save(Transaction transaction) {
        TransactionEntity entity = TransactionEntity.builder()
                .id(UUID.randomUUID())
                .accountId(transaction.getAccountId())
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
        return Transaction.of(entity.getId(), entity.getAccountId(), entity.getTransactionType(),
                new Money(entity.getAmount(), currency), new Money(entity.getBalanceAfter(), currency),
                entity.getCreatedAt());
    }
}
