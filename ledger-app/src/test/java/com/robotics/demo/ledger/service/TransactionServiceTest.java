package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.persistence.jpa.TransactionRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountService accountService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, accountService);
    }

    private static Account openAccount() {
        Instant now = Instant.now();
        return Account.of(1L, 101L, Money.zero(EUR), Account.AccountStatus.ACTIVE, 0, now, now);
    }

    private static TransactionEntity entity(long seqId, Transaction.TransactionType type, long amount, long balanceAfter) {
        TransactionEntity entity = new TransactionEntity();
        entity.setAccountId(1L);
        entity.setTransferId(10L);
        entity.setSeqId(seqId);
        entity.setTransactionType(type);
        entity.setAmount(amount);
        entity.setBalanceAfter(balanceAfter);
        entity.setCurrency("EUR");
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    @Test
    void getTransactionsChecksAccountExistsBeforeReturningHistory() {
        when(accountService.getExistingAccount(99L))
                .thenThrow(new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThatThrownBy(() -> transactionService.getTransactions(99L, null, 10))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(transactionRepository, never()).findByAccountIdOrderBySeqIdDesc(any(), any());
    }

    @Test
    void getTransactionsReturnsTheFirstPageNewestFirstWithoutACursorWhenNothingIsLeft() {
        when(accountService.getExistingAccount(1L)).thenReturn(openAccount());
        when(transactionRepository.findByAccountIdOrderBySeqIdDesc(1L, Limit.of(11))).thenReturn(List.of(
                entity(2, Transaction.TransactionType.WITHDRAWAL, -300, 700),
                entity(1, Transaction.TransactionType.DEPOSIT, 1000, 1000)));

        TransactionService.Page result = transactionService.getTransactions(1L, null, 10);

        assertThat(result.items()).extracting(Transaction::getSeqId).containsExactly(2L, 1L);
        assertThat(result.items().getFirst().getAmount().amount()).isEqualTo(-300);
        assertThat(result.items().getFirst().getBalanceAfter().amount()).isEqualTo(700);
        assertThat(result.items().getFirst().getTransferId()).isEqualTo(10L);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void getTransactionsCutsTheProbeRowAndPointsTheCursorAtTheLastReturnedOne() {
        when(accountService.getExistingAccount(1L)).thenReturn(openAccount());
        when(transactionRepository.findByAccountIdOrderBySeqIdDesc(1L, Limit.of(3))).thenReturn(List.of(
                entity(3, Transaction.TransactionType.DEPOSIT, 100, 300),
                entity(2, Transaction.TransactionType.DEPOSIT, 100, 200),
                entity(1, Transaction.TransactionType.DEPOSIT, 100, 100)));

        TransactionService.Page result = transactionService.getTransactions(1L, null, 2);

        assertThat(result.items()).extracting(Transaction::getSeqId).containsExactly(3L, 2L);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void getTransactionsWithACursorAsksOnlyForOlderEntries() {
        when(accountService.getExistingAccount(1L)).thenReturn(openAccount());
        when(transactionRepository.findByAccountIdAndSeqIdLessThanOrderBySeqIdDesc(1L, 2L, Limit.of(3)))
                .thenReturn(List.of(entity(1, Transaction.TransactionType.DEPOSIT, 100, 100)));

        TransactionService.Page result = transactionService.getTransactions(1L, 2L, 2);

        assertThat(result.items()).extracting(Transaction::getSeqId).containsExactly(1L);
        assertThat(result.hasMore()).isFalse();
        verify(transactionRepository, never()).findByAccountIdOrderBySeqIdDesc(any(), any());
    }

    @Test
    void recordTransferWritesAWithdrawalAndADepositWithTheirSequenceNumbers() {
        Instant now = Instant.now();
        Account from = Account.of(1L, 101L, new Money(7500, EUR), Account.AccountStatus.ACTIVE, 5, now, now);
        Account to = Account.of(2L, 102L, new Money(2500, EUR), Account.AccountStatus.ACTIVE, 8, now, now);

        transactionService.recordTransfer(7L, from, to, new Money(2500, EUR));

        ArgumentCaptor<TransactionEntity> entries = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository, times(2)).save(entries.capture());
        TransactionEntity withdrawal = entries.getAllValues().get(0);
        TransactionEntity deposit = entries.getAllValues().get(1);
        assertThat(withdrawal.getAccountId()).isEqualTo(1L);
        assertThat(withdrawal.getTransferId()).isEqualTo(7L);
        assertThat(withdrawal.getSeqId()).isEqualTo(5L);
        assertThat(withdrawal.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(withdrawal.getAmount()).isEqualTo(-2500);
        assertThat(withdrawal.getBalanceAfter()).isEqualTo(7500);
        assertThat(deposit.getAccountId()).isEqualTo(2L);
        assertThat(deposit.getTransferId()).isEqualTo(7L);
        assertThat(deposit.getSeqId()).isEqualTo(8L);
        assertThat(deposit.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(deposit.getAmount()).isEqualTo(2500);
        assertThat(deposit.getBalanceAfter()).isEqualTo(2500);
        assertThat(withdrawal.getCurrency()).isEqualTo("EUR");
    }
}
