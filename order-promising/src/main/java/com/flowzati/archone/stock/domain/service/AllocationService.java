package com.flowzati.archone.stock.domain.service;

import com.flowzati.archone.stock.domain.model.AllocatableBatches;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.DemandLine;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.service.selector.AllocationSelector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  public AllocationResult allocate(Demand demand, AllocatableBatches batches) {
    batches.requireCovers(demand);
    return attemptAllocation(demand, batches);
  }

  /**
   * 等待需求批次：由挑單政策決定這一輪配置哪幾張單，再逐張攤到批上。
   *
   * <p>政策看到的額度是**所有可配批的加總**，因為批對同一條需求是可互換的。挑完之後仍逐張
   * 重算取用計畫——政策只保證總量夠，不保證每一張都攤得平，真正的守門仍然是每一張單自己的
   * ship-complete 檢查。前一張單吃掉的量已經寫回 {@link StockPool} 的預留量了，下一張看到的
   * 可用量自然就是扣掉之後的。
   */
  public List<OrderAllocation> allocateWaitingBatch(
      List<Demand> candidates,
      AllocatableBatches batches,
      Instant now
  ) {
    candidates.forEach(batches::requireCovers);
    if (batches.isEmpty()) {
      return List.of();
    }

    AllocationRequest request = new AllocationRequest(availableBySku(batches), now);

    List<OrderAllocation> allocations = new ArrayList<>();
    for (Demand demand : allocationSelector.select(candidates, request)) {
      AllocationResult result = attemptAllocation(demand, batches);
      if (result.isAllocated()) {
        allocations.add(new OrderAllocation(demand, result.picks()));
      }
    }

    return allocations;
  }

  /**
   * 共用的單筆配置核心：先完成整單規劃，可行才一次套用；不負責輸入範圍驗證或候選單挑選。
   */
  private AllocationResult attemptAllocation(Demand demand, AllocatableBatches batches) {
    AllocationPlan plan = planPicks(demand, batches);
    if (!plan.isFeasible()) {
      return AllocationResult.notAllocated(outcomeFor(demand, batches), plan.shortfall());
    }

    plan.picks().forEach(pick -> pick.batch().reserve(pick.quantity()));
    return AllocationResult.allocated(plan.picks());
  }

  /**
   * 配不到的兩種：一件可配的都沒有（要進貨），或有批但不夠（等補貨）。畫面上引導出不同的
   * 動作，合成一個之後這個差別就再也回不來了。
   *
   * <p>多 SKU 之後只有**整張單指名的每一個 SKU 都一批不剩**才算前者。其中一個 SKU 賣光而別的
   * 還有貨，這張單在等的仍然是補貨。
   */
  private static AllocationOutcome outcomeFor(
      Demand demand, AllocatableBatches batches) {
    return demand.totalsBySku().keySet().stream()
        .allMatch(skuCode -> batches.forSku(skuCode).isEmpty())
        ? AllocationOutcome.NO_ALLOCATABLE_STOCK
        : AllocationOutcome.INSUFFICIENT_ATP;
  }

  /** 每個 SKU 目前可承諾的總量——批對同一條需求是可互換的，所以政策看的是加總。 */
  private static SkuQuantities availableBySku(AllocatableBatches batches) {
    Map<String, Integer> totals = new LinkedHashMap<>();
    batches.skuCodes().forEach(skuCode ->
        totals.put(skuCode, batches.availableToPromiseFor(skuCode)));
    return SkuQuantities.of(totals);
  }

  /**
   * 算出這張單要從哪些批各取多少；有任何一個 SKU 湊不滿就整單不取，並列出每一個缺口。
   *
   * <p>{@code planned} 記錄**這一張單**已規劃、但尚未寫回 {@link StockPool} 的取用量。少了
   * 它，同一張單的第二行會看到第一行還沒扣掉的可用量，兩行都算得出「夠」，然後其中一行在
   * {@code reserve()} 時炸掉——或更糟，兩行都成功而超賣。**這在單行下完全碰不到。**
   *
   * <p>跨訂單不需要同樣的累計——上一張單的 {@code reserve()} 已經寫回批次上了，這一張看到的
   * 可用量本來就是扣掉之後的。再累計一次就是重複扣。
   *
   * <p><b>可行性檢查不短路。</b>某個 SKU 湊不滿時仍然繼續算完其餘的，好讓缺口列出**每一個**
   * 不足的 SKU——問「這張單在等什麼」的人需要全部，而短路會讓答案取決於檢查順序。
   */
  private AllocationPlan planPicks(Demand demand, AllocatableBatches batches) {
    // picks 只是試算結果；整張單可行以前，不會呼叫 reserve() 改動庫存。
    List<BatchPick> picks = new ArrayList<>();
    // 同一個批可能被多條需求行命中，先扣掉本輪已規劃量，避免重複承諾。
    Map<UUID, Integer> planned = new HashMap<>();
    // 同一 SKU 可能出現在多條需求行，缺口必須累加成訂單層級的數量。
    Map<String, Integer> shortfall = new LinkedHashMap<>();

    for (DemandLine line : demand.lines()) {
      int remaining = line.quantity();
      for (StockPool batch : batches.forSku(line.skuCode())) {
        if (remaining == 0) {
          break;
        }
        int available = batch.availableToPromise() - planned.getOrDefault(batch.getId(), 0);
        if (available <= 0) {
          continue;
        }
        // 依批次既有順序攤量：批可供量與本行剩餘量取較小者。
        int quantity = Math.min(available, remaining);
        picks.add(new BatchPick(line.orderLineId(), batch, quantity));
        planned.merge(batch.getId(), quantity, Integer::sum);
        remaining -= quantity;
      }
      if (remaining > 0) {
        shortfall.merge(line.skuCode(), remaining, Integer::sum);
      }
    }

    // ship-complete：只要有缺口就捨棄半套 picks，回傳整張單的完整缺口。
    return shortfall.isEmpty()
        ? AllocationPlan.feasible(picks)
        : AllocationPlan.shortOf(SkuQuantities.of(shortfall));
  }

}
