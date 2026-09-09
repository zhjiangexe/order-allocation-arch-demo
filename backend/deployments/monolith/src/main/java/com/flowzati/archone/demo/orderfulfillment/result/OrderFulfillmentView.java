package com.flowzati.archone.demo.orderfulfillment.result;

import com.flowzati.archone.inventory.movement.entrypoint.rest.StockOperationResponse;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import java.util.List;

/** 一次回答 DEMO 訂單從需求、預留、倉內作業到終態的跨 Context read view。 */
public record OrderFulfillmentView(
        OrderView order,
        StockOperationResponse stockOperation,
        List<ShipmentView> shipments,
        OrderFulfillmentSnapshot temporalWorkflow,
        String orchestrationMode,
        WorkflowQueryStatus workflowQueryStatus) {

    public OrderFulfillmentView {
        shipments = List.copyOf(shipments);
    }
}
