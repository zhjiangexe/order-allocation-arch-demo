package com.flowzati.archone.stock.allocation.infrastructure.health;

import com.flowzati.archone.stock.allocation.domain.repository.AllocationDemandRepository;
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
  private final AllocationDemandRepository demands;
  private final AtomicInteger anomalyCount = new AtomicInteger();

  public AllocationDemandHealthIndicator(
      AllocationDemandRepository demands, MeterRegistry meters) {
    this.demands = demands;
    meters.gauge("allocation_anomaly_isolated", anomalyCount);
  }

  @Override
  public Health health() {
    List<UUID> anomalies = demands.findPendingExecutionAnomalyIds(SAMPLE_LIMIT);
    anomalyCount.set(anomalies.size());
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
