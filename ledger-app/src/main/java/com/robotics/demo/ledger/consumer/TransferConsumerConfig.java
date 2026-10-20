package com.robotics.demo.ledger.consumer;

import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.event.TransferCreatedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class TransferConsumerConfig {

    @Bean
    public NewTopic transfersTopic(@Value("${ledger.consumer.partitions}") int partitions,
                                   @Value("${ledger.consumer.replicas}") int replicas) {
        return TopicBuilder.name(TransferCreatedEvent.TOPIC)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic transfersDlqTopic(@Value("${ledger.consumer.partitions}") int partitions,
                                      @Value("${ledger.consumer.replicas}") int replicas) {
        return TopicBuilder.name(TransferDeadLetterRecoverer.DLQ_TOPIC) // shall exist before the first poisoned message
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public DefaultErrorHandler transferErrorHandler(TransferDeadLetterRecoverer recoverer,
                                                    @Value("${ledger.consumer.retry-interval-ms}") long retryIntervalMs) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retryIntervalMs, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.addNotRetryableExceptions(JacksonException.class, LedgerServiceException.class,
                ArithmeticException.class, IllegalArgumentException.class);
        return errorHandler;
    }
}
