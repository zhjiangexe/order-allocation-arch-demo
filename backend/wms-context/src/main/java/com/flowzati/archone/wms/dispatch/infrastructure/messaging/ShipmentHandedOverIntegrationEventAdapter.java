package com.flowzati.archone.wms.dispatch.infrastructure.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.wms.dispatch.application.event.ShipmentHandedOver;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentHandedOverPublisher;
import org.springframework.stereotype.Component;

/** Adapts the WMS custody-transfer fact to the public fulfillment handover contract. */
@Component
public class ShipmentHandedOverIntegrationEventAdapter implements ShipmentHandedOverPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public ShipmentHandedOverIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(ShipmentHandedOver event) {
        integrationEventPublisher.publish(
                new com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent(
                        IdGenerator.nextId(),
                        event.shipmentId(),
                        event.stockOperationId(),
                        event.orderId(),
                        event.movementIds(),
                        event.handedOverAt()),
                new AggregateReference(
                        FulfillmentAggregateTypes.WMS_SHIPMENT,
                        event.shipmentId().toString()),
                new PublicationTarget(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS,
                        event.orderId().toString()),
                event.handedOverAt());
    }
}
