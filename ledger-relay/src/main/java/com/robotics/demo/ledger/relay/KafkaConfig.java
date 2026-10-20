package com.robotics.demo.ledger.relay;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String TRANSFERS_TOPIC = "transfers";
    public static final String TRANSFER_RESULTS_TOPIC = "transfer-results";

    @Bean
    public NewTopic transfersTopic(@Value("${ledger.relay.partitions}") int partitions,
                                   @Value("${ledger.relay.replicas}") int replicas) {
        return TopicBuilder.name(TRANSFERS_TOPIC)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic transferResultsTopic(@Value("${ledger.relay.partitions}") int partitions,
                                          @Value("${ledger.relay.replicas}") int replicas) {
        return TopicBuilder.name(TRANSFER_RESULTS_TOPIC)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
