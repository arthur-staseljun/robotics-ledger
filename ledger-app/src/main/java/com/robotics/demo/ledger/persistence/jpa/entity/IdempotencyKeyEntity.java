package com.robotics.demo.ledger.persistence.jpa.entity;

import com.robotics.demo.ledger.interceptor.ApiHeaders;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "client_id", length = ApiHeaders.CLIENT_ID_MAX_LENGTH)
    private String clientId;

    @Id
    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "request_hash", nullable = false)
    private byte[] requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public record Key(String clientId, UUID idempotencyKey) implements Serializable {
    }
}
