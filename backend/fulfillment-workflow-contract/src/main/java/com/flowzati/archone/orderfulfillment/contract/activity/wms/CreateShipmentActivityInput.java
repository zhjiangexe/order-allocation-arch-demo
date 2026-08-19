package com.flowzati.archone.orderfulfillment.contract.activity.wms;

import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import java.util.Objects;

/** 要求 WMS 以 committed allocation snapshot 冪等建立 Shipment。 */
public record CreateShipmentActivityInput(String processId, AllocationSnapshot allocation) {

    public CreateShipmentActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(allocation, "Allocation snapshot is required");
    }
}
