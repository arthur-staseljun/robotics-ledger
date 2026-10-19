package com.robotics.demo.ledger.service;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Customer;
import com.robotics.demo.ledger.model.response.CustomerResponse;
import com.robotics.demo.ledger.persistence.jpa.CustomerRepository;
import com.robotics.demo.ledger.persistence.jpa.entity.CustomerEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    public static final String CUSTOMERS_REQUEST = "customers";

    private final CustomerRepository customerRepository;
    private final IdempotencyService idempotencyService;

    @Transactional
    public CustomerResponse createCustomer(String clientId, UUID idempotencyKey) {
        IdempotencyService.KeyClaim claim = idempotencyService.claim(clientId, idempotencyKey,
                IdempotencyService.hash(CUSTOMERS_REQUEST));
        if (!claim.isNew()) {
            return idempotencyService.storedResponse(claim, CustomerResponse.class);
        }
        CustomerResponse response = CustomerResponse.from(createCustomer());
        idempotencyService.storeResponse(claim, response);
        return response;
    }

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
