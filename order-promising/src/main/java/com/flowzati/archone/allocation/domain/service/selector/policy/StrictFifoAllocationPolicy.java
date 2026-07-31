package com.flowzati.archone.allocation.domain.service.selector.policy;

import com.flowzati.archone.allocation.domain.service.selector.context.BasicAllocationContext;
import com.flowzati.archone.allocation.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.service.SkuQuantities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StrictFifoAllocationPolicy
    implements AllocationPolicy<BasicAllocationContext> {

  @Override
  public List<Demand> selectOrders(
      List<Demand> candidates,
      BasicAllocationContext context
  ) {
    requireValidCandidates(candidates);

    SkuQuantities remaining = context.availableBySku();
    List<Demand> selected = new ArrayList<>();
    for (Demand candidate : candidates) {
      SkuQuantities required = SkuQuantities.of(candidate.totalsBySku());
      // **任一** SKU 不足就停——而且是 break 不是 continue。
      //
      // 一張單因為「別的 SKU」配不到，對排在它後面的單來說沒有分別：它們仍然是後來的。
      // 改成 continue 會讓隊首在後面不斷有小單時永遠等下去，那就不是先來先服務了。
      //
      // 若吞吐成為問題，緩解是給隊首**保留額度**（佔住它需要的量、後面的用剩下的），
      // 順序因此仍然成立——不是放棄順序。
      if (!remaining.covers(required)) {
        break;
      }
      selected.add(candidate);
      remaining = remaining.minus(required);
    }
    return List.copyOf(selected);
  }

  private static void requireValidCandidates(List<Demand> candidates) {
    if (candidates == null) {
      throw new IllegalArgumentException("Allocation candidates are required");
    }
    if (candidates.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Allocation candidate cannot be null");
    }
  }
}
