package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.OutboxStore.Message;
import com.robotics.demo.ledger.relay.Lease.LeadershipLostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final int BATCH_SIZE = 25;

    @Mock
    private OutboxStore outboxStore;

    @Mock
    private LeaderLockProvider leaderLockProvider;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private Lease lease;

    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        outboxRelay = new OutboxRelay(outboxStore, leaderLockProvider, kafkaTemplate, BATCH_SIZE, Duration.ofSeconds(10));
        lenient().when(leaderLockProvider.tryAcquire()).thenReturn(lease);
        lenient().when(lease.isLeader()).thenReturn(true);
    }

    private static Message event(long id, long accountId) {
        return new Message(id, accountId, "transfers", "{\"transferId\":" + id + "}");
    }

    private void givenUnsent(Message... events) {
        when(outboxStore.findUnsent(BATCH_SIZE)).thenReturn(List.of(events));
    }

    @Test
    void doesNothingWhenAnotherInstanceIsTheLeader() {
        when(lease.isLeader()).thenReturn(false);

        assertThat(outboxRelay.relayOnce()).isZero();

        verifyNoInteractions(outboxStore, kafkaTemplate);
        verify(lease).close();
    }

    @Test
    void asksForAsManyEventsAsTheConfiguredBatchSize() {
        givenUnsent();

        assertThat(outboxRelay.relayOnce()).isZero();

        verify(outboxStore).findUnsent(BATCH_SIZE);
    }

    @Test
    void publishesInOrderWithTheAccountAsKey() {
        Message first = event(1, 10);
        Message second = event(2, 20);
        givenUnsent(first, second);
        when(kafkaTemplate.send(eq("transfers"), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(2);
        InOrder order = inOrder(kafkaTemplate, outboxStore);
        order.verify(kafkaTemplate).send("transfers", "10", first.payload());
        order.verify(outboxStore).markSent(1);
        order.verify(kafkaTemplate).send("transfers", "20", second.payload());
        order.verify(outboxStore).markSent(2);
    }

    @Test
    void stopsAtTheFirstFailureAndSendsNothingAfterIt() {
        Message first = event(1, 10);
        Message second = event(2, 10);
        Message third = event(3, 10);
        givenUnsent(first, second, third);
        when(kafkaTemplate.send("transfers", "10", first.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        when(kafkaTemplate.send("transfers", "10", second.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new RuntimeException("broker down")));

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(1);
        verify(outboxStore).markSent(1);
        verify(outboxStore, never()).markSent(2);
        verify(kafkaTemplate, never()).send("transfers", "10", third.payload());
    }

    @Test
    void anUnexpectedSendFailureStopsTheBatch() {
        Message first = event(1, 10);
        Message second = event(2, 10);
        givenUnsent(first, second);
        when(kafkaTemplate.send("transfers", "10", first.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        when(kafkaTemplate.send("transfers", "10", second.payload())).thenThrow(new IllegalStateException("producer closed"));

        assertThat(outboxRelay.relayOnce()).isEqualTo(1);
    }

    @Test
    void stopsBeforeSendingWhenTheLeadershipIsLost() {
        Message first = event(1, 10);
        Message second = event(2, 10);
        givenUnsent(first, second);
        when(kafkaTemplate.send("transfers", "10", first.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        doNothing().doThrow(new LeadershipLostException("session lost")).when(lease).ensureHeld();

        assertThat(outboxRelay.relayOnce()).isEqualTo(1);

        verify(outboxStore).markSent(1);
        verify(kafkaTemplate, never()).send("transfers", "10", second.payload());
    }

    @Test
    void releasesTheLeaseWhenTheBatchFails() {
        when(outboxStore.findUnsent(BATCH_SIZE)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(outboxRelay::relayOnce).isInstanceOf(IllegalStateException.class);

        verify(lease).close();
    }
}
