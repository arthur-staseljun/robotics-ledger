package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Transfer;
import com.robotics.demo.ledger.model.request.CreateTransferRequest;
import com.robotics.demo.ledger.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public Transfer transfer(@RequestBody CreateTransferRequest request) {
        return transferService.createTransfer(parseAccountId(request.accountFrom()),
                parseAccountId(request.accountTo()), request.toMoney());
    }

    @GetMapping("/{transferId}")
    public Transfer getTransfer(@PathVariable String transferId) {
        try {
            return transferService.getTransfer(Long.parseLong(transferId));
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.TRANSFER_ID_PARSE_ERROR);
        }
    }

    private Long parseAccountId(String accountId) {
        try {
            return Long.parseLong(accountId);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.ACCOUNT_ID_PARSE_ERROR);
        }
    }
}
