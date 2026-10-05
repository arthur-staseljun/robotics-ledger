package com.teya.demo.ledger.persistence.inmemory;

import com.teya.demo.ledger.model.Account;
import com.teya.demo.ledger.model.Money;
import com.teya.demo.ledger.persistence.AccountRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentMap<Long, Account> store = new ConcurrentHashMap<>();
    private final AtomicLong identity = new AtomicLong();

    @Override
    public Account save(Account account) {
        Account stored = account.copy();
        if (stored.getId() == null) {
            stored.setId(identity.incrementAndGet());
        }
        store.put(stored.getId(), stored);
        return stored.copy();
    }

    @Override
    public Optional<Account> findById(Long id) {
        return Optional.ofNullable(store.get(id)).map(Account::copy);
    }

    @Override
    public Optional<Money> applyBalanceChange(Long accountId, Money amount) {
        AtomicReference<Money> newBalanceRef = new AtomicReference<>();
        store.computeIfPresent(accountId, (id, account) -> {
            Money newBalance = account.getBalance().plus(amount);
            if (newBalance.isNegative()) {
                return account;
            }
            account.setBalance(newBalance);
            account.setUpdatedAt(Instant.now());
            newBalanceRef.set(newBalance);
            return account;
        });
        return Optional.ofNullable(newBalanceRef.get());
    }
}
