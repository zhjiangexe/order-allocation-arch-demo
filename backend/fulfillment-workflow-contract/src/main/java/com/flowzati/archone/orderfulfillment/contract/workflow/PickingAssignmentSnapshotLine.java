package com.flowzati.archone.orderfulfillment.contract.workflow;

import java.util.Objects;
import java.util.UUID;

/** Legacy history-visible movement payload for {@link PickingAssignmentSnapshot}. */
@Deprecated(forRemoval = false)
public record PickingAssignmentSnapshotLine(
        UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {

    public PickingAssignmentSnapshotLine {
        Objects.requireNonNull(orderLineId, "Order line ID is required");
        Objects.requireNonNull(moveId, "Move ID is required");
        Objects.requireNonNull(sourceLocationId, "Source location ID is required");
        if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("Assigned movement requires SKU and positive quantity");
        }
    }
}
