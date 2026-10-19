package com.robotics.demo.ledger.consumer;

import com.robotics.demo.ledger.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransferConsumerTest {

    @Mock
    private TransferService transferService;

    private TransferConsumer transferConsumer;

    @BeforeEach
    void setUp() {
        transferConsumer = new TransferConsumer(transferService, JsonMapper.builder().build());
    }

    @Test
    void processesTheTransferFromTheEvent() {
        transferConsumer.onTransferCreated(
                "{\"transferId\": 42, \"accountFrom\": 1, \"accountTo\": 2, \"amount\": 100, \"currency\": \"EUR\"}");

        verify(transferService).process(42L);
    }

    @Test
    void rejectsAPayloadThatIsNotAnEvent() {
        assertThatThrownBy(() -> transferConsumer.onTransferCreated("not json"))
                .isInstanceOf(JacksonException.class);

        verify(transferService, never()).process(anyLong());
    }
}
