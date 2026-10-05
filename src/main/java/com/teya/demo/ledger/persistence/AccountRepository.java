package com.teya.demo.ledger.persistence;

import com.teya.demo.ledger.model.Account;
import com.teya.demo.ledger.model.Money;

import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(Long id);

    Optional<Money> applyBalanceChange(Long accountId, Money amount);
}
