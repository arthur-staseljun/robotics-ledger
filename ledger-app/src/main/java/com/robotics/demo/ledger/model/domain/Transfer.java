package com.robotics.demo.ledger.model.domain;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import lombok.Getter;

import java.time.Instant;
import java.util.Currency;

@Getter
public class Transfer {

    private Long transferId;
    private final Long ownerId;
    private final Long accountFrom;
    private final Long accountTo;
    private final Money amount;
    private TransferStatus status;
    private String errorCode;
    private final Instant createdAt;
    private Instant updatedAt;

    public enum TransferStatus {
        PENDING,
        SUCCESSFUL,
        FAILED
    }

    private Transfer(Long transferId, Long ownerId, Long accountFrom, Long accountTo, Money amount,
                     TransferStatus status, String errorCode, Instant createdAt, Instant updatedAt) {
        this.transferId = transferId;
        this.ownerId = ownerId;
        this.accountFrom = accountFrom;
        this.accountTo = accountTo;
        this.amount = amount;
        this.status = status;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Transfer pending(Long ownerId, Long accountFrom, Long accountTo, Money amount) {
        Instant now = Instant.now();
        return new Transfer(null, ownerId, accountFrom, accountTo, amount, TransferStatus.PENDING, null, now, now);
    }

    public static Transfer of(Long transferId, Long ownerId, Long accountFrom, Long accountTo, Money amount,
                              TransferStatus status, String errorCode, Instant createdAt, Instant updatedAt) {
        return new Transfer(transferId, ownerId, accountFrom, accountTo, amount, status, errorCode, createdAt, updatedAt);
    }

    public Currency getCurrency() {
        return amount.currency();
    }

    public void succeed() {
        status = TransferStatus.SUCCESSFUL;
        updatedAt = Instant.now();
    }

    public void fail(ErrorClassification classification) {
        status = TransferStatus.FAILED;
        errorCode = classification.getErrorCode();
        updatedAt = Instant.now();
    }
}
