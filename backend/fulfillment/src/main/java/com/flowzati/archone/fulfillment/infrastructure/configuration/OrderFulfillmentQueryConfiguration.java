package com.flowzati.archone.fulfillment.infrastructure.configuration;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.enums.FulfillmentWorkflowQueryStatus;
import com.flowzati.archone.fulfillment.application.port.FulfillmentWorkflowStateReader;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryResult;
import com.flowzati.archone.fulfillment.application.usecase.GetOrderFulfillmentUsecase;
import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the DEMO query without exposing configuration loading to the service.
 */
@Configuration(proxyBeanMethods = false)
public class OrderFulfillmentQueryConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
            havingValue = FulfillmentOrchestrationMode.EVENTS,
            matchIfMissing = true)
    FulfillmentWorkflowStateReader eventDrivenFulfillmentWorkflowStateReader() {
        return _ -> new FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus.NOT_APPLICABLE, null);
    }

    @Bean
    GetOrderFulfillmentUsecase orderFulfillmentQueryService(
            OrderQueryApi orders,
            InventoryOperationQueryApi operations,
            WmsShipmentQueryApi shipments,
            FulfillmentWorkflowStateReader reader) {
        return new GetOrderFulfillmentUsecase(orders, operations, shipments, reader);
    }
}
