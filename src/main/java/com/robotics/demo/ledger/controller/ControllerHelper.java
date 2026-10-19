package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;

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

    private static Long parseId(String id, ErrorClassification onInvalid) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(onInvalid);
        }
    }
}
