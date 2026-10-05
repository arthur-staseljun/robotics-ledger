package com.teya.demo.ledger.persistence.inmemory;

import com.teya.demo.ledger.model.Account;
import com.teya.demo.ledger.model.Money;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAccountRepositoryTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final InMemoryAccountRepository repository = new InMemoryAccountRepository();

    @Test
    void saveAssignsUniqueSequentialIds() {
        long first = repository.save(new Account()).getId();
        long second = repository.save(new Account()).getId();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void saveThenFindByIdRoundTrips() {
        Account account = repository.save(new Account());

        assertThat(repository.findById(account.getId())).get()
                .usingRecursiveComparison().isEqualTo(account);
    }

    @Test
    void returnedAccountsAreCopiesAndDoNotAffectStoredState() {
        Account saved = repository.save(new Account());

        saved.setBalance(eur(99900));
        repository.findById(saved.getId()).orElseThrow().setBalance(eur(99900));

        assertThat(repository.findById(saved.getId()).orElseThrow().getBalance()).isEqualTo(eur(0));
    }

    @Test
    void findByIdReturnsEmptyWhenAccountDoesNotExist() {
        assertThat(repository.findById(404L)).isEmpty();
    }

    @Test
    void applyBalanceChangeAddsDeposit() {
        Account account = repository.save(new Account());

        Money newBalance = repository.applyBalanceChange(account.getId(), eur(10000)).orElseThrow();

        assertThat(newBalance).isEqualTo(eur(10000));
        assertThat(repository.findById(account.getId())).get().extracting(Account::getBalance)
                .isEqualTo(newBalance);
    }

    @Test
    void applyBalanceChangeSubtractsWithdrawal() {
        Account account = repository.save(new Account());
        repository.applyBalanceChange(account.getId(), eur(10000));

        Money newBalance = repository.applyBalanceChange(account.getId(), eur(-3000)).orElseThrow();

        assertThat(newBalance).isEqualTo(eur(7000));
    }

    @Test
    void applyBalanceChangeRejectsOverdraft() {
        Account account = repository.save(new Account());

        assertThat(repository.applyBalanceChange(account.getId(), eur(-100))).isEmpty();

        Money balanceAfterRejectedAttempt = repository.findById(account.getId()).orElseThrow().getBalance();
        assertThat(balanceAfterRejectedAttempt).isEqualTo(eur(0));
    }

    @Test
    void applyBalanceChangeReturnsEmptyWhenAccountDoesNotExist() {
        assertThat(repository.applyBalanceChange(404L, eur(1))).isEmpty();
    }

    @Test
    void applyBalanceChangeIsAtomicUnderConcurrency() throws InterruptedException {
        Account account = repository.save(new Account());

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Money> observedBalancesAfterEachCall = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                observedBalancesAfterEachCall.add(repository.applyBalanceChange(account.getId(), eur(1)).orElseThrow());
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        Money finalBalance = repository.findById(account.getId()).orElseThrow().getBalance();
        assertThat(finalBalance).isEqualTo(eur(threads));

        Set<Money> everyRunningBalanceFromOneToThreads = IntStream.rangeClosed(1, threads)
                .mapToObj(InMemoryAccountRepositoryTest::eur)
                .collect(Collectors.toSet());
        assertThat(observedBalancesAfterEachCall).isEqualTo(everyRunningBalanceFromOneToThreads);
    }

    private static Money eur(long cents) {
        return new Money(cents, EUR);
    }
}
