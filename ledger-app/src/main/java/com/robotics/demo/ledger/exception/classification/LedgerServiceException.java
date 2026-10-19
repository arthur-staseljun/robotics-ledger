package com.robotics.demo.ledger.exception.classification;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class LedgerServiceException extends RuntimeException {

    private final ErrorClassification errorClassification;

}
