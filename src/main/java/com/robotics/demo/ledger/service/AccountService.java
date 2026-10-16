package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.persistence.jpa.AccountRepository;
import com.robotics.demo.ledger.persistence.jpa.CustomerRepository;
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
    private final CustomerRepository customerRepository;

    public Account createAccount(Long ownerId) {
        customerRepository.findById(ownerId)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.CUSTOMER_DOES_NOT_EXIST));
        return save(Account.openFor(ownerId));
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
                .ownerId(account.getOwnerId())
                .balance(account.getBalance().amount())
                .currency(account.getCurrency().getCurrencyCode())
                .status(account.getStatus())
                .lastSeq(account.getLastSeq())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
        return toDomain(accountRepository.save(entity));
    }

    private static Account toDomain(AccountEntity entity) {
        Money balance = new Money(entity.getBalance(), Currency.getInstance(entity.getCurrency()));
        return Account.of(entity.getId(), entity.getOwnerId(), balance, entity.getStatus(), entity.getLastSeq(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
