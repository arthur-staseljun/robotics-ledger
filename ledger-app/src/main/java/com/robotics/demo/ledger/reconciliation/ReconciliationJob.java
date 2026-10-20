package com.robotics.demo.ledger.reconciliation;

import com.robotics.demo.ledger.reconciliation.ReconciliationStore.OpenFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ReconciliationJob {

    private static final long LEADER_LOCK_KEY = 7_002L;
    private static final int LOGGED_FINDINGS_LIMIT = 20;

    private static final String BALANCE_MISMATCH =
            "balance differs from the sum of entries (minor units): account=%d owner=%d currency=%s balance=%d entries=%d difference=%d";
    private static final String ENTRY_COUNT_MISMATCH =
            "wrong number of entries for a finished transfer: transfer=%d status=%s accountFrom=%d accountTo=%d expected=%d found=%d";
    private static final String STUCK_TRANSFER =
            "transfer PENDING longer than %s: transfer=%d accountFrom=%d accountTo=%d created=%s ageMinutes=%d";
    private static final String UNSENT_OUTBOX_ROW =
            "outbox row unsent longer than %s: outbox=%d transfer=%d event=%s topic=%s created=%s ageMinutes=%d";

    private static final String ALARM = "RECONCILIATION ALARM: {}";
    private static final String ALARM_TOTAL = "RECONCILIATION ALARM: {} new mismatches in total, the first {} are listed above";
    private static final String SKIPPED = "Reconciliation skipped, another instance is running it";
    private static final String CLEAN_RUN = "Reconciliation finished, no mismatches, run {}, {} resolved";
    private static final String RUN_WITH_FINDINGS = "Reconciliation run {} finished: {} open, {} new, {} resolved";

    private final ReconciliationRepository repository;
    private final ReconciliationStore store;
    private final ReconciliationMetrics metrics;
    private final TransactionTemplate leaderTransaction;
    private final TransactionTemplate snapshotTransaction;
    private final Duration pendingTimeout;
    private final Duration outboxTimeout;

    public ReconciliationJob(ReconciliationRepository repository, ReconciliationStore store,
                             ReconciliationMetrics metrics, PlatformTransactionManager transactionManager,
                             @Value("${ledger.reconciliation-job.pending-timeout}") Duration pendingTimeout,
                             @Value("${ledger.reconciliation-job.outbox-timeout}") Duration outboxTimeout) {
        this.repository = repository;
        this.store = store;
        this.metrics = metrics;
        this.leaderTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.pendingTimeout = pendingTimeout;
        this.outboxTimeout = outboxTimeout;
    }

    @Scheduled(cron = "${ledger.reconciliation-job.cron}")
    public ReconciliationReport reconcile() {
        Run run = leaderTransaction.execute(status ->
                repository.tryAcquireLeadership(LEADER_LOCK_KEY) ? snapshotTransaction.execute(inner -> run()) : null);
        if (run == null) {
            log.info(SKIPPED);
            return ReconciliationReport.empty();
        }
        metrics.recordRun(run.findings(), run.duration(), Instant.now());
        logOutcome(run.id(), run.findings(), run.settlement());
        return run.report();
    }

    private Run run() {
        long startedNanos = System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long runId = store.startRun(now);

        ReconciliationReport report = check(now);
        List<Finding> findings = findingsOf(report, now);
        Settlement settlement = settle(runId, findings);
        store.finishRun(runId, settlement.newFindings().size(), findings.size(), settlement.resolved());
        return new Run(runId, report, findings, settlement, Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    private ReconciliationReport check(OffsetDateTime now) {
        return new ReconciliationReport(
                repository.findBalanceMismatches(),
                repository.findEntryCountMismatches(),
                repository.findStuckTransfers(now.minus(pendingTimeout)),
                repository.findUnsentOutboxRows(now.minus(outboxTimeout)));
    }

    private Settlement settle(long runId, List<Finding> findings) {
        Map<Finding.Key, OpenFinding> unresolved = new HashMap<>();
        store.findOpen().forEach(open -> unresolved.put(open.key(), open));
        List<Finding> newFindings = new ArrayList<>();
        for (Finding finding : findings) {
            OpenFinding known = unresolved.remove(finding.key());
            if (known == null) {
                store.insertFinding(runId, finding);
                newFindings.add(finding);
            } else {
                store.touchFinding(known.id(), runId, finding.details());
            }
        }
        unresolved.values().forEach(resolved -> store.resolveFinding(resolved.id(), runId));
        return new Settlement(newFindings, unresolved.size());
    }

    private List<Finding> findingsOf(ReconciliationReport report, OffsetDateTime now) {
        List<Finding> findings = new ArrayList<>();
        report.balanceMismatches().forEach(mismatch -> findings.add(new Finding(Finding.Kind.BALANCE,
                mismatch.accountId(),
                BALANCE_MISMATCH.formatted(mismatch.accountId(), mismatch.ownerId(), mismatch.currency(), mismatch.balance(),
                                mismatch.entriesSum(), mismatch.difference()))));
        report.entryCountMismatches().forEach(mismatch -> findings.add(new Finding(Finding.Kind.ENTRY_COUNT,
                mismatch.transferId(),
                ENTRY_COUNT_MISMATCH.formatted(mismatch.transferId(), mismatch.status(), mismatch.accountFrom(),
                                mismatch.accountTo(), mismatch.expected(), mismatch.found()))));
        report.stuckTransfers().forEach(transfer -> findings.add(new Finding(Finding.Kind.STUCK_TRANSFER,
                transfer.transferId(),
                STUCK_TRANSFER.formatted(pendingTimeout, transfer.transferId(), transfer.accountFrom(),
                                transfer.accountTo(), transfer.createdAt(),
                                Duration.between(transfer.createdAt(), now).toMinutes()))));
        report.unsentOutboxRows().forEach(row -> findings.add(new Finding(Finding.Kind.UNSENT_OUTBOX, row.id(),
                UNSENT_OUTBOX_ROW.formatted(outboxTimeout, row.id(), row.transferId(), row.eventType(), row.topic(),
                                row.createdAt(), Duration.between(row.createdAt(), now).toMinutes()))));
        return findings;
    }

    private static void logOutcome(long runId, List<Finding> findings, Settlement settlement) {
        List<Finding> newFindings = settlement.newFindings();
        newFindings.stream().limit(LOGGED_FINDINGS_LIMIT)
                .forEach(finding -> log.error(ALARM, finding.details()));
        if (newFindings.size() > LOGGED_FINDINGS_LIMIT) {
            log.error(ALARM_TOTAL, newFindings.size(), LOGGED_FINDINGS_LIMIT);
        }
        if (findings.isEmpty()) {
            log.info(CLEAN_RUN, runId, settlement.resolved());
        } else {
            log.warn(RUN_WITH_FINDINGS, runId, findings.size(), newFindings.size(), settlement.resolved());
        }
    }

    private record Settlement(List<Finding> newFindings, int resolved) {
    }

    private record Run(long id, ReconciliationReport report,
                       List<Finding> findings, Settlement settlement, Duration duration) {
    }
}
