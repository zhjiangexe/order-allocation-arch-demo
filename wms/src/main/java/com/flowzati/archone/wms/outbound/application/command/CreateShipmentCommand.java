package com.flowzati.archone.wms.outbound.application.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 由已提交的 allocation snapshot 映射而來，不要求 WMS 直接讀 Promising repository。
 *
 * <p>TODO(order-promising)：目前 {@code OrderAllocatedIntegrationEvent} 只有 {@code orderId}
 * 與 {@code allocatedAt}，尚不足以建立此 command。上游 integration contract／snapshot 必須先補齊
 * 下列已逐欄標示的 allocation facts；WMS 不應跨 bounded context 查詢其 repository。
 *
 * <p>TODO(order-promising)：未來若 Wave 確實要依 service level、配送區或承諾時窗分組，上游還要
 * 補 {@code serviceLevelCode}／destination grouping facts；本批不先加入沒有演算法使用的空欄位。
 */
public record CreateShipmentCommand(
    /** 由 WMS adapter 產生可重送的穩定識別，不要求 order-promising 產生。 */
    UUID shipmentId,
    /** TODO(order-promising)：提供 allocation／StockPicking ID，作為跨事件的冪等 business key。 */
    UUID allocationId,
    UUID orderId,
    /** TODO(order-promising)：目前 allocation event 未提供 owner。 */
    UUID ownerId,
    /** TODO(order-promising)：目前 allocation event 未提供履約 Facility。 */
    UUID facilityId,
    /**
     * TODO(order-promising)：提供 committed allocation lines，至少包含 orderLineId、moveId、SKU、
     * sourceLocationId 與 quantity；只通知 orderId 無法產生 WMS work。
     */
    List<AllocationLine> lines,
    /** TODO(order-promising)：由訂單承諾結果提供最晚離倉時間，不能由 WMS 自行猜測。 */
    Instant dispatchBy,
    /** TODO(order-promising)：由訂單／服務等級映射成 0..100 的 release priority。 */
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
    if (createdAt == null || dispatchBy == null || dispatchBy.isBefore(createdAt)) {
      throw new IllegalArgumentException(
          "Create shipment requires creation time and dispatch deadline not before it");
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
