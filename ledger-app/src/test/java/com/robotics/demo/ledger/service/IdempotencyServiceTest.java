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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String CLIENT_ID = "client-1";
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-10-17T10:00:00Z");

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private final Map<IdempotencyKeyEntity.Key, IdempotencyKeyEntity> table = new HashMap<>();

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        when(idempotencyKeyRepository.insertIfAbsent(anyString(), any(UUID.class), any(byte[].class)))
                .thenAnswer(invocation -> {
                    IdempotencyKeyEntity.Key key = new IdempotencyKeyEntity.Key(
                            invocation.getArgument(0), invocation.getArgument(1));
                    if (table.containsKey(key)) {
                        return 0;
                    }
                    Instant now = Instant.now();
                    table.put(key, IdempotencyKeyEntity.builder()
                            .clientId(key.clientId())
                            .idempotencyKey(key.idempotencyKey())
                            .requestHash(invocation.getArgument(2))
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                    return 1;
                });
        when(idempotencyKeyRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(table.get(invocation.<IdempotencyKeyEntity.Key>getArgument(0))));
        idempotencyService = new IdempotencyService(idempotencyKeyRepository, JsonMapper.builder().build());
    }

    private IdempotencyService.KeyClaim claim(String clientId, String... requestParts) {
        return idempotencyService.claim(clientId, KEY, requestParts);
    }

    private void assertRejectedAsReuse(String clientId, String... requestParts) {
        assertThatThrownBy(() -> claim(clientId, requestParts))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aFirstRequestWithAKeyIsNew() {
        assertThat(claim(CLIENT_ID, "customers").isNew()).isTrue();
    }

    @Test
    void theSameRequestWithTheSameKeyIsNotNewAndSharesTheStoredKey() {
        IdempotencyService.KeyClaim first = claim(CLIENT_ID, "accounts", "1");

        IdempotencyService.KeyClaim second = claim(CLIENT_ID, "accounts", "1");

        assertThat(second.isNew()).isFalse();
        assertThat(second.key()).isSameAs(first.key());
    }

    @Test
    void theSameKeyWithAnyOtherPartOfTheRequestIsRejected() {
        claim(CLIENT_ID, "transfers", "1", "2", "2500", "EUR");

        assertRejectedAsReuse(CLIENT_ID, "transfers", "9", "2", "2500", "EUR");
        assertRejectedAsReuse(CLIENT_ID, "transfers", "1", "9", "2500", "EUR");
        assertRejectedAsReuse(CLIENT_ID, "transfers", "1", "2", "2600", "EUR");
        assertRejectedAsReuse(CLIENT_ID, "transfers", "1", "2", "2500", "USD");
        assertRejectedAsReuse(CLIENT_ID, "accounts", "1", "2", "2500", "EUR");
    }

    @Test
    void requestsThatOnlyDifferInWhereThePartsSplitAreStillDifferent() {
        claim(CLIENT_ID, "a|b", "c");

        assertRejectedAsReuse(CLIENT_ID, "a", "b|c");
        assertRejectedAsReuse(CLIENT_ID, "a", "b", "c");
        assertRejectedAsReuse(CLIENT_ID, "a|b|c");
    }

    @Test
    void aMissingPartIsNotTheSameAsAnEmptyOne() {
        claim(CLIENT_ID, "accounts", "1");

        assertRejectedAsReuse(CLIENT_ID, "accounts", "1", "");
        assertRejectedAsReuse(CLIENT_ID, "accounts");
    }

    @Test
    void theSameKeyOfAnotherClientIsAnotherRequest() {
        claim("client-a", "customers");

        IdempotencyService.KeyClaim other = claim("client-b", "customers");

        assertThat(other.isNew()).isTrue();
    }

    @Test
    void storeResponseKeepsTheResponseOfTheClaimedKey() {
        IdempotencyService.KeyClaim claim = claim(CLIENT_ID, "customers");

        idempotencyService.storeResponse(claim, new CustomerResponse(5L, CREATED_AT));

        assertThat(claim.key().getResponseJson()).contains("\"customerId\":5");
    }

    @Test
    void theStoredResponseComesBackAsTheSameResponseOnARepeatedRequest() {
        idempotencyService.storeResponse(claim(CLIENT_ID, "customers"), new CustomerResponse(5L, CREATED_AT));

        IdempotencyService.KeyClaim repeated = claim(CLIENT_ID, "customers");
        CustomerResponse response = idempotencyService.storedResponse(repeated, CustomerResponse.class);

        assertThat(repeated.isNew()).isFalse();
        assertThat(response).isEqualTo(new CustomerResponse(5L, CREATED_AT));
    }

    @Test
    void linkTransferRecordsTheTransferOnTheKeyAndARepeatedRequestFindsIt() {
        idempotencyService.linkTransfer(claim(CLIENT_ID, "transfers"), 7L);

        assertThat(claim(CLIENT_ID, "transfers").key().getTransferId()).isEqualTo(7L);
    }
}
