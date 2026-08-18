package com.flowzati.archone.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.stock.domain.model.AllocationCandidateBatch;
import com.flowzati.archone.stock.domain.model.AllocationDemand;
import com.flowzati.archone.stock.domain.model.AllocationDemandLineRequest;
import com.flowzati.archone.stock.domain.model.AllocationSourceType;
import com.flowzati.archone.stock.domain.model.SourceAllocationUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("shared-SKU FIFO selector")
class AllocationFifoSelectorTest {

  private static final UUID OWNER_ID = uuid(1);
  private static final UUID FACILITY_ID = uuid(2);
  private static final UUID LOCATION_ID = uuid(3);

  private final AllocationFifoSelector selector = new AllocationFifoSelector();

  @Test
  @DisplayName("A+B candidate 不得越過 earlier B-only predecessor")
  void shouldEnforceHeadOfLineAcrossEveryRequiredSku() {
    AllocationDemand earlierB = queueDemand(
        40, Instant.parse("2026-08-18T00:00:00Z"),
        List.of(new AllocationDemandLineRequest("b", "SKU-B", 1)));
    AllocationDemand candidateAb = queueDemand(
        50, Instant.parse("2026-08-18T00:01:00Z"),
        List.of(
            new AllocationDemandLineRequest("a", "SKU-A", 1),
            new AllocationDemandLineRequest("b", "SKU-B", 1)));

    assertThat(selector.selectFirstEligible(new AllocationCandidateBatch(
        List.of(candidateAb), List.of(earlierB, candidateAb)))).isEmpty();
  }

  @Test
  @DisplayName("disjoint SKU predecessor 不得阻擋 candidate，empty batch 是 no-op")
  void shouldKeepDisjointQueuesIndependentAndHandleEmptyBatch() {
    AllocationDemand earlierB = queueDemand(
        60, Instant.parse("2026-08-18T00:00:00Z"),
        List.of(new AllocationDemandLineRequest("b", "SKU-B", 1)));
    AllocationDemand candidateC = queueDemand(
        70, Instant.parse("2026-08-18T00:01:00Z"),
        List.of(new AllocationDemandLineRequest("c", "SKU-C", 1)));

    assertThat(selector.selectFirstEligible(new AllocationCandidateBatch(
        List.of(candidateC), List.of(earlierB, candidateC))))
        .contains(candidateC);
    assertThat(selector.selectFirstEligible(AllocationCandidateBatch.empty())).isEmpty();
  }

  private static AllocationDemand queueDemand(
      int id, Instant enqueuedAt, List<AllocationDemandLineRequest> lines) {
    AtomicInteger lineIds = new AtomicInteger(id + 1);
    return AllocationDemand.accept(
        uuid(id),
        new SourceAllocationUnit(AllocationSourceType.MANUAL, "manual-" + id, "PRIMARY"),
        OWNER_ID,
        FACILITY_ID,
        LOCATION_ID,
        Instant.parse("2026-08-20T00:00:00Z"),
        50,
        enqueuedAt,
        lines,
        () -> uuid(lineIds.getAndIncrement()));
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
