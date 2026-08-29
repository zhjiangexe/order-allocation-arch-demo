package com.flowzati.archone.demo.fulfillment;

import com.flowzati.archone.inventory.movement.entrypoint.StockOperationResponse;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowSnapshot;
import com.flowzati.archone.wms.outbound.application.query.ShipmentView;
import java.util.List;

/** 一次回答 DEMO 訂單從需求、預留、倉內作業到終態的跨 Context read view。 */
public record OrderFulfillmentView(
        String orchestrationMode,
        FulfillmentOrderView order,
        StockOperationResponse stockOperation,
        List<ShipmentView> shipments,
        OrderFulfillmentWorkflowSnapshot workflow) {

    public OrderFulfillmentView {
        shipments = List.copyOf(shipments);
    }
}
