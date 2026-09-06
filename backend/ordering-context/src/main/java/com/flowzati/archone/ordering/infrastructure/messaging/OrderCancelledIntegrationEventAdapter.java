package com.flowzati.archone.ordering.infrastructure.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingAggregateTypes;
import com.flowzati.archone.contracts.ordering.v1.OrderingEventDestinations;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.ordering.application.event.OrderCancelled;
import com.flowzati.archone.ordering.application.port.OrderCancelledPublisher;
import org.springframework.stereotype.Component;

/** Adapts an Ordering cancellation fact to the public Order-cancelled Integration Event contract. */
@Component
public class OrderCancelledIntegrationEventAdapter implements OrderCancelledPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;
    private final OrderingPartitionKeyResolver partitionKeyResolver;

    public OrderCancelledIntegrationEventAdapter(
            IntegrationEventPublisher integrationEventPublisher, OrderingPartitionKeyResolver partitionKeyResolver) {
        this.integrationEventPublisher = integrationEventPublisher;
        this.partitionKeyResolver = partitionKeyResolver;
    }

    @Override
    public void publish(OrderCancelled event) {
        integrationEventPublisher.publish(
                new OrderCancelledIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.cancelledAt()),
                new AggregateReference(
                        OrderingAggregateTypes.ORDER, event.orderId().toString()),
                new PublicationTarget(
                        OrderingEventDestinations.ORDER_EVENTS,
                        partitionKeyResolver.resolve(event.orderId(), event.ownerId(), event.facilityId())),
                event.cancelledAt());
    }
}
