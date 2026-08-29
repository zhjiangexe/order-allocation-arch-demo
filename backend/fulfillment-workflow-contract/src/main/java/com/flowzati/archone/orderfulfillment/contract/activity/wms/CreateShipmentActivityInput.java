package com.flowzati.archone.orderfulfillment.contract.activity.wms;

import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshot;
import java.util.Objects;

/** 要求 WMS 以 committed stock-operation snapshot 冪等建立 Shipment。 */
public record CreateShipmentActivityInput(String processId, StockOperationAssignmentSnapshot assignment) {

    public CreateShipmentActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(assignment, "Stock operation assignment snapshot is required");
    }
}
