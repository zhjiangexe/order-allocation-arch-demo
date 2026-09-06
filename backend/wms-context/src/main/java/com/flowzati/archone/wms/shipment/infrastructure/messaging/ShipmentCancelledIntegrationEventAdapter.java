package com.flowzati.archone.wms.shipment.infrastructure.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancelled;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancelledPublisher;
import org.springframework.stereotype.Component;

/** Adapts the WMS cancellation fact to the public fulfillment cancellation contract. */
@Component
public class ShipmentCancelledIntegrationEventAdapter implements ShipmentCancelledPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public ShipmentCancelledIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(ShipmentCancelled event) {
        integrationEventPublisher.publish(
                new ShipmentCancelledIntegrationEvent(
                        IdGenerator.nextId(),
                        event.shipmentId(),
                        event.stockOperationId(),
                        event.orderId(),
                        event.cancellationRequestId(),
                        event.cancellationRequestedAt(),
                        event.cancellationReason(),
                        event.cancelledAt()),
                new AggregateReference(
                        FulfillmentAggregateTypes.SHIPMENT, event.shipmentId().toString()),
                new PublicationTarget(
                        FulfillmentEventDestinations.SHIPMENT_EVENTS,
                        event.orderId().toString()),
                event.cancelledAt());
    }
}
