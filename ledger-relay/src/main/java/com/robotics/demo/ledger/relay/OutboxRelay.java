package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.OutboxStore.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class OutboxRelay {

    private static final long LEADER_LOCK_KEY = 7_001L;

    private final OutboxStore outboxStore;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(OutboxStore outboxStore, KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${ledger.relay.batch-size}") int batchSize,
                       @Value("${ledger.relay.send-timeout}") Duration sendTimeout) {
        this.outboxStore = outboxStore;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(initialDelayString = "${ledger.relay.initial-delay}", fixedDelayString = "${ledger.relay.delay}")
    @Transactional
    public int relayOnce() {
        if (!outboxStore.tryAcquireLeadership(LEADER_LOCK_KEY)) {
            return 0;
        }
        List<Message> batch = outboxStore.findUnsentForUpdate(batchSize);
        int sent = 0;
        for (Message event : batch) {
            if (!send(event)) {
                break;
            }
            outboxStore.markSent(event.id());
            sent++;
        }
        return sent;
    }

    private boolean send(Message event) {
        try {
            kafkaTemplate.send(event.topic(), String.valueOf(event.accountId()), event.payload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            log.warn("Outbox event {} was not published, it stays unsent: {}", event.id(), ex.toString());
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
