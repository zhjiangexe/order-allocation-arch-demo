package com.flowzati.archone.inventory.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationBatchPick;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandPlan;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.balance.domain.valueobject.AllocatableBatches;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("source-agnostic allocation planner")
class AllocationDemandPlannerTest {

  private static final UUID OWNER_ID = uuid(1);
  private static final UUID FACILITY_ID = uuid(2);
  private static final UUID LOCATION_ID = uuid(3);

  private final AllocationDemandPlanner planner = new AllocationDemandPlanner();

  @Test
  @DisplayName("以 allocation-owned ids 依 canonical line sequence 與 FEFO 攤量且不修改 stock")
  void shouldPlanDeterministicallyWithoutMutatingStock() {
    AllocationDemand demand = demand(List.of(
        new AllocationDemandLineRequest("line-b", "SKU-A", 4),
        new AllocationDemandLineRequest("line-a", "SKU-A", 3)));
    StockQuant first = batch(20, 5, 0, LocalDate.of(2026, 9, 1));
    StockQuant second = batch(21, 5, 0, LocalDate.of(2026, 10, 1));

    AllocationDemandPlan plan = planner.plan(demand, batches(Map.of(
        "SKU-A", List.of(first, second))));

    assertThat(plan.isReadyToCommit()).isTrue();
    assertThat(plan.picks())
        .extracting(pick -> pick.allocationDemandLineId() + ":" + pick.stockQuantId() + ":" + pick.quantity())
        .containsExactly(
            uuid(100) + ":" + uuid(20) + ":3",
            uuid(101) + ":" + uuid(20) + ":2",
            uuid(101) + ":" + uuid(21) + ":2");
    assertThat(plan.picks()).allMatch(pick -> pick.allocationDemandId().equals(demand.id()));
    assertThat(first.getReservedQuantity()).isZero();
    assertThat(second.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("multi-SKU 任一缺貨即回完整缺少數量且不帶半套 picks")
  void shouldReturnAllShortfallsAndNoPartialPicks() {
    AllocationDemand demand = demand(List.of(
        new AllocationDemandLineRequest("a", "SKU-A", 7),
        new AllocationDemandLineRequest("b", "SKU-B", 4)));
    StockQuant skuA = batch(30, 5, 0, LocalDate.of(2026, 9, 1));
    StockQuant skuB = new StockQuant(
        uuid(31), OWNER_ID, LOCATION_ID, "SKU-B",
        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), 1, 0, 0L);

    AllocationDemandPlan plan = planner.plan(demand, batches(Map.of(
        "SKU-A", List.of(skuA), "SKU-B", List.of(skuB))));

    assertThat(plan.isReadyToCommit()).isFalse();
    assertThat(plan.picks()).isEmpty();
    assertThat(plan.missingQuantities().asMap()).containsExactly(
        Map.entry("SKU-A", 2), Map.entry("SKU-B", 3));
    assertThat(skuA.getReservedQuantity()).isZero();
    assertThat(skuB.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("每個 SKU 的 FEFO queue 在交錯 lines 間各自保留剩餘批次")
  void shouldKeepIndependentFefoQueueForEachSku() {
    AllocationDemand demand = demand(List.of(
        new AllocationDemandLineRequest("a", "SKU-A", 3),
        new AllocationDemandLineRequest("b", "SKU-B", 2),
        new AllocationDemandLineRequest("c", "SKU-A", 4)));
    StockQuant skuAFirst = batch(40, "SKU-A", 5, 0, LocalDate.of(2026, 9, 1));
    StockQuant skuASecond = batch(41, "SKU-A", 5, 0, LocalDate.of(2026, 10, 1));
    StockQuant skuB = batch(42, "SKU-B", 2, 0, LocalDate.of(2026, 9, 1));

    AllocationDemandPlan plan = planner.plan(demand, batches(Map.of(
        "SKU-A", List.of(skuAFirst, skuASecond),
        "SKU-B", List.of(skuB))));

    assertThat(plan.picks())
        .extracting(pick -> pick.allocationDemandLineId() + ":" + pick.stockQuantId()
            + ":" + pick.quantity())
        .containsExactly(
            uuid(100) + ":" + uuid(40) + ":3",
            uuid(101) + ":" + uuid(42) + ":2",
            uuid(102) + ":" + uuid(40) + ":2",
            uuid(102) + ":" + uuid(41) + ":2");
  }

  @Test
  @DisplayName("aggregate feasibility 將 supply 累加上限截在需求量以避免 int overflow")
  void shouldCapAggregateSupplyAtRequiredQuantity() {
    AllocationDemand demand = demand(List.of(
        new AllocationDemandLineRequest("a", "SKU-A", Integer.MAX_VALUE)));
    StockQuant first = batch(
        50, "SKU-A", Integer.MAX_VALUE - 1, 0, LocalDate.of(2026, 9, 1));
    StockQuant second = batch(
        51, "SKU-A", Integer.MAX_VALUE, 0, LocalDate.of(2026, 10, 1));

    AllocationDemandPlan plan = planner.plan(demand, batches(Map.of(
        "SKU-A", List.of(first, second))));

    assertThat(plan.isReadyToCommit()).isTrue();
    assertThat(plan.picks()).extracting(AllocationBatchPick::quantity)
        .containsExactly(Integer.MAX_VALUE - 1, 1);
  }

  private static AllocationDemand demand(List<AllocationDemandLineRequest> lines) {
    AtomicInteger lineIds = new AtomicInteger(100);
    return AllocationDemand.accept(
        uuid(10),
        new SourceAllocationUnit(AllocationSourceType.MANUAL, "manual-1", "PRIMARY"),
        OWNER_ID,
        FACILITY_ID,
        LOCATION_ID,
        Instant.parse("2026-08-20T00:00:00Z"),
        50,
        Instant.parse("2026-08-18T00:00:00Z"),
        lines,
        () -> uuid(lineIds.getAndIncrement()));
  }

  private static AllocatableBatches batches(Map<String, List<StockQuant>> batches) {
    return AllocatableBatches.of(OWNER_ID, LOCATION_ID, batches);
  }

  private static StockQuant batch(
      int id, int onHand, int reserved, LocalDate expiryDate) {
    return batch(id, "SKU-A", onHand, reserved, expiryDate);
  }

  private static StockQuant batch(
      int id, String skuCode, int onHand, int reserved, LocalDate expiryDate) {
    return new StockQuant(
        uuid(id), OWNER_ID, LOCATION_ID, skuCode,
        LocalDate.of(2026, 8, 1), expiryDate, onHand, reserved, 0L);
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
