package com.robotics.demo.ledger.persistence.inmemory;

import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.persistence.TransactionRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final ConcurrentMap<Long, Deque<Transaction>> store = new ConcurrentHashMap<>();

    @Override
    public Transaction save(Transaction transaction) {
        Transaction stored = transaction.copy();
        stored.setTransactionId(UUID.randomUUID());
        store.compute(stored.getAccountId(), (id, transactions) -> {
            if (transactions == null) {
                transactions = new ConcurrentLinkedDeque<>();
            }
            stored.setCreatedAt(Instant.now());
            transactions.addFirst(stored);
            return transactions;
        });
        return stored.copy();
    }

    @Override
    public List<Transaction> findAll(Long accountId) {
        Deque<Transaction> transactions = store.get(accountId);
        return transactions == null ? Collections.emptyList() : transactions.stream().map(Transaction::copy).toList();
    }
}
