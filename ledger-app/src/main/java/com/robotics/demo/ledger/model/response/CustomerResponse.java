package com.robotics.demo.ledger.model.response;

import com.robotics.demo.ledger.model.domain.Customer;

import java.time.Instant;

public record CustomerResponse(Long customerId, Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getCustomerId(), customer.getCreatedAt());
    }
}
