package com.flowzati.archone.wms.outbound.application.event;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import java.time.Instant;

/** Creates the integration-event publication for an explicit Shipment handover outcome. */
public final class ShipmentHandedOverPublicationFactory {

    private ShipmentHandedOverPublicationFactory() {}

    public static IntegrationEventPublication handedOver(Shipment shipment, Instant handedOverAt) {
        return new IntegrationEventPublication(
                new ShipmentHandedOverIntegrationEvent(
                        IdGenerator.nextId(),
                        shipment.id(),
                        shipment.allocationId(),
                        shipment.orderId(),
                        shipment.lines().stream().map(ShipmentLine::moveId).toList(),
                        handedOverAt),
                new AggregateReference(
                        FulfillmentAggregateTypes.WMS_SHIPMENT, shipment.id().toString()),
                new PublicationTarget(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS,
                        shipment.orderId().toString()),
                handedOverAt);
    }
}
