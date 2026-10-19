package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.persistence.jpa.IdempotencyKeyRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.IdempotencyKeyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public record KeyClaim(IdempotencyKeyEntity key, boolean isNew) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KeyClaim claim(String clientId, UUID idempotencyKey, byte[] requestHash) {
        int inserted = idempotencyKeyRepository.insertIfAbsent(clientId, idempotencyKey, requestHash);
        IdempotencyKeyEntity key = idempotencyKeyRepository
                .findById(new IdempotencyKeyEntity.Key(clientId, idempotencyKey))
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.INTERNAL_SERVER_ERROR));
        if (inserted == 0 && !Arrays.equals(key.getRequestHash(), requestHash)) {
            throw new LedgerServiceException(ErrorClassification.IDEMPOTENCY_KEY_REUSED);
        }
        return new KeyClaim(key, inserted == 1);
    }

    public <T> T storedResponse(KeyClaim claim, Class<T> responseType) {
        return objectMapper.readValue(claim.key().getResponseJson(), responseType);
    }

    public void storeResponse(KeyClaim claim, Object response) {
        claim.key().setResponseJson(objectMapper.writeValueAsString(response));
        claim.key().setUpdatedAt(Instant.now());
    }

    public static byte[] hash(String... parts) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
