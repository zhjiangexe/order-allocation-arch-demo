package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.port.FulfillmentWorkflowStateReader;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryResult;
import com.flowzati.archone.fulfillment.application.result.OrderFulfillmentQueryResult;
import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.inventory.api.operation.InventoryOperationView;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentView;
import java.util.List;
import java.util.UUID;

/** Monolith composition query；只組合各 Context 公開的 application query，不直接查它們的資料表。 */
public class OrderFulfillmentQueryUsecase {

    private final OrderQueryApi orderQueryApi;
    private final InventoryOperationQueryApi inventoryOperationQueryApi;
    private final WmsShipmentQueryApi wmsShipmentQueryApi;
    private final FulfillmentWorkflowStateReader workflowStateReader;

    public OrderFulfillmentQueryUsecase(
            OrderQueryApi orderQueryApi,
            InventoryOperationQueryApi inventoryOperationQueryApi,
            WmsShipmentQueryApi wmsShipmentQueryApi,
            FulfillmentWorkflowStateReader workflowStateReader) {
        this.orderQueryApi = orderQueryApi;
        this.inventoryOperationQueryApi = inventoryOperationQueryApi;
        this.wmsShipmentQueryApi = wmsShipmentQueryApi;
        this.workflowStateReader = workflowStateReader;
    }

    public OrderFulfillmentQueryResult query(UUID orderId) {
        var order = orderQueryApi.get(orderId);
        InventoryOperationView stockOperation = inventoryOperationQueryApi.findPrimaryOrder(orderId);
        List<WmsShipmentView> shipments = wmsShipmentQueryApi.findByOrderId(orderId);
        FulfillmentWorkflowQueryResult workflowQuery = workflowStateReader.find(orderId);
        return new OrderFulfillmentQueryResult(
                order, stockOperation, shipments, workflowQuery.snapshot(), workflowQuery.status());
    }
}
