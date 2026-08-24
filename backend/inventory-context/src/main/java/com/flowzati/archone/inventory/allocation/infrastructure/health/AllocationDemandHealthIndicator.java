package com.flowzati.archone.inventory.allocation.infrastructure.health;

import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Alert surface for isolated demand/movement execution anomalies; never auto-repairs them. */
@Component("allocationDemand")
public class AllocationDemandHealthIndicator implements HealthIndicator {

    private static final int SAMPLE_LIMIT = 20;
    private final JpaAllocationDemandRepository demands;
    private final AtomicInteger anomalyPresent = new AtomicInteger();

    public AllocationDemandHealthIndicator(JpaAllocationDemandRepository demands, MeterRegistry meters) {
        this.demands = demands;
        meters.gauge("allocation_anomaly_isolated", anomalyPresent);
    }

    @Override
    public Health health() {
        List<UUID> anomalies = demands.findPendingExecutionAnomalyIds(SAMPLE_LIMIT);
        // This gauge is a health signal, not a count: the query below intentionally returns only a bounded sample.
        anomalyPresent.set(anomalies.isEmpty() ? 0 : 1);
        if (anomalies.isEmpty()) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("sampleAllocationDemandIds", anomalies)
                .withDetail("sampleLimit", SAMPLE_LIMIT)
                .withDetail("repairPolicy", "manual-only")
                .build();
    }
}
