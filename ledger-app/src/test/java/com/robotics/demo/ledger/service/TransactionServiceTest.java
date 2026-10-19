package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.persistence.jpa.TransactionRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransactionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountService accountService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, accountService);
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
    void getAllTransactionsChecksAccountExistsBeforeReturningHistory() {
        when(accountService.getExistingAccount(99L))
                .thenThrow(new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThatThrownBy(() -> transactionService.getAllTransactions(99L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(transactionRepository, never()).findByAccountIdOrderBySeqIdDesc(any());
    }

    @Test
    void getAllTransactionsReturnsTheHistoryNewestFirstWhenAccountExists() {
        when(accountService.getExistingAccount(1L)).thenReturn(new Account(1L));
        when(transactionRepository.findByAccountIdOrderBySeqIdDesc(1L)).thenReturn(List.of(
                entity(2, Transaction.TransactionType.WITHDRAWAL, -300, 700),
                entity(1, Transaction.TransactionType.DEPOSIT, 1000, 1000)));

        List<Transaction> result = transactionService.getAllTransactions(1L);

        assertThat(result).extracting(Transaction::getSeqId).containsExactly(2L, 1L);
        assertThat(result.getFirst().getAmount().amount()).isEqualTo(-300);
        assertThat(result.getFirst().getBalanceAfter().amount()).isEqualTo(700);
        assertThat(result.getFirst().getTransferId()).isEqualTo(10L);
    }
}
