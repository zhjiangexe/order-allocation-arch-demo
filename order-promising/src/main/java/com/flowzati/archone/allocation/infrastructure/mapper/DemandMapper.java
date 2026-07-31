package com.flowzati.archone.allocation.infrastructure.mapper;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.model.DemandLine;
import com.flowzati.archone.allocation.infrastructure.entity.DemandLineEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DemandMapper {

  private DemandMapper() {
  }

  /**
   * 把 view 的列摺成一張張 {@link Demand}，**保持傳入的順序**。
   *
   * <p>用 {@link LinkedHashMap} 分組是為了保序，而查詢已經以 {@code orderId} 排好——那個順序
   * 就是佇列的順序，摺疊不得打亂它。回傳型別因此是 {@code List} 而不是 {@code Map}：順序是
   * List 的性質，而 Map 的保序得靠「別換成 HashMap」這種只寫在註解裡的約定。
   *
   * <p>貨主、倉別與收單時刻取自該單的第一列。view 的每一列都帶著它們，而同一張單的每一列必然
   * 相同——它們是 header 的值。
   */
  public static List<Demand> toDomain(List<DemandLineEntity> rows) {
    Map<UUID, List<DemandLineEntity>> byOrder = new LinkedHashMap<>();
    for (DemandLineEntity row : rows) {
      byOrder.computeIfAbsent(row.getOrderId(), id -> new ArrayList<>()).add(row);
    }

    List<Demand> demands = new ArrayList<>(byOrder.size());
    for (List<DemandLineEntity> orderRows : byOrder.values()) {
      DemandLineEntity first = orderRows.getFirst();
      demands.add(new Demand(
          first.getOrderId(),
          first.getOwnerId(),
          first.getNodeId(),
          first.getReceivedAt(),
          orderRows.stream().map(DemandMapper::toLine).toList()));
    }
    return List.copyOf(demands);
  }

  private static DemandLine toLine(DemandLineEntity row) {
    return new DemandLine(row.getOrderLineId(), row.getSkuCode(), row.getQuantity());
  }
}
