package com.robotics.demo.ledger.service;


import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Customer;
import com.robotics.demo.ledger.model.response.CustomerResponse;
import com.robotics.demo.ledger.persistence.jpa.CustomerRepository;
import com.robotics.demo.ledger.persistence.jpa.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    public static final String CUSTOMERS_REQUEST = "customers";

    private final IdempotencyService idempotencyService;

    private final CustomerRepository customerRepository;

    @Transactional
    public CustomerResponse createCustomer(String clientId, UUID idempotencyKey) {
        IdempotencyService.KeyClaim claim = idempotencyService.claim(clientId, idempotencyKey, CUSTOMERS_REQUEST);
        if (!claim.isNew()) {
            return idempotencyService.storedResponse(claim, CustomerResponse.class);
        }
        Customer customer = save(Customer.create());
        CustomerResponse response = CustomerResponse.from(customer);
        idempotencyService.storeResponse(claim, response);
        return response;
    }

    public Customer getExistingCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .map(CustomerMapper::toDomain)
                .orElseThrow(() -> new LedgerServiceException(ErrorClassification.CUSTOMER_DOES_NOT_EXIST));
    }

    private Customer save(Customer customer) {
        return CustomerMapper.toDomain(customerRepository.save(CustomerMapper.toEntity(customer)));
    }
}
