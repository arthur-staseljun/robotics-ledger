package com.robotics.demo.ledger.interceptor;

import com.robotics.demo.ledger.persistence.jpa.entity.IdempotencyKeyEntity;

public final class ApiHeaders {

    public static final String CLIENT_ID = "X-Client-Id";
    public static final String IDEMPOTENCY_KEY = "X-Idempotency-Key";
    public static final int CLIENT_ID_MAX_LENGTH = IdempotencyKeyEntity.CLIENT_ID_MAX_LENGTH;

    private ApiHeaders() {
    }
}
