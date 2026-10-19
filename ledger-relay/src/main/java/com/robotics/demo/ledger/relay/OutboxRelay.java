package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.OutboxStore.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final long LEADER_LOCK_KEY = 7_001L;

    private final OutboxStore outboxStore;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RelayProperties properties;

    @Scheduled(initialDelayString = "${ledger.relay.initial-delay-ms:0}",
            fixedDelayString = "${ledger.relay.delay-ms:500}")
    @Transactional
    public int relayOnce() {
        if (!outboxStore.tryAcquireLeadership(LEADER_LOCK_KEY)) {
            return 0;
        }
        List<Message> batch = outboxStore.findUnsentForUpdate(properties.batchSize());
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
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("Outbox event {} was not published, it stays unsent: {}", event.id(), ex.toString());
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
