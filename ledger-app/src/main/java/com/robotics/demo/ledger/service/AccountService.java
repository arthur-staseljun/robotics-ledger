package com.robotics.demo.ledger.service;


import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.response.AccountResponse;
import com.robotics.demo.ledger.persistence.jpa.AccountRepository;
import com.robotics.demo.ledger.persistence.jpa.mapper.AccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    public static final String ACCOUNTS_REQUEST = "accounts";

    private final CustomerService customerService;
    private final IdempotencyService idempotencyService;
    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(String clientId, UUID idempotencyKey, Long ownerId) {
        var claim = idempotencyService.claim(clientId, idempotencyKey, ACCOUNTS_REQUEST, String.valueOf(ownerId));
        if (!claim.isNew()) {
            return idempotencyService.storedResponse(claim, AccountResponse.class);
        }
        customerService.getExistingCustomer(ownerId);
        Account account = save(Account.openFor(ownerId));

        AccountResponse response = AccountResponse.from(account);
        idempotencyService.storeResponse(claim, response);
        return response;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account getExistingAccountLocking(Long accountId) {
        return findLocking(accountId);
    }

    @Transactional
    public void freeze(Long accountId) {
        Account account = findLocking(accountId);
        account.freeze();
        save(account);
    }

    public Account getExistingAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .map(AccountMapper::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));
    }

    public Account save(Account account) {
        return AccountMapper.toDomain(accountRepository.save(AccountMapper.toEntity(account)));
    }

    private Account findLocking(Long accountId) {
        return accountRepository.findByIdLocking(accountId)
                .map(AccountMapper::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));
    }
}
