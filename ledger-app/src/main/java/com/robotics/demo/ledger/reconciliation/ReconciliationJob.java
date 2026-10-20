package com.robotics.demo.ledger.reconciliation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.ToLongFunction;

@Slf4j
@Component
public class ReconciliationJob {

    private static final int LOGGED_IDS_LIMIT = 20;

    private final ReconciliationRepository repository;
    private final Duration pendingTimeout;
    private final Duration outboxTimeout;

    public ReconciliationJob(ReconciliationRepository repository,
                             @Value("${ledger.reconciliation.pending-timeout}") Duration pendingTimeout,
                             @Value("${ledger.reconciliation.outbox-timeout}") Duration outboxTimeout) {
        this.repository = repository;
        this.pendingTimeout = pendingTimeout;
        this.outboxTimeout = outboxTimeout;
    }

    @Scheduled(cron = "${ledger.reconciliation.cron}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ReconciliationReport report = new ReconciliationReport(
                repository.findBalanceMismatches(),
                repository.findEntryCountMismatches(),
                repository.findStuckTransfers(now.minus(pendingTimeout)),
                repository.findUnsentOutboxRows(now.minus(outboxTimeout)));
        if (report.isClean()) {
            log.info("Reconciliation finished, no mismatches");
        } else {
            raiseAlarm(report);
        }
        return report;
    }

    private void raiseAlarm(ReconciliationReport report) {
        if (!report.balanceMismatches().isEmpty()) {
            log.error("RECONCILIATION ALARM: balance != sum of entries for accounts {}",
                    sample(report.balanceMismatches(), ReconciliationReport.BalanceMismatch::accountId));
        }
        if (!report.entryCountMismatches().isEmpty()) {
            log.error("RECONCILIATION ALARM: wrong number of entries for transfers {}",
                    sample(report.entryCountMismatches(), ReconciliationReport.EntryCountMismatch::transferId));
        }
        if (!report.stuckTransfers().isEmpty()) {
            log.error("RECONCILIATION ALARM: transfers PENDING longer than {}: {}", pendingTimeout,
                    sample(report.stuckTransfers(), ReconciliationReport.StuckTransfer::transferId));
        }
        if (!report.unsentOutboxRows().isEmpty()) {
            log.error("RECONCILIATION ALARM: outbox rows unsent longer than {}: {}", outboxTimeout,
                    sample(report.unsentOutboxRows(), ReconciliationReport.UnsentOutboxRow::id));
        }
    }

    private static <T> String sample(List<T> findings, ToLongFunction<T> id) {
        String ids = findings.stream().limit(LOGGED_IDS_LIMIT).map(finding -> String.valueOf(id.applyAsLong(finding)))
                .reduce((a, b) -> a + ", " + b).orElse("none");
        return findings.size() > LOGGED_IDS_LIMIT
                ? "%d in total, first %d: %s".formatted(findings.size(), LOGGED_IDS_LIMIT, ids)
                : "%d: %s".formatted(findings.size(), ids);
    }
}
