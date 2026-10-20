package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transfer;
import com.robotics.demo.ledger.persistence.jpa.OutboxRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.OutboxEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Mock
    private OutboxRepository outboxRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxRepository, JsonMapper.builder().build());
    }

    private static Transfer transfer(Transfer.TransferStatus status, String errorCode) {
        Instant now = Instant.now();
        return Transfer.of(7L, 101L, 1L, 2L, new Money(2500, EUR), status, errorCode, now, now);
    }

    private OutboxEntity savedRow() {
        ArgumentCaptor<OutboxEntity> row = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(row.capture());
        return row.getValue();
    }

    @Test
    void aCreatedTransferIsSavedAsAnUnsentCreatedEventKeyedByTheSourceAccount() {
        outboxService.saveCreated(transfer(Transfer.TransferStatus.PENDING, null));

        OutboxEntity row = savedRow();
        assertThat(row.getAccountId()).isEqualTo(1L);
        assertThat(row.getTransferId()).isEqualTo(7L);
        assertThat(row.getEventType()).isEqualTo(OutboxEntity.EventType.created);
        assertThat(row.getTopic()).isEqualTo("transfers");
        assertThat(row.getPayload()).contains("\"transferId\":7").contains("\"accountFrom\":1")
                .contains("\"accountTo\":2").contains("\"amount\":2500").contains("\"currency\":\"EUR\"");
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void aProcessedTransferIsSavedAsAProcessedEventWithItsStatusAndErrorCode() {
        outboxService.saveProcessed(transfer(Transfer.TransferStatus.FAILED, "tx-ex-02"));

        OutboxEntity row = savedRow();
        assertThat(row.getAccountId()).isEqualTo(1L);
        assertThat(row.getTransferId()).isEqualTo(7L);
        assertThat(row.getEventType()).isEqualTo(OutboxEntity.EventType.processed);
        assertThat(row.getTopic()).isEqualTo("transfer-results");
        assertThat(row.getPayload()).contains("\"transferId\":7").contains("\"status\":\"FAILED\"")
                .contains("\"errorCode\":\"tx-ex-02\"");
    }
}
