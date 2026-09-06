package com.flowzati.archone.inventory.movement.infrastructure.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.application.event.StockOperationCompleted;
import com.flowzati.archone.inventory.movement.application.port.StockOperationCompletedPublisher;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import java.util.List;
import org.springframework.stereotype.Component;

/** Translates one completed Stock Operation into its audit and fulfillment Integration Events. */
@Component
public class StockOperationCompletedIntegrationEventAdapter implements StockOperationCompletedPublisher {

    private final IntegrationEventPublisher integrationEventPublisher;

    public StockOperationCompletedIntegrationEventAdapter(IntegrationEventPublisher integrationEventPublisher) {
        this.integrationEventPublisher = integrationEventPublisher;
    }

    @Override
    public void publish(StockOperationCompleted event) {
        var snapshot = event.snapshot();
        var fulfillmentEvent = new OutboundMovementsCompletedIntegrationEvent(
                IdGenerator.nextId(),
                snapshot.stockOperationId(),
                event.orderId(),
                event.shipmentId(),
                snapshot.moves().stream().map(move -> move.moveId()).toList(),
                snapshot.occurredAt());
        var fulfillmentPublication = new IntegrationEventPublication(
                fulfillmentEvent,
                new AggregateReference(
                        InventoryAggregateTypes.STOCK_OPERATION,
                        snapshot.stockOperationId().toString()),
                new PublicationTarget(
                        FulfillmentEventDestinations.FULFILLMENT_HANDOFFS,
                        event.orderId().toString()),
                snapshot.occurredAt());

        integrationEventPublisher.publishAll(
                List.of(StockOperationLifecycleTranslator.translate(event), fulfillmentPublication));
    }
}
