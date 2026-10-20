package com.robotics.demo.ledger.persistence.jpa.mapper;

import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.persistence.jpa.entity.TransferEntity;

import java.util.Currency;

public final class TransferMapper {

    private TransferMapper() {
    }

    public static TransferEntity toEntity(Transfer transfer) {
        return TransferEntity.builder()
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
    }

    public static Transfer toDomain(TransferEntity entity) {
        Money amount = new Money(entity.getAmount(), Currency.getInstance(entity.getCurrency()));
        return Transfer.of(entity.getId(), entity.getOwnerId(), entity.getAccountFrom(), entity.getAccountTo(),
                amount, entity.getStatus(), entity.getErrorCode(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
