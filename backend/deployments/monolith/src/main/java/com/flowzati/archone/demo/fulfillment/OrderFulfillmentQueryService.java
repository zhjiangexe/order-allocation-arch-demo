package com.flowzati.archone.demo.fulfillment;

import com.flowzati.archone.bootstrap.fulfillment.FulfillmentOrchestrationMode;
import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandQueryService;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Monolith composition query；只組合各 Context 公開的 application query，不直接查它們的資料表。 */
@Service
public class OrderFulfillmentQueryService {

    private final GetOrderUsecase getOrderUsecase;
    private final AllocationDemandQueryService allocationQueryService;
    private final GetOrderShipmentsUsecase getOrderShipmentsUsecase;
    private final FulfillmentWorkflowStateReader workflowStateReader;
    private final FulfillmentOrchestrationMode orchestrationMode;

    public OrderFulfillmentQueryService(
            GetOrderUsecase getOrderUsecase,
            AllocationDemandQueryService allocationQueryService,
            GetOrderShipmentsUsecase getOrderShipmentsUsecase,
            FulfillmentWorkflowStateReader workflowStateReader,
            FulfillmentOrchestrationMode orchestrationMode) {
        this.getOrderUsecase = getOrderUsecase;
        this.allocationQueryService = allocationQueryService;
        this.getOrderShipmentsUsecase = getOrderShipmentsUsecase;
        this.workflowStateReader = workflowStateReader;
        this.orchestrationMode = orchestrationMode;
    }

    public OrderFulfillmentView query(UUID orderId) {
        return new OrderFulfillmentView(
                orchestrationMode.name(),
                FulfillmentOrderView.from(getOrderUsecase.getOrder(orderId)),
                allocationQueryService.findPrimaryOrder(orderId).orElse(null),
                getOrderShipmentsUsecase.query(orderId),
                workflowStateReader.find(orderId).orElse(null));
    }
}
