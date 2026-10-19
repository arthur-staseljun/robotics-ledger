package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.interceptor.ApiHeaders;
import com.robotics.demo.ledger.model.response.CustomerResponse;
import com.robotics.demo.ledger.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.robotics.demo.ledger.controller.ControllerHelper.parseCustomerId;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseIdempotencyKey;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    public CustomerResponse createCustomer(@RequestHeader(ApiHeaders.CLIENT_ID) String clientId,
                                           @RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
        return customerService.createCustomer(clientId, parseIdempotencyKey(idempotencyKey));
    }

    @GetMapping("/{customerId}")
    public CustomerResponse getCustomer(@PathVariable String customerId) {
        return CustomerResponse.from(customerService.getExistingCustomer(
                parseCustomerId(customerId)));
    }
}
