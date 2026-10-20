package com.robotics.demo.ledger.reconciliation;

import java.time.OffsetDateTime;
import java.util.List;

public record ReconciliationReport(List<BalanceMismatch> balanceMismatches,
                                   List<EntryCountMismatch> entryCountMismatches,
                                   List<StuckTransfer> stuckTransfers,
                                   List<UnsentOutboxRow> unsentOutboxRows) {

    public record BalanceMismatch(long accountId, long balance, long entriesSum) {
    }

    public record EntryCountMismatch(long transferId, String status, long entries) {
    }

    public record StuckTransfer(long transferId, OffsetDateTime createdAt) {
    }

    public record UnsentOutboxRow(long id, long transferId, OffsetDateTime createdAt) {
    }

    public boolean isClean() {
        return balanceMismatches.isEmpty() && entryCountMismatches.isEmpty()
                && stuckTransfers.isEmpty() && unsentOutboxRows.isEmpty();
    }
}
