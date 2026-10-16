package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.model.Transaction;
import com.robotics.demo.ledger.model.Transfer;
import com.robotics.demo.ledger.persistence.jpa.TransferRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final AccountService accountService;
    private final TransactionService transactionService;

    @Transactional
    public Transfer createTransfer(Long accountFrom, Long accountTo, Money amount) {
        if (!amount.isPositive()) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        if (accountFrom.equals(accountTo)) {
            throw new LedgerServiceException(ErrorClassification.SAME_ACCOUNT_TRANSFER);
        }

        Map<Long, Account> locked = lockInIdOrder(accountFrom, accountTo);
        Account from = locked.get(accountFrom);
        Account to = locked.get(accountTo);

        Transfer transfer = Transfer.pending(accountFrom, accountTo, amount);
        try {
            from.apply(amount.negate());
            to.apply(amount);
            transfer.succeed();
        } catch (LedgerServiceException ex) {
            transfer.fail(ex.getErrorClassification());
        }
        transfer = save(transfer);

        if (transfer.getStatus() == Transfer.TransferStatus.SUCCESSFUL) {
            accountService.save(from);
            accountService.save(to);
            transactionService.save(new Transaction(accountFrom, amount.negate(), from.getBalance(),
                    transfer.getTransferId()));
            transactionService.save(new Transaction(accountTo, amount, to.getBalance(),
                    transfer.getTransferId()));
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

    private Transfer save(Transfer transfer) {
        TransferEntity entity = TransferEntity.builder()
                .id(transfer.getTransferId())
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
        return Transfer.of(entity.getId(), entity.getAccountFrom(), entity.getAccountTo(), amount,
                entity.getStatus(), entity.getErrorCode(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
