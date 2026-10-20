package com.robotics.demo.ledger.consumer;

import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TransferDeadLetterRecoverer implements ConsumerRecordRecoverer {

    public static final String DLQ_TOPIC = "transfers-dlq";

    private final AccountService accountService;
    private final DeadLetterPublishingRecoverer publisher;

    public TransferDeadLetterRecoverer(AccountService accountService, KafkaTemplate<String, String> kafkaTemplate) {
        this.accountService = accountService;
        this.publisher = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (message, exception) -> new TopicPartition(DLQ_TOPIC, -1));
    }

    @Override
    public void accept(ConsumerRecord<?, ?> message, Exception exception) {
        log.error("Transfer message {}-{}@{} goes to the DLQ: {}", message.topic(), message.partition(),
                message.offset(), exception.toString());
        freezeSourceAccount(message);
        publisher.accept(message, exception);
    }

    private void freezeSourceAccount(ConsumerRecord<?, ?> message) {
        try {
            accountService.freeze(Long.parseLong(String.valueOf(message.key())));
        } catch (NumberFormatException | LedgerServiceException ex) {
            log.warn("The account of message {}-{}@{} was not frozen: {}", message.topic(), message.partition(),
                    message.offset(), ex.toString());
        }
    }
}
