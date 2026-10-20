package com.robotics.demo.ledger.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxStore {

    private final JdbcClient jdbcClient;

    public List<Message> findUnsent(int limit) {
        return jdbcClient.sql("""
                        select id, account_id, topic, payload::text as payload from outbox
                        where sent_at is null
                        order by created_at, id
                        limit :limit
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

    public record Message(long id, long accountId, String topic, String payload) {
    }
}
