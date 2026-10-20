package com.robotics.demo.ledger.interceptor;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

@Component
public class IdempotencyHeaderValidatorInterceptor implements HandlerInterceptor {

    private static final Pattern CLIENT_ID_FORMAT = Pattern.compile("[A-Za-z0-9._-]{1," + ApiHeaders.CLIENT_ID_MAX_LENGTH + "}");
    private static final String MDC_CLIENT_ID = "clientId";
    private static final String MDC_IDEMPOTENCY_KEY = "idempotencyKey";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = request.getHeader(ApiHeaders.CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            throw new LedgerServiceException(ErrorClassification.REQUIRED_CLIENT_ID);
        }
        if (!CLIENT_ID_FORMAT.matcher(clientId).matches()) {
            throw new LedgerServiceException(ErrorClassification.CLIENT_ID_INVALID);
        }
        String idempotencyKey = request.getHeader(ApiHeaders.IDEMPOTENCY_KEY);
        boolean post = HttpMethod.POST.matches(request.getMethod());
        if (post && !StringUtils.hasText(idempotencyKey)) {
            throw new LedgerServiceException(ErrorClassification.REQUIRED_IDEMPOTENCY_KEY);
        }
        MDC.put(MDC_CLIENT_ID, clientId);
        if (post) {
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
