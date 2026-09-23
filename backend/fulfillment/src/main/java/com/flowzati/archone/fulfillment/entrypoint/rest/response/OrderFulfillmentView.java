package com.flowzati.archone.fulfillment.entrypoint.rest.response;

import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryStatus;
import com.flowzati.archone.inventory.api.operation.InventoryOperationView;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.ordering.api.query.OrderQueryView;
import com.flowzati.archone.wms.api.shipment.WmsShipmentView;
import java.util.List;

/** 一次回答 DEMO 訂單從需求、預留、倉內作業到終態的跨 Context read view。 */
public record OrderFulfillmentView(
        OrderQueryView order,
        InventoryOperationView stockOperation,
        List<WmsShipmentView> shipments,
        OrderFulfillmentSnapshot temporalWorkflow,
        FulfillmentWorkflowQueryStatus workflowQueryStatus) {

    public OrderFulfillmentView {
        shipments = List.copyOf(shipments);
    }
}
