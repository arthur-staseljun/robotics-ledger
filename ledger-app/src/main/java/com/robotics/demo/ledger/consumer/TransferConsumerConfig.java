package com.robotics.demo.ledger.consumer;

import com.robotics.demo.ledger.model.event.TransferCreatedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
}
