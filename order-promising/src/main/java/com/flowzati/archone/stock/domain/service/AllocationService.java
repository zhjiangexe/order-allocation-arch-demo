package com.flowzati.archone.stock.domain.service;

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
  public AllocationResult allocate(
      Demand demand, Map<String, List<StockPool>> batchesBySku, Instant now) {
    requireBatchesCoverDemand(demand, batchesBySku);

    AllocationPlan plan = planPicks(demand, batchesBySku);
    if (!plan.isFeasible()) {
      return AllocationResult.notAllocated(outcomeFor(demand, batchesBySku), plan.shortfall());
    }

    applyPicks(plan.picks());
    return AllocationResult.allocated(plan.picks());
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
      Map<String, List<StockPool>> batchesBySku,
      Instant now
  ) {
    candidates.forEach(demand -> requireBatchesCoverDemand(demand, batchesBySku));
    if (batchesBySku.values().stream().allMatch(List::isEmpty)) {
      return List.of();
    }

    AllocationRequest request = new AllocationRequest(availableBySku(batchesBySku), now);

    List<OrderAllocation> allocations = new ArrayList<>();
    for (Demand demand : allocationSelector.select(candidates, request)) {
      AllocationPlan plan = planPicks(demand, batchesBySku);
      if (!plan.isFeasible()) {
        continue;
      }
      applyPicks(plan.picks());
      allocations.add(new OrderAllocation(demand, plan.picks()));
    }

    return allocations;
  }

  /**
   * 配不到的兩種：一件可配的都沒有（要進貨），或有批但不夠（等補貨）。畫面上引導出不同的
   * 動作，合成一個之後這個差別就再也回不來了。
   *
   * <p>多 SKU 之後只有**整張單指名的每一個 SKU 都一批不剩**才算前者。其中一個 SKU 賣光而別的
   * 還有貨，這張單在等的仍然是補貨。
   */
  private static AllocationOutcome outcomeFor(
      Demand demand, Map<String, List<StockPool>> batchesBySku) {
    return demand.totalsBySku().keySet().stream()
        .allMatch(skuCode -> batchesBySku.get(skuCode).isEmpty())
        ? AllocationOutcome.NO_ALLOCATABLE_STOCK
        : AllocationOutcome.INSUFFICIENT_ATP;
  }

  /** 每個 SKU 目前可承諾的總量——批對同一條需求是可互換的，所以政策看的是加總。 */
  private static SkuQuantities availableBySku(Map<String, List<StockPool>> batchesBySku) {
    Map<String, Integer> totals = new LinkedHashMap<>();
    batchesBySku.forEach((skuCode, batches) -> totals.put(
        skuCode, batches.stream().mapToInt(StockPool::availableToPromise).sum()));
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
  private AllocationPlan planPicks(Demand demand, Map<String, List<StockPool>> batchesBySku) {
    List<BatchPick> picks = new ArrayList<>();
    Map<UUID, Integer> planned = new HashMap<>();
    Map<String, Integer> shortfall = new LinkedHashMap<>();

    for (DemandLine line : demand.lines()) {
      int remaining = line.quantity();
      for (StockPool batch : batchesBySku.get(line.skuCode())) {
        if (remaining == 0) {
          break;
        }
        int available = batch.availableToPromise() - planned.getOrDefault(batch.getId(), 0);
        if (available <= 0) {
          continue;
        }
        int quantity = Math.min(available, remaining);
        picks.add(new BatchPick(line.orderLineId(), batch, quantity));
        planned.merge(batch.getId(), quantity, Integer::sum);
        remaining -= quantity;
      }
      if (remaining > 0) {
        shortfall.merge(line.skuCode(), remaining, Integer::sum);
      }
    }

    return shortfall.isEmpty()
        ? AllocationPlan.feasible(picks)
        : AllocationPlan.shortOf(SkuQuantities.of(shortfall));
  }

  /**
   * 只動庫存。**訂單的狀態不在這裡改**——配貨結果經事件送回 ordering，由它自己推進。
   */
  private void applyPicks(List<BatchPick> picks) {
    picks.forEach(pick -> pick.batch().reserve(pick.quantity()));
  }

  /**
   * 這張單需要的<strong>每一個</strong> SKU 都必須在分組裡有鍵。
   *
   * <p><b>某個 SKU 沒有可配批時是空群組，不是缺鍵。</b>兩者意義不同：空群組是普通的缺貨
   * （業務結果），缺鍵是呼叫端組錯了輸入（程式錯誤）。少了這個檢查，缺鍵會經
   * {@code getOrDefault} 安靜地變成缺貨，於是「忘了載某個 SKU 的批」看起來就跟「那個 SKU 賣
   * 完了」一模一樣——而前者是要修的程式錯誤，後者是正常結果。
   *
   * <p><b>是「涵蓋」而不是「恰好相等」。</b>補貨喚醒傳進來的是**整輪候選單的 SKU 聯集**，一張
   * 只要 A 的單會看到 A、B 兩個鍵，那完全正常。多出來的鍵也擋不到任何錯——{@code planPicks}
   * 只按 {@code line.skuCode()} 取用，沒有被指名的批一件都不會動。真正危險的方向只有一個：
   * 鍵少了。
   *
   * <p>每一組裡的批還要屬於它掛的那個 SKU，並與這筆需求的貨主與倉相符。守的是**呼叫端給錯了
   * 批**這種程式錯誤，不是資料錯誤。
   */
  private void requireBatchesCoverDemand(
      Demand demand, Map<String, List<StockPool>> batchesBySku) {
    if (!batchesBySku.keySet().containsAll(demand.totalsBySku().keySet())) {
      java.util.Set<String> missing = new java.util.LinkedHashSet<>(demand.totalsBySku().keySet());
      missing.removeAll(batchesBySku.keySet());
      throw new IllegalArgumentException(
          "Batches are missing a group for demanded SKUs " + missing);
    }

    batchesBySku.forEach((skuCode, batches) -> {
      for (StockPool batch : batches) {
        if (!batch.getSkuCode().equals(skuCode)) {
          throw new IllegalArgumentException(
              "Batch grouped under " + skuCode + " belongs to " + batch.getSkuCode());
        }
        if (!batch.getOwnerId().equals(demand.ownerId())) {
          throw new IllegalArgumentException("Demand and batches must belong to the same owner");
        }
        if (!batch.getLocationId().equals(demand.locationId())) {
          throw new IllegalArgumentException("Demand and batches must belong to the same node");
        }
      }
    });
  }
}
