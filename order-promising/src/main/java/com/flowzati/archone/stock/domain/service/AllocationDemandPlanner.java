package com.flowzati.archone.stock.domain.service;

import com.flowzati.archone.stock.domain.model.AllocatableBatches;
import com.flowzati.archone.stock.domain.model.AllocationDemand;
import com.flowzati.archone.stock.domain.model.AllocationDemandLine;
import com.flowzati.archone.stock.domain.model.AllocationDemandStatus;
import com.flowzati.archone.stock.domain.model.StockPool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-agnostic 的 FEFO／all-or-nothing 供需規劃器。
 *
 * <p>傳進來的批清單**必須已經是 FEFO 順序，且已濾掉過期與預留光的批**。篩選與排序留在
 * repository，因為批數只會隨時間成長，把配不到的批載進記憶體只為了在這裡丟掉是錯的方向；
 * 而且那個順序正是索引的順序，資料庫本來就排好了。
 *
 * <p>本服務不查 repository、不保留或修改 {@link StockPool}，只回傳 allocation-owned immutable
 * plan；持久化、reservation、movement 與 completion fact 由 application committer 負責。
 */
public class AllocationDemandPlanner {

  /**
   * 先以 SKU aggregate 判斷 ship-complete；全部足夠後才依 canonical lines 與 FEFO 建立 picks。
   */
  public AllocationDemandPlan plan(AllocationDemand demand, AllocatableBatches batches) {
    // 入口只接受仍在等待的需求；batches 也必須完整涵蓋需求的 owner、location 與全部 SKU。
    requirePlannable(demand, batches);

    // lineSequence 是 acceptance 固定下來的 canonical 順序，不能依 transport 傳入順序配置。
    List<AllocationDemandLine> canonicalLines = canonicalLines(demand);

    // 階段一先回答三個問題：「每個 SKU 要多少？有多少？還缺多少？」
    // 1. 要多少：同一 SKU 可能出現在多條 lines，先加總。例如 A=3+4、B=2 → {A=7, B=2}。
    SkuQuantities required = aggregateDemand(canonicalLines);
    // 2. 有多少：加總各 SKU 的可用 batches，但超過需求的部分不用算。例如庫存 A=15、B=1
    //    對這張需求只記成 {A=7, B=1}；因為 A 多出的 8 不影響這張需求能否配完。
    SkuQuantities available = availableUpToRequired(required, batches);
    // 3. 還缺多少：required - available。上述例子得到 {B=1}；A 已足夠，所以不列入缺口。
    SkuQuantities missingQuantities = required.missingFrom(available);
    if (!missingQuantities.isEmpty()) {
      // Ship-complete／all-or-nothing：有任何缺口就不產生半套 picks。
      return AllocationDemandPlan.insufficientSupply(demand.id(), missingQuantities);
    }

    // 階段二：只有確認全部 SKU 足夠後，才依 canonical line 與 FEFO 批次建立實際 picks。
    return AllocationDemandPlan.readyToCommit(
        demand,
        planPicksByCanonicalLine(canonicalLines, batches));
  }

  /** 驗證 planner 的狀態與庫存快照邊界；這裡仍然不查 repository。 */
  private static void requirePlannable(AllocationDemand demand, AllocatableBatches batches) {
    if (demand.status() != AllocationDemandStatus.PENDING) {
      throw new IllegalStateException("Only a pending allocation demand can be planned");
    }
    batches.requireCovers(demand);
  }

  /** 即使 rehydrate 時 lines 的容器順序不同，配置結果仍由不可變的 lineSequence 決定。 */
  private static List<AllocationDemandLine> canonicalLines(AllocationDemand demand) {
    return demand.lines().stream()
        .sorted(Comparator.comparingInt(AllocationDemandLine::lineSequence))
        .toList();
  }

  /** 同 SKU 可以有多條 demand line；preflight 必須先合併成 SKU 層級的總需求。 */
  private static SkuQuantities aggregateDemand(List<AllocationDemandLine> canonicalLines) {
    Map<String, Integer> requiredBySku = new LinkedHashMap<>();
    for (AllocationDemandLine line : canonicalLines) {
      requiredBySku.merge(line.skuCode(), line.quantity(), Math::addExact);
    }
    return SkuQuantities.of(requiredBySku);
  }

  /**
   * 計算 preflight 看得到的供給量。
   *
   * <p>每個 SKU 只累加到需求量為止：超出的供給不影響「能否完整滿足」，也避免大量 supply 在
   * {@code int} 加總時溢位。這一步只讀 {@link StockPool#availableToPromise()}，不做 reservation。
   */
  private static SkuQuantities availableUpToRequired(SkuQuantities required, AllocatableBatches batches) {
    Map<String, Integer> availableBySku = new LinkedHashMap<>();
    for (String skuCode : required.skuCodes()) {
      int requiredQuantity = required.quantityOf(skuCode);
      int available = 0;
      // repository 已保證批次順序與可配條件；preflight 只需要累加數量，不需要重新排序。
      for (StockPool batch : batches.forSku(skuCode)) {
        int stillRequired = requiredQuantity - available;
        if (stillRequired == 0) {
          break;
        }
        available += Math.min(batch.availableToPromise(), stillRequired);
      }
      availableBySku.put(skuCode, available);
    }
    return SkuQuantities.of(availableBySku);
  }

  /**
   * 依 canonical line 順序分配；每個 SKU 有自己的 FEFO batch queue，所以交錯 SKU
   * 不會互相影響批次消耗進度。
   */
  private static List<AllocationBatchPick> planPicksByCanonicalLine(
      List<AllocationDemandLine> canonicalLines,
      AllocatableBatches batches) {
    // 每個 SKU 都有自己的 FEFO queue；A 使用哪些批次不會移動 B 的隊首。
    Map<String, FefoBatchQueue> queuesBySku = new HashMap<>();

    // 一條 demand line 可能跨多個 batches，因此最後的 picks 數量可能多於 demand lines。
    List<AllocationBatchPick> picks = new ArrayList<>();

    // 必須照 acceptance 固定的 lineSequence 處理，才能讓相同輸入永遠得到相同批次分配結果。
    for (AllocationDemandLine line : canonicalLines) {
      // 第一次遇到某個 SKU 才建立 queue；同 SKU 的下一條 line 沿用剩餘批次與數量。

      // Queue 從最早到期的剩餘批次開始，規劃滿足這條 line 所需的一個或多個 picks。
      FefoBatchQueue fefoBatchQueue =
          queuesBySku.computeIfAbsent(line.skuCode(), skuCode -> new FefoBatchQueue(batches.forSku(skuCode)));
      List<AllocationBatchPick> batchPicks = fefoBatchQueue.planPicksFor(line);
      picks.addAll(batchPicks);
    }

    // 這裡只回傳試算結果；StockPool reservation 會在 AllocationCommitter 的 transaction 中執行。
    return picks;
  }
}
