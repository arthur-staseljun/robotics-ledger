package com.robotics.demo.ledger.consumer;

import com.robotics.demo.ledger.model.event.TransferCreatedEvent;
import com.robotics.demo.ledger.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TransferConsumer {

    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TransferCreatedEvent.TOPIC, groupId = "${ledger.kafka.consumer.transfers.group-id}")
    public void onTransferCreated(String payload) {
        TransferCreatedEvent event = objectMapper.readValue(payload, TransferCreatedEvent.class);
        transferService.process(event.transferId());
    }
}
