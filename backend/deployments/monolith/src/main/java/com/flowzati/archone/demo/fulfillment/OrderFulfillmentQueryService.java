package com.flowzati.archone.demo.fulfillment;

import com.flowzati.archone.bootstrap.fulfillment.FulfillmentOrchestrationMode;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.inventory.movement.entrypoint.StockOperationResponse;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowSnapshot;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.wms.outbound.application.result.ShipmentView;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Monolith composition query；只組合各 Context 公開的 application query，不直接查它們的資料表。
 */
@Service
public class OrderFulfillmentQueryService {

    private final GetOrderUsecase getOrderUsecase;
    private final StockOperationQueryService stockOperationQueryService;
    private final GetOrderShipmentsUsecase getOrderShipmentsUsecase;
    private final FulfillmentWorkflowStateReader workflowStateReader;
    private final FulfillmentOrchestrationMode orchestrationMode;

    public OrderFulfillmentQueryService(
            GetOrderUsecase getOrderUsecase,
            StockOperationQueryService stockOperationQueryService,
            GetOrderShipmentsUsecase getOrderShipmentsUsecase,
            FulfillmentWorkflowStateReader workflowStateReader,
            FulfillmentOrchestrationMode orchestrationMode) {
        this.getOrderUsecase = getOrderUsecase;
        this.stockOperationQueryService = stockOperationQueryService;
        this.getOrderShipmentsUsecase = getOrderShipmentsUsecase;
        this.workflowStateReader = workflowStateReader;
        this.orchestrationMode = orchestrationMode;
    }

    public OrderFulfillmentView query(UUID orderId) {
        Order order = getOrderUsecase.getOrder(orderId);
        StockOperationResponse stockOperation = stockOperationQueryService
                .findPrimaryOrder(orderId)
                .map(StockOperationResponse::from)
                .orElse(null);
        List<ShipmentView> shipmentViewList = getOrderShipmentsUsecase.query(orderId);
        OrderFulfillmentWorkflowSnapshot workflow =
                workflowStateReader.find(orderId).orElse(null);
        return new OrderFulfillmentView(
                orchestrationMode.name(), FulfillmentOrderView.from(order), stockOperation, shipmentViewList, workflow);
    }
}
