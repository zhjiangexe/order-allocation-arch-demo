package com.flowzati.archone.fulfillment.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.AssignedStockMoveLine;
import com.flowzati.archone.fulfillment.application.invocation.RecordStockOperationAssignedCommand;
import com.flowzati.archone.fulfillment.application.usecase.RecordStockOperationAssignedUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal 模式：把「庫存已配貨」交給履約流程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalStockOperationAssignedEventConsumer {

    private final RecordStockOperationAssignedUsecase usecase;

    public TemporalStockOperationAssignedEventConsumer(RecordStockOperationAssignedUsecase usecase) {
        this.usecase = usecase;
    }

    @Bean
    IntegrationEventDispatcher temporalStockOperationAssignedDispatcher(IntegrationEventDispatcherFactory factory) {
        return factory.make(
                FulfillmentEventSubscriptions.WORKFLOW_STOCK_OPERATION_ASSIGNED,
                IntegrationEventHandlersBuilder.forDestination(AllocationEventDestinations.ALLOCATION_EVENTS)
                        .onEvent(
                                OrderAllocationCommittedIntegrationEvent.class,
                                envelope -> onStockOperationAssigned(envelope.event()))
                        .build());
    }

    void onStockOperationAssigned(OrderAllocationCommittedIntegrationEvent event) {
        usecase.execute(new RecordStockOperationAssignedCommand(
                event.getStockOperationId(),
                event.getOrderId(),
                event.getOwnerId(),
                event.getFacilityId(),
                event.getMoves().stream()
                        .map(line -> new AssignedStockMoveLine(
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                event.getSourceLocationId(),
                                line.quantity()))
                        .toList(),
                event.getDispatchBy(),
                event.getReleasePriority(),
                event.getAssignedAt()));
    }
}
