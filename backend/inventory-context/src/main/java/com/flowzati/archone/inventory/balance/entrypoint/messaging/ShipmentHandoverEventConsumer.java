package com.flowzati.archone.inventory.balance.entrypoint.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverForFulfillmentIntegrationEvent;
import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Event-driven 模式下，WMS handover 直接推進 Inventory 出庫過帳。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class ShipmentHandoverEventConsumer {

    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase;

    public ShipmentHandoverEventConsumer(CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase) {
        this.completeOutboundMovementsUsecase = completeOutboundMovementsUsecase;
    }

    @Bean
    IntegrationEventDispatcher inventoryShipmentHandoverIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS)
                .onEvent(
                        ShipmentHandedOverForFulfillmentIntegrationEvent.class,
                        envelope -> onShipmentHandedOver(envelope.event()))
                .build();
        return factory.make(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER, handlers);
    }

    void onShipmentHandedOver(ShipmentHandedOverForFulfillmentIntegrationEvent event) {
        completeOutboundMovementsUsecase.execute(new CompleteOutboundMovementsCommand(
                event.getAllocationId(),
                event.getOrderId(),
                event.getShipmentId(),
                event.getMovementIds(),
                event.getHandedOverAt()));
    }
}
