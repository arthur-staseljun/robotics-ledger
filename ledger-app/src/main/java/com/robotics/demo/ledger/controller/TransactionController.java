package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.model.response.TransactionResponse;
import com.robotics.demo.ledger.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.robotics.demo.ledger.controller.ControllerHelper.parseAccountId;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/accounts")
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/{accountId}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable String accountId) {
        Long id = parseAccountId(accountId);
        return transactionService.getAllTransactions(id).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
