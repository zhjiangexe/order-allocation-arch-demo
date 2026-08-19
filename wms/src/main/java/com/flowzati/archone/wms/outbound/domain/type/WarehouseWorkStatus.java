package com.flowzati.archone.wms.outbound.domain.type;

/** 一張 Shipment 的第一版 picking work 狀態。 */
public enum WarehouseWorkStatus {
  OPEN,
  IN_PROGRESS,
  EXCEPTION,
  COMPLETED,
  CANCELLED
}
