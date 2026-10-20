package com.robotics.demo.ledger.exception;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void anUnexpectedErrorIsLoggedWithItsStackTraceAndAnswersWithAnInternalError(CapturedOutput output) {
        ResponseEntity<?> response = handler.handleException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(output.getAll()).contains("Unexpected error").contains("IllegalStateException: boom")
                .contains("GlobalExceptionHandlerTest");
    }

    @Test
    void aClientErrorIsNotLoggedAsAnError(CapturedOutput output) {
        ResponseEntity<?> response = handler.handleLedgerServiceException(
                new LedgerServiceException(ErrorClassification.ACCOUNT_DOES_NOT_EXIST));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(output.getAll()).doesNotContain("ERROR");
    }

    @Test
    void aServerSideClassificationIsLoggedAsAnError(CapturedOutput output) {
        ResponseEntity<?> response = handler.handleLedgerServiceException(
                new LedgerServiceException(ErrorClassification.INTERNAL_SERVER_ERROR));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(output.getAll()).contains("ERROR").contains("Request failed");
    }
}
