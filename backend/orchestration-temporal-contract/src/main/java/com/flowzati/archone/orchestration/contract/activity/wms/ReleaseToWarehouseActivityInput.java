package com.flowzati.archone.orchestration.contract.activity.wms;

import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import java.util.Objects;

/** 向 WMS 下達倉庫出庫需求，由 WMS 以 committed stock-operation snapshot 冪等建立 Shipment。 */
public record ReleaseToWarehouseActivityInput(String processId, StockOperationAssignedInput assignment) {

    public ReleaseToWarehouseActivityInput {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("Process ID is required");
        }
        Objects.requireNonNull(assignment, "Stock operation assignment snapshot is required");
    }
}
