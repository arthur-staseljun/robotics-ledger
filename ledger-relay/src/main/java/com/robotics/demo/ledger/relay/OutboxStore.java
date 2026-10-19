package com.robotics.demo.ledger.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxStore {

    public record Message(long id, long accountId, String topic, String payload) {
    }

    private final JdbcClient jdbcClient;

    public boolean tryAcquireLeadership(long key) {
        return Boolean.TRUE.equals(jdbcClient.sql("select pg_try_advisory_xact_lock(:key)")
                .param("key", key)
                .query(Boolean.class)
                .single());
    }

    public List<Message> findUnsentForUpdate(int limit) {
        return jdbcClient.sql("""
                        select id, account_id, topic, payload::text as payload from outbox
                        where sent_at is null
                        order by created_at, id
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", limit)
                .query(Message.class)
                .list();
    }

    public void markSent(long id) {
        jdbcClient.sql("update outbox set sent_at = now(), updated_at = now() where id = :id")
                .param("id", id)
                .update();
    }
}
