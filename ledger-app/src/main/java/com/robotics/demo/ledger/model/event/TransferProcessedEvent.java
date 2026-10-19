package com.robotics.demo.ledger.model.event;

public record TransferProcessedEvent(Long transferId, String status, String errorCode) {

    public static final String TOPIC = "transfer-complete";
}
