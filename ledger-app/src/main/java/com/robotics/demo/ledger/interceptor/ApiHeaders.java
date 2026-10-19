package com.robotics.demo.ledger.interceptor;

public final class ApiHeaders {

    public static final String CLIENT_ID = "X-Client-Id";
    public static final String IDEMPOTENCY_KEY = "X-Idempotency-Key";
    public static final int CLIENT_ID_MAX_LENGTH = 64;

    private ApiHeaders() {
    }
}
