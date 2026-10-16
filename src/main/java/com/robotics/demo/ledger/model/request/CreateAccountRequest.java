package com.robotics.demo.ledger.model.request;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;

public record CreateAccountRequest(String ownerId) {

    public Long toOwnerId() {
        try {
            return Long.parseLong(ownerId);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.CUSTOMER_ID_PARSE_ERROR);
        }
    }
}
