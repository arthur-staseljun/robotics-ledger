package com.robotics.demo.ledger.service;


import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.persistence.jpa.TransferRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import com.robotics.demo.ledger.persistence.jpa.mapper.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static java.lang.String.valueOf;

@Service
@RequiredArgsConstructor
public class TransferService {

    public static final String TRANSFERS_REQUEST = "transfers";

    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final AccountService accountService;
    private final TransactionService transactionService;

    private final TransferRepository transferRepository;

    @Transactional
    public AcceptedTransfer accept(String clientId, UUID idempotencyKey,
                                   Long accountFromId, Long accountToId, Money amount) {
        if (!amount.isPositive()) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        if (accountFromId.equals(accountToId)) {
            throw new LedgerServiceException(ErrorClassification.SAME_ACCOUNT_TRANSFER);
        }

        IdempotencyService.KeyClaim claim = idempotencyService.claim(clientId, idempotencyKey, TRANSFERS_REQUEST,
                valueOf(accountFromId), valueOf(accountToId), valueOf(amount.amount()),
                amount.currency().getCurrencyCode());
        if (!claim.isNew()) {
            return new AcceptedTransfer(getTransfer(claim.key().getTransferId()), true);
        }

        Account accountFrom = accountService.getExistingAccount(accountFromId);
        Account accountTo = accountService.getExistingAccount(accountToId);
        Transfer transfer = save(Transfer.pending(accountFrom, accountTo, amount));
        outboxService.saveCreated(transfer);

        idempotencyService.linkTransfer(claim, transfer.getTransferId());
        return new AcceptedTransfer(transfer, false);
    }

    @Transactional
    public Transfer process(Long transferId) {
        TransferEntity entity = transferRepository.findByIdLocking(transferId)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.TRANSFER_DOES_NOT_EXIST));
        Transfer transfer = TransferMapper.toDomain(entity);
        if (transfer.getStatus() != Transfer.TransferStatus.PENDING) {
            return transfer;
        }

        Map<Long, Account> locked = lockInIdOrder(transfer.getAccountFrom(), transfer.getAccountTo());
        Account accountFrom = locked.get(transfer.getAccountFrom());
        Account accountTo = locked.get(transfer.getAccountTo());

        transfer.settle(accountFrom, accountTo);
        transfer = save(transfer);

        if (transfer.getStatus() == Transfer.TransferStatus.SUCCESSFUL) {
            accountFrom.advanceSeq();
            accountTo.advanceSeq();
            accountService.save(accountFrom);
            accountService.save(accountTo);
            transactionService.recordTransfer(transfer.getTransferId(), accountFrom, accountTo, transfer.getAmount());
        }
        outboxService.saveProcessed(transfer);
        return transfer;
    }

    public Transfer getTransfer(Long transferId) {
        return transferRepository.findById(transferId)
                .map(TransferMapper::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.TRANSFER_DOES_NOT_EXIST));
    }

    private Map<Long, Account> lockInIdOrder(Long... accountIds) {
        Map<Long, Account> locked = new HashMap<>();
        Stream.of(accountIds).sorted()
                .forEach(id -> locked.put(id, accountService.getExistingAccountLocking(id)));
        return locked;
    }

    private Transfer save(Transfer transfer) {
        return TransferMapper.toDomain(
                transferRepository.saveAndFlush(TransferMapper.toEntity(transfer))
        );
    }

    public record AcceptedTransfer(Transfer transfer, boolean replayed) {
    }
}
