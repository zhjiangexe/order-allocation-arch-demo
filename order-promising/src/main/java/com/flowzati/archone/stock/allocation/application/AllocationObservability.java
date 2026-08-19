package com.flowzati.archone.stock.allocation.application;

import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationCandidateBatch;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics plus structured evidence for strict-FIFO head-of-line blocking. */
@Component
public class AllocationObservability {

  private static final Logger log = LoggerFactory.getLogger(AllocationObservability.class);
  private static final Comparator<AllocationDemand> PRECEDENCE = Comparator
      .comparing(AllocationDemand::enqueuedAt)
      .thenComparing(AllocationDemand::id);

  private final MeterRegistry meters;

  public AllocationObservability(MeterRegistry meters) {
    this.meters = meters;
  }

  public void recordBlocked(AllocationCandidateBatch batch, Instant observedAt) {
    batch.candidates().stream().min(PRECEDENCE).ifPresent(candidate ->
        candidate.totalsBySku().keySet().forEach(sku ->
            batch.fifoContext().stream()
                .filter(other -> other.totalsBySku().containsKey(sku))
                .min(PRECEDENCE)
                .filter(predecessor -> !predecessor.id().equals(candidate.id()))
                .ifPresent(predecessor -> record(candidate, predecessor, sku, observedAt))));
  }

  private void record(
      AllocationDemand blocked,
      AllocationDemand predecessor,
      String blockedSku,
      Instant observedAt) {
    meters.counter(
        "allocation_fifo_blocked_total",
        "source_type", blocked.source().sourceType().name(),
        "sku", blockedSku).increment();
    meters.timer(
        "allocation_fifo_pending_age",
        "source_type", predecessor.source().sourceType().name(),
        "blocked_sku", blockedSku)
        .record(Duration.between(predecessor.enqueuedAt(), observedAt).abs());
    log.atWarn()
        .addKeyValue("allocationDemandId", blocked.id())
        .addKeyValue("blockingPredecessorId", predecessor.id())
        .addKeyValue("blockedSku", blockedSku)
        .addKeyValue("predecessorEnqueuedAt", predecessor.enqueuedAt())
        .log("Allocation demand blocked by strict shared-SKU FIFO predecessor");
  }
}
