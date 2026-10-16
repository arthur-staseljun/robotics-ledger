package com.robotics.demo.ledger.exception;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(LedgerServiceException.class)
    public ResponseEntity<ErrorResponse> handleLedgerServiceException(LedgerServiceException exception) {
        ErrorClassification classification = exception.getErrorClassification();
        return ResponseEntity.status(classification.getHttpStatus())
                .body(new ErrorResponse(classification.getErrorCode(), classification.getErrorMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        return ResponseEntity.internalServerError().body(new ErrorResponse(
                ErrorClassification.INTERNAL_SERVER_ERROR.getErrorCode(),
                ErrorClassification.INTERNAL_SERVER_ERROR.getErrorMessage()));
    }

    private record ErrorResponse(String errorCode, String errorMessage) {}
}
