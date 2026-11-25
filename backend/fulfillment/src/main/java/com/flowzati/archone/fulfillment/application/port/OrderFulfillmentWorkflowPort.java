package com.flowzati.archone.fulfillment.application.port;

import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentCancelledCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentHandedOverCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordStockOperationAssignedCommand;
import com.flowzati.archone.fulfillment.application.invocation.StartOrderFulfillmentCommand;

/** 由外部流程引擎（Temporal 模式）管理的訂單履約流程：開始流程，並把各方已發生的事實交給它。 */
public interface OrderFulfillmentWorkflowPort {

    /** 同一張訂單重複開始時不得建立第二條流程，也不得失敗（訊息可能重送）。 */
    void start(StartOrderFulfillmentCommand command);

    void stockOperationAssigned(RecordStockOperationAssignedCommand command);

    void shipmentHandedOver(RecordShipmentHandedOverCommand command);

    void shipmentCancelled(RecordShipmentCancelledCommand command);
}
