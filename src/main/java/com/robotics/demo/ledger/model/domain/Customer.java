package com.robotics.demo.ledger.model.domain;

import lombok.Getter;

import java.time.Instant;

@Getter
public class Customer {

    private Long customerId;
    private final Instant createdAt;

    private Customer(Long customerId, Instant createdAt) {
        this.customerId = customerId;
        this.createdAt = createdAt;
    }

    public static Customer create() {
        return new Customer(null, Instant.now());
    }

    public static Customer of(Long customerId, Instant createdAt) {
        return new Customer(customerId, createdAt);
    }
}
