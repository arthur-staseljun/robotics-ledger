package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transaction;
import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.model.event.TransferCreatedEvent;
import com.robotics.demo.ledger.model.response.TransferResponse;
import com.robotics.demo.ledger.persistence.jpa.OutboxRepository;
import com.robotics.demo.ledger.persistence.jpa.TransferRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.OutboxEntity;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static java.lang.String.valueOf;

@Service
@RequiredArgsConstructor
public class TransferService {

    public static final String TRANSFERS_REQUEST = "transfers";
    private static final String TRANSFERS_TOPIC = "transfers";

    private final TransferRepository transferRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxRepository outboxRepository;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    public record AcceptedTransfer(Transfer transfer, boolean replayed) {
    }

    @Transactional
    public AcceptedTransfer accept(String clientId, UUID idempotencyKey, Long accountFrom, Long accountTo,
                                   Money amount) {
        if (!amount.isPositive()) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        if (accountFrom.equals(accountTo)) {
            throw new LedgerServiceException(ErrorClassification.SAME_ACCOUNT_TRANSFER);
        }

        IdempotencyService.KeyClaim claim = idempotencyService.claim(clientId, idempotencyKey, IdempotencyService.hash(TRANSFERS_REQUEST,
                valueOf(accountFrom), valueOf(accountTo), valueOf(amount.amount()),
                amount.currency().getCurrencyCode()));
        if (!claim.isNew()) {
            return new AcceptedTransfer(getTransfer(claim.key().getTransferId()), true);
        }

        Account from = accountService.getExistingAccount(accountFrom);
        accountService.getExistingAccount(accountTo);
        Transfer transfer = save(Transfer.pending(from.getOwnerId(), accountFrom, accountTo, amount));
        saveCreatedEvent(transfer);

        claim.key().setTransferId(transfer.getTransferId());
        claim.key().setResponseJson(objectMapper.writeValueAsString(TransferResponse.from(transfer)));
        claim.key().setUpdatedAt(Instant.now());
        return new AcceptedTransfer(transfer, false);
    }

    @Transactional
    public Transfer process(Long transferId) {
        TransferEntity entity = transferRepository.findByIdLocking(transferId)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.TRANSFER_DOES_NOT_EXIST));
        if (entity.getStatus() != Transfer.TransferStatus.PENDING) {
            return toDomain(entity);
        }

        Transfer transfer = toDomain(entity);
        Long accountFrom = transfer.getAccountFrom();
        Long accountTo = transfer.getAccountTo();
        Map<Long, Account> locked = lockInIdOrder(accountFrom, accountTo);
        Account from = locked.get(accountFrom);
        Account to = locked.get(accountTo);

        Money amount = transfer.getAmount();
        try {
            from.apply(amount.negate());
            to.apply(amount);
            transfer.succeed();
        } catch (LedgerServiceException ex) {
            transfer.fail(ex.getErrorClassification());
        }
        transfer = save(transfer);

        if (transfer.getStatus() == Transfer.TransferStatus.SUCCESSFUL) {
            long fromSeq = from.nextSeq();
            long toSeq = to.nextSeq();
            accountService.save(from);
            accountService.save(to);
            transactionService.save(new Transaction(accountFrom, transfer.getTransferId(), fromSeq,
                    amount.negate(), from.getBalance()));
            transactionService.save(new Transaction(accountTo, transfer.getTransferId(), toSeq,
                    amount, to.getBalance()));
        }
        return transfer;
    }

    public Transfer getTransfer(Long transferId) {
        return transferRepository.findById(transferId)
                .map(TransferService::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.TRANSFER_DOES_NOT_EXIST));
    }

    private Map<Long, Account> lockInIdOrder(Long... accountIds) {
        Map<Long, Account> locked = new HashMap<>();
        Stream.of(accountIds).sorted()
                .forEach(id -> locked.put(id, accountService.getExistingAccountLocking(id)));
        return locked;
    }

    private void saveCreatedEvent(Transfer transfer) {
        Instant now = Instant.now();
        TransferCreatedEvent event = new TransferCreatedEvent(transfer.getTransferId(), transfer.getAccountFrom(),
                transfer.getAccountTo(), transfer.getAmount().amount(), transfer.getCurrency().getCurrencyCode());
        outboxRepository.save(OutboxEntity.builder()
                .accountId(transfer.getAccountFrom())
                .transferId(transfer.getTransferId())
                .eventType(OutboxEntity.EventType.created)
                .topic(TRANSFERS_TOPIC)
                .payload(objectMapper.writeValueAsString(event))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Transfer save(Transfer transfer) {
        TransferEntity entity = TransferEntity.builder()
                .id(transfer.getTransferId())
                .ownerId(transfer.getOwnerId())
                .accountFrom(transfer.getAccountFrom())
                .accountTo(transfer.getAccountTo())
                .amount(transfer.getAmount().amount())
                .currency(transfer.getCurrency().getCurrencyCode())
                .status(transfer.getStatus())
                .errorCode(transfer.getErrorCode())
                .createdAt(transfer.getCreatedAt())
                .updatedAt(transfer.getUpdatedAt())
                .build();
        return toDomain(transferRepository.saveAndFlush(entity));
    }

    private static Transfer toDomain(TransferEntity entity) {
        Money amount = new Money(entity.getAmount(), Currency.getInstance(entity.getCurrency()));
        return Transfer.of(entity.getId(), entity.getOwnerId(), entity.getAccountFrom(), entity.getAccountTo(), amount,
                entity.getStatus(), entity.getErrorCode(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
