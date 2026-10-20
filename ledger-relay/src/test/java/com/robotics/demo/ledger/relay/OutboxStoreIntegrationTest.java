package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.OutboxStore.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("container")
class OutboxStoreIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static LedgerDatabase ledger;
    private static OutboxStore store;

    @BeforeAll
    static void startDatabase() throws SQLException {
        POSTGRES.start();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        LedgerDatabase.migrate(source);
        jdbcTemplate = new JdbcTemplate(source);
        ledger = new LedgerDatabase(jdbcTemplate);
        store = new OutboxStore(JdbcClient.create(source));
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void markEverythingFromEarlierTestsAsSent() {
        jdbcTemplate.update("update outbox set sent_at = now() where sent_at is null");
    }

    private static List<Long> unsentEvents(int count) {
        long from = ledger.newAccount();
        long to = ledger.newAccount();
        List<Long> transferIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            transferIds.add(ledger.unsentEvent(from, to));
        }
        return transferIds;
    }

    private static int unsentCount() {
        return jdbcTemplate.queryForObject("select count(*) from outbox where sent_at is null", Integer.class);
    }

    @Test
    void findsUnsentEventsInIdOrderRegardlessOfTheirCreationTime() {
        unsentEvents(3);
        List<Long> ids = store.findUnsent(10).stream().map(Message::id).toList();
        for (int i = 0; i < ids.size(); i++) {
            jdbcTemplate.update("update outbox set created_at = now() - make_interval(secs => ?) where id = ?",
                    i, ids.get(i));
        }

        List<Message> found = store.findUnsent(10);

        assertThat(found).extracting(Message::id).isSorted().containsExactlyElementsOf(ids);
        assertThat(found).allSatisfy(message -> {
            assertThat(message.topic()).isEqualTo(KafkaConfig.TRANSFERS_TOPIC);
            assertThat(message.payload()).contains("transferId");
        });
    }

    @Test
    void respectsTheLimit() {
        unsentEvents(5);

        assertThat(store.findUnsent(2)).hasSize(2);
    }

    @Test
    void doesNotFindSentEvents() {
        unsentEvents(2);
        Message first = store.findUnsent(10).getFirst();

        store.markSent(first.id());

        assertThat(store.findUnsent(10)).extracting(Message::id).doesNotContain(first.id()).hasSize(1);
        assertThat(unsentCount()).isEqualTo(1);
    }
}
