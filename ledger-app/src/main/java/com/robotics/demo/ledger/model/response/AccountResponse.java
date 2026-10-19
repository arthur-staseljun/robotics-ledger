package com.robotics.demo.ledger.model.response;

import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Account.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

public record AccountResponse(Long id, Long ownerId, BigDecimal balance, Currency currency, AccountStatus status,
                              long lastSeq, Instant createdAt, Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerId(), account.getBalance().toDecimal(),
                account.getCurrency(), account.getStatus(), account.getLastSeq(), account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
