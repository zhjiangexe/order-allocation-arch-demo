package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style consumer for allocation results projected into Ordering. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class OrderingAllocationResultEventConsumer {

    private final RecordOrderAllocationUsecase recordOrderAllocationUsecase;

    public OrderingAllocationResultEventConsumer(RecordOrderAllocationUsecase recordOrderAllocationUsecase) {
        this.recordOrderAllocationUsecase = recordOrderAllocationUsecase;
    }

    @Bean
    IntegrationEventDispatcher orderingAllocationResultIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        AllocationChannels.ALLOCATION_EVENTS)
                .onEvent(
                        OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onOrderAllocationCommitted(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onOrderPickingAssigned(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onStockOperationAssigned(envelope.event()))
                .build();
        return factory.make(OrderingEventSubscriptions.ALLOCATION_RESULTS, handlers);
    }

    void onOrderAllocationCommitted(OrderAllocationCommittedIntegrationEvent event) {
        recordOrderAllocationUsecase.execute(
                new RecordOrderAllocationCommand(event.getOrderId(), event.getCommittedAt()));
    }

    void onOrderPickingAssigned(
            com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent event) {
        recordOrderAllocationUsecase.execute(
                new RecordOrderAllocationCommand(event.getOrderId(), event.getAssignedAt()));
    }

    void onStockOperationAssigned(
            com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent event) {
        recordOrderAllocationUsecase.execute(
                new RecordOrderAllocationCommand(event.getOrderId(), event.getAssignedAt()));
    }
}
