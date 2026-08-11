package com.flowzati.archone.wms.outbound.application.command;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 由已提交的 allocation snapshot 映射而來，不要求 WMS 直接讀 Promising repository。
 *
 * <p>order-promising 以獨立的 {@code AllocationCommittedForFulfillmentIntegrationEvent} 提供完整
 * handoff snapshot；刻意不膨脹只供 Ordering lifecycle 使用的 {@code OrderAllocatedIntegrationEvent}。
 * WMS adapter 只做 contract → command 映射，不跨 bounded context 查詢 repository。
 *
 * <p>TODO(order-promising)：未來若 Wave 確實要依 service level、配送區或承諾時窗分組，上游還要
 * 補 {@code serviceLevelCode}／destination grouping facts；本批不先加入沒有演算法使用的空欄位。
 */
public record CreateShipmentCommand(
    /** 由 WMS 產生的內部識別；跨重送的 business idempotency 由 allocationId 保證。 */
    UUID shipmentId,
    /** allocation／StockPicking ID，是跨事件重送的冪等 business key。 */
    UUID allocationId,
    UUID orderId,
    /** 由 committed allocation snapshot 提供。 */
    UUID ownerId,
    /** 由 committed allocation snapshot 提供履約 Facility。 */
    UUID facilityId,
    /**
     * committed allocation lines；只通知 orderId 無法產生 WMS work。
     */
    List<AllocationLine> lines,
    /** 由訂單承諾結果提供最晚離倉時間；可以已逾期，WMS 仍須建單並讓 planner 看見。 */
    Instant dispatchBy,
    /** 上游明確提供的 0..100 release priority，不由 WMS 反推。 */
    int releasePriority,
    /** 可直接使用 allocation snapshot 的 committed／occurred time。 */
    Instant createdAt
) {

  public CreateShipmentCommand {
    if (shipmentId == null || allocationId == null || orderId == null
        || ownerId == null || facilityId == null) {
      throw new IllegalArgumentException("Create shipment requires all business IDs");
    }
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Create shipment requires allocation lines");
    }
    lines = List.copyOf(lines);
    Set<UUID> moveIds = new HashSet<>();
    if (lines.stream().anyMatch(line -> line == null || !moveIds.add(line.moveId()))) {
      throw new IllegalArgumentException(
          "Create shipment requires unique non-null allocation lines");
    }
    if (createdAt == null || dispatchBy == null) {
      throw new IllegalArgumentException(
          "Create shipment requires creation time and dispatch deadline");
    }
    if (releasePriority < 0 || releasePriority > 100) {
      throw new IllegalArgumentException("Release priority must be between 0 and 100");
    }
  }

  public record AllocationLine(
      UUID orderLineId,
      UUID moveId,
      String skuCode,
      /** 目前由 order-promising allocation snapshot 提供；若改由 WMS location directive 決定需重畫邊界。 */
      UUID sourceLocationId,
      int quantity
  ) {

    public AllocationLine {
      if (orderLineId == null || moveId == null || sourceLocationId == null) {
        throw new IllegalArgumentException("Allocation line requires order line, move and location IDs");
      }
      if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
        throw new IllegalArgumentException("Allocation line requires SKU and positive quantity");
      }
    }
  }
}
