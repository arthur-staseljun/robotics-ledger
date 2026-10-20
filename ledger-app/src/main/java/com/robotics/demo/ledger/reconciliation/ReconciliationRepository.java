package com.robotics.demo.ledger.reconciliation;

import com.robotics.demo.ledger.reconciliation.ReconciliationReport.BalanceMismatch;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.EntryCountMismatch;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.StuckTransfer;
import com.robotics.demo.ledger.reconciliation.ReconciliationReport.UnsentOutboxRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ReconciliationRepository {

    private final JdbcClient jdbcClient;

    public List<BalanceMismatch> findBalanceMismatches() {
        return jdbcClient.sql("""
                        select a.account_id, a.balance, coalesce(sum(t.amount), 0)::bigint as entries_sum
                        from accounts a
                        left join transactions t on t.account_id = a.account_id
                        group by a.account_id, a.balance
                        having a.balance <> coalesce(sum(t.amount), 0)
                        order by a.account_id
                        """)
                .query(BalanceMismatch.class)
                .list();
    }

    public List<EntryCountMismatch> findEntryCountMismatches() {
        return jdbcClient.sql("""
                        select tr.transfer_id, tr.status::text as status, count(t.transfer_id) as entries
                        from transfers tr
                        left join transactions t on t.transfer_id = tr.transfer_id
                        where tr.status <> 'PENDING'
                        group by tr.transfer_id, tr.status
                        having count(t.transfer_id) <> case when tr.status = 'SUCCESSFUL' then 2 else 0 end
                        order by tr.transfer_id
                        """)
                .query(EntryCountMismatch.class)
                .list();
    }

    public List<StuckTransfer> findStuckTransfers(OffsetDateTime createdBefore) {
        return jdbcClient.sql("""
                        select transfer_id, created_at from transfers
                        where status = 'PENDING' and created_at < :createdBefore
                        order by transfer_id
                        """)
                .param("createdBefore", createdBefore)
                .query(StuckTransfer.class)
                .list();
    }

    public List<UnsentOutboxRow> findUnsentOutboxRows(OffsetDateTime createdBefore) {
        return jdbcClient.sql("""
                        select id, transfer_id, created_at from outbox
                        where sent_at is null and created_at < :createdBefore
                        order by id
                        """)
                .param("createdBefore", createdBefore)
                .query(UnsentOutboxRow.class)
                .list();
    }
}
