package com.robotics.demo.ledger.reconciliation;

import com.robotics.demo.ledger.AbstractPostgresIntegrationTest;
import com.robotics.demo.ledger.model.domain.Account;
import com.robotics.demo.ledger.model.domain.Money;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.BalanceMismatch;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.EntryCountMismatch;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.StuckTransfer;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.UnsentOutboxRow;
import com.robotics.demo.ledger.service.AccountService;
import com.robotics.demo.ledger.service.CustomerService;
import com.robotics.demo.ledger.service.TransferService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("container")
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
class ReconciliationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private ReconciliationJob reconciliationJob;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private Long accountWithBalance(String amount) {
        Instant now = Instant.now();
        Long ownerId = customerService.createCustomer("test-client", UUID.randomUUID()).customerId();
        Money balance = Money.of(new BigDecimal(amount), EUR);
        return accountService.save(Account.of(null, ownerId, balance, Account.AccountStatus.ACTIVE, 0, now, now))
                .getId();
    }

    private Long ownerOf(Long accountId) {
        return jdbcTemplate.queryForObject("select owner_id from accounts where account_id = ?", Long.class, accountId);
    }

    private Long transfer(Long from, Long to, String status, String errorCode, String createdAgo) {
        return jdbcTemplate.queryForObject("""
                insert into transfers (owner_id, account_from, account_to, amount, currency, status, error_code, created_at)
                values (?, ?, ?, 100, 'EUR', ?::transfer_status, ?, now() - ?::interval)
                returning transfer_id
                """, Long.class, ownerOf(from), from, to, status, errorCode, createdAgo);
    }

    private void entry(Long accountId, Long transferId, long seqId) {
        jdbcTemplate.update("""
                insert into transactions (account_id, transfer_id, seq_id, transaction_type, amount, balance_after, currency)
                values (?, ?, ?, 'DEPOSIT', 100, 100, 'EUR')
                """, accountId, transferId, seqId);
    }

    private Long outboxRow(Long accountId, Long transferId, boolean sent, String createdAgo) {
        return jdbcTemplate.queryForObject("""
                insert into outbox (account_id, transfer_id, event_type, topic, payload, sent_at, created_at)
                values (?, ?, 'created', 'transfers', '{}'::jsonb, case when ? then now() end, now() - ?::interval)
                returning id
                """, Long.class, accountId, transferId, sent, createdAgo);
    }

    @Test
    void aRealTransferLeavesBothTheReceiverAndTheTransferConsistent() {
        Long from = accountWithBalance("10.00");
        Long to = accountWithBalance("0.00");
        Long transferId = transferService.accept("test-client", UUID.randomUUID(), from, to,
                Money.of(new BigDecimal("4.00"), EUR)).transfer().getTransferId();
        transferService.process(transferId);

        ReconciliationReport report = reconciliationJob.reconcile();

        assertThat(report.balanceMismatches()).extracting(BalanceMismatch::accountId).doesNotContain(to);
        assertThat(report.entryCountMismatches()).extracting(EntryCountMismatch::transferId)
                .doesNotContain(transferId);
        assertThat(report.balanceMismatches()).extracting(BalanceMismatch::accountId).contains(from);
    }

    @Test
    void reportsABalanceThatIsNotTheSumOfItsEntries() {
        Long account = accountWithBalance("5.00");

        ReconciliationReport report = reconciliationJob.reconcile();

        assertThat(report.balanceMismatches()).contains(new BalanceMismatch(account, ownerOf(account), "EUR", 500, 0));
    }

    @Test
    void reportsASuccessfulTransferWithoutItsTwoEntries() {
        Long from = accountWithBalance("0.00");
        Long to = accountWithBalance("0.00");
        Long noEntries = transfer(from, to, "SUCCESSFUL", null, "1 hour");
        Long oneEntry = transfer(from, to, "SUCCESSFUL", null, "1 hour");
        entry(from, oneEntry, 1);

        ReconciliationReport report = reconciliationJob.reconcile();

        assertThat(report.entryCountMismatches()).contains(
                new EntryCountMismatch(noEntries, "SUCCESSFUL", from, to, 2, 0),
                new EntryCountMismatch(oneEntry, "SUCCESSFUL", from, to, 2, 1));
    }

    @Test
    void reportsAFailedTransferThatHasEntriesButNotOneWithout() {
        Long from = accountWithBalance("0.00");
        Long to = accountWithBalance("0.00");
        Long failedClean = transfer(from, to, "FAILED", "tx-ex-02", "1 hour");
        Long failedWithEntry = transfer(from, to, "FAILED", "tx-ex-02", "1 hour");
        entry(to, failedWithEntry, 1);

        ReconciliationReport report = reconciliationJob.reconcile();

        assertThat(report.entryCountMismatches()).extracting(EntryCountMismatch::transferId)
                .contains(failedWithEntry).doesNotContain(failedClean);
    }

    @Test
    void reportsOnlyTransfersPendingLongerThanTheTimeout() {
        Long from = accountWithBalance("0.00");
        Long to = accountWithBalance("0.00");
        Long stuck = transfer(from, to, "PENDING", null, "10 minutes");
        Long fresh = transfer(from, to, "PENDING", null, "1 minute");

        ReconciliationReport report = reconciliationJob.reconcile();

        assertThat(report.stuckTransfers()).extracting(StuckTransfer::transferId)
                .contains(stuck).doesNotContain(fresh);
    }

    @Test
    void reportsOnlyOutboxRowsUnsentLongerThanTheTimeout() {
        Long from = accountWithBalance("0.00");
        Long to = accountWithBalance("0.00");
        Long stuckRow = outboxRow(from, transfer(from, to, "PENDING", null, "1 minute"), false, "10 minutes");
        Long freshRow = outboxRow(from, transfer(from, to, "PENDING", null, "1 minute"), false, "30 seconds");
        Long sentRow = outboxRow(from, transfer(from, to, "PENDING", null, "1 minute"), true, "10 minutes");

        ReconciliationReport report = reconciliationJob.reconcile();

        assertThat(report.unsentOutboxRows()).extracting(UnsentOutboxRow::id)
                .contains(stuckRow).doesNotContain(freshRow, sentRow);
    }

    private List<Map<String, Object>> findingsOf(String kind, Long subjectId) {
        return jdbcTemplate.queryForList(
                "select status::text as status, first_run_id, last_run_id, resolved_at from reconciliation_finding where kind = cast(? as finding_kind) and subject_id = ?",
                kind, subjectId);
    }

    @Test
    void aMismatchIsStoredOnceAndLaterRunsOnlyRefreshIt() {
        Long from = accountWithBalance("0.00");
        Long to = accountWithBalance("0.00");
        Long stuck = transfer(from, to, "PENDING", null, "10 minutes");

        reconciliationJob.reconcile();
        reconciliationJob.reconcile();

        List<Map<String, Object>> findings = findingsOf("STUCK_TRANSFER", stuck);
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst()).containsEntry("status", "OPEN");
        assertThat((Long) findings.getFirst().get("last_run_id")).isGreaterThan((Long) findings.getFirst().get("first_run_id"));
    }

    @Test
    void aMismatchIsResolvedWhenItGoesAway() {
        Long from = accountWithBalance("0.00");
        Long to = accountWithBalance("0.00");
        Long stuck = transfer(from, to, "PENDING", null, "10 minutes");
        reconciliationJob.reconcile();

        jdbcTemplate.update("update transfers set status = 'FAILED', error_code = 'tx-ex-02' where transfer_id = ?", stuck);
        reconciliationJob.reconcile();

        List<Map<String, Object>> findings = findingsOf("STUCK_TRANSFER", stuck);
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst()).containsEntry("status", "RESOLVED");
        assertThat(findings.getFirst().get("resolved_at")).isNotNull();
    }

    @Test
    void everyRunIsRecordedWithItsCounts() {
        Integer before = jdbcTemplate.queryForObject("select count(*) from reconciliation_run", Integer.class);

        reconciliationJob.reconcile();

        assertThat(jdbcTemplate.queryForObject("select count(*) from reconciliation_run", Integer.class))
                .isEqualTo(before + 1);
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "select finished_at, new_findings, open_findings, resolved_findings from reconciliation_run order by id desc limit 1");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(run.get("open_findings")).isNotNull();
    }

    @Test
    void anotherInstanceHoldingTheLeaderLockBlocksTheRun() throws Exception {
        Integer before = jdbcTemplate.queryForObject("select count(*) from reconciliation_run", Integer.class);

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.createStatement().execute("select pg_advisory_lock(7002)");

            ReconciliationReport report = reconciliationJob.reconcile();

            assertThat(report.isClean()).isTrue();
            assertThat(jdbcTemplate.queryForObject("select count(*) from reconciliation_run", Integer.class))
                    .isEqualTo(before);
            otherInstance.createStatement().execute("select pg_advisory_unlock(7002)");
        }

        reconciliationJob.reconcile();
        assertThat(jdbcTemplate.queryForObject("select count(*) from reconciliation_run", Integer.class))
                .isEqualTo(before + 1);
    }
}
