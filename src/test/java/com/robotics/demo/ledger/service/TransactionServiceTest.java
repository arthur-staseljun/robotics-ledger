package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.persistence.AccountRepository;
import com.robotics.demo.ledger.persistence.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, accountRepository);
    }

    @Test
    void depositIncreasesBalanceAndIsRecordedAsDeposit() {
        Account account = new Account(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.applyBalanceChange(1L, eur(10000))).thenReturn(Optional.of(eur(10000)));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Transaction result = transactionService.createTransaction(1L, eur(10000));

        assertThat(result.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(result.getBalanceAfter()).isEqualTo(eur(10000));
        verify(accountRepository).applyBalanceChange(1L, eur(10000));
    }

    @Test
    void withdrawalDecreasesBalanceAndIsRecordedAsWithdrawal() {
        Account account = new Account(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.applyBalanceChange(1L, eur(-3000))).thenReturn(Optional.of(eur(7000)));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Transaction result = transactionService.createTransaction(1L, eur(-3000));

        assertThat(result.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(result.getBalanceAfter()).isEqualTo(eur(7000));
    }

    @Test
    void zeroAmountIsRejectedBeforeTouchingTheAccount() {
        assertThatThrownBy(() -> transactionService.createTransaction(1L, eur(0)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.AMOUNT_PARSE_ERROR);

        verify(accountRepository, never()).findById(any());
    }

    @Test
    void accountDoesNotExistIsRejected() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.createTransaction(99L, eur(1000)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(accountRepository, never()).applyBalanceChange(any(), any());
    }

    @Test
    void currencyMismatchIsRejected() {
        Account account = new Account(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> transactionService.createTransaction(1L, new Money(1000, USD)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.CURRENCY_MISMATCH);

        verify(accountRepository, never()).applyBalanceChange(any(), any());
    }

    @Test
    void insufficientFundsIsRaisedWhenRepositoryRejectsTheChange() {
        Account account = new Account(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.applyBalanceChange(eq(1L), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.createTransaction(1L, eur(-100000)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void getAllTransactionsChecksAccountExistsBeforeReturningHistory() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getAllTransactions(99L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(transactionRepository, never()).findAll(any());
    }

    @Test
    void getAllTransactionsDelegatesToRepositoryWhenAccountExists() {
        Account account = new Account(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        List<Transaction> history = List.of(new Transaction(1L, eur(1000), eur(1000)));
        when(transactionRepository.findAll(1L)).thenReturn(history);

        List<Transaction> result = transactionService.getAllTransactions(1L);

        assertThat(result).isEqualTo(history);
    }

    private static Money eur(long cents) {
        return new Money(cents, EUR);
    }
}
