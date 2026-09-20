package com.flowzati.archone.wms.shipment.infrastructure.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.WmsCancellationRequestResolvedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.wms.shipment.application.event.OrderShipmentCancellationResolved;
import com.flowzati.archone.wms.shipment.application.port.OrderShipmentCancellationResolvedPublisher;
import org.springframework.stereotype.Component;

@Component
public class WmsCancellationRequestResolvedIntegrationEventAdapter
        implements OrderShipmentCancellationResolvedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public WmsCancellationRequestResolvedIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(OrderShipmentCancellationResolved event) {
        integrationEventPublisher.publish(
                new WmsCancellationRequestResolvedIntegrationEvent(
                        IdGenerator.nextId(),
                        event.requestId(),
                        event.orderId(),
                        event.requestedAt(),
                        event.reason(),
                        WmsCancellationRequestResolvedIntegrationEvent.Outcome.valueOf(
                                event.outcome().name())),
                new AggregateReference(
                        "OrderCancellationRequest", event.requestId().toString()),
                new PublicationTarget(
                        CancellationEventDestinations.SHIPMENT_EVENTS,
                        event.orderId().toString()),
                event.requestedAt());
    }
}
