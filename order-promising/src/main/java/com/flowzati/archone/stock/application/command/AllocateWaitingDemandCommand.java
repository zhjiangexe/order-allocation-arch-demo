package com.flowzati.archone.stock.application.command;

import java.util.UUID;

/**
 * 執行一輪有上限的等待需求配貨。
 *
 * <p><b>不帶數量</b>——它不是收貨。availability event 與 Scheduler 都只用這個 scope 找出當下
 * 可配的等待需求；實體庫存已由先前的收貨或其他庫存異動提交。
 */
public record AllocateWaitingDemandCommand(
    UUID ownerId,
    UUID facilityId,
    UUID locationId,
    String sku
) {
  public AllocateWaitingDemandCommand {
    if (ownerId == null || facilityId == null || locationId == null) {
      throw new IllegalArgumentException("Owner ID, facility ID and location ID are required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
  }
}
