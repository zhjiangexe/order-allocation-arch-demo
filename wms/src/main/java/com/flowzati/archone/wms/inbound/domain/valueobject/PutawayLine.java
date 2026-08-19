package com.flowzati.archone.wms.inbound.domain.valueobject;

import java.time.LocalDate;
import java.util.UUID;

/** 實際上架結果；目的庫位與批次身分會成為 inventory receipt 的輸入。 */
public record PutawayLine(
    String skuCode,
    UUID locationId,
    LocalDate inDate,
    LocalDate expiryDate,
    int quantity
) {

  public PutawayLine {
    if (skuCode == null || skuCode.isBlank() || locationId == null) {
      throw new IllegalArgumentException("Putaway line requires SKU and location");
    }
    if (inDate == null || expiryDate == null) {
      throw new IllegalArgumentException("Putaway batch dates are required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Putaway quantity must be positive");
    }
  }
}
