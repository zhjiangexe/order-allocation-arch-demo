package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentHandedOverCommand;
import com.flowzati.archone.fulfillment.application.port.OrderFulfillmentWorkflowPort;

/** 把「出貨單已交給物流商」交給訂單的履約流程。 */
public class RecordShipmentHandedOverUsecase {

    private final OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort;

    public RecordShipmentHandedOverUsecase(OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort) {
        this.orderFulfillmentWorkflowPort = orderFulfillmentWorkflowPort;
    }

    public void execute(RecordShipmentHandedOverCommand command) {
        orderFulfillmentWorkflowPort.shipmentHandedOver(command);
    }
}
