package com.teya.demo.ledger.exception.classification;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

import java.io.Serializable;

@Getter
@RequiredArgsConstructor
public enum ErrorClassification implements ErrorClassifier, Serializable {
    INTERNAL_SERVER_ERROR("int-err-00", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    ACCOUNT_ID_PARSE_ERROR("acc-ex-00", "Invalid account id", HttpStatus.BAD_REQUEST),
    ACCOUNT_DOES_NOT_EXIST("acc-ex-02", "Account does not exist", HttpStatus.NOT_FOUND),
    AMOUNT_PARSE_ERROR("tx-ex-00", "Invalid amount", HttpStatus.BAD_REQUEST),
    CURRENCY_MISMATCH("tx-ex-01", "Account currency does not match", HttpStatus.BAD_REQUEST),
    INSUFFICIENT_FUNDS("tx-ex-02", "Insufficient funds", HttpStatus.BAD_REQUEST),
    REQUIRED_CLIENT_ID("auth-ex-00", "Client id is required", HttpStatus.UNAUTHORIZED),
    REQUIRED_IDEMPOTENCY_KEY("idem-ex-00", "Idempotency key is required", HttpStatus.BAD_REQUEST);

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final String errorMessage;
    private final HttpStatus httpStatus;
}
