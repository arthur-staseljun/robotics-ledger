package com.robotics.demo.ledger.controller;

import com.robotics.demo.ledger.interceptor.ApiHeaders;
import com.robotics.demo.ledger.model.response.TransferResponse;
import com.robotics.demo.ledger.model.request.CreateTransferRequest;
import com.robotics.demo.ledger.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.robotics.demo.ledger.controller.ControllerHelper.parseAccountId;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseIdempotencyKey;
import static com.robotics.demo.ledger.controller.ControllerHelper.parseTransferId;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@RequestHeader(ApiHeaders.CLIENT_ID) String clientId,
                                                     @RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
                                                     @RequestBody CreateTransferRequest request) {
        TransferService.AcceptedTransfer accepted = transferService.accept(clientId, parseIdempotencyKey(idempotencyKey),
                parseAccountId(request.accountFrom()),
                parseAccountId(request.accountTo()), request.toMoney());
        HttpStatus status = accepted.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(TransferResponse.from(accepted.transfer()));
    }

    @GetMapping("/{transferId}")
    public TransferResponse getTransfer(@PathVariable String transferId) {
        return TransferResponse.from(transferService.getTransfer(
                parseTransferId(transferId)));
    }
}
