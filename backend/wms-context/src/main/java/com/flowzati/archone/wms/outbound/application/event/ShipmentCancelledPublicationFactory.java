package com.flowzati.archone.wms.outbound.application.event;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;

/** Creates the integration-event publication for a completed Shipment cancellation. */
public final class ShipmentCancelledPublicationFactory {

    private ShipmentCancelledPublicationFactory() {}

    public static IntegrationEventPublication cancelled(Shipment shipment) {
        if (shipment.cancelledAt() == null) {
            throw new IllegalStateException("Cancelled Shipment must contain its completion time");
        }
        return new IntegrationEventPublication(
                new ShipmentCancelledIntegrationEvent(
                        IdGenerator.nextId(),
                        shipment.id(),
                        shipment.orderId(),
                        shipment.cancellationRequestId(),
                        shipment.cancellationRequestedAt(),
                        shipment.cancellationReason(),
                        shipment.cancelledAt()),
                new AggregateReference(
                        FulfillmentAggregateTypes.WMS_SHIPMENT, shipment.id().toString()),
                new PublicationTarget(
                        FulfillmentChannels.SHIPMENT_EVENTS, shipment.orderId().toString()),
                shipment.cancelledAt());
    }
}
