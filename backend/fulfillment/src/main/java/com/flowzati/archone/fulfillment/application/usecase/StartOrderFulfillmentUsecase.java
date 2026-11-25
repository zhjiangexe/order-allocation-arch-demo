package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.invocation.StartOrderFulfillmentCommand;
import com.flowzati.archone.fulfillment.application.port.OrderFulfillmentWorkflowPort;

/** 開始一張訂單的履約流程。 */
public class StartOrderFulfillmentUsecase {

    private final OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort;

    public StartOrderFulfillmentUsecase(OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort) {
        this.orderFulfillmentWorkflowPort = orderFulfillmentWorkflowPort;
    }

    public void execute(StartOrderFulfillmentCommand command) {
        orderFulfillmentWorkflowPort.start(command);
    }
}
