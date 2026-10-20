package com.robotics.demo.ledger.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@RequiredArgsConstructor
public class LeaderLockProvider {

    static final long LEADER_LOCK_KEY = 7_001L;

    private final DataSource dataSource;

    public Lease tryAcquire() {
        Connection connection = connect();
        JdbcClient session = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        try {
            return new Lease(connection, session, tryLock(session));
        } catch (RuntimeException ex) {
            Lease.release(connection, session);
            throw ex;
        }
    }

    private Connection connect() {
        try {
            return dataSource.getConnection();
        } catch (SQLException ex) {
            throw new DataAccessResourceFailureException("The leader lock connection failed", ex);
        }
    }

    private static boolean tryLock(JdbcClient session) {
        return Boolean.TRUE.equals(session.sql("select pg_try_advisory_lock(:key)")
                .param("key", LEADER_LOCK_KEY)
                .query(Boolean.class)
                .single());
    }
}
