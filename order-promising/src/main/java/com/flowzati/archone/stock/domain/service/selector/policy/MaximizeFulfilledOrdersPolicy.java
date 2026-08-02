package com.flowzati.archone.stock.domain.service.selector.policy;

import com.flowzati.archone.stock.domain.service.selector.context.BasicAllocationContext;
import com.flowzati.archone.stock.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.service.SkuQuantities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MaximizeFulfilledOrdersPolicy
    implements AllocationPolicy<BasicAllocationContext> {

  @Override
  public List<Demand> selectOrders(
      List<Demand> candidates,
      BasicAllocationContext context
  ) {
    // **排序鍵是整籃的總件數**，不是 SKU 數、也不是最稀缺 SKU 的需求量。
    //
    // 這個政策的目標是「這一輪餵飽最多張單」，而多 SKU 之後「小」有三個候選讀法。選總件數
    // 的理由：它是唯一與目標同一個單位的度量——庫存以件計，餵飽一張單付出的代價就是它的
    // 總件數。SKU 數衡量的是廣度而非代價（一張只要 1 件卻跨 5 個 SKU 的單並不昂貴）；最稀缺
    // SKU 的需求量只看一個維度，會讓一張在別的 SKU 上很貴的單排到前面。
    //
    // 總件數不是最佳解——真正的最佳化是多維背包，而那是 NP-hard。貪婪在單維度下是最佳的，
    // 多維度下只是一個合理的啟發式。這個政策目前**沒有生產呼叫端**（只有測試），所以先取
    // 一個說得出理由的度量，等它真的被用時再依實際目標調整。
    List<Demand> smallestFirst = new ArrayList<>(candidates);
    smallestFirst.sort(Comparator.comparingInt(MaximizeFulfilledOrdersPolicy::totalUnits));

    SkuQuantities remaining = context.availableBySku();
    List<Demand> selected = new ArrayList<>();
    for (Demand candidate : smallestFirst) {
      SkuQuantities required = SkuQuantities.of(candidate.totalsBySku());
      // 與嚴格 FIFO 不同，這裡刻意是 continue 而不是 break——這個政策的存在理由就是不照
      // 順序、盡量多餵幾張。它與 FIFO 的取捨在挑單這一層做完，配貨本身兩者共用。
      if (!remaining.covers(required)) {
        continue;
      }
      selected.add(candidate);
      remaining = remaining.minus(required);
    }
    return List.copyOf(selected);
  }

  private static int totalUnits(Demand demand) {
    return demand.totalsBySku().values().stream().mapToInt(Integer::intValue).sum();
  }
}
