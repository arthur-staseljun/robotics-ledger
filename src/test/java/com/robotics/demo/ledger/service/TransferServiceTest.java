package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.model.Transfer;
import com.robotics.demo.ledger.model.Transfer.TransferStatus;
import com.robotics.demo.ledger.persistence.jpa.TransferRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private TransactionService transactionService;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, accountService, transactionService);
    }

    private static Account account(long id, long cents) {
        Instant now = Instant.now();
        return Account.of(id, eur(cents), now, now);
    }

    private void givenAccounts(Account from, Account to) {
        when(accountService.getExistingAccountLocking(from.getId())).thenReturn(from);
        when(accountService.getExistingAccountLocking(to.getId())).thenReturn(to);
    }

    private void givenTransfersAreStored() {
        when(transferRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            TransferEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(7L);
            }
            return entity;
        });
    }

    @Test
    void successfulTransferChangesBothBalancesAndWritesTwoLinkedEntries() {
        givenAccounts(account(1L, 10000), account(2L, 0));
        givenTransfersAreStored();

        Transfer result = transferService.createTransfer(1L, 2L, eur(2500));

        assertThat(result.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(result.getErrorCode()).isNull();
        ArgumentCaptor<Account> accounts = ArgumentCaptor.forClass(Account.class);
        verify(accountService, times(2)).save(accounts.capture());
        assertThat(accounts.getAllValues()).extracting(Account::getBalance)
                .containsExactlyInAnyOrder(eur(7500), eur(2500));
        ArgumentCaptor<Transaction> entries = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionService, times(2)).save(entries.capture());
        Transaction withdrawal = entries.getAllValues().get(0);
        Transaction deposit = entries.getAllValues().get(1);
        assertThat(withdrawal.getAccountId()).isEqualTo(1L);
        assertThat(withdrawal.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(withdrawal.getAmount()).isEqualTo(eur(-2500));
        assertThat(withdrawal.getBalanceAfter()).isEqualTo(eur(7500));
        assertThat(withdrawal.getTransferId()).isEqualTo(7L);
        assertThat(deposit.getAccountId()).isEqualTo(2L);
        assertThat(deposit.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(deposit.getAmount()).isEqualTo(eur(2500));
        assertThat(deposit.getBalanceAfter()).isEqualTo(eur(2500));
        assertThat(deposit.getTransferId()).isEqualTo(7L);
    }

    @Test
    void insufficientFundsIsRecordedAsFailedTransferAndNothingElseIsSaved() {
        givenAccounts(account(1L, 1000), account(2L, 0));
        givenTransfersAreStored();

        Transfer result = transferService.createTransfer(1L, 2L, eur(2500));

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getErrorCode()).isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS.getErrorCode());
        verify(accountService, never()).save(any());
        verify(transactionService, never()).save(any());
    }

    @Test
    void currencyMismatchIsRecordedAsFailedTransfer() {
        Instant now = Instant.now();
        Account usdAccount = Account.of(2L, new Money(0, Currency.getInstance("USD")), now, now);
        givenAccounts(account(1L, 10000), usdAccount);
        givenTransfersAreStored();

        Transfer result = transferService.createTransfer(1L, 2L, eur(100));

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getErrorCode()).isEqualTo(ErrorClassification.CURRENCY_MISMATCH.getErrorCode());
        verify(accountService, never()).save(any());
    }

    @Test
    void accountsAreLockedInIdOrderWhateverTheDirection() {
        givenAccounts(account(1L, 0), account(2L, 10000));
        givenTransfersAreStored();

        transferService.createTransfer(2L, 1L, eur(100));

        InOrder order = inOrder(accountService);
        order.verify(accountService).getExistingAccountLocking(1L);
        order.verify(accountService).getExistingAccountLocking(2L);
    }

    @Test
    void nonPositiveAmountIsRejectedBeforeAnythingIsTouched() {
        assertThatThrownBy(() -> transferService.createTransfer(1L, 2L, eur(0)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.AMOUNT_PARSE_ERROR);
        assertThatThrownBy(() -> transferService.createTransfer(1L, 2L, eur(-100)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.AMOUNT_PARSE_ERROR);

        verify(accountService, never()).getExistingAccountLocking(any());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        assertThatThrownBy(() -> transferService.createTransfer(1L, 1L, eur(100)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.SAME_ACCOUNT_TRANSFER);

        verify(accountService, never()).getExistingAccountLocking(any());
    }

    @Test
    void unknownAccountIsRejectedAndNothingIsStored() {
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 10000));
        when(accountService.getExistingAccountLocking(2L))
                .thenThrow(new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThatThrownBy(() -> transferService.createTransfer(1L, 2L, eur(100)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(transferRepository, never()).saveAndFlush(any());
        verify(accountService, never()).save(any());
    }

    @Test
    void unknownTransferIsRejected() {
        when(transferRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(5L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.TRANSFER_DOES_NOT_EXIST);
    }

    private static Money eur(long cents) {
        return new Money(cents, EUR);
    }
}
