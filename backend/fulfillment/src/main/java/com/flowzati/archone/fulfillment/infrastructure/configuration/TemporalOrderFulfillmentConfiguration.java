package com.flowzati.archone.fulfillment.infrastructure.configuration;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.port.OrderFulfillmentWorkflowPort;
import com.flowzati.archone.fulfillment.application.usecase.RecordShipmentCancelledUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordShipmentHandedOverUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordStockOperationAssignedUsecase;
import com.flowzati.archone.fulfillment.application.usecase.StartOrderFulfillmentUsecase;
import com.flowzati.archone.fulfillment.infrastructure.temporal.TemporalOrderFulfillmentWorkflowAdapter;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal 模式：訂單履約流程由 Temporal Workflow 管理。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalOrderFulfillmentConfiguration {

    @Bean
    OrderFulfillmentWorkflowPort orderFulfillmentWorkflowPort(WorkflowClient workflowClient) {
        return new TemporalOrderFulfillmentWorkflowAdapter(workflowClient);
    }

    @Bean
    StartOrderFulfillmentUsecase startOrderFulfillmentUsecase(OrderFulfillmentWorkflowPort port) {
        return new StartOrderFulfillmentUsecase(port);
    }

    @Bean
    RecordStockOperationAssignedUsecase recordStockOperationAssignedUsecase(OrderFulfillmentWorkflowPort port) {
        return new RecordStockOperationAssignedUsecase(port);
    }

    @Bean
    RecordShipmentHandedOverUsecase recordShipmentHandedOverUsecase(OrderFulfillmentWorkflowPort port) {
        return new RecordShipmentHandedOverUsecase(port);
    }

    @Bean
    RecordShipmentCancelledUsecase recordShipmentCancelledUsecase(OrderFulfillmentWorkflowPort port) {
        return new RecordShipmentCancelledUsecase(port);
    }
}
