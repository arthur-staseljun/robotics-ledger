package com.robotics.demo.ledger.consumer;

import com.robotics.demo.ledger.AbstractPostgresIntegrationTest;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.model.domain.Transfer.TransferStatus;
import com.robotics.demo.ledger.model.event.TransferCreatedEvent;
import com.robotics.demo.ledger.service.CustomerService;
import com.robotics.demo.ledger.service.AccountService;
import com.robotics.demo.ledger.service.TransferService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Currency;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Tag("container")
@SpringBootTest(properties = "ledger.consumer.retry-interval-ms=100")
class TransferConsumerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(30);
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockitoSpyBean
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private TransferConsumer transferConsumer;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${ledger.consumer.partitions}")
    private int partitions;

    private static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), EUR);
    }

    private Long fundedAccount(String amount) {
        Instant now = Instant.now();
        Long ownerId = customerService.createCustomer().getCustomerId();
        return accountService.save(Account.of(null, ownerId, eur(amount), Account.AccountStatus.ACTIVE, 0, now, now))
                .getId();
    }

    private Long pendingTransfer(Long from, Long to, String amount) {
        return transferService.accept("test-client", UUID.randomUUID(), from, to, eur(amount))
                .transfer().getTransferId();
    }

    private void publishCreatedEvent(Long transferId, Long accountFrom) throws Exception {
        String payload = jdbcTemplate.queryForObject(
                "select payload::text from outbox where transfer_id = ?", String.class, transferId);
        kafkaTemplate.send(TransferCreatedEvent.TOPIC, String.valueOf(accountFrom), payload)
                .get(10, TimeUnit.SECONDS);
    }

    private void awaitStatus(Long transferId, TransferStatus expected) {
        await().atMost(SETTLE_TIMEOUT).untilAsserted(
                () -> assertThat(transferService.getTransfer(transferId).getStatus()).isEqualTo(expected));
    }

    private void publishRaw(Long accountFrom, String payload) throws Exception {
        kafkaTemplate.send(TransferCreatedEvent.TOPIC, String.valueOf(accountFrom), payload)
                .get(10, TimeUnit.SECONDS);
    }

    private List<ConsumerRecord<String, String>> awaitDeadLetters(Long accountFrom) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> dlqPartitions = consumer.partitionsFor(TransferDeadLetterRecoverer.DLQ_TOPIC).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(dlqPartitions);
            consumer.seekToBeginning(dlqPartitions);
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(message -> {
                    if (String.valueOf(accountFrom).equals(message.key())) {
                        found.add(message);
                    }
                });
            }
        }
        return found;
    }

    private long balanceOf(Long accountId) {
        return accountService.getExistingAccount(accountId).getBalance().amount();
    }

    @Test
    void settlesAPendingTransferWhenItsEventArrives() throws Exception {
        Long from = fundedAccount("100.00");
        Long to = fundedAccount("0.00");
        Long transferId = pendingTransfer(from, to, "25.50");

        publishCreatedEvent(transferId, from);

        awaitStatus(transferId, TransferStatus.SUCCESSFUL);
        assertThat(balanceOf(from)).isEqualTo(7450);
        assertThat(balanceOf(to)).isEqualTo(2550);
    }

    @Test
    void aRedeliveredEventIsAppliedOnce() throws Exception {
        Long from = fundedAccount("100.00");
        Long to = fundedAccount("0.00");
        Long first = pendingTransfer(from, to, "10.00");
        Long second = pendingTransfer(from, to, "5.00");

        publishCreatedEvent(first, from);
        publishCreatedEvent(first, from);
        publishCreatedEvent(second, from);

        awaitStatus(second, TransferStatus.SUCCESSFUL);
        assertThat(transferService.getTransfer(first).getStatus()).isEqualTo(TransferStatus.SUCCESSFUL);
        assertThat(balanceOf(from)).isEqualTo(8500);
        assertThat(balanceOf(to)).isEqualTo(1500);
    }

    @Test
    void failsATransferTheSourceCannotCover() throws Exception {
        Long from = fundedAccount("10.00");
        Long to = fundedAccount("0.00");
        Long transferId = pendingTransfer(from, to, "25.00");

        publishCreatedEvent(transferId, from);

        awaitStatus(transferId, TransferStatus.FAILED);
        assertThat(balanceOf(from)).isEqualTo(1000);
        assertThat(balanceOf(to)).isZero();
    }

    @Test
    void anUnreadableMessageGoesStraightToTheDlqAndFreezesTheAccount() throws Exception {
        Long from = fundedAccount("100.00");

        publishRaw(from, "not json");

        List<ConsumerRecord<String, String>> deadLetters = awaitDeadLetters(from);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().value()).isEqualTo("not json");
        assertThat(accountService.getExistingAccount(from).getStatus()).isEqualTo(Account.AccountStatus.FROZEN);
    }

    @Test
    void aMessageOfAnUnknownTransferGoesToTheDlqAndFreezesTheAccount() throws Exception {
        Long from = fundedAccount("100.00");

        publishRaw(from, "{\"transferId\": 999999999, \"accountFrom\": " + from
                + ", \"accountTo\": 1, \"amount\": 100, \"currency\": \"EUR\"}");

        assertThat(awaitDeadLetters(from)).hasSize(1);
        assertThat(accountService.getExistingAccount(from).getStatus()).isEqualTo(Account.AccountStatus.FROZEN);
    }

    @Test
    void aTemporaryFailureIsRetriedUntilItSucceeds() throws Exception {
        Long from = fundedAccount("100.00");
        Long to = fundedAccount("0.00");
        Long transferId = pendingTransfer(from, to, "10.00");
        doThrow(new QueryTimeoutException("db down"), new QueryTimeoutException("db down"))
                .doCallRealMethod()
                .when(transferService).process(transferId);

        publishCreatedEvent(transferId, from);

        awaitStatus(transferId, TransferStatus.SUCCESSFUL);
        verify(transferService, atLeast(3)).process(transferId);
        assertThat(accountService.getExistingAccount(from).getStatus()).isEqualTo(Account.AccountStatus.ACTIVE);
        assertThat(balanceOf(from)).isEqualTo(9000);
    }

    @Test
    void twoInstancesInOneGroupShareThePartitionsAndEveryTransferIsAppliedOnce() throws Exception {
        ContainerProperties containerProperties = new ContainerProperties(TransferCreatedEvent.TOPIC);
        containerProperties.setGroupId(groupId);
        containerProperties.setMessageListener(
                (MessageListener<String, String>) message -> transferConsumer.onTransferCreated(message.value()));
        KafkaMessageListenerContainer<String, String> secondInstance =
                new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        MessageListenerContainer firstInstance = listenerRegistry.getListenerContainers().iterator().next();

        secondInstance.start();
        try {
            await().atMost(SETTLE_TIMEOUT).untilAsserted(() -> {
                Set<TopicPartition> first = new HashSet<>(firstInstance.getAssignedPartitions());
                Set<TopicPartition> second = new HashSet<>(secondInstance.getAssignedPartitions());
                assertThat(first).isNotEmpty();
                assertThat(second).isNotEmpty();
                assertThat(first).doesNotContainAnyElementsOf(second);
                assertThat(first.size() + second.size()).isEqualTo(partitions);
            });

            List<Long> sources = new ArrayList<>();
            List<Long> transfers = new ArrayList<>();
            Long to = fundedAccount("0.00");
            for (int i = 0; i < 12; i++) {
                Long from = fundedAccount("100.00");
                sources.add(from);
                transfers.add(pendingTransfer(from, to, "10.00"));
            }
            for (int i = 0; i < transfers.size(); i++) {
                publishCreatedEvent(transfers.get(i), sources.get(i));
            }

            transfers.forEach(transferId -> awaitStatus(transferId, TransferStatus.SUCCESSFUL));
            sources.forEach(from -> assertThat(balanceOf(from)).isEqualTo(9000));
            assertThat(balanceOf(to)).isEqualTo(12000);
        } finally {
            secondInstance.stop();
        }
    }
}
