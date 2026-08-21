package com.flowzati.archone.demo.fulfillment;

import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandView;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowSnapshot;
import com.flowzati.archone.wms.outbound.application.query.ShipmentView;
import java.util.List;

/** 一次回答 DEMO 訂單從需求、預留、倉內作業到終態的跨 Context read view。 */
public record OrderFulfillmentView(
        String orchestrationMode,
        FulfillmentOrderView order,
        AllocationDemandView allocation,
        List<ShipmentView> shipments,
        OrderFulfillmentWorkflowSnapshot workflow) {

    public OrderFulfillmentView {
        shipments = List.copyOf(shipments);
    }
}
