package com.flowzati.archone.wms.inbound.domain.model;

public record InboundLine(String skuCode, int expectedQuantity) {

  public InboundLine {
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("Inbound line SKU is required");
    }
    if (expectedQuantity <= 0) {
      throw new IllegalArgumentException("Expected quantity must be positive");
    }
  }
}
