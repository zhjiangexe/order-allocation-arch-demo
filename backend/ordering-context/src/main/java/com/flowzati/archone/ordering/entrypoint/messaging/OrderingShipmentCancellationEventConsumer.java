package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.error.OrderErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Events mode 在 WMS cancellation 終態後才推進 Ordering，避免 Order 先取消而 Shipment 仍可出貨。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class OrderingShipmentCancellationEventConsumer {

    private final CancelOrderUsecase cancelOrderUsecase;

    public OrderingShipmentCancellationEventConsumer(CancelOrderUsecase cancelOrderUsecase) {
        this.cancelOrderUsecase = cancelOrderUsecase;
    }

    @Bean
    IntegrationEventDispatcher orderingShipmentCancellationIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentEventDestinations.SHIPMENT_EVENTS)
                .onEvent(ShipmentCancelledIntegrationEvent.class, envelope -> onShipmentCancelled(envelope.event()))
                .build();
        return factory.make(OrderingEventSubscriptions.SHIPMENT_CANCELLATIONS, handlers);
    }

    void onShipmentCancelled(ShipmentCancelledIntegrationEvent event) {
        Order.CancellationStatus result = cancelOrderUsecase.cancel(new CancelOrderCommand(
                event.getCancellationRequestId(),
                event.getOrderId(),
                event.getCancelledAt(),
                event.getCancellationReason()));
        if (result == Order.CancellationStatus.REJECTED) {
            throw new DomainConflictException(
                    OrderErrorCode.FULFILLMENT_CONFLICT,
                    "Ordering rejected cancellation after WMS cancelled Shipment: " + event.getShipmentId());
        }
    }
}
