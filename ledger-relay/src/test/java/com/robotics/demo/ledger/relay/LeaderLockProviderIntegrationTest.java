package com.robotics.demo.ledger.relay;

import com.robotics.demo.ledger.relay.Lease.LeadershipLostException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("container")
class LeaderLockProviderIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DataSource dataSource;
    private static LedgerDatabase ledger;
    private static LeaderLockProvider leaderLockProvider;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
        dataSource = dataSourceOf(new PGSimpleDataSource());
        ledger = new LedgerDatabase(new JdbcTemplate(dataSource));
        leaderLockProvider = new LeaderLockProvider(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    private static <T extends PGSimpleDataSource> T dataSourceOf(T source) {
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static boolean canBecomeLeader() {
        try (Lease lease = leaderLockProvider.tryAcquire()) {
            return lease.isLeader();
        }
    }

    @Test
    void theLeaseHoldsTheLockUntilItIsClosed() {
        try (Lease lease = leaderLockProvider.tryAcquire()) {
            assertThat(lease.isLeader()).isTrue();
            assertThat(ledger.leaderLocks()).isEqualTo(1);
        }

        assertThat(ledger.leaderLocks()).isZero();
    }

    @Test
    void noOneBecomesTheLeaderWhileAnotherSessionHoldsTheLock() throws SQLException {
        try (Connection other = dataSource.getConnection()) {
            other.createStatement().execute("select pg_advisory_lock(7001)");

            try (Lease lease = leaderLockProvider.tryAcquire()) {
                assertThat(lease.isLeader()).isFalse();
            }

            other.createStatement().execute("select pg_advisory_unlock(7001)");
        }

        assertThat(canBecomeLeader()).isTrue();
    }

    @Test
    void aLeaseThatIsNotTheLeaderFailsTheCheck() throws SQLException {
        try (Connection other = dataSource.getConnection()) {
            other.createStatement().execute("select pg_advisory_lock(7001)");

            try (Lease lease = leaderLockProvider.tryAcquire()) {
                assertThatThrownBy(lease::ensureHeld).isInstanceOf(LeadershipLostException.class);
            }

            other.createStatement().execute("select pg_advisory_unlock(7001)");
        }
    }

    @Test
    void theLockIsReleasedWhenTheWorkUnderTheLeaseFails() {
        assertThatThrownBy(() -> {
            try (Lease lease = leaderLockProvider.tryAcquire()) {
                lease.ensureHeld();
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(ledger.leaderLocks()).isZero();
        assertThat(canBecomeLeader()).isTrue();
    }

    @Test
    void theLeaseFailsTheCheckOnceTheSessionHoldingTheLockDies() {
        try (Lease lease = leaderLockProvider.tryAcquire()) {
            lease.ensureHeld();
            ledger.terminateTheSessionHoldingTheLeaderLock();

            assertThatThrownBy(lease::ensureHeld).isInstanceOf(LeadershipLostException.class);
        }

        assertThat(ledger.leaderLocks()).isZero();
        assertThat(canBecomeLeader()).isTrue();
    }

    @Test
    void theLeaseFailsTheCheckWhenTheLockIsGoneButTheSessionIsStillAlive() throws SQLException {
        AtomicReference<Connection> issued = new AtomicReference<>();
        PGSimpleDataSource capturing = dataSourceOf(new PGSimpleDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                issued.set(connection);
                return connection;
            }
        });

        try (Lease lease = new LeaderLockProvider(capturing).tryAcquire()) {
            lease.ensureHeld();
            try (Statement statement = issued.get().createStatement()) {
                statement.execute("select pg_advisory_unlock_all()");
            }

            assertThatThrownBy(lease::ensureHeld)
                    .isInstanceOf(LeadershipLostException.class)
                    .hasMessageContaining("no longer held");
        }

        assertThat(ledger.leaderLocks()).isZero();
        assertThat(canBecomeLeader()).isTrue();
    }

    @Test
    void aFailureAfterTheServerGrantedTheLockDoesNotLeaveItWithTheConnection() throws SQLException {
        PooledLikeDataSource pool = dataSourceOf(new PooledLikeDataSource());
        try {
            assertThatThrownBy(() -> new LeaderLockProvider(pool).tryAcquire()).isInstanceOf(DataAccessException.class);

            assertThat(ledger.leaderLocks()).isZero();
            assertThat(canBecomeLeader()).isTrue();
        } finally {
            pool.closePhysicalConnection();
        }
    }

    private static final class PooledLikeDataSource extends PGSimpleDataSource {

        private transient Connection physical;

        @Override
        public Connection getConnection() throws SQLException {
            if (physical == null || physical.isClosed()) {
                physical = super.getConnection();
            }
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> null;
                        case "abort" -> {
                            physical.abort((Executor) args[0]);
                            yield null;
                        }
                        case "prepareStatement" -> lockStatementFailingAfterExecute(invoke(physical, method, args),
                                String.valueOf(args[0]));
                        default -> invoke(physical, method, args);
                    });
        }

        void closePhysicalConnection() throws SQLException {
            if (physical != null) {
                physical.close();
            }
        }

        private static Object lockStatementFailingAfterExecute(Object statement, String sql) {
            if (!sql.contains("pg_try_advisory_lock")) {
                return statement;
            }
            return Proxy.newProxyInstance(statement.getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        Object result = invoke(statement, method, args);
                        if (method.getName().startsWith("execute")) {
                            throw new SQLException("connection reset after the lock was granted");
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
