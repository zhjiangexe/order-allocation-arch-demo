package com.flowzati.archone.stock.allocation.domain.service;

import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationBatchPick;
import com.flowzati.archone.stock.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.stock.inventory.domain.aggregate.StockPool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 單一 SKU 的 FEFO 批次佇列。
 *
 * <p>Repository 已將 batches 按 FEFO 排好，因此隊首永遠是目前應優先使用的批次。規劃時從隊首
 * 取量；批次用完就移除，還有剩餘量就放回隊首，讓下一條相同 SKU 的 demand line 接著使用。
 *
 * <p>Queue 只保存本次規劃的剩餘量並產生 {@link AllocationBatchPick}，不會修改或 reserve
 * {@link StockPool}。真正的 reservation 仍由 {@code AllocationCommitter} 在 transaction 中執行。
 */
final class FefoBatchQueue {

  private final Deque<RemainingBatch> remainingBatches;

  FefoBatchQueue(List<StockPool> fefoBatches) {
    remainingBatches = new ArrayDeque<>(fefoBatches.size());
    for (StockPool batch : fefoBatches) {
      int availableQuantity = batch.availableToPromise();
      if (availableQuantity > 0) {
        remainingBatches.addLast(new RemainingBatch(batch, availableQuantity));
      }
    }
  }

  /** 規劃一條 demand line 要從哪些 FEFO batches 各取多少。 */
  List<AllocationBatchPick> planPicksFor(AllocationDemandLine line) {
    List<AllocationBatchPick> picks = new ArrayList<>();
    int quantityStillNeeded = line.quantity();

    // 一條 line 可能跨多個 batches；直到需要量全部分配完才結束。
    while (quantityStillNeeded > 0) {
      RemainingBatch batch = firstBatchOrThrow(line);

      // 這次最多只能取「本行還需要的量」與「隊首批次剩餘量」兩者中較小者。
      int quantityToPick = Math.min(quantityStillNeeded, batch.remainingQuantity());
      picks.add(toPick(line, batch.stockPool(), quantityToPick));

      quantityStillNeeded -= quantityToPick;
      consumeFromFirstBatch(batch, quantityToPick);
    }

    return List.copyOf(picks);
  }

  /** 建立純試算 pick；此時不會改動 StockPool。 */
  private static AllocationBatchPick toPick(
      AllocationDemandLine line, StockPool batch, int quantity) {
    return new AllocationBatchPick(
        line.allocationDemandId(),
        line.id(),
        batch.getId(),
        quantity);
  }

  /**
   * 先移除已使用的隊首；若批次還有剩餘量，再以新的不可變狀態放回隊首供下一輪使用。
   */
  private void consumeFromFirstBatch(RemainingBatch batch, int consumedQuantity) {
    remainingBatches.removeFirst();
    int quantityLeft = batch.remainingQuantity() - consumedQuantity;
    if (quantityLeft > 0) {
      remainingBatches.addFirst(new RemainingBatch(batch.stockPool(), quantityLeft));
    }
  }

  private RemainingBatch firstBatchOrThrow(AllocationDemandLine line) {
    RemainingBatch batch = remainingBatches.peekFirst();
    if (batch == null) {
      // Planner 的 aggregate preflight 已判定供給足夠；走到這裡表示兩段演算法不一致。
      throw new IllegalStateException(
          "Preflight supply could not satisfy allocation demand line " + line.id());
    }
    return batch;
  }

  /** Queue 自己擁有的試算狀態；不把剩餘量寫回 StockPool。 */
  private record RemainingBatch(StockPool stockPool, int remainingQuantity) {
  }
}
