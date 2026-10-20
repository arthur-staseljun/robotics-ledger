package com.robotics.demo.ledger.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class ReconciliationStore {

    public record OpenFinding(long id, Finding.Kind kind, long subjectId) {

        public Finding.Key key() {
            return new Finding.Key(kind, subjectId);
        }
    }

    private static final String RUN_ID = "runId";

    private final JdbcClient jdbcClient;

    public long startRun(OffsetDateTime startedAt) {
        return Objects.requireNonNull(
                jdbcClient.sql("insert into reconciliation_run (started_at) values (:startedAt) returning id")
                        .param("startedAt", startedAt)
                        .query(Long.class)
                        .single(),
                "The insert of a reconciliation run returned no id");
    }

    public void finishRun(long runId, int newFindings, int openFindings, int resolvedFindings) {
        jdbcClient.sql("""
                        update reconciliation_run
                        set finished_at = now(), new_findings = :newFindings, open_findings = :openFindings,
                            resolved_findings = :resolvedFindings
                        where id = :runId
                        """)
                .param(RUN_ID, runId)
                .param("newFindings", newFindings)
                .param("openFindings", openFindings)
                .param("resolvedFindings", resolvedFindings)
                .update();
    }

    public List<OpenFinding> findOpen() {
        return jdbcClient.sql("select id, kind::text as kind, subject_id from reconciliation_finding where status = 'OPEN'")
                .query(OpenFinding.class)
                .list();
    }

    public void insertFinding(long runId, Finding finding) {
        jdbcClient.sql("""
                        insert into reconciliation_finding (kind, subject_id, details, first_run_id, last_run_id)
                        values (cast(:kind as finding_kind), :subjectId, :details, :runId, :runId)
                        """)
                .param("kind", finding.kind().name())
                .param("subjectId", finding.subjectId())
                .param("details", finding.details())
                .param(RUN_ID, runId)
                .update();
    }

    public void touchFinding(long id, long runId, String details) {
        jdbcClient.sql("update reconciliation_finding set details = :details, last_run_id = :runId, last_seen_at = now() where id = :id")
                .param("id", id)
                .param(RUN_ID, runId)
                .param("details", details)
                .update();
    }

    public void resolveFinding(long id, long runId) {
        jdbcClient.sql("""
                        update reconciliation_finding
                        set status = 'RESOLVED', resolved_at = now(), last_run_id = :runId
                        where id = :id
                        """)
                .param("id", id)
                .param(RUN_ID, runId)
                .update();
    }
}
