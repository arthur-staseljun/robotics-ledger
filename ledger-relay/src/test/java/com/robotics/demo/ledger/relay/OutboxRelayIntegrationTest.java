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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@Tag("container")
@SpringBootTest(properties = "spring.main.web-application-type=none")
class OutboxRelayIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(15);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        POSTGRES.start();
        KAFKA.start();
        try (Connection connection = POSTGRES.createConnection("")) {
            LedgerDatabase.migrate(new SingleConnectionDataSource(connection, true));
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
        registry.add("ledger.relay.initial-delay", () -> "1h");
        registry.add("ledger.relay.delay", () -> "1h");
    }

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LedgerDatabase ledger;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private LeaderLockProvider leaderLockProvider;

    @MockitoSpyBean
    private KafkaTemplate<?, ?> kafkaTemplate;

    @BeforeEach
    void markEverythingFromEarlierTestsAsSent() {
        ledger = new LedgerDatabase(jdbcTemplate);
        jdbcTemplate.update("update outbox set sent_at = now() where sent_at is null");
    }

    private long newAccount() {
        return ledger.newAccount();
    }

    private long createdEvent(long from, long to) {
        return ledger.unsentEvent(from, to);
    }

    private static long transferIdOf(ConsumerRecord<String, String> message) {
        return JSON.readTree(message.value()).get("transferId").asLong();
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
                consumer.poll(Duration.ofMillis(200)).forEach(message -> {
                    if (wanted.test(message)) {
                        found.add(message);
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
        List<ConsumerRecord<String, String>> records = consume(1, message -> transferIdOf(message) == transferId);
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
        List<ConsumerRecord<String, String>> records = consume(5, message -> message.key().equals(String.valueOf(from)));
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

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stringTemplate() {
        return (KafkaTemplate<String, String>) kafkaTemplate;
    }

    @Test
    void noDatabaseTransactionIsOpenWhileAnEventIsBeingSent() {
        long from = newAccount();
        long to = newAccount();
        createdEvent(from, to);
        AtomicInteger openTransactionsWhileSending = new AtomicInteger(-1);
        doAnswer(invocation -> {
            openTransactionsWhileSending.set(jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'",
                    Integer.class));
            return invocation.callRealMethod();
        }).when(stringTemplate()).send(anyString(), anyString(), anyString());

        int sent = outboxRelay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(openTransactionsWhileSending.get()).isZero();
    }

    @Test
    void aLeaderWhoseSessionDiesStopsBeforeSendingTheNextEvent() {
        long from = newAccount();
        long to = newAccount();
        for (int i = 0; i < 3; i++) {
            createdEvent(from, to);
        }
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (sends.incrementAndGet() == 1) {
                ledger.terminateTheSessionHoldingTheLeaderLock();
            }
            return result;
        }).when(stringTemplate()).send(anyString(), anyString(), anyString());

        assertThat(outboxRelay.relayOnce()).isEqualTo(1);

        assertThat(sends.get()).isEqualTo(1);
        assertThat(unsentCount()).isEqualTo(2);
    }

    @Test
    void afterALeaderLosesItsSessionTheNextTickSendsTheRestAndTheAccountKeepsItsOrder() {
        long from = newAccount();
        long to = newAccount();
        List<Long> transferIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            transferIds.add(createdEvent(from, to));
        }
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (sends.incrementAndGet() == 1) {
                ledger.terminateTheSessionHoldingTheLeaderLock();
            }
            return result;
        }).when(stringTemplate()).send(anyString(), anyString(), anyString());
        assertThat(outboxRelay.relayOnce()).isEqualTo(1);

        int sentAfterwards = outboxRelay.relayOnce();

        assertThat(sentAfterwards).isEqualTo(2);
        assertThat(unsentCount()).isZero();
        List<ConsumerRecord<String, String>> records = consume(3, message -> message.key().equals(String.valueOf(from)));
        assertThat(records).hasSize(3);
        assertThat(records.stream()
                .sorted(Comparator.comparingLong(ConsumerRecord::offset))
                .map(OutboxRelayIntegrationTest::transferIdOf)
                .toList()).isEqualTo(transferIds);
    }

    private void drain(OutboxRelay relay, AtomicInteger published) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(20)).until(() -> {
            published.addAndGet(relay.relayOnce());
            return unsentCount() == 0;
        });
    }

    @Test
    void twoRelaysRunningTogetherPublishEveryEventOnceAndKeepTheOrderOfEachAccount() throws Exception {
        OutboxRelay secondRelay = new OutboxRelay(outboxStore, leaderLockProvider, stringTemplate(), 100, Duration.ofSeconds(10));
        long to = newAccount();
        List<Long> accounts = List.of(newAccount(), newAccount(), newAccount(), newAccount());
        Map<String, List<Long>> createdOrder = new HashMap<>();
        for (int round = 0; round < 10; round++) {
            for (long account : accounts) {
                createdOrder.computeIfAbsent(String.valueOf(account), key -> new ArrayList<>())
                        .add(createdEvent(account, to));
            }
        }
        AtomicInteger firstPublished = new AtomicInteger();
        AtomicInteger secondPublished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> drain(outboxRelay, firstPublished));
            Future<?> second = pool.submit(() -> drain(secondRelay, secondPublished));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(firstPublished.get() + secondPublished.get()).isEqualTo(40);
            assertThat(Math.min(firstPublished.get(), secondPublished.get())).isZero();
            assertThat(unsentCount()).isZero();
            List<ConsumerRecord<String, String>> records = consume(40, message -> createdOrder.containsKey(message.key()));
            assertThat(records).hasSize(40);
            createdOrder.forEach((account, expectedOrder) -> assertThat(records.stream()
                    .filter(message -> message.key().equals(account))
                    .sorted((a, b) -> Long.compare(a.offset(), b.offset()))
                    .map(OutboxRelayIntegrationTest::transferIdOf)
                    .toList()).isEqualTo(expectedOrder));
        } finally {
            pool.shutdownNow();
        }
    }
}
