package com.robotics.demo.ledger.exception.classification;

import org.springframework.http.HttpStatus;

public interface ErrorClassifier {
    String getErrorCode();
    String getErrorMessage();
    HttpStatus getHttpStatus();
}
