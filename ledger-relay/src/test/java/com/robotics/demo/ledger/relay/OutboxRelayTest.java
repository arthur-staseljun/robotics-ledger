package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.OutboxStore.Message;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final int BATCH_SIZE = 25;

    @Mock
    private OutboxStore outboxStore;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        outboxRelay = new OutboxRelay(outboxStore, kafkaTemplate, BATCH_SIZE, Duration.ofSeconds(10));
    }

    private static Message event(long id, long accountId) {
        return new Message(id, accountId, "transfers", "{\"transferId\":" + id + "}");
    }

    private void givenLeadership() {
        when(outboxStore.tryAcquireLeadership(7_001L)).thenReturn(true);
    }

    @Test
    void doesNothingWhenAnotherInstanceIsTheLeader() {
        when(outboxStore.tryAcquireLeadership(7_001L)).thenReturn(false);

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isZero();
        verify(outboxStore, never()).findUnsentForUpdate(anyInt());
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(String.class));
    }

    @Test
    void asksForAsManyEventsAsTheConfiguredBatchSize() {
        givenLeadership();
        when(outboxStore.findUnsentForUpdate(BATCH_SIZE)).thenReturn(List.of());

        outboxRelay.relayOnce();

        verify(outboxStore).findUnsentForUpdate(BATCH_SIZE);
    }

    @Test
    void doesNothingWhenThereAreNoUnsentEvents() {
        givenLeadership();
        when(outboxStore.findUnsentForUpdate(anyInt())).thenReturn(List.of());

        assertThat(outboxRelay.relayOnce()).isZero();
    }

    @Test
    void publishesInOrderWithTheAccountAsKeyAndMarksEventsSent() {
        Message first = event(1, 10);
        Message second = event(2, 20);
        givenLeadership();
        when(outboxStore.findUnsentForUpdate(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(eq("transfers"), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(2);
        InOrder order = inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send("transfers", "10", first.payload());
        order.verify(kafkaTemplate).send("transfers", "20", second.payload());
        verify(outboxStore).markSent(1);
        verify(outboxStore).markSent(2);
    }

    @Test
    void stopsAtTheFirstFailureAndKeepsTheRestUnsent() {
        Message first = event(1, 10);
        Message second = event(2, 10);
        Message third = event(3, 10);
        givenLeadership();
        when(outboxStore.findUnsentForUpdate(anyInt())).thenReturn(List.of(first, second, third));
        when(kafkaTemplate.send("transfers", "10", first.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        when(kafkaTemplate.send("transfers", "10", second.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new RuntimeException("broker down")));

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(1);
        verify(outboxStore).markSent(1);
        verify(outboxStore, never()).markSent(2);
        verify(outboxStore, never()).markSent(3);
        verify(kafkaTemplate, never()).send("transfers", "10", third.payload());
    }

    @Test
    void anUnexpectedSendFailureStopsTheBatchAndKeepsTheMarksOfTheEventsAlreadySent() {
        Message first = event(1, 10);
        Message second = event(2, 10);
        givenLeadership();
        when(outboxStore.findUnsentForUpdate(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send("transfers", "10", first.payload()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        when(kafkaTemplate.send("transfers", "10", second.payload())).thenThrow(new IllegalStateException("producer closed"));

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(1);
        verify(outboxStore).markSent(1);
        verify(outboxStore, never()).markSent(2);
    }
}
