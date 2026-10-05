package com.teya.demo.ledger.controller;

import com.teya.demo.ledger.exception.classification.ErrorClassification;
import com.teya.demo.ledger.exception.classification.LedgerServiceException;
import com.teya.demo.ledger.model.Transaction;
import com.teya.demo.ledger.model.request.CreateTransactionRequest;
import com.teya.demo.ledger.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/accounts")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/{accountId}/transactions")
    public Transaction transact(@PathVariable String accountId,
                                @RequestBody CreateTransactionRequest request) {
        return transactionService.createTransaction(parse(accountId), request.toMoney());
    }

    @GetMapping("/{accountId}/transactions")
    public List<Transaction> getTransactions(@PathVariable String accountId) {
        return transactionService.getAllTransactions(parse(accountId));
    }

    private Long parse(String accountId) {
        try {
            return Long.parseLong(accountId);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.ACCOUNT_ID_PARSE_ERROR);
        }
    }
}
