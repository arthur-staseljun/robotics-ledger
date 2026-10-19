package com.robotics.demo.ledger.persistence.jpa;

import com.robotics.demo.ledger.persistence.jpa.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyEntity.Key> {

    @Modifying
    @Query(value = """
            insert into idempotency_keys (client_id, idempotency_key, request_hash)
            values (:clientId, :idempotencyKey, :requestHash)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("clientId") String clientId,
                       @Param("idempotencyKey") UUID idempotencyKey,
                       @Param("requestHash") byte[] requestHash);
}
