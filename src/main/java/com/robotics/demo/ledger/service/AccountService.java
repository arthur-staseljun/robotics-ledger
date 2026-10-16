package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.persistence.jpa.AccountRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.AccountEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    public Account createAccount() {
        return save(new Account());
    }

    public Account getExistingAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .map(AccountService::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account getExistingAccountLocking(Long accountId) {
        return accountRepository.findByIdLocking(accountId)
                .map(AccountService::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));
    }

    public Account save(Account account) {
        AccountEntity entity = AccountEntity.builder()
                .id(account.getId())
                .balance(account.getBalance().amount())
                .currency(account.getCurrency().getCurrencyCode())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
        return toDomain(accountRepository.save(entity));
    }

    private static Account toDomain(AccountEntity entity) {
        Money balance = new Money(entity.getBalance(), Currency.getInstance(entity.getCurrency()));
        return Account.of(entity.getId(), balance, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
