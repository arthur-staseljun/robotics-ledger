package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class ControllerHelper {

    private ControllerHelper() {
    }

    public static Long parseAccountId(String accountId) {
        return parseId(accountId, ErrorClassification.ACCOUNT_ID_PARSE_ERROR);
    }

    public static Long parseCustomerId(String customerId) {
        return parseId(customerId, ErrorClassification.CUSTOMER_ID_PARSE_ERROR);
    }

    public static Long parseTransferId(String transferId) {
        return parseId(transferId, ErrorClassification.TRANSFER_ID_PARSE_ERROR);
    }

    public static UUID parseIdempotencyKey(String idempotencyKey) {
        try {
            return UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException ex) {
            throw new LedgerServiceException(ErrorClassification.IDEMPOTENCY_KEY_INVALID);
        }
    }

    public static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long seqId = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (seqId <= 0) {
                throw new LedgerServiceException(ErrorClassification.CURSOR_INVALID);
            }
            return seqId;
        } catch (IllegalArgumentException ex) {
            throw new LedgerServiceException(ErrorClassification.CURSOR_INVALID);
        }
    }

    public static String encodeCursor(Long seqId) {
        if (seqId == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(seqId).getBytes(StandardCharsets.UTF_8));
    }

    public static int parseLimit(String limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit.isBlank()) {
            return defaultLimit;
        }
        try {
            int parsed = Integer.parseInt(limit);
            if (parsed < 1 || parsed > maxLimit) {
                throw new LedgerServiceException(ErrorClassification.LIMIT_INVALID);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.LIMIT_INVALID);
        }
    }

    private static Long parseId(String id, ErrorClassification onInvalid) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(onInvalid);
        }
    }
}
