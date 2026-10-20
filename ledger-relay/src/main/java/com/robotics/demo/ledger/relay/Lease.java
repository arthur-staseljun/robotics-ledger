package com.robotics.demo.ledger.relay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Connection;
import java.sql.SQLException;

import static com.robotics.demo.ledger.relay.LeaderLockProvider.LEADER_LOCK_KEY;

@Slf4j
public class Lease implements AutoCloseable {

    private final Connection connection;
    private final JdbcClient session;
    private final boolean leader;

    Lease(Connection connection, JdbcClient session, boolean leader) {
        this.connection = connection;
        this.session = session;
        this.leader = leader;
    }

    public boolean isLeader() {
        return leader;
    }

    public void ensureHeld() {
        if (!leader) {
            throw new LeadershipLostException("The leader lock was not acquired");
        }
        boolean held;
        try {
            held = Boolean.TRUE.equals(session.sql("""
                            select exists (
                                select 1 from pg_locks
                                where locktype = 'advisory' and classid = 0 and objid = cast(:key as oid) and objsubid = 1
                                  and pid = pg_backend_pid() and granted)
                            """)
                    .param("key", LEADER_LOCK_KEY)
                    .query(Boolean.class)
                    .single());
        } catch (DataAccessException ex) {
            throw new LeadershipLostException("The leader lock session is no longer available", ex);
        }
        if (!held) {
            throw new LeadershipLostException("The leader lock is no longer held by its session");
        }
    }

    @Override
    public void close() {
        if (leader) {
            release(connection, session);
        } else {
            closeConnection(connection);
        }
    }

    static void release(Connection connection, JdbcClient session) {
        if (!tryUnlock(session)) {
            abort(connection);
        }
        closeConnection(connection);
    }

    private static boolean tryUnlock(JdbcClient session) {
        try {
            return Boolean.TRUE.equals(session.sql("select pg_advisory_unlock(:key)")
                    .param("key", LEADER_LOCK_KEY)
                    .query(Boolean.class)
                    .single());
        } catch (DataAccessException ex) {
            log.warn("The leader lock was not released: {}", ex.toString());
            return false;
        }
    }

    private static void abort(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ex) {
            log.warn("The connection holding the leader lock was not aborted: {}", ex.toString());
        }
    }

    private static void closeConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ex) {
            log.warn("The leader lock connection was not closed: {}", ex.toString());
        }
    }

    public static class LeadershipLostException extends RuntimeException {

        public LeadershipLostException(String message) {
            super(message);
        }

        public LeadershipLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
