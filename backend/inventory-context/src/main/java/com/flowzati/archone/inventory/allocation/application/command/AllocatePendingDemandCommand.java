package com.flowzati.archone.inventory.allocation.application.command;

import java.util.UUID;

/**
 * 嘗試配置指定 FIFO queue 的下一筆 PENDING demand。
 *
 * <p><b>不帶數量</b>——它不是收貨。availability event 與 Scheduler 都只用這組 queue key
 * 找出當下可配的 PENDING demand；實體庫存已由先前的收貨或其他庫存異動提交。
 */
public record AllocatePendingDemandCommand(UUID ownerId, UUID facilityId, UUID locationId, String sku) {
    public AllocatePendingDemandCommand {
        if (ownerId == null || facilityId == null || locationId == null) {
            throw new IllegalArgumentException("Owner ID, facility ID and location ID are required");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }
    }
}
