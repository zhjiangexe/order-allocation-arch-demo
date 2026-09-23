package com.flowzati.archone.fulfillment.infrastructure.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.OrderingCancellationRequestedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.fulfillment.application.event.OrderingCancellationRequested;
import com.flowzati.archone.fulfillment.application.port.OrderingCancellationRequestedPublisher;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class OrderingCancellationRequestedIntegrationEventAdapter implements OrderingCancellationRequestedPublisher {
    private final IntegrationEventPublisher publisher;

    public OrderingCancellationRequestedIntegrationEventAdapter(IntegrationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OrderingCancellationRequested event) {
        publisher.publish(
                new OrderingCancellationRequestedIntegrationEvent(
                        IdGenerator.nextId(), event.requestId(), event.orderId(), event.requestedAt(), event.reason()),
                new AggregateReference(
                        "OrderCancellationRequest", event.requestId().toString()),
                new PublicationTarget(
                        CancellationEventDestinations.ORDERING_CANCELLATION_REQUESTS,
                        event.orderId().toString()),
                event.requestedAt());
    }
}
