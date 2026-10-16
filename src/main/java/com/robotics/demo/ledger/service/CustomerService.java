package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Customer;
import com.robotics.demo.ledger.persistence.jpa.CustomerRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.CustomerEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    public Customer createCustomer() {
        return save(Customer.create());
    }

    public Customer getExistingCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .map(CustomerService::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.CUSTOMER_DOES_NOT_EXIST));
    }

    private Customer save(Customer customer) {
        CustomerEntity entity = CustomerEntity.builder()
                .id(customer.getCustomerId())
                .createdAt(customer.getCreatedAt())
                .build();
        return toDomain(customerRepository.save(entity));
    }

    private static Customer toDomain(CustomerEntity entity) {
        return Customer.of(entity.getId(), entity.getCreatedAt());
    }
}
