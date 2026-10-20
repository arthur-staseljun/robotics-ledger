package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.response.CustomerResponse;
import com.robotics.demo.ledger.persistence.jpa.IdempotencyKeyRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.IdempotencyKeyEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String CLIENT_ID = "client-1";
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-10-17T10:00:00Z");

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(idempotencyKeyRepository, JsonMapper.builder().build());
    }

    private static byte[] hashOf(String... parts) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static IdempotencyKeyEntity stored(byte[] requestHash, String responseJson) {
        Instant now = Instant.now();
        return IdempotencyKeyEntity.builder()
                .clientId(CLIENT_ID)
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .responseJson(responseJson)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    void newKeyIsClaimedAsNew() {
        when(idempotencyKeyRepository.insertIfAbsent(eq(CLIENT_ID), eq(KEY), any(byte[].class))).thenReturn(1);
        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.of(stored(hashOf("customers"), null)));

        IdempotencyService.KeyClaim claim = idempotencyService.claim(CLIENT_ID, KEY, "customers");

        assertThat(claim.isNew()).isTrue();
    }

    @Test
    void knownKeyWithTheSameRequestIsNotNew() {
        when(idempotencyKeyRepository.insertIfAbsent(eq(CLIENT_ID), eq(KEY), any(byte[].class))).thenReturn(0);
        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.of(stored(hashOf("customers"), null)));

        IdempotencyService.KeyClaim claim = idempotencyService.claim(CLIENT_ID, KEY, "customers");

        assertThat(claim.isNew()).isFalse();
    }

    @Test
    void knownKeyWithAnotherRequestIsRejected() {
        when(idempotencyKeyRepository.insertIfAbsent(eq(CLIENT_ID), eq(KEY), any(byte[].class))).thenReturn(0);
        when(idempotencyKeyRepository.findById(any()))
                .thenReturn(Optional.of(stored(hashOf("accounts", "1"), null)));

        assertThatThrownBy(() -> idempotencyService.claim(CLIENT_ID, KEY, "customers"))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void storeResponseKeepsTheResponseOfTheClaimedKey() {
        IdempotencyKeyEntity key = stored(hashOf("customers"), null);

        idempotencyService.storeResponse(new IdempotencyService.KeyClaim(key, true), new CustomerResponse(5L, CREATED_AT));

        assertThat(key.getResponseJson()).contains("\"customerId\":5");
    }

    @Test
    void storedResponseReadsTheResponseBackAsTheSameType() {
        IdempotencyKeyEntity key = stored(hashOf("customers"), "{\"customerId\":5,\"createdAt\":\"" + CREATED_AT + "\"}");

        CustomerResponse response = idempotencyService.storedResponse(new IdempotencyService.KeyClaim(key, false), CustomerResponse.class);

        assertThat(response.customerId()).isEqualTo(5L);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void theRequestIsIdentifiedByEveryPartOfIt() {
        when(idempotencyKeyRepository.insertIfAbsent(eq(CLIENT_ID), eq(KEY), any(byte[].class))).thenReturn(0);
        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.of(stored(hashOf("accounts", "1"), null)));

        assertThat(idempotencyService.claim(CLIENT_ID, KEY, "accounts", "1").isNew()).isFalse();
        assertThatThrownBy(() -> idempotencyService.claim(CLIENT_ID, KEY, "accounts", "2"))
                .isInstanceOf(LedgerServiceException.class);
        assertThatThrownBy(() -> idempotencyService.claim(CLIENT_ID, KEY, "customers", "1"))
                .isInstanceOf(LedgerServiceException.class);
    }

    @Test
    void linkTransferRecordsTheTransferOnTheClaimedKey() {
        IdempotencyKeyEntity key = stored(hashOf("transfers"), null);
        Instant before = key.getUpdatedAt();

        idempotencyService.linkTransfer(new IdempotencyService.KeyClaim(key, true), 7L);

        assertThat(key.getTransferId()).isEqualTo(7L);
        assertThat(key.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}
