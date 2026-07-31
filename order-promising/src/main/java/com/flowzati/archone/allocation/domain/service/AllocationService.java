package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.model.DemandLine;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.service.selector.AllocationSelector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配貨：把一張訂單的需求，攤到一組已排序的可配批上。
 *
 * <p>傳進來的批清單**必須已經是 FEFO 順序，且已濾掉過期與預留光的批**。篩選與排序留在
 * repository，因為批數只會隨時間成長，把配不到的批載進記憶體只為了在這裡丟掉是錯的方向；
 * 而且那個順序正是索引的順序，資料庫本來就排好了。
 *
 * <p>本服務**不查資料庫、不寫資料庫**。它只做決策並改動傳進來的 {@link StockPool}，持久化與
 * 事件由 {@code OrderAllocationCoordinator} 負責。
 *
 * <p><b>它不認識訂單聚合根。</b>需求以 {@link Demand} 傳入——那是 allocation 自己的唯讀模型，
 * 來自 {@code demand_lines} view。配貨結果不寫回訂單，而是發事件由 ordering 自己推進狀態：
 * 一個交易只該修改一個 aggregate。
 */
public class AllocationService {

  private final AllocationSelector allocationSelector;

  public AllocationService(AllocationSelector allocationSelector) {
    this.allocationSelector = allocationSelector;
  }

  public AllocationService() {
    this(AllocationSelector.strictFifo());
  }

  /**
   * 整單配或整單不配。
   *
   * <p>先把整張單的取用計畫算完，確認每一條行都湊得滿，才真正動 {@code reserve()}。**規劃與
   * 套用必須分開**——邊算邊扣的話，需求 80 而可配只有 50 時會先扣掉 50 才發現配不到，那 50
   * 件就被一張出不了貨的單鎖住，而後面一張本來出得了的小單反而拿不到貨。
   */
  public AllocationResult allocate(
      Demand demand, List<StockPool> allocatableBatches, Instant now) {
    requireBatchesMatchDemand(demand, allocatableBatches);
    if (allocatableBatches.isEmpty()) {
      return AllocationResult.notAllocated(AllocationOutcome.NO_ALLOCATABLE_STOCK);
    }

    List<BatchPick> picks = planPicks(demand, allocatableBatches);
    if (picks.isEmpty()) {
      return AllocationResult.notAllocated(AllocationOutcome.INSUFFICIENT_ATP);
    }

    applyPicks(picks);
    return AllocationResult.allocated(picks);
  }

  /**
   * 補貨喚醒：由挑單政策決定這一輪餵飽哪幾張單，再逐張攤到批上。
   *
   * <p>政策看到的額度是**所有可配批的加總**，因為批對同一條需求是可互換的。挑完之後仍逐張
   * 重算取用計畫——政策只保證總量夠，不保證每一張都攤得平，真正的守門仍然是每一張單自己的
   * ship-complete 檢查。前一張單吃掉的量已經寫回 {@link StockPool} 的預留量了，下一張看到的
   * 可用量自然就是扣掉之後的。
   */
  public List<OrderAllocation> allocateBackorders(
      List<Demand> candidates,
      List<StockPool> allocatableBatches,
      Instant now
  ) {
    candidates.forEach(demand -> requireBatchesMatchDemand(demand, allocatableBatches));
    if (allocatableBatches.isEmpty()) {
      return List.of();
    }

    AllocationRequest request = new AllocationRequest(
        allocatableBatches.get(0).getSkuCode(),
        allocatableBatches.stream().mapToInt(StockPool::availableToPromise).sum(),
        now
    );

    List<OrderAllocation> allocations = new ArrayList<>();
    for (Demand demand : allocationSelector.select(candidates, request)) {
      List<BatchPick> picks = planPicks(demand, allocatableBatches);
      if (picks.isEmpty()) {
        continue;
      }
      applyPicks(picks);
      allocations.add(new OrderAllocation(demand, picks));
    }

    return allocations;
  }

