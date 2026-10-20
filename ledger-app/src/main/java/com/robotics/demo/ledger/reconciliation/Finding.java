package com.robotics.demo.ledger.reconciliation;

public record Finding(Kind kind, long subjectId, String details) {

    public Key key() {
        return new Key(kind, subjectId);
    }

    public record Key(Kind kind, long subjectId) {
    }

    public enum Kind {
        BALANCE,
        ENTRY_COUNT,
        STUCK_TRANSFER,
        UNSENT_OUTBOX
    }
}
