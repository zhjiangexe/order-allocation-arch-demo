package com.flowzati.archone.inventory.movement.entrypoint.consumer;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.inventory.movement.application.invocation.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.movement.entrypoint.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Event-driven handover adapter from WMS custody transfer to Inventory movement completion. */
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
                        FulfillmentEventDestinations.FULFILLMENT_HANDOFFS)
                .onEvent(ShipmentHandedOverIntegrationEvent.class, envelope -> onShipmentHandedOver(envelope.event()))
                .build();
        return factory.make(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER, handlers);
    }

    void onShipmentHandedOver(ShipmentHandedOverIntegrationEvent event) {
        completeOutboundMovements.execute(new CompleteOutboundMovementsCommand(
                event.getOrderId(),
                event.getShipmentId(),
                event.getStockOperationId(),
                event.getMovementIds(),
                event.getHandedOverAt()));
    }
}
