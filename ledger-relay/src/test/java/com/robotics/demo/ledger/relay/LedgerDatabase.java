package com.robotics.demo.ledger.relay;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class LedgerDatabase {

    private static final Path MIGRATIONS = Path.of("..", "ledger-app", "src", "main", "resources", "db", "migration");

    private final JdbcTemplate jdbcTemplate;

    LedgerDatabase(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    static void migrate(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (Path migration : migrationsInOrder()) {
                statement.execute(Files.readString(migration));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static List<Path> migrationsInOrder() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(file -> file.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(LedgerDatabase::versionOf))
                    .toList();
        }
    }

    private static int versionOf(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    long newAccount() {
        long ownerId = jdbcTemplate.queryForObject(
                "insert into customers default values returning customer_id", Long.class);
        return jdbcTemplate.queryForObject(
                "insert into accounts (owner_id, balance, currency) values (?, 0, 'EUR') returning account_id",
                Long.class, ownerId);
    }

    long unsentEvent(long from, long to) {
        long ownerId = jdbcTemplate.queryForObject("select owner_id from accounts where account_id = ?", Long.class, from);
        long transferId = jdbcTemplate.queryForObject("""
                insert into transfers (owner_id, account_from, account_to, amount, currency)
                values (?, ?, ?, 100, 'EUR') returning transfer_id
                """, Long.class, ownerId, from, to);
        String payload = "{\"transferId\":" + transferId + ",\"accountFrom\":" + from + ",\"accountTo\":" + to
                + ",\"amount\":100,\"currency\":\"EUR\"}";
        jdbcTemplate.update("""
                insert into outbox (account_id, transfer_id, event_type, topic, payload)
                values (?, ?, 'created', ?, ?::jsonb)
                """, from, transferId, KafkaConfig.TRANSFERS_TOPIC, payload);
        return transferId;
    }

    int leaderLocks() {
        return jdbcTemplate.queryForObject(
                "select count(*) from pg_locks where locktype = 'advisory' and objid = 7001 and granted", Integer.class);
    }

    void terminateTheSessionHoldingTheLeaderLock() {
        Integer pid = jdbcTemplate.queryForObject(
                "select pid from pg_locks where locktype = 'advisory' and objid = 7001 and granted", Integer.class);
        jdbcTemplate.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid);
    }
}
