package com.flowzati.archone.inventory.allocation.domain.valueobject;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure, immutable result of planning one source-agnostic allocation demand. */
public final class AllocationDemandPlan {

  private final UUID allocationDemandId;
  private final List<AllocationBatchPick> picks;
  private final SkuQuantities missingQuantities;

  private AllocationDemandPlan(
      UUID allocationDemandId,
      List<AllocationBatchPick> picks,
      SkuQuantities missingQuantities) {
    this.allocationDemandId = allocationDemandId;
    this.picks = List.copyOf(picks);
    this.missingQuantities = missingQuantities;
  }

  /**
   * 建立可提交的 plan，並在建立當下保證每條 demand line 都剛好被完整滿足。
   *
   * <p>Constructor 保持 private，避免其他呼叫端繞過這個 invariant 後再要求 Committer 防守。
   */
  public static AllocationDemandPlan readyToCommit(
      AllocationDemand demand, List<AllocationBatchPick> picks) {
    if (demand == null || picks == null || picks.isEmpty()) {
      throw new IllegalArgumentException(
          "A plan ready to commit requires a demand and its picks");
    }
    List<AllocationBatchPick> immutablePicks = List.copyOf(picks);
    requireExactlySatisfies(demand, immutablePicks);
    return new AllocationDemandPlan(demand.id(), immutablePicks, SkuQuantities.empty());
  }

  /** 至少一個 SKU 供給不足；保留缺少數量，但不得攜帶半套 picks。 */
  public static AllocationDemandPlan insufficientSupply(
      UUID allocationDemandId, SkuQuantities missingQuantities) {
    if (allocationDemandId == null || missingQuantities == null || missingQuantities.isEmpty()) {
      throw new IllegalArgumentException(
          "An insufficient-supply plan requires demand identity and missing quantities");
    }
    return new AllocationDemandPlan(allocationDemandId, List.of(), missingQuantities);
  }

  private static void requireExactlySatisfies(
      AllocationDemand demand, List<AllocationBatchPick> picks) {
    Map<UUID, AllocationDemandLine> demandLineById = demand.lines().stream()
        .collect(Collectors.toMap(AllocationDemandLine::id, Function.identity()));
    Map<UUID, Integer> plannedQuantityByLine = new LinkedHashMap<>();

    for (AllocationBatchPick pick : picks) {
      boolean belongsToDemand = demand.id().equals(pick.allocationDemandId());
      boolean lineExists = demandLineById.containsKey(pick.allocationDemandLineId());
      if (!belongsToDemand || !lineExists) {
        throw new IllegalArgumentException("Plan contains a foreign allocation demand line");
      }
      plannedQuantityByLine.merge(
          pick.allocationDemandLineId(), pick.quantity(), Math::addExact);
    }

    for (AllocationDemandLine line : demandLineById.values()) {
      int plannedQuantity = plannedQuantityByLine.getOrDefault(line.id(), 0);
      if (plannedQuantity != line.quantity()) {
        throw new IllegalArgumentException(
            "Plan does not completely satisfy allocation demand line " + line.id());
      }
    }
  }

  public UUID allocationDemandId() {
    return allocationDemandId;
  }

  public List<AllocationBatchPick> picks() {
    return picks;
  }

  public SkuQuantities missingQuantities() {
    return missingQuantities;
  }

  public boolean isReadyToCommit() {
    return missingQuantities.isEmpty();
  }
}
