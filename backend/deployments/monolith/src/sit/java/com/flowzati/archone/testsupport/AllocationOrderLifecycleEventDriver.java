package com.flowzati.archone.testsupport;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.ordering.v1.OrderingEventDestinations;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationSubscriberIds;
import com.flowzati.archone.inventory.movement.entrypoint.consumer.MovementCancellationEventSubscriptions;
import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventMessageMapper;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Drives the production order-lifecycle typed chain without starting Kafka in PostgreSQL SITs.
 *
 * <p>Business transaction SITs start from the canonical message envelope. Physical Kafka record
 * mapping and serialized-header restoration belong to the dedicated dispatcher equivalence SIT.
 */
@Component
public class AllocationOrderLifecycleEventDriver {

    private final IntegrationEventMessageMapper messageMapper;
    private final ControllableMessageConsumerImplementation transport;
    private final String physicalDestination;

    public AllocationOrderLifecycleEventDriver(
            IntegrationEventSerializer serializer,
            IntegrationEventNameMapping nameMapping,
            ChannelMapping channelMapping,
            ControllableMessageConsumerImplementation transport) {
        this.messageMapper = new IntegrationEventMessageMapper(serializer, nameMapping);
        this.transport = transport;
        this.physicalDestination = channelMapping.transform(OrderingEventDestinations.ORDER_EVENTS);
    }

    public void consume(OrderPlacedIntegrationEvent event) {
        consume(event, event.getOrderId().toString(), event.getReceivedAt());
    }

    public void consume(OrderCancelledIntegrationEvent event) {
        consume(event, event.getOrderId().toString(), event.getCancelledAt());
    }

    private void consume(IntegrationEvent event, String orderId, Instant occurredAt) {
        IntegrationEventPublication publication = new IntegrationEventPublication(
                event,
                new AggregateReference(OrderingAggregateTypes.ORDER, orderId),
                new PublicationTarget(OrderingEventDestinations.ORDER_EVENTS, orderId),
                occurredAt);
        String subscriberId = event instanceof OrderCancelledIntegrationEvent
                ? MovementCancellationEventSubscriptions.ORDER_CANCELLATIONS
                : AllocationSubscriberIds.ORDER_PLACEMENT;
        transport.emit(subscriberId, physicalDestination, messageMapper.toMessage(publication), 1);
    }
}
