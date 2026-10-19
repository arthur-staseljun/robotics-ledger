package com.robotics.demo.ledger.model.response;

import java.util.List;

public record TransactionPageResponse(List<TransactionResponse> items, String nextCursor) {
}
