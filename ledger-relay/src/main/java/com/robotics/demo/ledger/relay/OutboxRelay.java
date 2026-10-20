package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.Lease.LeadershipLostException;
import com.robotics.demo.ledger.relay.OutboxStore.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class OutboxRelay {

    private final OutboxStore outboxStore;
    private final LeaderLockProvider leaderLockProvider;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(OutboxStore outboxStore, LeaderLockProvider leaderLockProvider,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${ledger.relay.batch-size}") int batchSize,
                       @Value("${ledger.relay.send-timeout}") Duration sendTimeout) {
        this.outboxStore = outboxStore;
        this.leaderLockProvider = leaderLockProvider;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(initialDelayString = "${ledger.relay.initial-delay}", fixedDelayString = "${ledger.relay.delay}")
    public int relayOnce() {
        try (Lease lease = leaderLockProvider.tryAcquire()) {
            return lease.isLeader() ? relayBatch(lease) : 0;
        }
    }

    private int relayBatch(Lease lease) {
        int sent = 0;
        try {
            for (Message event : outboxStore.findUnsent(batchSize)) {
                lease.ensureHeld();
                if (!send(event)) {
                    break;
                }
                outboxStore.markSent(event.id());
                sent++;
            }
        } catch (LeadershipLostException ex) {
            log.warn("The leadership is lost, {} events were sent before: {}", sent, ex.getMessage());
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
