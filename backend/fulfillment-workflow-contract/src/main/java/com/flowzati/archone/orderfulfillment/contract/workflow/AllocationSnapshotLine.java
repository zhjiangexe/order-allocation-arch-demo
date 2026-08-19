package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.util.Objects;
import java.util.UUID;

/** Committed allocation 中可交給 WMS 的單筆庫存搬運資料。 */
public record AllocationSnapshotLine(
        UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {

    public AllocationSnapshotLine {
        Objects.requireNonNull(orderLineId, "Order line ID is required");
        Objects.requireNonNull(moveId, "Move ID is required");
        Objects.requireNonNull(sourceLocationId, "Source location ID is required");
        if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("Allocation line requires SKU and positive quantity");
        }
    }
}
