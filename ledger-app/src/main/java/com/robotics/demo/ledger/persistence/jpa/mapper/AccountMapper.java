package com.robotics.demo.ledger.persistence.jpa.mapper;

import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.persistence.jpa.entity.AccountEntity;

import java.util.Currency;

public final class AccountMapper {

    private AccountMapper() {
    }

    public static AccountEntity toEntity(Account account) {
        return AccountEntity.builder()
                .id(account.getId())
                .ownerId(account.getOwnerId())
                .balance(account.getBalance().amount())
                .currency(account.getCurrency().getCurrencyCode())
                .status(account.getStatus())
                .lastSeq(account.getLastSeq())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    public static Account toDomain(AccountEntity entity) {
        Money balance = new Money(entity.getBalance(), Currency.getInstance(entity.getCurrency()));
        return Account.of(entity.getId(), entity.getOwnerId(), balance, entity.getStatus(), entity.getLastSeq(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
