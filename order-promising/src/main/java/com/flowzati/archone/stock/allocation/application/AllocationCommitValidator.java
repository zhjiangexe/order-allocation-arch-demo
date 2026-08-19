package com.flowzati.archone.stock.allocation.application;

import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import com.flowzati.archone.stock.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.stock.inventory.domain.aggregate.StockPool;
import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationBatchPick;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Allocation commit 前的跨模型一致性檢查。
 *
 * <p>這是一個純 validator：只讀 {@link AllocationCommitData}，不查 repository，也不修改 demand、
 * move、picking 或 stock pool。驗證通過後，Committer 才開始 mutation。
 */
final class AllocationCommitValidator {

  private AllocationCommitValidator() {
  }

  static void validate(AllocationCommitData data) {
    Map<UUID, AllocationDemandLine> demandLineById = indexDemandLines(data.demand());
    requireExecutionMatchesDemand(data.demand(), data.moves());
    requireStockPoolsMatchPlan(data, demandLineById);
    requirePickingsMatchDemand(data.demand(), data.pickings());
  }

  /** 一條 demand line 必須恰好有一筆 outbound move，且接受時的內容不能漂移。 */
  private static void requireExecutionMatchesDemand(
      AllocationDemand demand, List<StockMove> moves) {
    Map<UUID, StockMove> moveByDemandLine = indexOneMovePerDemandLine(moves);
    if (moveByDemandLine.size() != demand.lines().size()) {
      throw invalidExecutionReferences(demand);
    }

    for (AllocationDemandLine line : demand.lines()) {
      StockMove move = moveByDemandLine.get(line.id());
      if (!matchesAcceptedLine(demand, line, move)) {
        throw new IllegalStateException(
            "Allocation demand execution differs from accepted line " + line.id());
      }
    }
  }

  private static Map<UUID, StockMove> indexOneMovePerDemandLine(List<StockMove> moves) {
    // DB 的 composite FK 保證 line 屬於這張 demand，unique index 保證不會一條 line 有兩筆 move。
    return moves.stream().collect(Collectors.toMap(
        StockMove::getAllocationDemandLineId,
        Function.identity()));
  }

  private static boolean matchesAcceptedLine(
      AllocationDemand demand, AllocationDemandLine line, StockMove move) {
    return move != null
        && line.skuCode().equals(move.getSkuCode())
        && line.quantity() == move.getDemandQuantity()
        && line.sourceLineId().equals(move.getSourceLineId())
        && demand.ownerId().equals(move.getOwnerId())
        && demand.locationId().equals(move.getFromLocationId());
  }

  private static IllegalStateException invalidExecutionReferences(AllocationDemand demand) {
    return new IllegalStateException(
        "Allocation demand has missing execution references: " + demand.id());
  }

  /** Plan 指到的每個 pool 都必須存在，而且 owner、location 與 SKU 必須相符。 */
  private static void requireStockPoolsMatchPlan(
      AllocationCommitData data, Map<UUID, AllocationDemandLine> demandLineById) {
    Set<UUID> plannedPoolIds = data.plan().picks().stream()
        .map(AllocationBatchPick::stockPoolId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, StockPool> poolById = data.stockPoolsInWriteOrder().stream()
        .collect(Collectors.toMap(StockPool::getId, Function.identity()));

    requireAllStockPoolsExist(plannedPoolIds, poolById);
    for (AllocationBatchPick pick : data.plan().picks()) {
      requirePickScopeMatches(
          data.demand(),
          demandLineById.get(pick.allocationDemandLineId()),
          poolById.get(pick.stockPoolId()));
    }
  }

  private static void requireAllStockPoolsExist(
      Set<UUID> plannedPoolIds, Map<UUID, StockPool> poolById) {
    if (poolById.keySet().equals(plannedPoolIds)) {
      return;
    }
    Set<UUID> missingPoolIds = new LinkedHashSet<>(plannedPoolIds);
    missingPoolIds.removeAll(poolById.keySet());
    throw new IllegalStateException("Planned stock pools no longer exist: " + missingPoolIds);
  }

  private static void requirePickScopeMatches(
      AllocationDemand demand,
      AllocationDemandLine line,
      StockPool pool) {
    boolean sameOwner = demand.ownerId().equals(pool.getOwnerId());
    boolean sameLocation = demand.locationId().equals(pool.getLocationId());
    boolean sameSku = line.skuCode().equals(pool.getSkuCode());
    if (!sameOwner || !sameLocation || !sameSku) {
      throw new IllegalStateException(
          "Allocation plan, demand and stock pool scopes do not match");
    }
  }

  /** Picking 是 execution summary；存在時必須和 demand 使用相同 owner 與來源庫位。 */
  private static void requirePickingsMatchDemand(
      AllocationDemand demand, List<StockPicking> pickings) {
    for (StockPicking picking : pickings) {
      boolean sameOwner = picking.ownerId().equals(demand.ownerId());
      boolean sameSourceLocation = picking.fromLocationId().equals(demand.locationId());
      if (!sameOwner || !sameSourceLocation) {
        throw new IllegalStateException(
            "Picking and demand movements use different source locations");
      }
    }
  }

  private static Map<UUID, AllocationDemandLine> indexDemandLines(AllocationDemand demand) {
    return demand.lines().stream()
        .collect(Collectors.toMap(AllocationDemandLine::id, Function.identity()));
  }
}
