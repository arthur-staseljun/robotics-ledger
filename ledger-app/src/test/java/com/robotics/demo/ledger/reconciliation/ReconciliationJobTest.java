package com.robotics.demo.ledger.reconciliation;

import com.robotics.demo.ledger.reconciliation.ReconciliationReport.BalanceMismatch;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.EntryCountMismatch;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.StuckTransfer;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.UnsentOutboxRow;
import com.robotics.demo.ledger.reconciliation.ReconciliationStore.OpenFinding;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ReconciliationJobTest {

    private static final long RUN_ID = 31L;

    @Mock
    private ReconciliationRepository repository;

    @Mock
    private ReconciliationStore store;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new ReconciliationJob(repository, store, new ReconciliationMetrics(registry), transactionManager,
                Duration.ofMinutes(5), Duration.ofMinutes(2));
    }

    private void givenTheLeaderLockIsFree() {
        when(repository.tryAcquireLeadership(anyLong())).thenReturn(true);
        when(store.startRun(any())).thenReturn(RUN_ID);
    }

    private void givenNothingIsWrong() {
        givenTheLeaderLockIsFree();
        when(repository.findBalanceMismatches()).thenReturn(List.of());
        when(repository.findEntryCountMismatches()).thenReturn(List.of());
        when(repository.findStuckTransfers(any())).thenReturn(List.of());
        when(repository.findUnsentOutboxRows(any())).thenReturn(List.of());
        when(store.findOpen()).thenReturn(List.of());
    }

    private void givenOneBalanceMismatch() {
        givenTheLeaderLockIsFree();
        when(repository.findBalanceMismatches()).thenReturn(List.of(new BalanceMismatch(17L, 3L, "EUR", 12000, 11500)));
        when(repository.findEntryCountMismatches()).thenReturn(List.of());
        when(repository.findStuckTransfers(any())).thenReturn(List.of());
        when(repository.findUnsentOutboxRows(any())).thenReturn(List.of());
    }

    @Test
    void aCleanRunSaysSoAndRaisesNoAlarm(CapturedOutput output) {
        givenNothingIsWrong();

        ReconciliationReport report = job.reconcile();

        assertThat(report.isClean()).isTrue();
        assertThat(output.getAll()).contains("Reconciliation finished, no mismatches")
                .doesNotContain("RECONCILIATION ALARM");
        verify(store).finishRun(RUN_ID, 0, 0, 0);
    }

    @Test
    void everyNewMismatchIsReportedWithTheDetailsNeededToInvestigateIt(CapturedOutput output) {
        OffsetDateTime created = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(9);
        givenTheLeaderLockIsFree();
        when(repository.findBalanceMismatches())
                .thenReturn(List.of(new BalanceMismatch(17L, 3L, "EUR", 12000, 11500)));
        when(repository.findEntryCountMismatches())
                .thenReturn(List.of(new EntryCountMismatch(305L, "SUCCESSFUL", 17L, 18L, 2, 1)));
        when(repository.findStuckTransfers(any())).thenReturn(List.of(new StuckTransfer(311L, 17L, 18L, created)));
        when(repository.findUnsentOutboxRows(any()))
                .thenReturn(List.of(new UnsentOutboxRow(902L, 311L, "created", "transfers", created)));
        when(store.findOpen()).thenReturn(List.of());

        job.reconcile();

        assertThat(output.getAll())
                .contains("balance differs from the sum of entries (minor units): account=17 owner=3 currency=EUR "
                        + "balance=12000 entries=11500 difference=500")
                .contains("wrong number of entries for a finished transfer: transfer=305 status=SUCCESSFUL "
                        + "accountFrom=17 accountTo=18 expected=2 found=1")
                .contains("transfer PENDING longer than PT5M: transfer=311 accountFrom=17 accountTo=18")
                .contains("ageMinutes=9")
                .contains("outbox row unsent longer than PT2M: outbox=902 transfer=311 event=created topic=transfers");
        verify(store).finishRun(RUN_ID, 4, 4, 0);
    }

    @Test
    void aMismatchThatIsAlreadyOpenIsNotAlarmedAgainButIsKeptUpToDate(CapturedOutput output) {
        givenOneBalanceMismatch();
        when(store.findOpen()).thenReturn(List.of(new OpenFinding(5L, Finding.Kind.BALANCE, 17L)));

        job.reconcile();

        assertThat(output.getAll()).doesNotContain("RECONCILIATION ALARM");
        verify(store, never()).insertFinding(anyLong(), any());
        verify(store).touchFinding(eq(5L), eq(RUN_ID), any());
        verify(store).finishRun(RUN_ID, 0, 1, 0);
    }

    @Test
    void aMismatchThatDisappearedIsResolved() {
        givenNothingIsWrong();
        when(store.findOpen()).thenReturn(List.of(new OpenFinding(5L, Finding.Kind.BALANCE, 17L)));

        job.reconcile();

        verify(store).resolveFinding(5L, RUN_ID);
        verify(store).finishRun(RUN_ID, 0, 0, 1);
    }

    @Test
    void aLongListOfNewMismatchesIsCutAndTheTotalIsReported(CapturedOutput output) {
        OffsetDateTime created = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(9);
        givenTheLeaderLockIsFree();
        when(repository.findBalanceMismatches()).thenReturn(List.of());
        when(repository.findEntryCountMismatches()).thenReturn(List.of());
        when(repository.findUnsentOutboxRows(any())).thenReturn(List.of());
        when(repository.findStuckTransfers(any())).thenReturn(LongStream.rangeClosed(1, 25)
                .mapToObj(id -> new StuckTransfer(id, 1L, 2L, created)).toList());
        when(store.findOpen()).thenReturn(List.of());

        job.reconcile();

        assertThat(output.getAll()).contains("transfer=20 ").doesNotContain("transfer=21 ")
                .contains("25 new mismatches in total, the first 20 are listed above");
    }

    @Test
    void anotherInstanceRunningTheJobMakesThisOneSkipTheRun() {
        when(repository.tryAcquireLeadership(anyLong())).thenReturn(false);

        ReconciliationReport report = job.reconcile();

        assertThat(report.isClean()).isTrue();
        verify(store, never()).startRun(any());
        verify(repository, never()).findBalanceMismatches();
        verify(store, never()).finishRun(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void theChecksTakeTheirSnapshotOnlyAfterTheLeaderLockIsHeld() {
        givenNothingIsWrong();

        job.reconcile();

        InOrder order = inOrder(transactionManager, repository);
        order.verify(transactionManager).getTransaction(
                argThat(definition -> definition.getIsolationLevel() == TransactionDefinition.ISOLATION_DEFAULT));
        order.verify(repository).tryAcquireLeadership(anyLong());
        order.verify(transactionManager).getTransaction(argThat(definition ->
                definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ
                        && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(repository).findBalanceMismatches();
    }

    @Test
    void theMetricsStayUntouchedWhenTheRunIsNotCommitted() {
        givenOneBalanceMismatch();
        when(store.findOpen()).thenReturn(List.of());
        doThrow(new TransactionSystemException("commit failed")).when(transactionManager).commit(any());

        assertThatThrownBy(job::reconcile).isInstanceOf(TransactionSystemException.class);

        assertThat(registry.get("ledger.reconciliation.last.success.timestamp.seconds").gauge().value()).isZero();
        assertThat(registry.get("ledger.reconciliation.open.findings").tag("kind", "balance").gauge().value()).isZero();
    }
}
