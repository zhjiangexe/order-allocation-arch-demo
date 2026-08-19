package com.flowzati.archone.inventory.balance.infrastructure.messaging.producer;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedForFulfillmentIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.balance.application.event.OutboundMovementEventPublisher;
import com.flowzati.archone.inventory.balance.domain.event.OutboundMovementsCompleted;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import org.springframework.stereotype.Component;

/** Inventory outbound completion fact 的 transactional Outbox adapter。 */
@Component
public class OutboundMovementIntegrationEventPublisher implements OutboundMovementEventPublisher {

    private final IntegrationEventPublisher eventPublisher;

    public OutboundMovementIntegrationEventPublisher(IntegrationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(OutboundMovementsCompleted event) {
        OutboundMovementsCompletedForFulfillmentIntegrationEvent integration =
                new OutboundMovementsCompletedForFulfillmentIntegrationEvent(
                        IdGenerator.nextId(),
                        event.allocationId(),
                        event.orderId(),
                        event.shipmentId(),
                        event.movementIds(),
                        event.occurredAt());
        eventPublisher.publish(
                integration,
                new AggregateReference(
                        FulfillmentAggregateTypes.STOCK_PICKING,
                        event.allocationId().toString()),
                new PublicationTarget(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS,
                        event.orderId().toString()),
                event.occurredAt());
    }
}
