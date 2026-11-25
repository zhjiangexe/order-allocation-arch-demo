package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentCancelledCommand;
import com.flowzati.archone.fulfillment.application.port.OrderFulfillmentWorkflowPort;

/** 把「出貨單已取消」交給訂單的履約流程。 */
public class RecordShipmentCancelledUsecase {

    private final OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort;

    public RecordShipmentCancelledUsecase(OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort) {
        this.orderFulfillmentWorkflowPort = orderFulfillmentWorkflowPort;
    }

    public void execute(RecordShipmentCancelledCommand command) {
        orderFulfillmentWorkflowPort.shipmentCancelled(command);
    }
}
