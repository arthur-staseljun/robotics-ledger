package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.robotics.demo.ledger.controller.ControllerHelper.encodeCursor;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseAccountId;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseCursor;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseCustomerId;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseIdempotencyKey;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseLimit;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseTransferId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControllerHelperTest {

    @Test
    void parsesNumericIdentifiers() {
        assertThat(parseAccountId("42")).isEqualTo(42L);
        assertThat(parseCustomerId("7")).isEqualTo(7L);
        assertThat(parseTransferId("1")).isEqualTo(1L);
    }

    @Test
    void eachIdentifierFailsWithItsOwnErrorClassification() {
        assertThatThrownBy(() -> parseAccountId("x"))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification").isEqualTo(ErrorClassification.ACCOUNT_ID_PARSE_ERROR);
        assertThatThrownBy(() -> parseCustomerId("x"))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification").isEqualTo(ErrorClassification.CUSTOMER_ID_PARSE_ERROR);
        assertThatThrownBy(() -> parseTransferId("x"))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification").isEqualTo(ErrorClassification.TRANSFER_ID_PARSE_ERROR);
    }

    @Test
    void missingIdentifierIsInvalidToo() {
        assertThatThrownBy(() -> parseAccountId(null))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification").isEqualTo(ErrorClassification.ACCOUNT_ID_PARSE_ERROR);
    }

    @Test
    void idempotencyKeyMustBeAUuid() {
        UUID key = UUID.randomUUID();

        assertThat(parseIdempotencyKey(key.toString())).isEqualTo(key);
        assertThatThrownBy(() -> parseIdempotencyKey("not-a-uuid"))
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification").isEqualTo(ErrorClassification.IDEMPOTENCY_KEY_INVALID);
    }

    @Test
    void cursorRoundTripsAndMissingCursorMeansTheFirstPage() {
        assertThat(parseCursor(encodeCursor(42L))).isEqualTo(42L);
        assertThat(parseCursor(null)).isNull();
        assertThat(parseCursor(" ")).isNull();
        assertThat(encodeCursor(null)).isNull();
    }

    @Test
    void brokenCursorIsRejected() {
        for (String invalid : new String[]{"%%%", "abc", encodeCursor(0L), encodeCursor(-5L)}) {
            assertThatThrownBy(() -> parseCursor(invalid))
                    .isInstanceOf(LedgerServiceException.class)
                    .extracting("errorClassification").isEqualTo(ErrorClassification.CURSOR_INVALID);
        }
    }

    @Test
    void limitFallsBackToTheDefaultAndStaysWithinTheMaximum() {
        assertThat(parseLimit(null, 50, 100)).isEqualTo(50);
        assertThat(parseLimit("", 50, 100)).isEqualTo(50);
        assertThat(parseLimit("1", 50, 100)).isEqualTo(1);
        assertThat(parseLimit("100", 50, 100)).isEqualTo(100);
        for (String invalid : new String[]{"0", "-1", "101", "abc"}) {
            assertThatThrownBy(() -> parseLimit(invalid, 50, 100))
                    .isInstanceOf(LedgerServiceException.class)
                    .extracting("errorClassification").isEqualTo(ErrorClassification.LIMIT_INVALID);
        }
    }
}
