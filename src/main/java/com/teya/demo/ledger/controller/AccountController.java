package com.teya.demo.ledger.controller;

import com.teya.demo.ledger.exception.classification.ErrorClassification;
import com.teya.demo.ledger.exception.classification.LedgerServiceException;
import com.teya.demo.ledger.model.Account;
import com.teya.demo.ledger.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public Account createAccount() {
        return accountService.createAccount();
    }

    @GetMapping("/{accountId}")
    public Account getAccount(@PathVariable String accountId) {
        return accountService.getExistingAccount(parse(accountId));
    }

    private Long parse(String accountId) {
        try {
            return Long.parseLong(accountId);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.ACCOUNT_ID_PARSE_ERROR);
        }
    }
}