  /**
   * 算出這張單要從哪些批各取多少；有任何一條行湊不滿就回空清單。
   *
   * <p>{@code planned} 記錄**這一張單**已規劃、但尚未寫回 {@link StockPool} 的取用量。少了
   * 它，同一張單的第二行會看到第一行還沒扣掉的可用量，兩行都算得出「夠」，然後其中一行在
   * {@code reserve()} 時炸掉。
   *
   * <p>跨訂單不需要同樣的累計——上一張單的 {@code reserve()} 已經寫回批次上了，這一張看到的
   * 可用量本來就是扣掉之後的。再累計一次就是重複扣。
   */
  private List<BatchPick> planPicks(Demand demand, List<StockPool> allocatableBatches) {
    List<BatchPick> picks = new ArrayList<>();
    Map<UUID, Integer> planned = new HashMap<>();

    for (DemandLine line : demand.lines()) {
      int remaining = line.quantity();
      for (StockPool batch : allocatableBatches) {
        if (remaining == 0) {
          break;
        }
        int available =
            batch.availableToPromise() - planned.getOrDefault(batch.getId(), 0);
        if (available <= 0) {
          continue;
        }
        int quantity = Math.min(available, remaining);
        picks.add(new BatchPick(line.orderLineId(), batch, quantity));
        planned.merge(batch.getId(), quantity, Integer::sum);
        remaining -= quantity;
      }
      if (remaining > 0) {
        return List.of();
      }
    }

    return picks;
  }

  /**
   * 只動庫存。**訂單的狀態不在這裡改**——配貨結果經事件送回 ordering，由它自己推進。
   */
  private void applyPicks(List<BatchPick> picks) {
    picks.forEach(pick -> pick.batch().reserve(pick.quantity()));
  }

  /**
   * 這張單的需求必須<strong>恰好</strong>落在傳進來的這一組批上。
   *
   * <p>檢查四件事：所有批屬於同一個 {@code (貨主, 倉, SKU)}、那個貨主與倉就是這筆需求的貨主
   * 與倉、且該 SKU 集合等於需求的 SKU 集合。倉別現在也查——需求帶得出倉別之後，「拿別倉的批
   * 去配」就從查不出來變成查得出來。寫成「包含」而不是「等於」的話，一張跨多個 SKU 的訂單會通過
   * 檢查，然後只扣其中一個 SKU 的量而整張單被標為已配——那是靜默的錯，不會有任何測試失敗，
   * 因為單行訂單下兩種寫法完全等價。
   *
   * <p>守的是**呼叫端給錯了批**這種程式錯誤，不是資料錯誤。一次配貨只取一個
   * {@code (貨主, 倉, SKU)} 的批，所以跨 SKU 的訂單在這一層就必須被擋下；讓它能被配貨屬於
   * 後續 change 的工作（見 roadmap R8）。
   */
  private void requireBatchesMatchDemand(Demand demand, List<StockPool> batches) {
    if (batches.isEmpty()) {
      return;
    }

    StockPool first = batches.get(0);
    boolean homogeneous = batches.stream().allMatch(batch ->
        batch.getOwnerId().equals(first.getOwnerId())
            && batch.getNodeId().equals(first.getNodeId())
            && batch.getSkuCode().equals(first.getSkuCode()));
    if (!homogeneous) {
      throw new IllegalArgumentException("Batches must share one owner, node and SKU");
    }
    if (!demand.ownerId().equals(first.getOwnerId())) {
      throw new IllegalArgumentException("Demand and batches must belong to the same owner");
    }
    if (!demand.nodeId().equals(first.getNodeId())) {
      throw new IllegalArgumentException("Demand and batches must belong to the same node");
    }
    if (!demand.totalsBySku().keySet().equals(Set.of(first.getSkuCode()))) {
      throw new IllegalArgumentException("Demand and batch SKU must match");
    }
  }
}
