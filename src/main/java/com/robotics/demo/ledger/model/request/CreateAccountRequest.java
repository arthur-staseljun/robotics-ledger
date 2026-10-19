package com.robotics.demo.ledger.model.request;

import static com.robotics.demo.ledger.controller.ControllerHelper.parseCustomerId;

public record CreateAccountRequest(String ownerId) {

    public Long getOwnerId() {
        return parseCustomerId(ownerId);
    }
}
