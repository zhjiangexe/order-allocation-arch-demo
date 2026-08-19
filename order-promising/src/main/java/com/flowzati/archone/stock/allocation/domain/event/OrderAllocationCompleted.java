package com.flowzati.archone.stock.allocation.domain.event;

import com.flowzati.archone.foundation.domain.event.DomainEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Order、StockPool 與 StockReservation 已共同完成完整配置的領域事實。
 *
 * <p>由 order completion adapter 從 generic {@code AllocationCommitted} fact 轉譯；allocation
 * core 不直接建立 source-specific completion。
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
  }
}
