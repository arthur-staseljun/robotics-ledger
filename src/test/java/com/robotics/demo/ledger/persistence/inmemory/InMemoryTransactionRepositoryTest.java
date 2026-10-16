package com.robotics.demo.ledger.persistence.inmemory;

import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTransactionRepositoryTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();

    @Test
    void saveAssignsTransactionIdAndCreatedAt() {
        Transaction transaction = new Transaction(1L, eur(1000), eur(1000));

        Transaction saved = repository.save(transaction);

        assertThat(saved.getTransactionId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void returnedTransactionsAreCopiesAndDoNotAffectStoredState() {
        Transaction saved = repository.save(new Transaction(1L, eur(1000), eur(1000)));

        saved.setTransactionId(UUID.randomUUID());
        repository.findAll(1L).get(0).setTransactionId(UUID.randomUUID());

        assertThat(repository.findAll(1L).get(0).getTransactionId()).isNotEqualTo(saved.getTransactionId());
        assertThat(repository.findAll(1L)).hasSize(1);
    }

    @Test
    void findAllReturnsEmptyListWhenAccountDoesNotExist() {
        assertThat(repository.findAll(404L)).isEmpty();
    }

    @Test
    void findAllIsolatesTransactionsPerAccount() {
        repository.save(new Transaction(1L, eur(1000), eur(1000)));
        repository.save(new Transaction(2L, eur(50000), eur(50000)));

        List<Transaction> account1History = repository.findAll(1L);

        assertThat(account1History).hasSize(1);
        assertThat(account1History.get(0).getAccountId()).isEqualTo(1L);
    }

    @Test
    void findAllReturnsNewestFirst() {
        Transaction first = repository.save(new Transaction(1L, eur(1000), eur(1000)));
        Transaction second = repository.save(new Transaction(1L, eur(2000), eur(3000)));
        Transaction third = repository.save(new Transaction(1L, eur(3000), eur(6000)));

        List<Transaction> history = repository.findAll(1L);

        assertThat(history).extracting(Transaction::getTransactionId)
                .containsExactly(third.getTransactionId(), second.getTransactionId(), first.getTransactionId());
    }

    @Test
    void saveUnderConcurrencyKeepsEveryTransactionWithAUniqueId() throws InterruptedException {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<UUID> savedIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                Transaction saved = repository.save(new Transaction(1L, eur(1), eur(1)));
                savedIds.add(saved.getTransactionId());
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(repository.findAll(1L)).hasSize(threads);
        assertThat(savedIds).hasSize(threads);
    }

    private static Money eur(long cents) {
        return new Money(cents, EUR);
    }
}
