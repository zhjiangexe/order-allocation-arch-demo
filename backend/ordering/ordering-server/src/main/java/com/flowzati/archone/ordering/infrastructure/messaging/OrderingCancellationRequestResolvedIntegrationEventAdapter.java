package com.flowzati.archone.ordering.infrastructure.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.OrderingCancellationRequestResolvedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.ordering.application.event.OrderCancellationResolved;
import com.flowzati.archone.ordering.application.port.OrderCancellationResolvedPublisher;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class OrderingCancellationRequestResolvedIntegrationEventAdapter implements OrderCancellationResolvedPublisher {
    private final IntegrationEventPublisher publisher;

    public OrderingCancellationRequestResolvedIntegrationEventAdapter(IntegrationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OrderCancellationResolved event) {
        var outcome =
                switch (event.status()) {
                    case CANCELLED -> OrderingCancellationRequestResolvedIntegrationEvent.Outcome.CANCELLED;
                    case ALREADY_CANCELLED ->
                        OrderingCancellationRequestResolvedIntegrationEvent.Outcome.ALREADY_CANCELLED;
                    case REJECTED -> OrderingCancellationRequestResolvedIntegrationEvent.Outcome.REJECTED;
                };
        publisher.publish(
                new OrderingCancellationRequestResolvedIntegrationEvent(
                        IdGenerator.nextId(), event.requestId(), event.orderId(), outcome),
                new AggregateReference(
                        "OrderCancellationRequest", event.requestId().toString()),
                new PublicationTarget(
                        CancellationEventDestinations.ORDERING_CANCELLATION_RESULTS,
                        event.orderId().toString()),
                Instant.now());
    }
}
