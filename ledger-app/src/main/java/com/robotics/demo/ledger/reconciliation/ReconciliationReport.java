package com.robotics.demo.ledger.reconciliation;

import java.time.OffsetDateTime;
import java.util.List;

public record ReconciliationReport(List<BalanceMismatch> balanceMismatches,
                                   List<EntryCountMismatch> entryCountMismatches,
                                   List<StuckTransfer> stuckTransfers,
                                   List<UnsentOutboxRow> unsentOutboxRows) {

    public record BalanceMismatch(long accountId, long ownerId, String currency, long balance, long entriesSum) {

        public long difference() {
            return balance - entriesSum;
        }
    }

    public record EntryCountMismatch(long transferId, String status, long accountFrom, long accountTo,
                                     long expected, long found) {
    }

    public record StuckTransfer(long transferId, long accountFrom, long accountTo, OffsetDateTime createdAt) {
    }

    public record UnsentOutboxRow(long id, long transferId, String eventType, String topic,
                                  OffsetDateTime createdAt) {
    }

    public static ReconciliationReport empty() {
        return new ReconciliationReport(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isClean() {
        return balanceMismatches.isEmpty() && entryCountMismatches.isEmpty()
                && stuckTransfers.isEmpty() && unsentOutboxRows.isEmpty();
    }
}
