package com.robotics.demo.ledger.interceptor;

import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyHeaderValidatorInterceptorTest {

    private final IdempotencyHeaderValidatorInterceptor interceptor = new IdempotencyHeaderValidatorInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aRejectedRequestLeavesNothingInTheMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/accounts");
        request.addHeader(ApiHeaders.CLIENT_ID, "client-a");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(LedgerServiceException.class);

        assertThat(MDC.get("clientId")).isNull();
        assertThat(MDC.get("idempotencyKey")).isNull();
    }

    @Test
    void anAcceptedRequestPutsTheClientAndTheKeyIntoTheMdcAndTheyAreRemovedAfterwards() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/accounts");
        request.addHeader(ApiHeaders.CLIENT_ID, "client-a");
        request.addHeader(ApiHeaders.IDEMPOTENCY_KEY, "3f0c3f4e-3a52-4c67-a1a1-5f5e2d8c9b10");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(MDC.get("clientId")).isEqualTo("client-a");
        assertThat(MDC.get("idempotencyKey")).isEqualTo("3f0c3f4e-3a52-4c67-a1a1-5f5e2d8c9b10");

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(MDC.get("clientId")).isNull();
        assertThat(MDC.get("idempotencyKey")).isNull();
    }
}
