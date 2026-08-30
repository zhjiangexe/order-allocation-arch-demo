package com.flowzati.archone.ordering.infrastructure.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.ordering.application.event.OrderPlaced;
import com.flowzati.archone.ordering.application.port.OrderPlacedPublisher;
import org.springframework.stereotype.Component;

/** Adapts an Ordering placement fact to the public Order-placed Integration Event contract. */
@Component
public class OrderPlacedIntegrationEventAdapter implements OrderPlacedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;
    private final OrderingPartitionKeyResolver partitionKeyResolver;

    public OrderPlacedIntegrationEventAdapter(
            IntegrationEventPublisher integrationEventPublisher, OrderingPartitionKeyResolver partitionKeyResolver) {
        this.integrationEventPublisher = integrationEventPublisher;
        this.partitionKeyResolver = partitionKeyResolver;
    }

    @Override
    public void publish(OrderPlaced event) {
        integrationEventPublisher.publish(
                new OrderPlacedIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.receivedAt()),
                new AggregateReference(
                        OrderingAggregateTypes.ORDER, event.orderId().toString()),
                new PublicationTarget(
                        OrderingChannels.ORDER_EVENTS,
                        partitionKeyResolver.resolve(event.orderId(), event.ownerId(), event.facilityId())),
                event.receivedAt());
    }
}
