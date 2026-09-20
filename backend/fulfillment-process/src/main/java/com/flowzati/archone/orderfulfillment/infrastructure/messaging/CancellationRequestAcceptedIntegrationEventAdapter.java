package com.flowzati.archone.orderfulfillment.infrastructure.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.WmsCancellationRequestedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.orderfulfillment.application.event.CancellationRequestAccepted;
import com.flowzati.archone.orderfulfillment.application.port.CancellationRequestAcceptedPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class CancellationRequestAcceptedIntegrationEventAdapter implements CancellationRequestAcceptedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public CancellationRequestAcceptedIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(CancellationRequestAccepted event) {
        integrationEventPublisher.publish(
                new WmsCancellationRequestedIntegrationEvent(
                        IdGenerator.nextId(), event.requestId(), event.orderId(), event.requestedAt(), event.reason()),
                new AggregateReference(
                        "OrderCancellationRequest", event.requestId().toString()),
                new PublicationTarget(
                        CancellationEventDestinations.CANCELLATION_REQUESTS,
                        event.orderId().toString()),
                event.requestedAt());
    }
}
