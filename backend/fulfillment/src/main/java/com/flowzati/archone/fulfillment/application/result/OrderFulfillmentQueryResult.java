package com.flowzati.archone.fulfillment.application.result;

import com.flowzati.archone.inventory.api.operation.InventoryOperationView;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.ordering.api.query.OrderQueryView;
import com.flowzati.archone.wms.api.shipment.WmsShipmentView;
import java.util.List;

public record OrderFulfillmentQueryResult(
        OrderQueryView order,
        InventoryOperationView stockOperation,
        List<WmsShipmentView> shipments,
        OrderFulfillmentSnapshot temporalWorkflow,
        FulfillmentWorkflowQueryStatus workflowQueryStatus) {

    public OrderFulfillmentQueryResult {
        shipments = List.copyOf(shipments);
    }
}
