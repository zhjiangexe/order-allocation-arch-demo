package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.invocation.RecordStockOperationAssignedCommand;
import com.flowzati.archone.fulfillment.application.port.OrderFulfillmentWorkflowPort;

/** 把「庫存已配貨」交給訂單的履約流程。 */
public class RecordStockOperationAssignedUsecase {

    private final OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort;

    public RecordStockOperationAssignedUsecase(OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort) {
        this.orderFulfillmentWorkflowPort = orderFulfillmentWorkflowPort;
    }

    public void execute(RecordStockOperationAssignedCommand command) {
        orderFulfillmentWorkflowPort.stockOperationAssigned(command);
    }
}
