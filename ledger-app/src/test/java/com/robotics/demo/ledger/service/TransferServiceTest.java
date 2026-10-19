package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.model.domain.Transfer.TransferStatus;
import com.robotics.demo.ledger.persistence.jpa.OutboxRepository;
import com.robotics.demo.ledger.persistence.jpa.TransferRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.IdempotencyKeyEntity;
import com.robotics.demo.ledger.persistence.jpa.entity.OutboxEntity;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

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
    private static final String CLIENT_ID = "client-1";
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private TransactionService transactionService;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, idempotencyService, outboxRepository,
                accountService, transactionService, JsonMapper.builder().build());
    }

    private static Account account(long id, long cents) {
        Instant now = Instant.now();
        return Account.of(id, 100L + id, eur(cents), Account.AccountStatus.ACTIVE, 4, now, now);
    }

    private static TransferEntity pendingEntity(long from, long to, long cents, String currency) {
        Instant now = Instant.now();
        return TransferEntity.builder()
                .id(7L)
                .ownerId(100L + from)
                .accountFrom(from)
                .accountTo(to)
                .amount(cents)
                .currency(currency)
                .status(TransferStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static IdempotencyKeyEntity keyEntity(byte[] requestHash, Long transferId) {
        Instant now = Instant.now();
        return IdempotencyKeyEntity.builder()
                .clientId(CLIENT_ID)
                .idempotencyKey(KEY)
                .transferId(transferId)
                .requestHash(requestHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
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
    void newKeyCreatesPendingTransferOutboxEventAndKeyRecord() {
        IdempotencyKeyEntity key = keyEntity(new byte[0], null);
        when(idempotencyService.claim(any(), any(), any())).thenReturn(new IdempotencyService.KeyClaim(key, true));
        when(accountService.getExistingAccount(1L)).thenReturn(account(1L, 10000));
        when(accountService.getExistingAccount(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        TransferService.AcceptedTransfer accepted = transferService.accept(CLIENT_ID, KEY, 1L, 2L, eur(2500));

        assertThat(accepted.replayed()).isFalse();
        Transfer transfer = accepted.transfer();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getTransferId()).isEqualTo(7L);
        assertThat(transfer.getOwnerId()).isEqualTo(101L);
        ArgumentCaptor<OutboxEntity> event = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(OutboxEntity.EventType.created);
        assertThat(event.getValue().getTopic()).isEqualTo("transfers");
        assertThat(event.getValue().getTransferId()).isEqualTo(7L);
        assertThat(event.getValue().getAccountId()).isEqualTo(1L);
        assertThat(event.getValue().getPayload()).contains("\"transferId\":7").contains("\"amount\":2500");
        assertThat(key.getTransferId()).isEqualTo(7L);
        assertThat(key.getResponseJson()).contains("PENDING");
        verify(accountService, never()).getExistingAccountLocking(any());
        verify(transactionService, never()).save(any());
    }

    @Test
    void sameKeyAndSameRequestReturnsTheExistingTransferAndStoresNothing() {
        when(idempotencyService.claim(any(), any(), any())).thenReturn(new IdempotencyService.KeyClaim(keyEntity(new byte[0], 7L), false));
        when(transferRepository.findById(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 2500, "EUR")));

        TransferService.AcceptedTransfer accepted = transferService.accept(CLIENT_ID, KEY, 1L, 2L, eur(2500));

        assertThat(accepted.replayed()).isTrue();
        assertThat(accepted.transfer().getTransferId()).isEqualTo(7L);
        verify(transferRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void theRequestHashCoversEveryFieldOfTheTransfer() {
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        when(idempotencyService.claim(any(), any(), hash.capture()))
                .thenReturn(new IdempotencyService.KeyClaim(keyEntity(new byte[0], 7L), false));
        when(transferRepository.findById(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 2500, "EUR")));

        transferService.accept(CLIENT_ID, KEY, 1L, 2L, eur(2500));

        assertThat(hash.getValue()).isEqualTo(IdempotencyService.hash(TransferService.TRANSFERS_REQUEST, "1", "2", "2500", "EUR"));
    }

    @Test
    void nonPositiveAmountIsRejectedBeforeAnythingIsTouched() {
        assertThatThrownBy(() -> transferService.accept(CLIENT_ID, KEY, 1L, 2L, eur(0)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.AMOUNT_PARSE_ERROR);
        assertThatThrownBy(() -> transferService.accept(CLIENT_ID, KEY, 1L, 2L, eur(-100)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.AMOUNT_PARSE_ERROR);

        verify(idempotencyService, never()).claim(any(), any(), any());
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        assertThatThrownBy(() -> transferService.accept(CLIENT_ID, KEY, 1L, 1L, eur(100)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.SAME_ACCOUNT_TRANSFER);

        verify(idempotencyService, never()).claim(any(), any(), any());
    }

    @Test
    void unknownAccountIsRejectedAndNoTransferIsStored() {
        when(idempotencyService.claim(any(), any(), any()))
                .thenReturn(new IdempotencyService.KeyClaim(keyEntity(new byte[0], null), true));
        when(accountService.getExistingAccount(1L))
                .thenThrow(new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThatThrownBy(() -> transferService.accept(CLIENT_ID, KEY, 1L, 2L, eur(100)))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.ACCOUNT_DOES_NOT_EXIST);

        verify(transferRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void processingChangesBothBalancesAndWritesTwoLinkedEntries() {
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 2500, "EUR")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 10000));
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        Transfer result = transferService.process(7L);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(result.getErrorCode()).isNull();
        ArgumentCaptor<Account> accounts = ArgumentCaptor.forClass(Account.class);
        verify(accountService, times(2)).save(accounts.capture());
        assertThat(accounts.getAllValues()).extracting(Account::getBalance)
                .containsExactlyInAnyOrder(eur(7500), eur(2500));
        assertThat(accounts.getAllValues()).extracting(Account::getLastSeq).containsOnly(5L);
        ArgumentCaptor<Transaction> entries = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionService, times(2)).save(entries.capture());
        Transaction withdrawal = entries.getAllValues().get(0);
        Transaction deposit = entries.getAllValues().get(1);
        assertThat(withdrawal.getAccountId()).isEqualTo(1L);
        assertThat(withdrawal.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(withdrawal.getAmount()).isEqualTo(eur(-2500));
        assertThat(withdrawal.getBalanceAfter()).isEqualTo(eur(7500));
        assertThat(withdrawal.getTransferId()).isEqualTo(7L);
        assertThat(withdrawal.getSeqId()).isEqualTo(5);
        assertThat(deposit.getAccountId()).isEqualTo(2L);
        assertThat(deposit.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(deposit.getAmount()).isEqualTo(eur(2500));
        assertThat(deposit.getBalanceAfter()).isEqualTo(eur(2500));
        assertThat(deposit.getSeqId()).isEqualTo(5);
    }

    @Test
    void insufficientFundsIsRecordedAsFailedTransferAndNothingElseIsSaved() {
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 2500, "EUR")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 1000));
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        Transfer result = transferService.process(7L);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getErrorCode()).isEqualTo(ErrorClassification.INSUFFICIENT_FUNDS.getErrorCode());
        verify(accountService, never()).save(any());
        verify(transactionService, never()).save(any());
    }

    @Test
    void inactiveAccountIsRecordedAsFailedTransfer() {
        Instant now = Instant.now();
        Account frozen = Account.of(1L, 101L, eur(10000), Account.AccountStatus.FROZEN, 0, now, now);
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 100, "EUR")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(frozen);
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        Transfer result = transferService.process(7L);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getErrorCode()).isEqualTo(ErrorClassification.ACCOUNT_NOT_ACTIVE.getErrorCode());
        verify(accountService, never()).save(any());
    }

    @Test
    void currencyMismatchIsRecordedAsFailedTransfer() {
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 100, "USD")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 10000));
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        Transfer result = transferService.process(7L);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getErrorCode()).isEqualTo(ErrorClassification.CURRENCY_MISMATCH.getErrorCode());
        verify(accountService, never()).save(any());
    }

    @Test
    void anAlreadyFinishedTransferIsNotProcessedAgain() {
        TransferEntity finished = pendingEntity(1L, 2L, 2500, "EUR");
        finished.setStatus(TransferStatus.SUCCESSFUL);
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(finished));

        Transfer result = transferService.process(7L);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        verify(accountService, never()).getExistingAccountLocking(any());
        verify(transferRepository, never()).saveAndFlush(any());
        verify(transactionService, never()).save(any());
    }

    @Test
    void accountsAreLockedInIdOrderWhateverTheDirection() {
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(2L, 1L, 100, "EUR")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 0));
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 10000));
        givenTransfersAreStored();

        transferService.process(7L);

        InOrder order = inOrder(accountService);
        order.verify(accountService).getExistingAccountLocking(1L);
        order.verify(accountService).getExistingAccountLocking(2L);
    }

    @Test
    void unknownTransferIsRejectedWhenProcessing() {
        when(transferRepository.findByIdLocking(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.process(5L))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.TRANSFER_DOES_NOT_EXIST);
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

    @Test
    void successfulProcessingWritesAProcessedEventToTheOutbox() {
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 2500, "EUR")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 10000));
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        transferService.process(7L);

        ArgumentCaptor<OutboxEntity> event = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(OutboxEntity.EventType.processed);
        assertThat(event.getValue().getTopic()).isEqualTo("transfer-complete");
        assertThat(event.getValue().getAccountId()).isEqualTo(1L);
        assertThat(event.getValue().getTransferId()).isEqualTo(7L);
        assertThat(event.getValue().getPayload()).contains("\"status\":\"SUCCESSFUL\"");
    }

    @Test
    void failedProcessingWritesAProcessedEventWithTheErrorCode() {
        when(transferRepository.findByIdLocking(7L)).thenReturn(Optional.of(pendingEntity(1L, 2L, 2500, "EUR")));
        when(accountService.getExistingAccountLocking(1L)).thenReturn(account(1L, 1000));
        when(accountService.getExistingAccountLocking(2L)).thenReturn(account(2L, 0));
        givenTransfersAreStored();

        transferService.process(7L);

        ArgumentCaptor<OutboxEntity> event = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(event.capture());
        assertThat(event.getValue().getPayload()).contains("\"status\":\"FAILED\"")
                .contains(ErrorClassification.INSUFFICIENT_FUNDS.getErrorCode());
    }
}
