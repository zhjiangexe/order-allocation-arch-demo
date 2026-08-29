package com.flowzati.archone.inventory.movement.entrypoint.consumer;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.inventory.movement.application.command.CancelSourceStockMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CancelSourceStockMovementsUsecase;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import com.flowzati.archone.inventory.movement.entrypoint.MovementCancellationEventSubscriptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Order cancellation 在兩種 orchestration mode 都要釋放 Inventory reservation。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationOrderCancellationEventConsumer {

    private final CancelSourceStockMovementsUsecase cancelMovementsUsecase;

    public AllocationOrderCancellationEventConsumer(CancelSourceStockMovementsUsecase cancelMovementsUsecase) {
        this.cancelMovementsUsecase = cancelMovementsUsecase;
    }

    @Bean
    IntegrationEventDispatcher allocationOrderCancellationIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        OrderingChannels.ORDER_EVENTS)
                .onEvent(OrderCancelledIntegrationEvent.class, envelope -> onOrderCancelled(envelope.event()))
                .build();
        return factory.make(MovementCancellationEventSubscriptions.ORDER_CANCELLATIONS, handlers);
    }

    void onOrderCancelled(OrderCancelledIntegrationEvent event) {
        cancelMovementsUsecase.execute(CancelSourceStockMovementsCommand.afterWarehouseConfirmation(
                StockOperationSource.primaryOrder(event.getOrderId().toString()), event.getEventId()));
    }
}
