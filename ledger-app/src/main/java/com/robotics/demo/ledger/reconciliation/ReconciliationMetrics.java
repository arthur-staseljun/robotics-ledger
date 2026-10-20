package com.robotics.demo.ledger.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReconciliationMetrics {

    private final Map<Finding.Kind, AtomicLong> openFindings = new EnumMap<>(Finding.Kind.class);
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final Timer runDuration;

    public ReconciliationMetrics(MeterRegistry registry) {
        for (Finding.Kind kind : Finding.Kind.values()) {
            AtomicLong open = new AtomicLong();
            openFindings.put(kind, open);
            Gauge.builder("ledger.reconciliation.open.findings", open, AtomicLong::get)
                    .tag("kind", kind.name().toLowerCase())
                    .register(registry);
        }
        Gauge.builder("ledger.reconciliation.last.success.timestamp.seconds", lastSuccessEpochSeconds, AtomicLong::get)
                .register(registry);
        runDuration = Timer.builder("ledger.reconciliation.run.duration").register(registry);
    }

    public void recordRun(List<Finding> findings, Duration duration, Instant finishedAt) {
        openFindings.forEach((kind, open) ->
                open.set(findings.stream().filter(finding -> finding.kind() == kind).count()));
        lastSuccessEpochSeconds.set(finishedAt.getEpochSecond());
        runDuration.record(duration);
    }
}
