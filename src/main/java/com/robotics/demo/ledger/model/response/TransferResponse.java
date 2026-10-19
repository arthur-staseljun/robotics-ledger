package com.robotics.demo.ledger.model.response;

import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.model.domain.Transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

public record TransferResponse(Long transferId, Long ownerId, Long accountFrom, Long accountTo, BigDecimal amount,
                               Currency currency, TransferStatus status, String errorCode, Instant createdAt,
                               Instant updatedAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.getTransferId(), transfer.getOwnerId(), transfer.getAccountFrom(),
                transfer.getAccountTo(), transfer.getAmount().toDecimal(), transfer.getCurrency(),
                transfer.getStatus(), transfer.getErrorCode(), transfer.getCreatedAt(), transfer.getUpdatedAt());
    }
}
