package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.persistence.AccountRepository;
import com.robotics.demo.ledger.persistence.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public Transaction createTransaction(Long accountId, Money money) {
        if (money.amount() == 0) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        Account account = getAccount(accountId);
        if (!account.getBalance().currency().equals(money.currency())) {
            throw new LedgerServiceException(ErrorClassification.CURRENCY_MISMATCH);
        }
        Money newBalance = accountRepository.applyBalanceChange(accountId, money)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.INSUFFICIENT_FUNDS));
        return transactionRepository.save(new Transaction(accountId, money, newBalance));
    }

    public List<Transaction> getAllTransactions(Long accountId) {
        getAccount(accountId);
        return transactionRepository.findAll(accountId);
    }

    private Account getAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));
    }
}
