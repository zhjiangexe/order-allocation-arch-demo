package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.command.CancelMovementsCommand;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.allocation.application.usecase.CancelMovementsUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style consumer for Order lifecycle facts handled by Allocation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationOrderLifecycleEventConsumer {

    private final AllocateOrderUsecase allocateOrderUsecase;
    private final CancelMovementsUsecase cancelMovementsUsecase;

    public AllocationOrderLifecycleEventConsumer(
            AllocateOrderUsecase allocateOrderUsecase, CancelMovementsUsecase cancelMovementsUsecase) {
        this.allocateOrderUsecase = allocateOrderUsecase;
        this.cancelMovementsUsecase = cancelMovementsUsecase;
    }

    @Bean
    IntegrationEventDispatcher allocationOrderLifecycleIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        OrderingChannels.ORDER_EVENTS)
                .onEvent(OrderPlacedIntegrationEvent.class, envelope -> onOrderPlaced(envelope.event()))
                .onEvent(OrderCancelledIntegrationEvent.class, envelope -> onOrderCancelled(envelope.event()))
                .build();
        return factory.make(AllocationEventSubscriptions.ORDER_LIFECYCLE, handlers);
    }

    void onOrderPlaced(OrderPlacedIntegrationEvent event) {
        allocateOrderUsecase.execute(new AllocateOrderCommand(event.getOrderId()));
    }

    void onOrderCancelled(OrderCancelledIntegrationEvent event) {
        cancelMovementsUsecase.execute(new CancelMovementsCommand(event.getOrderId(), event.getEventId()));
    }
}
