package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.persistence.jpa.TransactionRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private AccountService accountService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, accountService);
    }

    private static Account accountWithBalance(long cents) {
        Instant now = Instant.now();
        return Account.of(1L, eur(cents), now, now);
    }

    private void givenEntriesAreStored() {
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void depositIncreasesBalanceAndIsRecordedAsDeposit() {
        when(accountService.getExistingAccountLocking(1L)).thenReturn(accountWithBalance(0));
        givenEntriesAreStored();

        Transaction result = transactionService.createTransaction(1L, eur(10000));

        assertThat(result.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(result.getBalanceAfter()).isEqualTo(eur(10000));
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountService).save(saved.capture());
        assertThat(saved.getValue().getBalance()).isEqualTo(eur(10000));
    }

    @Test
    void withdrawalDecreasesBalanceAndIsRecordedAsWithdrawal() {
        when(accountService.getExistingAccountLocking(1L)).thenReturn(accountWithBalance(10000));
        givenEntriesAreStored();

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

        verify(accountService, never()).getExistingAccountLocking(any());
    }

    @Test
    void accountDoesNotExistIsRejected() {
        when(accountService.getExistingAccountLocking(99L))
                .thenThrow(new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThatThrownBy(() -> transactionService.createTransaction(99L, eur(1000)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(accountService, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void currencyMismatchIsRejectedAndNothingIsSaved() {
        when(accountService.getExistingAccountLocking(1L)).thenReturn(accountWithBalance(0));

        assertThatThrownBy(() -> transactionService.createTransaction(1L, new Money(1000, USD)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.CURRENCY_MISMATCH);

        verify(accountService, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void insufficientFundsIsRejectedAndNothingIsSaved() {
        when(accountService.getExistingAccountLocking(1L)).thenReturn(accountWithBalance(500));

        assertThatThrownBy(() -> transactionService.createTransaction(1L, eur(-501)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS);

        verify(accountService, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void getAllTransactionsChecksAccountExistsBeforeReturningHistory() {
        when(accountService.getExistingAccount(99L))
                .thenThrow(new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThatThrownBy(() -> transactionService.getAllTransactions(99L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(transactionRepository, never()).findByAccountIdOrderByCreatedAtDesc(any());
    }

    @Test
    void getAllTransactionsReturnsTheHistoryWhenAccountExists() {
        when(accountService.getExistingAccount(1L)).thenReturn(accountWithBalance(0));
        TransactionEntity entity = TransactionEntity.builder()
                .id(UUID.randomUUID())
                .accountId(1L)
                .transactionType(Transaction.TransactionType.DEPOSIT)
                .amount(1000)
                .balanceAfter(1000)
                .currency("EUR")
                .createdAt(Instant.now())
                .build();
        when(transactionRepository.findByAccountIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(entity));

        List<Transaction> result = transactionService.getAllTransactions(1L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getAmount()).isEqualTo(eur(1000));
    }

    private static Money eur(long cents) {
        return new Money(cents, EUR);
    }
}
