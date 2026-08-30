package com.flowzati.archone.inventory.movement.entrypoint.consumer;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.inventory.movement.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.movement.entrypoint.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Legacy V1 handover adapter; allocation identities are ignored after source-to-operation resolution. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class ShipmentHandoverEventConsumer {

    private final CompleteOutboundMovementsUsecase completeOutboundMovements;

    public ShipmentHandoverEventConsumer(CompleteOutboundMovementsUsecase completeOutboundMovements) {
        this.completeOutboundMovements = completeOutboundMovements;
    }

    @Bean
    IntegrationEventDispatcher inventoryShipmentHandoverIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS)
                .onEvent(ShipmentHandedOverIntegrationEvent.class, envelope -> onShipmentHandedOver(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent.class,
                        envelope -> onShipmentHandedOver(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent.class,
                        envelope -> onShipmentHandedOver(envelope.event()))
                .build();
        return factory.make(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER, handlers);
    }

    void onShipmentHandedOver(ShipmentHandedOverIntegrationEvent event) {
        accept(new ShipmentHandover(
                event.getOrderId(), event.getShipmentId(), null, event.getMovementIds(), event.getHandedOverAt()));
    }

    void onShipmentHandedOver(com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent event) {
        accept(new ShipmentHandover(
                event.getOrderId(),
                event.getShipmentId(),
                event.getPickingId(),
                event.getMovementIds(),
                event.getHandedOverAt()));
    }

    void onShipmentHandedOver(com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent event) {
        accept(new ShipmentHandover(
                event.getOrderId(),
                event.getShipmentId(),
                event.getStockOperationId(),
                event.getMovementIds(),
                event.getHandedOverAt()));
    }

    private void accept(ShipmentHandover handover) {
        completeOutboundMovements.execute(new CompleteOutboundMovementsCommand(
                handover.orderId(),
                handover.shipmentId(),
                handover.stockOperationId(),
                handover.movementIds(),
                handover.completedAt()));
    }

    private record ShipmentHandover(
            UUID orderId, UUID shipmentId, UUID stockOperationId, List<UUID> movementIds, Instant completedAt) {}
}
