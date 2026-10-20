package com.robotics.demo.ledger.persistence.jpa.mapper;

import com.robotics.demo.ledger.model.domain.Customer;
import com.robotics.demo.ledger.persistence.jpa.entity.CustomerEntity;

public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerEntity toEntity(Customer customer) {
        return CustomerEntity.builder()
                .id(customer.getCustomerId())
                .createdAt(customer.getCreatedAt())
                .build();
    }

    public static Customer toDomain(CustomerEntity entity) {
        return Customer.of(entity.getId(), entity.getCreatedAt());
    }
}
