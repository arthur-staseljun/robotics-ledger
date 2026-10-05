package com.teya.demo.ledger.interceptor;

import com.teya.demo.ledger.exception.classification.ErrorClassification;
import com.teya.demo.ledger.exception.classification.LedgerServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class IdempotencyHeaderValidatorInterceptor implements HandlerInterceptor {

    private static final String HEADER_CLIENT_ID = "X-Client-Id";
    private static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";
    private static final String MDC_CLIENT_ID = "clientId";
    private static final String MDC_IDEMPOTENCY_KEY = "idempotencyKey";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = request.getHeader(HEADER_CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            throw new LedgerServiceException(ErrorClassification.REQUIRED_CLIENT_ID);
        }
        MDC.put(MDC_CLIENT_ID, clientId);

        if (HttpMethod.POST.matches(request.getMethod())) {
            String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY_KEY);
            if (!StringUtils.hasText(idempotencyKey)) {
                throw new LedgerServiceException(ErrorClassification.REQUIRED_IDEMPOTENCY_KEY);
            }
            MDC.put(MDC_IDEMPOTENCY_KEY, idempotencyKey);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(MDC_CLIENT_ID);
        MDC.remove(MDC_IDEMPOTENCY_KEY);
    }
}
