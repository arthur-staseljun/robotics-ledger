package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.interceptor.ApiHeaders;
import com.robotics.demo.ledger.model.response.AccountResponse;
import com.robotics.demo.ledger.model.request.CreateAccountRequest;
import com.robotics.demo.ledger.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import static com.robotics.demo.ledger.controller.ControllerHelper.parseAccountId;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseIdempotencyKey;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public AccountResponse createAccount(@RequestHeader(ApiHeaders.CLIENT_ID) String clientId,
                                         @RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
                                         @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(clientId, parseIdempotencyKey(idempotencyKey), request.getOwnerId());
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return AccountResponse.from(accountService.getExistingAccount(
                parseAccountId(accountId)));
    }
}
