package com.flowzati.archone.wms.outbound.domain.valueobject;

import java.util.UUID;

/** Shipment 對一筆已提交 allocation line 的不可變快照。 */
public record ShipmentLine(
    UUID orderLineId,
    UUID moveId,
    String skuCode,
    UUID sourceLocationId,
    int quantity
) {

  public ShipmentLine {
    if (orderLineId == null || moveId == null || sourceLocationId == null) {
      throw new IllegalArgumentException("Shipment line requires order line, move and location IDs");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("Shipment line SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Shipment line quantity must be positive");
    }
  }
}
