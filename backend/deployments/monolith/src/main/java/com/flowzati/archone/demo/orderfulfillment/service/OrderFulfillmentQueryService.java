package com.flowzati.archone.demo.orderfulfillment.service;

import com.flowzati.archone.demo.orderfulfillment.result.OrderFulfillmentView;
import com.flowzati.archone.demo.orderfulfillment.result.OrderView;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.inventory.movement.entrypoint.rest.StockOperationResponse;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Monolith composition query；只組合各 Context 公開的 application query，不直接查它們的資料表。
 */
@Service
public class OrderFulfillmentQueryService {

    private final GetOrderUsecase getOrderUsecase;
    private final StockOperationQueryService stockOperationQueryService;
    private final GetOrderShipmentsUsecase getOrderShipmentsUsecase;
    private final ObjectProvider<TemporalWorkflowStateReader> temporalWorkflowStateReader;

    public OrderFulfillmentQueryService(
            GetOrderUsecase getOrderUsecase,
            StockOperationQueryService stockOperationQueryService,
            GetOrderShipmentsUsecase getOrderShipmentsUsecase,
            ObjectProvider<TemporalWorkflowStateReader> temporalWorkflowStateReader) {
        this.getOrderUsecase = getOrderUsecase;
        this.stockOperationQueryService = stockOperationQueryService;
        this.getOrderShipmentsUsecase = getOrderShipmentsUsecase;
        this.temporalWorkflowStateReader = temporalWorkflowStateReader;
    }

    public OrderFulfillmentView query(UUID orderId) {
        Order order = getOrderUsecase.getOrder(orderId);
        StockOperationResponse stockOperation = stockOperationQueryService
                .findPrimaryOrder(orderId)
                .map(StockOperationResponse::from)
                .orElse(null);
        List<ShipmentView> shipmentViewList = getOrderShipmentsUsecase.query(orderId);
        TemporalWorkflowStateReader workflowStateReader = temporalWorkflowStateReader.getIfAvailable();
        OrderFulfillmentSnapshot temporalWorkflow = workflowStateReader == null
                ? null
                : workflowStateReader.find(orderId).orElse(null);
        return new OrderFulfillmentView(OrderView.from(order), stockOperation, shipmentViewList, temporalWorkflow);
    }
}
