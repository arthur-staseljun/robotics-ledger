package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.model.response.TransactionPageResponse;
import com.robotics.demo.ledger.model.response.TransactionResponse;
import com.robotics.demo.ledger.service.TransactionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import static com.robotics.demo.ledger.controller.ControllerHelper.encodeCursor;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseAccountId;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseCursor;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseLimit;

@RestController
@RequestMapping("/api/accounts")
public class TransactionController {

    private final TransactionService transactionService;
    private final int defaultLimit;
    private final int maxLimit;

    public TransactionController(TransactionService transactionService,
                                 @Value("${ledger.history.default-limit}") int defaultLimit,
                                 @Value("${ledger.history.max-limit}") int maxLimit) {
        this.transactionService = transactionService;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    @GetMapping("/{accountId}/transactions")
    public TransactionPageResponse getTransactions(@PathVariable String accountId,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) String limit) {
        Long id = parseAccountId(accountId);
        Long beforeSeqId = parseCursor(cursor);
        int pageSize = parseLimit(limit, defaultLimit, maxLimit);
        TransactionService.Page page = transactionService.getTransactions(id, beforeSeqId, pageSize);
        String nextCursor = page.hasMore() ? encodeCursor(page.items().getLast().getSeqId()) : null;
        return new TransactionPageResponse(
                page.items().stream().map(TransactionResponse::from).toList(), nextCursor);
    }
}
