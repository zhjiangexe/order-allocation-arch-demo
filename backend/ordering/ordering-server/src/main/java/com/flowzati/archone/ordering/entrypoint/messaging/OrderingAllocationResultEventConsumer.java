package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.invocation.RecordOrderAllocationCommand;
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
                        AllocationEventDestinations.ALLOCATION_EVENTS)
                .onEvent(
                        OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onStockOperationAssigned(envelope.event()))
                .build();
        return factory.make(OrderingEventSubscriptions.ALLOCATION_RESULTS, handlers);
    }

    void onStockOperationAssigned(OrderAllocationCommittedIntegrationEvent event) {
        recordOrderAllocationUsecase.execute(
                new RecordOrderAllocationCommand(event.getOrderId(), event.getAssignedAt()));
    }
}
