package com.robotics.demo.ledger.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationMetricsTest {

    private SimpleMeterRegistry registry;
    private ReconciliationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ReconciliationMetrics(registry);
    }

    private double openFindings(String kind) {
        return registry.get("ledger.reconciliation.open.findings").tag("kind", kind).gauge().value();
    }

    @Test
    void theOpenFindingsAreCountedPerKind() {
        metrics.recordRun(List.of(
                new Finding(Finding.Kind.BALANCE, 1, "a"),
                new Finding(Finding.Kind.BALANCE, 2, "b"),
                new Finding(Finding.Kind.STUCK_TRANSFER, 3, "c")), Duration.ofSeconds(2), Instant.now());

        assertThat(openFindings("balance")).isEqualTo(2);
        assertThat(openFindings("stuck_transfer")).isEqualTo(1);
        assertThat(openFindings("entry_count")).isZero();
        assertThat(openFindings("unsent_outbox")).isZero();
    }

    @Test
    void aCleanRunBringsTheCountsBackToZero() {
        metrics.recordRun(List.of(new Finding(Finding.Kind.BALANCE, 1, "a")), Duration.ofSeconds(1), Instant.now());

        metrics.recordRun(List.of(), Duration.ofSeconds(1), Instant.now());

        assertThat(openFindings("balance")).isZero();
    }

    @Test
    void theLastSuccessAndTheDurationAreRecorded() {
        Instant finished = Instant.parse("2026-10-20T03:00:05Z");

        metrics.recordRun(List.of(), Duration.ofSeconds(5), finished);

        assertThat(registry.get("ledger.reconciliation.last.success.timestamp.seconds").gauge().value())
                .isEqualTo(finished.getEpochSecond());
        assertThat(registry.get("ledger.reconciliation.run.duration").timer().count()).isEqualTo(1);
    }
}
