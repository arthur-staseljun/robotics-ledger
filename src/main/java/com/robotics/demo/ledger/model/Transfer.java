package com.robotics.demo.ledger.model;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Currency;

@Getter
public class Transfer {

    public enum TransferStatus {
        PENDING,
        SUCCESSFUL,
        FAILED
    }

    @Setter
    private Long transferId;
    private final Long accountFrom;
    private final Long accountTo;
    private final Money amount;
    private TransferStatus status;
    private String errorCode;
    private final Instant createdAt;
    private Instant updatedAt;

    private Transfer(Long transferId, Long accountFrom, Long accountTo, Money amount, TransferStatus status,
                     String errorCode, Instant createdAt, Instant updatedAt) {
        this.transferId = transferId;
        this.accountFrom = accountFrom;
        this.accountTo = accountTo;
        this.amount = amount;
        this.status = status;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Transfer pending(Long accountFrom, Long accountTo, Money amount) {
        Instant now = Instant.now();
        return new Transfer(null, accountFrom, accountTo, amount, TransferStatus.PENDING, null, now, now);
    }

    public static Transfer of(Long transferId, Long accountFrom, Long accountTo, Money amount,
                              TransferStatus status, String errorCode, Instant createdAt, Instant updatedAt) {
        return new Transfer(transferId, accountFrom, accountTo, amount, status, errorCode, createdAt, updatedAt);
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

    public Transfer copy() {
        return new Transfer(transferId, accountFrom, accountTo, amount, status, errorCode, createdAt, updatedAt);
    }
}
