package com.robotics.demo.ledger.relay;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("container")
@SpringBootTest(properties = "spring.main.web-application-type=none")
class OutboxRelayIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong IDS = new AtomicLong();
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(15);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        POSTGRES.start();
        KAFKA.start();
        try (Connection connection = POSTGRES.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("outbox-schema.sql"));
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ledger.relay.initial-delay-ms", () -> "3600000");
        registry.add("ledger.relay.delay-ms", () -> "3600000");
    }

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void markEverythingFromEarlierTestsAsSent() {
        jdbcTemplate.update("update outbox set sent_at = now() where sent_at is null");
    }

    private long newAccount() {
        return IDS.incrementAndGet();
    }

    private long createdEvent(long from, long to) {
        long transferId = IDS.incrementAndGet();
        String payload = JSON.writeValueAsString(Map.of(
                "transferId", transferId, "accountFrom", from, "accountTo", to, "amount", 100, "currency", "EUR"));
        jdbcTemplate.update("""
                insert into outbox (account_id, transfer_id, event_type, topic, payload)
                values (?, ?, 'created', ?, ?::jsonb)
                """, from, transferId, KafkaConfig.TRANSFERS_TOPIC, payload);
        return transferId;
    }

    private static long transferIdOf(ConsumerRecord<String, String> record) {
        return JSON.readTree(record.value()).get("transferId").asLong();
    }

    private List<ConsumerRecord<String, String>> consume(int expected,
                                                         Predicate<ConsumerRecord<String, String>> wanted) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = consumer.partitionsFor(KafkaConfig.TRANSFERS_TOPIC).stream()
                    .map((PartitionInfo info) -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + CONSUME_TIMEOUT.toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (wanted.test(record)) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    private int unsentCount() {
        return jdbcTemplate.queryForObject("select count(*) from outbox where sent_at is null", Integer.class);
    }

    @Test
    void createdEventIsPublishedWithTheSourceAccountAsKeyAndMarkedSent() {
        long from = newAccount();
        long to = newAccount();
        long transferId = createdEvent(from, to);

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(1);
        List<ConsumerRecord<String, String>> records = consume(1, record -> transferIdOf(record) == transferId);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(String.valueOf(from));
        assertThat(JSON.readTree(records.getFirst().value()).get("accountTo").asLong()).isEqualTo(to);
        assertThat(unsentCount()).isZero();
        assertThat(outboxRelay.relayOnce()).isZero();
    }

    @Test
    void eventsOfOneAccountArePublishedInOrderToOnePartition() {
        long from = newAccount();
        long to = newAccount();
        List<Long> transferIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            transferIds.add(createdEvent(from, to));
        }

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(5);
        List<ConsumerRecord<String, String>> records = consume(5, record -> record.key().equals(String.valueOf(from)));
        assertThat(records).hasSize(5);
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
        List<Long> publishedOrder = records.stream()
                .sorted((a, b) -> Long.compare(a.offset(), b.offset()))
                .map(OutboxRelayIntegrationTest::transferIdOf)
                .toList();
        assertThat(publishedOrder).isEqualTo(transferIds);
    }

    @Test
    void anotherInstanceHoldingTheLeaderLockBlocksPublishing() throws Exception {
        long from = newAccount();
        long to = newAccount();
        createdEvent(from, to);

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.createStatement().execute("select pg_advisory_lock(7001)");

            assertThat(outboxRelay.relayOnce()).isZero();
            assertThat(unsentCount()).isEqualTo(1);

            otherInstance.createStatement().execute("select pg_advisory_unlock(7001)");
        }

        assertThat(outboxRelay.relayOnce()).isEqualTo(1);
        assertThat(unsentCount()).isZero();
    }
}
