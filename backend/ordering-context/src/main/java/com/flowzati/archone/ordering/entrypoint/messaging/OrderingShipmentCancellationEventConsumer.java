package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.command.CancelOrderCommand;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
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
                        FulfillmentChannels.SHIPMENT_EVENTS)
                .onEvent(ShipmentCancelledIntegrationEvent.class, envelope -> onShipmentCancelled(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent.class,
                        envelope -> onShipmentCancelled(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent.class,
                        envelope -> onShipmentCancelled(envelope.event()))
                .build();
        return factory.make(OrderingEventSubscriptions.SHIPMENT_CANCELLATIONS, handlers);
    }

    void onShipmentCancelled(ShipmentCancelledIntegrationEvent event) {
        applyCancellation(
                event.getShipmentId(),
                event.getCancellationRequestId(),
                event.getOrderId(),
                event.getCancelledAt(),
                event.getCancellationReason());
    }

    void onShipmentCancelled(com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent event) {
        applyCancellation(
                event.getShipmentId(),
                event.getCancellationRequestId(),
                event.getOrderId(),
                event.getCancelledAt(),
                event.getCancellationReason());
    }

    void onShipmentCancelled(com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent event) {
        applyCancellation(
                event.getShipmentId(),
                event.getCancellationRequestId(),
                event.getOrderId(),
                event.getCancelledAt(),
                event.getCancellationReason());
    }

    private void applyCancellation(
            java.util.UUID shipmentId,
            java.util.UUID requestId,
            java.util.UUID orderId,
            java.time.Instant cancelledAt,
            String reason) {
        Order.CancellationStatus status =
                cancelOrderUsecase.cancel(new CancelOrderCommand(requestId, orderId, cancelledAt, reason));
        if (status == Order.CancellationStatus.REJECTED) {
            throw new IllegalStateException(
                    "Ordering rejected cancellation after WMS cancelled Shipment: " + shipmentId);
        }
    }
}
