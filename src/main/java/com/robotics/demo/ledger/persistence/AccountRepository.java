package com.robotics.demo.ledger.persistence;

import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;

import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(Long id);

    Optional<Money> applyBalanceChange(Long accountId, Money amount);
}
