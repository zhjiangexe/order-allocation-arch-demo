package com.flowzati.archone.stock.domain.event;

import com.flowzati.archone.promising.domain.DomainEvent;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.MoveState;
import com.flowzati.archone.stock.domain.model.StockMove;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Order、StockPool 與 StockReservation 已共同完成完整配置的領域事實。
 *
 * <p><b>由 {@code MovementAssigner} 在三個聚合根都寫入之後發出</b>,不是由哪個聚合根在自己
 * 狀態轉換時發出。對外事件只能由它觸發。
 *
 * <p>同一事實有兩個外部表示：Ordering 的 lifecycle notification 只需識別與時間；WMS handoff
 * 則需要完整 committed allocation snapshot。兩份 external events 都由 translator 在同一個
 * transaction 寫入 Outbox。
 */
public record OrderAllocationCompleted(
    UUID allocationId,
    UUID orderId,
    UUID ownerId,
    UUID facilityId,
    List<AllocationLine> lines,
    Instant dispatchBy,
    int releasePriority,
    Instant allocatedAt
)
    implements DomainEvent {

  public OrderAllocationCompleted {
    if (allocationId == null || orderId == null || ownerId == null || facilityId == null) {
      throw new IllegalArgumentException("Completed allocation requires all business IDs");
    }
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Completed allocation requires lines");
    }
    lines = List.copyOf(lines);
    Set<UUID> moveIds = new HashSet<>();
    if (lines.stream().anyMatch(line -> line == null || !moveIds.add(line.moveId()))) {
      throw new IllegalArgumentException("Completed allocation requires unique non-null moves");
    }
    if (dispatchBy == null || allocatedAt == null) {
      throw new IllegalArgumentException("Dispatch deadline and allocated time are required");
    }
    if (releasePriority < 0 || releasePriority > 100) {
      throw new IllegalArgumentException("Release priority must be between 0 and 100");
    }
  }

  public static OrderAllocationCompleted from(
      Demand demand,
      List<StockMove> moves,
      Instant allocatedAt
  ) {
    if (demand == null || moves == null || moves.isEmpty()) {
      throw new IllegalArgumentException("Completed allocation requires demand and movements");
    }
    UUID allocationId = moves.getFirst().getPickingId();
    if (allocationId == null || moves.stream().anyMatch(move ->
        !allocationId.equals(move.getPickingId()) || move.getState() != MoveState.ASSIGNED)) {
      throw new IllegalStateException(
          "Fulfillment handoff requires assigned moves from one picking");
    }
    Set<UUID> demandLineIds = demand.lines().stream()
        .map(line -> line.orderLineId())
        .collect(java.util.stream.Collectors.toSet());
    if (moves.size() != demandLineIds.size()
        || moves.stream().anyMatch(move -> !demandLineIds.contains(move.getOrderLineId()))) {
      throw new IllegalStateException(
          "Fulfillment handoff movements must match every demand line");
    }
    return new OrderAllocationCompleted(
        allocationId,
        demand.orderId(),
        demand.ownerId(),
        demand.facilityId(),
        moves.stream().map(AllocationLine::from).toList(),
        demand.dispatchBy(),
        demand.releasePriority(),
        allocatedAt);
  }

  public record AllocationLine(
      UUID orderLineId,
      UUID moveId,
      String skuCode,
      UUID sourceLocationId,
      int quantity
  ) {

    public AllocationLine {
      if (orderLineId == null || moveId == null || sourceLocationId == null) {
        throw new IllegalArgumentException("Completed allocation line requires business IDs");
      }
      if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
        throw new IllegalArgumentException(
            "Completed allocation line requires SKU and positive quantity");
      }
    }

    private static AllocationLine from(StockMove move) {
      return new AllocationLine(
          move.getOrderLineId(), move.getId(), move.getSkuCode(),
          move.getFromLocationId(), move.getDemandQuantity());
    }
  }
}
