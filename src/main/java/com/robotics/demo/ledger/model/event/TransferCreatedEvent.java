package com.robotics.demo.ledger.model.event;

public record TransferCreatedEvent(Long transferId, Long accountFrom, Long accountTo, long amount, String currency) {
}
