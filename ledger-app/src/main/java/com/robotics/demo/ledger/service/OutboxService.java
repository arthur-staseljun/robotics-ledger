package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.model.event.TransferCreatedEvent;
import com.robotics.demo.ledger.model.event.TransferProcessedEvent;
import com.robotics.demo.ledger.persistence.jpa.OutboxRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.OutboxEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveCreated(Transfer transfer) {
        save(transfer, OutboxEntity.EventType.created, TransferCreatedEvent.TOPIC,
                new TransferCreatedEvent(transfer.getTransferId(), transfer.getAccountFrom(),
                        transfer.getAccountTo(), transfer.getAmount().amount(),
                        transfer.getCurrency().getCurrencyCode()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveProcessed(Transfer transfer) {
        save(transfer, OutboxEntity.EventType.processed, TransferProcessedEvent.TOPIC,
                new TransferProcessedEvent(transfer.getTransferId(), transfer.getStatus().name(),
                        transfer.getErrorCode()));
    }

    private void save(Transfer transfer, OutboxEntity.EventType eventType, String topic, Object event) {
        Instant now = Instant.now();
        outboxRepository.save(OutboxEntity.builder()
                .accountId(transfer.getAccountFrom())
                .transferId(transfer.getTransferId())
                .eventType(eventType)
                .topic(topic)
                .payload(objectMapper.writeValueAsString(event))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
